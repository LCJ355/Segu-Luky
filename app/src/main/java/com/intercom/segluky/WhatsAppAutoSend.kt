package com.intercom.segluky

import android.content.Context
import android.content.SharedPreferences
import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.text.Normalizer
import java.util.UUID

enum class WhatsAppRequestStatus {
    PENDING,
    PROCESSING,
    SENT,
    FAILED,
    EXPIRED;

    val isTerminal: Boolean
        get() = this == SENT || this == FAILED || this == EXPIRED
}

data class WhatsAppSendRequest(
    val eventId: String,
    val requestId: String,
    val recipient: String,
    val expectedPackage: String,
    val normalizedMessage: String,
    val messageSha256: String,
    val issuedElapsedRealtimeMillis: Long,
    val issuedWallTimeMillis: Long,
    val ttlMillis: Long,
    val status: WhatsAppRequestStatus = WhatsAppRequestStatus.PENDING,
    val outcomeDetail: String? = null,
    val completedWallTimeMillis: Long? = null
)

enum class WhatsAppEnqueueStatus {
    QUEUED,
    DISABLED,
    INVALID_EVENT_ID,
    INVALID_REQUEST_ID,
    INVALID_RECIPIENT,
    INVALID_PACKAGE,
    INVALID_MESSAGE,
    INVALID_TTL,
    DUPLICATE_REQUEST_ID,
    QUEUE_FULL,
    PERSISTENCE_FAILED
}

data class WhatsAppEnqueueResult(
    val status: WhatsAppEnqueueStatus,
    val request: WhatsAppSendRequest? = null
) {
    val queued: Boolean
        get() = status == WhatsAppEnqueueStatus.QUEUED
}

enum class WhatsAppQueueMutationResult {
    UPDATED,
    DISABLED,
    NOT_FOUND,
    NOT_HEAD,
    INVALID_STATE,
    EXPIRED,
    PERSISTENCE_FAILED
}

/** Durable authorization contract between the SOS producer and accessibility service. */
object WhatsAppAutoSend {
    const val WHATSAPP_PACKAGE = "com.whatsapp"
    const val WHATSAPP_BUSINESS_PACKAGE = "com.whatsapp.w4b"
    const val DEFAULT_REQUEST_TTL_MILLIS = 45_000L
    const val MAX_REQUEST_TTL_MILLIS = 120_000L

    private const val PREFERENCES = "segu_prefs"
    private const val KEY_ENABLED = "whatsapp_auto_send_enabled"
    private const val KEY_QUEUE = "whatsapp_auto_send_queue_v2"

    // Removed from authorization decisions, but cleared whenever state is touched.
    private const val LEGACY_KEY_PENDING = "whatsapp_auto_send_pending"
    private const val LEGACY_KEY_EXPECTED_TEXT = "whatsapp_auto_send_expected"

    private const val KEY_EVENT_ID = "event_id"
    private const val KEY_REQUEST_ID = "request_id"
    private const val KEY_RECIPIENT = "recipient"
    private const val KEY_EXPECTED_PACKAGE = "expected_package"
    private const val KEY_NORMALIZED_MESSAGE = "normalized_message"
    private const val KEY_MESSAGE_SHA256 = "message_sha256"
    private const val KEY_ISSUED_ELAPSED = "issued_elapsed_realtime_ms"
    private const val KEY_ISSUED_WALL = "issued_wall_time_ms"
    private const val KEY_TTL = "ttl_ms"
    private const val KEY_STATUS = "status"
    private const val KEY_OUTCOME_DETAIL = "outcome_detail"
    private const val KEY_COMPLETED_WALL = "completed_wall_time_ms"

