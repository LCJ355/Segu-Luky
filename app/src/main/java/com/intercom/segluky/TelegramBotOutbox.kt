package com.intercom.segluky

import android.annotation.SuppressLint
import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Un aviso del bot de Telegram pendiente de entregar. */
internal data class TelegramBotJob(
    val eventId: String,
    val chatId: String,
    val message: String,
    val attempts: Int = 0,
    val nextAttemptAtWall: Long = 0L,
    val delivered: Boolean = false,
    val lastError: String = ""
)

/**
 * Espera creciente entre intentos del bot de Telegram.
 *
 * Un accidente suele ocurrir donde la cobertura es mala o los datos tardan en
 * volver: un unico POST (lo que hacia antes) pierde el aviso para siempre. La
 * escala suma 8 min y media, dentro de la ventana de 10 min que ya vigila el
 * despacho, asi que cinco fallos no alargan el protocolo mas de lo previsto.
 */
internal object TelegramBotRetry {
    const val MAX_ATTEMPTS = 5
    private val DELAYS_MS = longArrayOf(20_000L, 60_000L, 120_000L, 300_000L)

    /**
     * Fallos que no mejoran esperando: token revocado, chat inexistente o el
     * contacto no inicio el bot. Reintentarlos solo gasta la ventana del despacho.
     */
    private val PERMANENT = listOf(
        "token del bot no valido",
        "chat id no valido",
        "HTTP 400",
        "HTTP 401",
        "HTTP 403",
        "HTTP 404",
        "chat not found",
        "bot was blocked",
        "bot can't initiate",
        "Forbidden",
        "Unauthorized"
    )

    /** Espera tras [failures] fallos, o null cuando ya no se reintenta. */
    fun delayAfter(failures: Int): Long? =
        if (failures <= 0) 0L else DELAYS_MS.getOrNull(failures - 1)

    /** true mientras queden intentos disponibles. */
    fun canRetry(failures: Int): Boolean = failures < MAX_ATTEMPTS

    /** true cuando el detalle del Bot API describe un fallo definitivo. */
    fun isPermanent(detail: String): Boolean =
        detail.isNotBlank() && PERMANENT.any { detail.contains(it, ignoreCase = true) }
}

/** Resumen del estado de una entrega de Telegram. */
internal data class TelegramBotStatus(
    val total: Int,
    val delivered: Int,
    val pending: Int,
    val exhausted: Int,
    val failures: String
) {
    val finished: Boolean get() = pending == 0 && exhausted == 0
}

/**
 * Almacen de la cola.
 *
 * Existe para poder probar la cola sin un `Context`: las pruebas usan una version en
 * memoria que imita el almacen real. [write] tiene que dejar el dato persistido antes
 * de volver (el `apply()` de SharedPreferences no lo garantiza si el proceso muere).
 */
internal interface TelegramOutboxStore {
    fun read(): String
    fun write(raw: String)
}

private class PreferencesOutboxStore(context: Context) : TelegramOutboxStore {
    private val preferences = context.applicationContext
        .getSharedPreferences("segu_telegram_bot", Context.MODE_PRIVATE)

    override fun read(): String = preferences.getString(KEY_JOBS, "") ?: ""

    // commit() y no apply(): la cola guarda avisos de SOS y apply() deja la escritura
    // en segundo plano, asi que un proceso muerto justo despues puede perderla.
    @SuppressLint("ApplySharedPref")
    override fun write(raw: String) {
        preferences.edit().putString(KEY_JOBS, raw).commit()
    }

    private companion object {
        const val KEY_JOBS = "jobs"
    }
}

/**
 * Cola durable de los avisos del bot de Telegram.
 *
 * Cada contacto es un trabajo independiente que sobrevive a reinicios y que se
 * reintenta con espera creciente: el WhatsApp ya tenia cola, Telegram no, y es
 * justo el canal que funciona con el movil en el bolsillo.
 */
internal class TelegramBotOutbox internal constructor(private val store: TelegramOutboxStore) {
    constructor(context: Context) : this(PreferencesOutboxStore(context))

    /** Anade los avisos de un evento que aun no estaban en la cola. */
    fun enqueue(
        eventId: String,
        targets: List<Pair<String, String>>,
        nowWall: Long = System.currentTimeMillis()
    ) {
        if (eventId.isBlank() || targets.isEmpty()) return
        synchronized(LOCK) {
            val jobs = loadLocked().toMutableList()
            val known = jobs.filter { it.eventId == eventId }.map { it.chatId }.toSet()
            var changed = false
            targets.forEach { (chatId, message) ->
                if (chatId.isNotBlank() && chatId !in known) {
                    jobs += TelegramBotJob(
                        eventId = eventId,
                        chatId = chatId,
                        message = message,
                        nextAttemptAtWall = nowWall
                    )
                    changed = true
                }
            }
            if (changed) saveLocked(jobs)
        }
    }

    /** Trabajos que toca intentar ahora. */
    fun due(nowWall: Long = System.currentTimeMillis()): List<TelegramBotJob> {
        val jobs = synchronized(LOCK) { loadLocked() }
        return jobs.filter {
            !it.delivered &&
                TelegramBotRetry.canRetry(it.attempts) &&
                it.nextAttemptAtWall <= nowWall
        }
    }

