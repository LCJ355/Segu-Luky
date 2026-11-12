package com.intercom.segluky

import java.util.concurrent.CountDownLatch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * La cola guarda los avisos del bot de Telegram que no salieron al primer intento.
 *
 * La prueba de concurrencia reproduce el fallo de 1.52s: cada mutacion era un ciclo
 * leer-modificar-escribir sin candado, asi que si el hilo principal escribia justo
 * despues de que el hilo de red apuntara una entrega, esa escritura borraba el apunte
 * y el contacto se quedaba sin aviso y sin reintento.
 */
class TelegramBotOutboxTest {

    /** Almacen en memoria; [latencyMillis] ensancha la ventana del ciclo de escritura. */
    private class SlowStore(private val latencyMillis: Long = 0L) : TelegramOutboxStore {
        @Volatile
        private var raw: String = ""

        override fun read(): String {
            if (latencyMillis > 0) Thread.sleep(latencyMillis)
            return raw
        }

        override fun write(raw: String) {
            if (latencyMillis > 0) Thread.sleep(latencyMillis)
            this.raw = raw
        }
    }

    private fun outbox(store: TelegramOutboxStore = SlowStore()) = TelegramBotOutbox(store)

    @Test
    fun enqueueGuardaUnTrabajoPorContacto() {
        val outbox = outbox()
        outbox.enqueue("evt", listOf("111" to "hola", "222" to "hola"), nowWall = 1_000L)

        assertEquals(listOf("111", "222"), outbox.due(1_000L).map { it.chatId })
        assertEquals(2, outbox.status("evt").total)
        assertEquals(2, outbox.status("evt").pending)
    }

    @Test
    fun enqueueNoDuplicaElMismoContacto() {
        val outbox = outbox()
        outbox.enqueue("evt", listOf("111" to "hola"), nowWall = 1_000L)
        outbox.enqueue("evt", listOf("111" to "hola"), nowWall = 2_000L)

        assertEquals(1, outbox.status("evt").total)
    }

    @Test
    fun entregaCorrectaDejaDeEstarPendiente() {
        val outbox = outbox()
        outbox.enqueue("evt", listOf("111" to "hola", "222" to "hola"), nowWall = 1_000L)
        val job = outbox.due(1_000L).first()

        outbox.record(job, ok = true, nowWall = 1_500L)

        assertEquals(listOf("222"), outbox.due(1_000L).map { it.chatId })
        assertEquals(1, outbox.status("evt").delivered)
        assertEquals(1, outbox.status("evt").pending)
    }

    @Test
    fun falloTemporalEsperaAntesDeReintentar() {
        val outbox = outbox()
        outbox.enqueue("evt", listOf("111" to "hola"), nowWall = 1_000L)
        val job = outbox.due(1_000L).single()

        outbox.record(job, ok = false, error = "timeout", nowWall = 2_000L)

        assertTrue(outbox.due(2_000L).isEmpty())
        assertEquals(22_000L, outbox.nextAttemptAtWall())
        assertEquals(1, outbox.due(22_000L).size)
        assertEquals("111 (timeout)", outbox.status("evt").failures)
    }

    @Test
    fun falloDefinitivoAgotaLosIntentos() {
        val outbox = outbox()
        outbox.enqueue("evt", listOf("111" to "hola"), nowWall = 1_000L)
        val job = outbox.due(1_000L).single()

        outbox.record(job, ok = false, error = "HTTP 403", permanent = true, nowWall = 2_000L)

        assertTrue(outbox.due(Long.MAX_VALUE / 2).isEmpty())
        assertEquals(null, outbox.nextAttemptAtWall())
        assertEquals(1, outbox.status("evt").exhausted)
    }

    @Test
    fun recordDeUnTrabajoQueYaNoEstaNoAnadeNada() {
        val outbox = outbox()
        outbox.enqueue("evt", listOf("111" to "hola"), nowWall = 1_000L)
        val job = outbox.due(1_000L).single()
        outbox.clearEvent("evt")

        outbox.record(job, ok = true, nowWall = 2_000L)

        assertEquals(0, outbox.status("evt").total)
    }

    @Test
    fun clearEventSoloBorraSuEvento() {
        val outbox = outbox()
        outbox.enqueue("evt-1", listOf("111" to "hola"), nowWall = 1_000L)
        outbox.enqueue("evt-2", listOf("222" to "hola"), nowWall = 1_000L)

        outbox.clearEvent("evt-1")

        assertEquals(0, outbox.status("evt-1").total)
        assertEquals(1, outbox.status("evt-2").total)
    }

    @Test
    fun closeEventInformaYDescartaEnUnaSolaOperacion() {
        val outbox = outbox()
        outbox.enqueue("evt", listOf("111" to "hola", "222" to "hola"), nowWall = 1_000L)
        outbox.record(outbox.due(1_000L).first(), ok = true, nowWall = 1_100L)

        val status = outbox.closeEvent("evt")

        assertEquals(2, status.total)
        assertEquals(1, status.delivered)
        assertEquals(1, status.pending)
        assertEquals(0, outbox.status("evt").total)
    }

    @Test
    fun laColaPersisteEntreInstancias() {
        val store = SlowStore()
        outbox(store).enqueue("evt", listOf("111" to "hola"), nowWall = 1_000L)

        assertEquals(listOf("111"), outbox(store).due(1_000L).map { it.chatId })
    }

    /**
     * El hilo que anota entregas y el que apunta contactos nuevos escriben a la vez.
     * Sin el candado compartido, algunas entregas se perdian y esos contactos volvian
     * a recibir el aviso (o se quedaban sin el reintento esperado).
     */
    @Test
    fun dosHilosEscribiendoALaVezNoPierdenTrabajos() {
        repeat(5) { round ->
            val store = SlowStore(latencyMillis = 1L)
            val outbox = outbox(store)
            val chats = (0 until 10).map { "1$round$it" }
            outbox.enqueue("evt", chats.map { it to "sos" }, nowWall = 1_000L)

            val pending = outbox.due(1_000L)
            val start = CountDownLatch(1)
            val deliveries = Thread {
                start.await()
                pending.forEach { outbox.record(it, ok = true, nowWall = 2_000L) }
            }
            val newcomers = Thread {
                start.await()
                (0 until 10).forEach { index ->
                    outbox.enqueue("evt", listOf("2$round$index" to "sos"), nowWall = 1_000L)
                }
            }

            deliveries.start()
            newcomers.start()
            start.countDown()
            deliveries.join()
            newcomers.join()

            val status = outbox.status("evt")
            assertEquals("ronda $round: trabajos perdidos", 20, status.total)
            assertEquals("ronda $round: entregas perdidas", 10, status.delivered)
            assertEquals("ronda $round: pendientes", 10, status.pending)
        }
    }
}
