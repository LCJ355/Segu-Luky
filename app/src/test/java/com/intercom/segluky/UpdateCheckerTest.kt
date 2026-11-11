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
}
