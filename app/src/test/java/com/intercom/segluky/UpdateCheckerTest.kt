package com.intercom.segluky

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateCheckerTest {

    @Test
    fun unaVersionPublicadaMasAltaCuentaComoNueva() {
        assertTrue(UpdateChecker.isNewer("1.40s", "1.41"))
        assertTrue(UpdateChecker.isNewer("1.40s", "v1.41"))
        assertTrue(UpdateChecker.isNewer("1.40s", "1.41.0"))
        assertTrue(UpdateChecker.isNewer("1.9s", "1.10"))
    }

    @Test
    fun laMismaVersionNoCuentaComoNueva() {
        assertFalse(UpdateChecker.isNewer("1.40s", "1.40"))
        assertFalse(UpdateChecker.isNewer("1.40s", "V-1.40s"))
        assertFalse(UpdateChecker.isNewer("1.40", "1.40.0"))
    }

    @Test
    fun unaVersionPublicadaMasBajaNoCuentaComoNueva() {
        assertFalse(UpdateChecker.isNewer("1.40s", "1.39"))
        assertFalse(UpdateChecker.isNewer("1.40s", "1.4"))
    }

    @Test
    fun unaEtiquetaSinNumerosNuncaEsMasNueva() {
        assertFalse(UpdateChecker.isNewer("1.40s", ""))
        assertFalse(UpdateChecker.isNewer("1.40s", "ultima"))
    }

    @Test
    fun leeLaEtiquetaYLaPaginaDelRelease() {
        val body = """
            {"url":"https://api.github.com/x","tag_name":"v1.41","name":"1.41s",
             "html_url":"https://github.com/LCJ355/Segu-Luky/releases/tag/v1.41"}
        """.trimIndent()
        assertEquals("v1.41", UpdateChecker.jsonString(body, "tag_name"))
        assertEquals(
            "https://github.com/LCJ355/Segu-Luky/releases/tag/v1.41",
            UpdateChecker.jsonString(body, "html_url")
        )
        assertNull(UpdateChecker.jsonString(body, "no_existe"))
    }

    @Test
    fun leeElApkAdjuntoAlRelease() {
        val body = """
            {"tag_name":"V-1.45s","html_url":"https://github.com/LCJ355/Segu-Luky/releases/tag/V-1.45s",
             "assets":[{"name":"Segu-Luky-1.45s.apk",
                        "browser_download_url":"https://github.com/LCJ355/Segu-Luky/releases/download/V-1.45s/Segu-Luky-1.45s.apk"}]}
        """.trimIndent()
        assertEquals(
            "https://github.com/LCJ355/Segu-Luky/releases/download/V-1.45s/Segu-Luky-1.45s.apk",
            UpdateChecker.apkUrl(body)
        )
    }

    @Test
    fun unReleaseSinApkNoTieneEnlaceDeDescarga() {
        val body = """{"tag_name":"V-1.45s","assets":[]}"""
        assertNull(UpdateChecker.apkUrl(body))
    }

    @Test
    fun separaElListadoDeReleasesEnObjetos() {
        val body = """[{"tag_name":"V-1.45s"},{"tag_name":"V-1.44s"}]"""
        val objects = UpdateChecker.jsonObjects(body)
        assertEquals(2, objects.size)
        assertEquals("V-1.45s", UpdateChecker.jsonString(objects[0], "tag_name"))
        assertEquals("V-1.44s", UpdateChecker.jsonString(objects[1], "tag_name"))
        assertTrue(UpdateChecker.jsonObjects("[]").isEmpty())
    }

    @Test
    fun lasLlavesDentroDeTextosNoRompenElListado() {
        val body = """[{"body":"texto con { y } dentro"},{"tag_name":"V-1.45s"}]"""
        val objects = UpdateChecker.jsonObjects(body)
        assertEquals(2, objects.size)
        assertEquals("V-1.45s", UpdateChecker.jsonString(objects[1], "tag_name"))
    }

    @Test
    fun eligeLaEtiquetaMasAltaAunqueLlegueDesordenada() {
        assertEquals(
            "V-1.44s",
            UpdateChecker.highestTag(listOf("V-1.9s", "V-1.10s", "V-1.44s", "sin-numeros", "V-1.8s"))
        )
        assertEquals(
            "1.10",
            UpdateChecker.highestTag(listOf("1.9", "1.10", "1.8"))
        )
        assertNull(UpdateChecker.highestTag(listOf("ultima", "principal")))
        assertNull(UpdateChecker.highestTag(emptyList()))
    }

    @Test
    fun unaEtiquetaSinReleaseCuentaAunqueHayaReleasesAntiguas() {
        // Este fue el fallo real: la 1.48s se etiqueto sin crear su release y la
        // app solo miraba las etiquetas cuando no habia ninguna release.
        val newest = UpdateChecker.newestOf(
            releases = listOf("V-1.47s" to "https://github.com/x/releases/download/V-1.47s/a.apk"),
            tags = listOf("V-1.48s", "V-1.47s")
        )
        assertEquals("V-1.48s", newest?.first)
        assertEquals(UpdateChecker.APK_FOLDER, newest?.second)
    }

    @Test
    fun laReleaseMandaParaSuEnlaceDeDescarga() {
        val apk = "https://github.com/x/releases/download/V-1.48s/Segu-Luky-1.48s.apk"
        val newest = UpdateChecker.newestOf(
            releases = listOf("V-1.48s" to apk),
            tags = listOf("V-1.48s", "V-1.49s-beta")
        )
        assertEquals("V-1.49s-beta", newest?.first)
        assertEquals(
            apk,
            UpdateChecker.newestOf(releases = listOf("V-1.48s" to apk), tags = listOf("V-1.48s"))?.second
        )
    }

    @Test
    fun sinNadaPublicadoNoHayVersion() {
        assertNull(UpdateChecker.newestOf(emptyList(), emptyList()))
        assertNull(UpdateChecker.newestOf(emptyList(), listOf("ultima", "principal")))
    }

    @Test
    fun leeLosNombresDeEtiquetaDelJson() {
        val body = """[{"name":"V-1.48s","zipball_url":"z"},{"name":"V-1.47s","zipball_url":"z"}]"""
        assertEquals(listOf("V-1.48s", "V-1.47s"), UpdateChecker.tagNames(body))
        assertTrue(UpdateChecker.tagNames("[]").isEmpty())
    }
}
