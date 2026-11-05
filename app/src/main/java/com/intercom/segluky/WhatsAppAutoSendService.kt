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
        if (pendingAttempt != null) return
        attempts = 0
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
            return
        }
        attempts += 1
        if (attempts >= MAX_ATTEMPTS) {
            WhatsAppAutoSend.markFailed(this, requestId, "max attempts exceeded")
            WhatsAppAutoSend.advance(this, requestId)
            notifyQueueAdvanced()
            return
        }
        val runnable = Runnable { runAttempt(requestId) }
        pendingAttempt = runnable
        handler.postDelayed(runnable, RETRY_DELAY_MS)
    }

    private fun attemptSend(requestId: String): Boolean {
        val request = WhatsAppAutoSend.peek(this) ?: return false
        if (request.requestId != requestId) return false
        val root = rootInActiveWindow ?: return false
        val rootPkg = root.packageName?.toString()
        if (rootPkg != WHATSAPP_PACKAGE && rootPkg != WHATSAPP_BUSINESS_PACKAGE) return false
        if (rootPkg != request.expectedPackage) return false
        val input = findInputField(root)
        val messageText = input?.text?.toString().orEmpty()
        if (messageText.isBlank()) return false
        if (!WhatsAppAutoSend.messageMatches(request, messageText)) return false
        val sendButton = findSendButton(root, rootPkg)
        return sendButton != null && sendButton.performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }

    private fun notifyQueueAdvanced() {
        val intent = Intent(this, SafetyMonitorService::class.java)
            .setAction(SafetyMonitorService.ACTION_WHATSAPP_ADVANCE)
        runCatching { ContextCompat.startForegroundService(this, intent) }
    }

    private fun findInputField(root: AccessibilityNodeInfo): AccessibilityNodeInfo? =
        findByTraversal(root) { node ->
            node.className?.toString()?.contains("EditText") == true &&
                !node.text.isNullOrBlank()
        }

    private fun findSendButton(root: AccessibilityNodeInfo, pkg: String): AccessibilityNodeInfo? {
        listOf("$pkg:id/send", "$pkg:id/send_btn").forEach { viewId ->
            val matches = runCatching { root.findAccessibilityNodeInfosByViewId(viewId) }.getOrNull()
                ?: emptyList()
            val node = matches.firstOrNull { it.isClickable }
            if (node != null) return node
        }
        return findByTraversal(root) { node ->
            val description = node.contentDescription?.toString().orEmpty()
            description.isNotBlank() &&
                (description.equals("enviar", true) || description.equals("send", true))
        }
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

    companion object {
        private const val WHATSAPP_PACKAGE = "com.whatsapp"
        private const val WHATSAPP_BUSINESS_PACKAGE = "com.whatsapp.w4b"
        private const val INITIAL_DELAY_MS = 800L
        private const val RETRY_DELAY_MS = 700L
        private const val MAX_ATTEMPTS = 8
    }
}
