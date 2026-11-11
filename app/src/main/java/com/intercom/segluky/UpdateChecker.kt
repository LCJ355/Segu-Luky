package com.intercom.segluky

import java.net.HttpURLConnection
import java.net.URL

/**
 * Comprobacion de la ultima version publicada en GitHub.
 *
 * Solo la usa el menu de informacion y siempre desde un hilo de trabajo: hace una
 * peticion HTTP que puede tardar. Lee el listado de releases y se queda con la
 * version mas alta; si el proyecto todavia no tiene releases mira las etiquetas,
 * asi una version recien etiquetada tambien cuenta. Cuando de verdad no hay nada
 * publicado se informa como tal, sin inventar una version nueva ni bloquear la
 * pantalla.
 */
object UpdateChecker {

    const val REPOSITORY = "LCJ355/Segu-Luky"
    const val RELEASES_PAGE = "https://github.com/LCJ355/Segu-Luky/releases"
    const val APK_FOLDER = "https://github.com/LCJ355/Segu-Luky/tree/main/APK%C2%B4s"
    const val ISSUES_PAGE = "https://github.com/LCJ355/Segu-Luky/issues/new"
    private const val API_ROOT = "https://api.github.com/repos/$REPOSITORY"
    private const val RELEASES_API = "$API_ROOT/releases"
    private const val TAGS_API = "$API_ROOT/tags"
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
    fun check(currentVersion: String): Status =
        runCatching { request(currentVersion) }
            .getOrElse { Status.Failed(it.message?.take(80) ?: "sin conexion") }

    private fun request(currentVersion: String): Status {
        // Se lee el listado completo y se elige la version mas alta: /releases/latest
        // depende del orden de creacion de las releases, no del numero de version.
        val releases = get(RELEASES_API)
        if (releases.code !in 200..299) return Status.Failed("HTTP ${releases.code}")
        val published = jsonObjects(releases.body)
            .mapNotNull { body ->
                val tag = jsonString(body, "tag_name") ?: return@mapNotNull null
                tag to (apkUrl(body) ?: jsonString(body, "html_url") ?: RELEASES_PAGE)
            }
        val newest = newestOf(published) { it.first }
        return if (newest != null) {
            evaluate(currentVersion, newest.first, newest.second)
        } else {
            publishedFromTags(currentVersion)
        }
    }

    /** Sin releases: una etiqueta publicada tambien cuenta como version disponible. */
    private fun publishedFromTags(currentVersion: String): Status {
        val tags = get(TAGS_API)
        if (tags.code !in 200..299) return Status.Failed("HTTP ${tags.code}")
        val newestTag = highestTag(
            Regex("\"name\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").findAll(tags.body)
                .map { it.groupValues[1] }
                .toList()
        ) ?: return Status.NoReleases
        return evaluate(currentVersion, newestTag, APK_FOLDER)
    }

    private fun evaluate(currentVersion: String, tag: String, url: String): Status =
        if (isNewer(currentVersion, tag)) Status.Available(tag, url) else Status.UpToDate

    /** Compara nombres de version tipo "1.40s", "v1.41" o "1.41.0" parte a parte. */
    fun isNewer(current: String, published: String): Boolean {
        if (versionNumbers(published).isEmpty()) return false
        return compareVersions(published, current) > 0
    }

    /** La etiqueta mas alta de una lista, o null si ninguna tiene numeros. */
    internal fun highestTag(tags: List<String>): String? =
        tags.filter { versionNumbers(it).isNotEmpty() }
            .maxWithOrNull { first, second -> compareVersions(first, second) }

    private fun <T> newestOf(items: List<T>, tagOf: (T) -> String): T? =
        items.filter { versionNumbers(tagOf(it)).isNotEmpty() }
            .maxWithOrNull { first, second -> compareVersions(tagOf(first), tagOf(second)) }

    private fun compareVersions(first: String, second: String): Int {
        val left = versionNumbers(first)
        val right = versionNumbers(second)
        for (index in 0 until maxOf(left.size, right.size)) {
            val difference = left.getOrElse(index) { 0 } - right.getOrElse(index) { 0 }
            if (difference != 0) return difference
        }
        return 0
    }

    private class Response(val code: Int, val body: String)

    private fun get(address: String): Response {
        val connection = URL(address).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "GET"
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS
            connection.setRequestProperty("Accept", "application/vnd.github+json")
            connection.setRequestProperty("User-Agent", "Segu-Luky")
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            return Response(code, body)
        } finally {
            connection.disconnect()
        }
    }

    /** Lee un campo de texto del JSON de GitHub sin anadir dependencias. */
    internal fun jsonString(body: String, field: String): String? {
        val pattern = Regex("\"" + Regex.escape(field) + "\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"")
        return pattern.find(body)?.groupValues?.getOrNull(1)
    }

    /** Parte un array JSON en sus objetos de primer nivel. */
    internal fun jsonObjects(array: String): List<String> {
        val objects = mutableListOf<String>()
        var depth = 0
        var start = -1
        var insideString = false
        var escaped = false
        for (index in array.indices) {
            val char = array[index]
            if (insideString) {
                when {
                    escaped -> escaped = false
                    char == '\\' -> escaped = true
                    char == '"' -> insideString = false
                }
                continue
            }
            when (char) {
                '"' -> insideString = true
                '{' -> {
                    if (depth == 0) start = index
                    depth++
                }
                '}' -> {
                    depth--
                    if (depth == 0 && start >= 0) {
                        objects += array.substring(start, index + 1)
                        start = -1
                    }
                }
            }
        }
        return objects
    }

    /** Enlace de descarga del APK adjunto al release, si lo tiene. */
    internal fun apkUrl(body: String): String? =
        Regex("\"browser_download_url\"\\s*:\\s*\"([^\"]+?\\.apk)\"", RegexOption.IGNORE_CASE)
            .find(body)?.groupValues?.getOrNull(1)

    private fun versionNumbers(value: String): List<Int> {
        val trimmed = value.trim().dropWhile { !it.isDigit() }
        if (trimmed.isEmpty()) return emptyList()
        return trimmed.split('.')
            .mapNotNull { part -> part.takeWhile(Char::isDigit).toIntOrNull() }
    }
}
