package com.intercom.segluky

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ContactsRepositoryTest {
    @Test
    fun canonicalNumberKeepsInternationalPrefix() {
        assertEquals("+34600111222", ContactsRepository.canonicalPhoneNumber("+34 600 111 222"))
    }

    @Test
    fun canonicalNumberStripsFormattingWithoutPlus() {
        assertEquals("34600111222", ContactsRepository.canonicalPhoneNumber("34 (600) 111-222"))
    }

    @Test
    fun canonicalNumberRejectsTooShortValues() {
        assertNull(ContactsRepository.canonicalPhoneNumber("112"))
        assertNull(ContactsRepository.canonicalPhoneNumber("sin numeros"))
    }
}
