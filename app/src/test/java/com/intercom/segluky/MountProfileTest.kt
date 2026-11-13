package com.intercom.segluky

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MountProfileTest {
    @Test
    fun unknownOrMissingIdFallsBackToUndefined() {
        assertEquals(MountProfile.UNDEFINED, MountProfile.fromId(null))
        assertEquals(MountProfile.UNDEFINED, MountProfile.fromId(""))
        assertEquals(MountProfile.UNDEFINED, MountProfile.fromId("inventado"))
    }

    @Test
    fun knownIdsRoundTrip() {
        MountProfile.entries.forEach { profile ->
            assertEquals(profile, MountProfile.fromId(profile.id))
        }
    }

    @Test
    fun undefinedIsTheOnlyNonDefinedProfile() {
        assertFalse(MountProfile.UNDEFINED.isDefined)
        MountProfile.entries
            .filterNot { it == MountProfile.UNDEFINED }
            .forEach { assertTrue(it.isDefined) }
    }
}