    /** Momento del siguiente reintento pendiente, o null si no queda ninguno. */
    fun nextAttemptAtWall(): Long? {
        val jobs = synchronized(LOCK) { loadLocked() }
        return jobs.filter { !it.delivered && TelegramBotRetry.canRetry(it.attempts) }
            .minOfOrNull { it.nextAttemptAtWall }
    }

    /**
     * Anota el resultado de un intento.
     *
     * [permanent] marca el trabajo como agotado sin esperar: no tiene sentido
     * reintentar un token revocado o un chat al que el bot no puede escribir.
     */
    fun record(
        job: TelegramBotJob,
        ok: Boolean,
        error: String = "",
        nowWall: Long = System.currentTimeMillis(),
        permanent: Boolean = false
    ) {
        synchronized(LOCK) {
            val jobs = loadLocked().toMutableList()
            val index = jobs.indexOfFirst { it.eventId == job.eventId && it.chatId == job.chatId }
            if (index < 0) return
            val current = jobs[index]
            jobs[index] = if (ok) {
                current.copy(delivered = true, attempts = current.attempts + 1, lastError = "")
            } else {
                val failures = if (permanent) {
                    TelegramBotRetry.MAX_ATTEMPTS
                } else {
                    current.attempts + 1
                }
                current.copy(
                    attempts = failures,
                    lastError = error.ifBlank { "sin respuesta" },
                    nextAttemptAtWall = nowWall + (TelegramBotRetry.delayAfter(failures) ?: 0L)
                )
            }
            saveLocked(jobs)
        }
    }

    fun status(eventId: String): TelegramBotStatus =
        summarize(synchronized(LOCK) { loadLocked() }.filter { it.eventId == eventId })

    /**
     * Resumen final de un evento y borrado de su cola en una sola operacion.
     *
     * Leer el resumen y borrar por separado daba dos fotos distintas: un trabajo que
     * se entregaba entre las dos llamadas aparecia como "sin entregar" en el registro
     * aunque hubiera llegado. Con el candado se informa exactamente de lo que se
     * descarta.
     */
    fun closeEvent(eventId: String): TelegramBotStatus = synchronized(LOCK) {
        val jobs = loadLocked()
        val eventJobs = jobs.filter { it.eventId == eventId }
        val kept = jobs.filterNot { it.eventId == eventId }
        if (kept.size != jobs.size) saveLocked(kept)
        summarize(eventJobs)
    }

    fun clearEvent(eventId: String) {
        synchronized(LOCK) {
            val jobs = loadLocked()
            val kept = jobs.filterNot { it.eventId == eventId }
            if (kept.size != jobs.size) saveLocked(kept)
        }
    }

    fun clear() {
        synchronized(LOCK) { store.write("") }
    }

    private fun summarize(jobs: List<TelegramBotJob>): TelegramBotStatus {
        if (jobs.isEmpty()) return TelegramBotStatus(0, 0, 0, 0, "")
        val delivered = jobs.count { it.delivered }
        val failed = jobs.filter { !it.delivered }
        val pending = failed.count { TelegramBotRetry.canRetry(it.attempts) }
        val exhausted = failed.size - pending
        val failures = failed
            .filter { it.lastError.isNotBlank() }
            .joinToString("; ") { "${it.chatId} (${it.lastError})" }
            .take(160)
        return TelegramBotStatus(jobs.size, delivered, pending, exhausted, failures)
    }

    /** Lectura de la cola. Solo se llama con [LOCK] tomado. */
    private fun loadLocked(): List<TelegramBotJob> {
        val raw = store.read()
        if (raw.isBlank()) return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    val eventId = item.optString("eventId")
                    val chatId = item.optString("chatId")
                    if (eventId.isBlank() || chatId.isBlank()) continue
                    add(
                        TelegramBotJob(
                            eventId = eventId,
                            chatId = chatId,
                            message = item.optString("message"),
                            attempts = item.optInt("attempts"),
                            nextAttemptAtWall = item.optLong("nextAttemptAtWall"),
                            delivered = item.optBoolean("delivered"),
                            lastError = item.optString("lastError")
                        )
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    /** Escritura de la cola. Solo se llama con [LOCK] tomado. */
    private fun saveLocked(jobs: List<TelegramBotJob>) {
        val array = JSONArray()
        jobs.forEach { job ->
            array.put(
                JSONObject().apply {
                    put("eventId", job.eventId)
                    put("chatId", job.chatId)
                    put("message", job.message)
                    put("attempts", job.attempts)
                    put("nextAttemptAtWall", job.nextAttemptAtWall)
                    put("delivered", job.delivered)
                    put("lastError", job.lastError)
                }
            )
        }
        store.write(array.toString())
    }

    private companion object {

        /**
         * Candado compartido por todas las instancias.
         *
         * Cada `load() + save()` era un ciclo leer-modificar-escribir sin proteccion.
         * El servicio crea una instancia en el hilo del manejador y la cola se toca
         * desde el hilo del trabajador de red: si un `enqueue` leia la lista justo
         * antes de que el trabajador apuntara una entrega, la escritura del `enqueue`
         * borraba ese apunte y el aviso de un contacto se perdia en silencio.
         * El candado va en un objeto compartido (no en `this`) justo porque hay
         * varias instancias vivas a la vez.
         */
        val LOCK = Any()
    }
}
