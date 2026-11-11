package com.intercom.segluky

import java.net.HttpURLConnection
import java.net.URL

/**
 * Comprobacion de la ultima version publicada en GitHub.
 *
 * Solo la usa el menu de informacion y siempre desde un hilo de trabajo: hace una
 * peticion HTTP que puede tardar. Cuando el proyecto no tiene ninguna version
 * publicada la respuesta de GitHub es 404 y se informa como tal, sin inventar
 * una version nueva ni bloquear la pantalla.
 */
object UpdateChecker {

    const val REPOSITORY = "LCJ355/Segu-Luky"
    const val RELEASES_PAGE = "https://github.com/LCJ355/Segu-Luky/releases"
    const val ISSUES_PAGE = "https://github.com/LCJ355/Segu-Luky/issues/new"
    private const val LATEST_RELEASE_API = "https://api.github.com/repos/$REPOSITORY/releases/latest"
    private const val TIMEOUT_MS = 6_000

    sealed class Status {
        /** Hay una version publicada mas reciente que la instalada. */
        data class Available(val tag: String, val url: String) : Status()

        /** La version instalada es la ultima publicada. */
        data object UpToDate : Status()

        /** El proyecto todavia no tiene ninguna version publicada. */
        data object NoReleases : Status()

        data class Failed(val detail: String) : Status()
    }

    /** Bloqueante: llamar siempre desde un hilo de trabajo. */
    fun check(currentVersion: String): Status = runCatching {
        val connection = URL(LATEST_RELEASE_API).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "GET"
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS
            connection.setRequestProperty("Accept", "application/vnd.github+json")
            connection.setRequestProperty("User-Agent", "Segu-Luky")
            when (val code = connection.responseCode) {
                HttpURLConnection.HTTP_NOT_FOUND -> Status.NoReleases
                !in 200..299 -> Status.Failed("HTTP $code")
                else -> {
                    val body = connection.inputStream.bufferedReader().use { it.readText() }
                    val tag = jsonString(body, "tag_name")
                    if (tag.isNullOrBlank()) {
                        Status.Failed("respuesta sin version")
                    } else if (isNewer(currentVersion, tag)) {
                        Status.Available(tag, jsonString(body, "html_url") ?: RELEASES_PAGE)
                    } else {
                        Status.UpToDate
                    }
                }
            }
        } finally {
            connection.disconnect()
        }
    }.getOrElse { Status.Failed(it.message?.take(80) ?: "sin conexion") }

    /** Compara nombres de version tipo "1.40s", "v1.41" o "1.41.0" parte a parte. */
    fun isNewer(current: String, published: String): Boolean {
        val local = versionNumbers(current)
        val remote = versionNumbers(published)
        if (remote.isEmpty()) return false
        for (index in 0 until maxOf(local.size, remote.size)) {
            val mine = local.getOrElse(index) { 0 }
            val theirs = remote.getOrElse(index) { 0 }
            if (mine != theirs) return theirs > mine
        }
        return false
    }

    /** Lee un campo de texto del JSON de GitHub sin anadir dependencias. */
    internal fun jsonString(body: String, field: String): String? {
        val pattern = Regex("\"" + Regex.escape(field) + "\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"")
        return pattern.find(body)?.groupValues?.getOrNull(1)
    }

    private fun versionNumbers(value: String): List<Int> {
        val trimmed = value.trim().dropWhile { !it.isDigit() }
        if (trimmed.isEmpty()) return emptyList()
        return trimmed.split('.')
            .mapNotNull { part -> part.takeWhile(Char::isDigit).toIntOrNull() }
    }
}
