package com.intercom.segluky

import android.accessibilityservice.AccessibilityService
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.content.Intent
import androidx.core.content.ContextCompat

/**
 * Presses WhatsApp's send button once when an SOS auto-send request exists in
 * the structured queue. It only acts on the queue head, verifies exact package,
 * full normalized message match, and reports SENT/FAILED per request.
 */
class WhatsAppAutoSendService : AccessibilityService() {
    private val handler = Handler(Looper.getMainLooper())
    private var pendingAttempt: Runnable? = null
    private var attempts = 0
    private var activeRequestId: String? = null
    private var lastLoggedProblem = ""

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        val head = WhatsAppAutoSend.peek(this) ?: return
        if (head.status != WhatsAppRequestStatus.PENDING) {
            if (head.status == WhatsAppRequestStatus.PROCESSING) {
                // A previous process may have died after claiming the head.
                scheduleAttempt(head.requestId)
            } else if (head.status.isTerminal) {
                // Do not let a terminal head block the rest of the queue.
                WhatsAppAutoSend.advance(this, head.requestId)
                notifyQueueAdvanced()
            }
            return
        }
        val pkg = event.packageName?.toString() ?: return
        if (pkg != WHATSAPP_PACKAGE && pkg != WHATSAPP_BUSINESS_PACKAGE) return
        if (pkg != head.expectedPackage) return
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            event.eventType != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
        ) {
            return
        }
        scheduleAttempt(head.requestId)
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private fun scheduleAttempt(requestId: String) {
        if (pendingAttempt != null && activeRequestId == requestId) return
        if (activeRequestId != requestId) {
            activeRequestId = requestId
            attempts = 0
            lastLoggedProblem = ""
        }
        pendingAttempt?.let { handler.removeCallbacks(it) }
        val runnable = Runnable { runAttempt(requestId) }
        pendingAttempt = runnable
        handler.postDelayed(runnable, INITIAL_DELAY_MS)
    }

    private fun runAttempt(requestId: String) {
        pendingAttempt = null
        val claimResult = WhatsAppAutoSend.claimHead(this, requestId)
        if (claimResult != WhatsAppQueueMutationResult.UPDATED) return

        if (attemptSend(requestId)) {
            WhatsAppAutoSend.markSent(this, requestId)
            WhatsAppAutoSend.advance(this, requestId)
            notifyQueueAdvanced()
            attempts = 0
            lastLoggedProblem = ""
            return
        }
        attempts += 1
        if (attempts >= MAX_ATTEMPTS) {
            WhatsAppAutoSend.markFailed(this, requestId, "max attempts exceeded")
            WhatsAppAutoSend.advance(this, requestId)
            notifyQueueAdvanced()
            attempts = 0
            return
        }
        val runnable = Runnable { runAttempt(requestId) }
        pendingAttempt = runnable
        // Growing delay: WhatsApp needs a moment to build the chat the first time,
        // and a locked screen may still be waiting for the user to unlock.
        handler.postDelayed(runnable, retryDelayFor(attempts))
    }

    private fun retryDelayFor(attempt: Int): Long =
        (RETRY_DELAY_MS + attempt.toLong() * RETRY_BACKOFF_STEP_MS)
            .coerceAtMost(RETRY_DELAY_MAX_MS)

    private fun attemptSend(requestId: String): Boolean {
        val request = WhatsAppAutoSend.peek(this) ?: return false
        if (request.requestId != requestId) return false
        val root = rootInActiveWindow ?: return false
        val rootPkg = root.packageName?.toString()
        if (rootPkg != WHATSAPP_PACKAGE && rootPkg != WHATSAPP_BUSINESS_PACKAGE) return false
        if (rootPkg != request.expectedPackage) return false
        val input = findInputField(root, request)
        val messageText = input?.text?.toString().orEmpty()
        if (messageText.isBlank()) {
            logProblemOnce("sin campo de texto con el mensaje")
            return false
        }
        if (!WhatsAppAutoSend.messageMatches(request, messageText)) {
            logProblemOnce("el mensaje del campo no coincide con el autorizado")
            return false
        }
        val sendButton = findSendButton(root, rootPkg)
        if (sendButton == null) {
            logProblemOnce("no se encontro el boton de enviar")
            return false
        }
        return sendButton.performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }

    private fun logProblemOnce(problem: String) {
        if (problem == lastLoggedProblem) return
        lastLoggedProblem = problem
        SafetyStateStore.log("WhatsApp autoeenvio: $problem")
    }

    private fun notifyQueueAdvanced() {
        val intent = Intent(this, SafetyMonitorService::class.java)
            .setAction(SafetyMonitorService.ACTION_WHATSAPP_ADVANCE)
        runCatching { ContextCompat.startForegroundService(this, intent) }
    }

    private fun findInputField(
        root: AccessibilityNodeInfo,
        request: WhatsAppSendRequest
    ): AccessibilityNodeInfo? {
        val fields = collectByTraversal(root) { node ->
            node.className?.toString()?.contains("EditText") == true &&
                !node.text.isNullOrBlank()
        }
        if (fields.isEmpty()) return null
        // The composer is the field holding our message. Without an exact match
        // fall back to the last one, which is where WhatsApp places it.
        return fields.firstOrNull { node ->
            WhatsAppAutoSend.messageMatches(request, node.text?.toString().orEmpty())
        } ?: fields.last()
    }

    private fun findSendButton(root: AccessibilityNodeInfo, pkg: String): AccessibilityNodeInfo? {
        SEND_VIEW_IDS.forEach { id ->
            val matches = runCatching {
                root.findAccessibilityNodeInfosByViewId("$pkg:id/$id")
            }.getOrNull().orEmpty()
            val node = matches.firstOrNull { it.isClickable && it.isVisibleToUser }
                ?: matches.firstOrNull { it.isClickable }
            if (node != null) return node
        }
        val described = findByTraversal(root) { node ->
            val description = node.contentDescription?.toString().orEmpty()
            description.isNotBlank() && SEND_DESCRIPTIONS.any { it.equals(description, true) }
        }
        return described?.let(::clickableSelfOrAncestor)
    }

    private fun clickableSelfOrAncestor(node: AccessibilityNodeInfo): AccessibilityNodeInfo {
        var current: AccessibilityNodeInfo? = node
        var depth = 0
        while (current != null && depth < MAX_PARENT_DEPTH) {
            if (current.isClickable) return current
            current = current.parent
            depth += 1
        }
        return node
    }

    private inline fun findByTraversal(
        root: AccessibilityNodeInfo,
        predicate: (AccessibilityNodeInfo) -> Boolean
    ): AccessibilityNodeInfo? {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            if (predicate(node)) return node
            for (index in 0 until node.childCount) {
                node.getChild(index)?.let(queue::addLast)
            }
        }
        return null
    }

    private inline fun collectByTraversal(
        root: AccessibilityNodeInfo,
        predicate: (AccessibilityNodeInfo) -> Boolean
    ): List<AccessibilityNodeInfo> {
        val matches = mutableListOf<AccessibilityNodeInfo>()
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            if (predicate(node)) matches.add(node)
            for (index in 0 until node.childCount) {
                node.getChild(index)?.let(queue::addLast)
            }
        }
        return matches
    }

    companion object {
        private const val WHATSAPP_PACKAGE = "com.whatsapp"
        private const val WHATSAPP_BUSINESS_PACKAGE = "com.whatsapp.w4b"
        private const val INITIAL_DELAY_MS = 1_200L
        private const val RETRY_DELAY_MS = 800L
        private const val RETRY_BACKOFF_STEP_MS = 120L
        private const val RETRY_DELAY_MAX_MS = 2_000L
        private const val MAX_ATTEMPTS = 24
        private const val MAX_PARENT_DEPTH = 4
        private val SEND_VIEW_IDS = listOf(
            "send",
            "send_btn",
            "send_button",
            "btn_send"
        )
        private val SEND_DESCRIPTIONS = listOf("enviar", "send", "send message")
    }
}