    private const val MAX_QUEUE_SIZE = 32
    private const val MAX_ID_LENGTH = 160
    private const val MAX_MESSAGE_LENGTH = 8_192
    private const val MAX_OUTCOME_DETAIL_LENGTH = 240
    private const val MAX_CLOCK_DIVERGENCE_MILLIS = 5_000L
    private val lock = Any()

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    fun isEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_ENABLED, false)

    /** A false result means the in-memory change was made but disk persistence failed. */
    fun setEnabled(context: Context, enabled: Boolean): Boolean = synchronized(lock) {
        val editor = prefs(context).edit()
            .putBoolean(KEY_ENABLED, enabled)
            .remove(LEGACY_KEY_PENDING)
            .remove(LEGACY_KEY_EXPECTED_TEXT)
        if (!enabled) editor.remove(KEY_QUEUE)
        runCatching { editor.commit() }.getOrDefault(false)
    }

    fun isSupportedPackage(packageName: String): Boolean =
        packageName == WHATSAPP_PACKAGE || packageName == WHATSAPP_BUSINESS_PACKAGE

    /**
     * Adds one identity-bound authorization to the tail of the persisted FIFO.
     * Call this only after resolving an installed WhatsApp package; browser URLs are not valid packages.
     */
    fun enqueue(
        context: Context,
        eventId: String,
        recipient: String,
        expectedPackage: String,
        message: String,
        requestId: String = UUID.randomUUID().toString(),
        ttlMillis: Long = DEFAULT_REQUEST_TTL_MILLIS
    ): WhatsAppEnqueueResult = synchronized(lock) {
        val preferences = prefs(context)
        clearLegacyAuthorization(preferences)
        if (!preferences.getBoolean(KEY_ENABLED, false)) {
            clearQueue(preferences)
            return@synchronized WhatsAppEnqueueResult(WhatsAppEnqueueStatus.DISABLED)
        }

        val canonicalEventId = eventId.trim()
        if (canonicalEventId.isEmpty() || canonicalEventId.length > MAX_ID_LENGTH) {
            return@synchronized WhatsAppEnqueueResult(WhatsAppEnqueueStatus.INVALID_EVENT_ID)
        }
        val canonicalRequestId = requestId.trim()
        if (canonicalRequestId.isEmpty() || canonicalRequestId.length > MAX_ID_LENGTH) {
            return@synchronized WhatsAppEnqueueResult(WhatsAppEnqueueStatus.INVALID_REQUEST_ID)
        }
        val canonicalRecipient = ContactsRepository.canonicalPhoneNumber(recipient)
            ?: return@synchronized WhatsAppEnqueueResult(WhatsAppEnqueueStatus.INVALID_RECIPIENT)
        if (!isSupportedPackage(expectedPackage)) {
            return@synchronized WhatsAppEnqueueResult(WhatsAppEnqueueStatus.INVALID_PACKAGE)
        }
        val normalizedMessage = normalizeMessage(message)
        if (normalizedMessage.isEmpty() || normalizedMessage.length > MAX_MESSAGE_LENGTH) {
            return@synchronized WhatsAppEnqueueResult(WhatsAppEnqueueStatus.INVALID_MESSAGE)
        }
        if (ttlMillis !in 1..MAX_REQUEST_TTL_MILLIS) {
            return@synchronized WhatsAppEnqueueResult(WhatsAppEnqueueStatus.INVALID_TTL)
        }

        val queue = readQueue(preferences)
        val nowElapsed = SystemClock.elapsedRealtime()
        val nowWall = System.currentTimeMillis()
        expireActiveRequests(queue, nowElapsed, nowWall)
        if (queue.any { it.requestId == canonicalRequestId }) {
            persistQueue(preferences, queue)
            return@synchronized WhatsAppEnqueueResult(WhatsAppEnqueueStatus.DUPLICATE_REQUEST_ID)
        }
        if (queue.size >= MAX_QUEUE_SIZE) {
            persistQueue(preferences, queue)
            return@synchronized WhatsAppEnqueueResult(WhatsAppEnqueueStatus.QUEUE_FULL)
        }

        val request = WhatsAppSendRequest(
            eventId = canonicalEventId,
            requestId = canonicalRequestId,
            recipient = canonicalRecipient,
            expectedPackage = expectedPackage,
            normalizedMessage = normalizedMessage,
            messageSha256 = normalizedMessageSha256(normalizedMessage),
            issuedElapsedRealtimeMillis = nowElapsed,
            issuedWallTimeMillis = nowWall,
            ttlMillis = ttlMillis
        )
        queue += request
        if (!persistQueue(preferences, queue)) {
            WhatsAppEnqueueResult(WhatsAppEnqueueStatus.PERSISTENCE_FAILED)
        } else {
            WhatsAppEnqueueResult(WhatsAppEnqueueStatus.QUEUED, request)
        }
    }

    /** Returns an immutable, ordered snapshot and materializes any expirations first. */
    fun inspectQueue(context: Context): List<WhatsAppSendRequest> = synchronized(lock) {
        val preferences = prefs(context)
        clearLegacyAuthorization(preferences)
        if (!preferences.getBoolean(KEY_ENABLED, false)) {
            clearQueue(preferences)
            return@synchronized emptyList()
        }
        val queue = readQueue(preferences)
        if (expireActiveRequests(queue, SystemClock.elapsedRealtime(), System.currentTimeMillis())) {
            persistQueue(preferences, queue)
        }
        queue.toList()
    }

    fun peek(context: Context): WhatsAppSendRequest? = inspectQueue(context).firstOrNull()

    fun status(context: Context, requestId: String): WhatsAppRequestStatus? =
        inspectQueue(context).firstOrNull { it.requestId == requestId }?.status

    /** Atomically reserves the pending head before accessibility performs a click. */
    internal fun claimHead(context: Context, requestId: String): WhatsAppQueueMutationResult =
        synchronized(lock) {
            val preferences = prefs(context)
            if (!preferences.getBoolean(KEY_ENABLED, false)) {
                clearQueue(preferences)
                return@synchronized WhatsAppQueueMutationResult.DISABLED
            }
            val queue = readQueue(preferences)
            val expired = expireActiveRequests(
                queue,
                SystemClock.elapsedRealtime(),
                System.currentTimeMillis()
            )
            if (queue.firstOrNull()?.let {
                    it.requestId == requestId && it.status == WhatsAppRequestStatus.PROCESSING
                } == true
            ) {
                return@synchronized WhatsAppQueueMutationResult.UPDATED
            }
            val result = validateHead(queue, requestId, WhatsAppRequestStatus.PENDING)
            if (result != null) {
                if (expired && !persistQueue(preferences, queue)) {
                    return@synchronized WhatsAppQueueMutationResult.PERSISTENCE_FAILED
                }
                return@synchronized result
            }
            queue[0] = queue[0].copy(status = WhatsAppRequestStatus.PROCESSING)
            if (persistQueue(preferences, queue)) {
                WhatsAppQueueMutationResult.UPDATED
            } else {
                WhatsAppQueueMutationResult.PERSISTENCE_FAILED
            }
        }

    /** Completes a previously claimed head. This never authorizes another click. */
    internal fun markSent(context: Context, requestId: String): WhatsAppQueueMutationResult =
        completeClaim(context, requestId, WhatsAppRequestStatus.SENT, null)

    /** Marks an exact pending/processing head as failed without removing or skipping it. */
    fun markFailed(
        context: Context,
        requestId: String,
        detail: String? = null
    ): WhatsAppQueueMutationResult = synchronized(lock) {
        val preferences = prefs(context)
        if (!preferences.getBoolean(KEY_ENABLED, false)) {
            clearQueue(preferences)
            return@synchronized WhatsAppQueueMutationResult.DISABLED
        }
        val queue = readQueue(preferences)
        expireActiveRequests(queue, SystemClock.elapsedRealtime(), System.currentTimeMillis())
        val index = queue.indexOfFirst { it.requestId == requestId }
        if (index < 0) return@synchronized WhatsAppQueueMutationResult.NOT_FOUND
        if (index != 0) return@synchronized WhatsAppQueueMutationResult.NOT_HEAD
        val head = queue[0]
        if (head.status == WhatsAppRequestStatus.EXPIRED) {
            persistQueue(preferences, queue)
            return@synchronized WhatsAppQueueMutationResult.EXPIRED
        }
        if (head.status != WhatsAppRequestStatus.PENDING &&
            head.status != WhatsAppRequestStatus.PROCESSING
        ) {
            return@synchronized WhatsAppQueueMutationResult.INVALID_STATE
        }
        queue[0] = head.copy(
            status = WhatsAppRequestStatus.FAILED,
            outcomeDetail = detail?.trim()?.take(MAX_OUTCOME_DETAIL_LENGTH)?.ifEmpty { null },
            completedWallTimeMillis = System.currentTimeMillis()
        )
        if (persistQueue(preferences, queue)) {
            WhatsAppQueueMutationResult.UPDATED
        } else {
            WhatsAppQueueMutationResult.PERSISTENCE_FAILED
        }
    }

    /** Removes only the named terminal head, preventing stale callers from skipping work. */
    fun advance(context: Context, requestId: String): WhatsAppQueueMutationResult =
        synchronized(lock) {
            val preferences = prefs(context)
            if (!preferences.getBoolean(KEY_ENABLED, false)) {
                clearQueue(preferences)
                return@synchronized WhatsAppQueueMutationResult.DISABLED
            }
            val queue = readQueue(preferences)
            expireActiveRequests(queue, SystemClock.elapsedRealtime(), System.currentTimeMillis())
            val index = queue.indexOfFirst { it.requestId == requestId }
            if (index < 0) return@synchronized WhatsAppQueueMutationResult.NOT_FOUND
            if (index != 0) return@synchronized WhatsAppQueueMutationResult.NOT_HEAD
            if (!queue[0].status.isTerminal) {
                return@synchronized WhatsAppQueueMutationResult.INVALID_STATE
            }
            queue.removeAt(0)
            if (persistQueue(preferences, queue)) {
                WhatsAppQueueMutationResult.UPDATED
            } else {
                WhatsAppQueueMutationResult.PERSISTENCE_FAILED
            }
        }

    /** Synchronously removes every current authorization while leaving the setting unchanged. */
    fun disarmAll(context: Context): Boolean = synchronized(lock) {
        val preferences = prefs(context)
        runCatching {
            preferences.edit()
                .remove(KEY_QUEUE)
                .remove(LEGACY_KEY_PENDING)
                .remove(LEGACY_KEY_EXPECTED_TEXT)
                .commit()
        }.getOrDefault(false)
    }

    /**
     * The old contract lacks recipient, event and package identity. It intentionally cannot arm.
     * It remains source-compatible so the isolated rollout fails closed until its caller migrates.
     */
    @Deprecated("Use enqueue with event, recipient and exact installed WhatsApp package")
    @Suppress("UNUSED_PARAMETER")
    fun arm(context: Context, message: String) {
        synchronized(lock) { clearLegacyAuthorization(prefs(context)) }
    }

    @Deprecated("Use peek or inspectQueue")
    fun isArmed(context: Context): Boolean =
        peek(context)?.status == WhatsAppRequestStatus.PENDING

    @Deprecated("Inspect the structured queue head instead")
    fun expectedText(context: Context): String? =
        peek(context)?.takeIf { it.status == WhatsAppRequestStatus.PENDING }?.normalizedMessage

    @Deprecated("Use markFailed/advance for one request, or disarmAll explicitly")
    fun consume(context: Context) {
        disarmAll(context)
    }

    fun normalizeMessage(message: String): String {
        val unicodeNormalized = Normalizer.normalize(message, Normalizer.Form.NFC)
        return buildString(unicodeNormalized.length) {
            var pendingSpace = false
            unicodeNormalized.forEach { character ->
                if (character.isWhitespace() || Character.isSpaceChar(character)) {
                    if (isNotEmpty()) pendingSpace = true
                } else {
                    if (pendingSpace) append(' ')
                    append(character)
                    pendingSpace = false
                }
            }
        }
    }

    fun normalizedMessageSha256(message: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(normalizeMessage(message).toByteArray(Charsets.UTF_8))
        val hex = "0123456789abcdef"
        return buildString(digest.size * 2) {
            digest.forEach { byte ->
                val value = byte.toInt() and 0xff
                append(hex[value ushr 4])
                append(hex[value and 0x0f])
            }
        }
    }

    fun messageMatches(request: WhatsAppSendRequest, actualMessage: String): Boolean {
        val normalizedActual = normalizeMessage(actualMessage)
        return normalizedActual == request.normalizedMessage &&
            normalizedMessageSha256(normalizedActual) == request.messageSha256
    }

    /** Dual-clock validation fails closed on timeout, wall-clock rollback, or reboot discontinuity. */
    fun isExpiredAt(
        request: WhatsAppSendRequest,
        elapsedRealtimeMillis: Long,
        wallTimeMillis: Long
    ): Boolean {
        if (request.ttlMillis !in 1..MAX_REQUEST_TTL_MILLIS) return true
        if (elapsedRealtimeMillis < request.issuedElapsedRealtimeMillis) return true
        if (wallTimeMillis < request.issuedWallTimeMillis) return true
        val elapsedAge = elapsedRealtimeMillis - request.issuedElapsedRealtimeMillis
        val wallAge = wallTimeMillis - request.issuedWallTimeMillis
        if (elapsedAge >= request.ttlMillis || wallAge >= request.ttlMillis) return true
        val clockDivergence = if (elapsedAge >= wallAge) elapsedAge - wallAge else wallAge - elapsedAge
        return clockDivergence > MAX_CLOCK_DIVERGENCE_MILLIS
    }

    private fun completeClaim(
        context: Context,
        requestId: String,
        status: WhatsAppRequestStatus,
        detail: String?
    ): WhatsAppQueueMutationResult = synchronized(lock) {
        val preferences = prefs(context)
        if (!preferences.getBoolean(KEY_ENABLED, false)) {
            clearQueue(preferences)
            return@synchronized WhatsAppQueueMutationResult.DISABLED
        }
        val queue = readQueue(preferences)
        val result = validateHead(queue, requestId, WhatsAppRequestStatus.PROCESSING)
        if (result != null) return@synchronized result
        queue[0] = queue[0].copy(
            status = status,
            outcomeDetail = detail,
            completedWallTimeMillis = System.currentTimeMillis()
        )
        if (persistQueue(preferences, queue)) {
            WhatsAppQueueMutationResult.UPDATED
        } else {
            WhatsAppQueueMutationResult.PERSISTENCE_FAILED
        }
    }

    private fun validateHead(
        queue: List<WhatsAppSendRequest>,
        requestId: String,
        expectedStatus: WhatsAppRequestStatus
    ): WhatsAppQueueMutationResult? {
        val index = queue.indexOfFirst { it.requestId == requestId }
        if (index < 0) return WhatsAppQueueMutationResult.NOT_FOUND
        if (index != 0) return WhatsAppQueueMutationResult.NOT_HEAD
        if (queue[0].status == WhatsAppRequestStatus.EXPIRED) {
            return WhatsAppQueueMutationResult.EXPIRED
        }
        if (queue[0].status != expectedStatus) return WhatsAppQueueMutationResult.INVALID_STATE
        return null
    }

    private fun expireActiveRequests(
        queue: MutableList<WhatsAppSendRequest>,
        elapsedRealtimeMillis: Long,
        wallTimeMillis: Long
    ): Boolean {
        var changed = false
        queue.indices.forEach { index ->
            val request = queue[index]
            if ((request.status == WhatsAppRequestStatus.PENDING ||
                    request.status == WhatsAppRequestStatus.PROCESSING) &&
                isExpiredAt(request, elapsedRealtimeMillis, wallTimeMillis)
            ) {
                queue[index] = request.copy(
                    status = WhatsAppRequestStatus.EXPIRED,
                    outcomeDetail = "authorization expired",
                    completedWallTimeMillis = wallTimeMillis
                )
                changed = true
            }
        }
        return changed
    }

    private fun readQueue(preferences: SharedPreferences): MutableList<WhatsAppSendRequest> {
        val raw = preferences.getString(KEY_QUEUE, null) ?: return mutableListOf()
        val array = runCatching { JSONArray(raw) }.getOrNull()
        if (array == null) {
            clearQueue(preferences)
            return mutableListOf()
        }

        val seenRequestIds = mutableSetOf<String>()
        val queue = mutableListOf<WhatsAppSendRequest>()
        var sanitized = false
        for (index in 0 until array.length()) {
            val request = array.optJSONObject(index)?.let(::decodeRequest)
            if (request == null || !isStructurallyValid(request) ||
                !seenRequestIds.add(request.requestId) || queue.size >= MAX_QUEUE_SIZE
            ) {
                sanitized = true
                continue
            }
            queue += request
        }
        if (sanitized) persistQueue(preferences, queue)
        return queue
    }

    private fun decodeRequest(item: JSONObject): WhatsAppSendRequest? {
        val status = runCatching {
            WhatsAppRequestStatus.valueOf(item.optString(KEY_STATUS, ""))
        }.getOrNull() ?: return null
        return WhatsAppSendRequest(
            eventId = item.optString(KEY_EVENT_ID, ""),
            requestId = item.optString(KEY_REQUEST_ID, ""),
            recipient = item.optString(KEY_RECIPIENT, ""),
            expectedPackage = item.optString(KEY_EXPECTED_PACKAGE, ""),
            normalizedMessage = item.optString(KEY_NORMALIZED_MESSAGE, ""),
            messageSha256 = item.optString(KEY_MESSAGE_SHA256, ""),
            issuedElapsedRealtimeMillis = item.optLong(KEY_ISSUED_ELAPSED, -1L),
            issuedWallTimeMillis = item.optLong(KEY_ISSUED_WALL, -1L),
            ttlMillis = item.optLong(KEY_TTL, -1L),
            status = status,
            outcomeDetail = item.optString(KEY_OUTCOME_DETAIL, "").ifEmpty { null },
            completedWallTimeMillis = if (item.has(KEY_COMPLETED_WALL)) {
                item.optLong(KEY_COMPLETED_WALL, -1L).takeIf { it >= 0L }
            } else {
                null
            }
        )
    }

    private fun isStructurallyValid(request: WhatsAppSendRequest): Boolean {
        if (request.eventId.isBlank() || request.eventId.length > MAX_ID_LENGTH) return false
        if (request.requestId.isBlank() || request.requestId.length > MAX_ID_LENGTH) return false
        if (ContactsRepository.canonicalPhoneNumber(request.recipient) != request.recipient) return false
        if (!isSupportedPackage(request.expectedPackage)) return false
        if (request.normalizedMessage.isEmpty() ||
            request.normalizedMessage.length > MAX_MESSAGE_LENGTH ||
            normalizeMessage(request.normalizedMessage) != request.normalizedMessage
        ) {
            return false
        }
        if (normalizedMessageSha256(request.normalizedMessage) != request.messageSha256) return false
        if (request.issuedElapsedRealtimeMillis < 0L || request.issuedWallTimeMillis <= 0L) return false
        if (request.ttlMillis !in 1..MAX_REQUEST_TTL_MILLIS) return false
        if (request.outcomeDetail?.length?.let { it > MAX_OUTCOME_DETAIL_LENGTH } == true) return false
        return true
    }

    private fun persistQueue(
        preferences: SharedPreferences,
        queue: List<WhatsAppSendRequest>
    ): Boolean {
        val editor = preferences.edit()
            .remove(LEGACY_KEY_PENDING)
            .remove(LEGACY_KEY_EXPECTED_TEXT)
        if (queue.isEmpty()) {
            editor.remove(KEY_QUEUE)
        } else {
            val array = JSONArray()
            queue.forEach { request ->
                array.put(
                    JSONObject().apply {
                        put(KEY_EVENT_ID, request.eventId)
                        put(KEY_REQUEST_ID, request.requestId)
                        put(KEY_RECIPIENT, request.recipient)
                        put(KEY_EXPECTED_PACKAGE, request.expectedPackage)
                        put(KEY_NORMALIZED_MESSAGE, request.normalizedMessage)
                        put(KEY_MESSAGE_SHA256, request.messageSha256)
                        put(KEY_ISSUED_ELAPSED, request.issuedElapsedRealtimeMillis)
                        put(KEY_ISSUED_WALL, request.issuedWallTimeMillis)
                        put(KEY_TTL, request.ttlMillis)
                        put(KEY_STATUS, request.status.name)
                        request.outcomeDetail?.let { put(KEY_OUTCOME_DETAIL, it) }
                        request.completedWallTimeMillis?.let { put(KEY_COMPLETED_WALL, it) }
                    }
                )
            }
            editor.putString(KEY_QUEUE, array.toString())
        }
        return runCatching { editor.commit() }.getOrDefault(false)
    }

    private fun clearLegacyAuthorization(preferences: SharedPreferences) {
        if (!preferences.contains(LEGACY_KEY_PENDING) &&
            !preferences.contains(LEGACY_KEY_EXPECTED_TEXT)
        ) {
            return
        }
        runCatching {
            preferences.edit()
                .remove(LEGACY_KEY_PENDING)
                .remove(LEGACY_KEY_EXPECTED_TEXT)
                .commit()
        }
    }

    private fun clearQueue(preferences: SharedPreferences): Boolean = runCatching {
        preferences.edit()
            .remove(KEY_QUEUE)
            .remove(LEGACY_KEY_PENDING)
            .remove(LEGACY_KEY_EXPECTED_TEXT)
            .commit()
    }.getOrDefault(false)
}
