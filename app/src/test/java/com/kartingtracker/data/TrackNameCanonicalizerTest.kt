package com.kartingtracker.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackNameCanonicalizerTest {
    @Test
    fun canonicalizesLoerrachVariantsToHumanReadableName() {
        assertEquals("Lörrach VM Kart Racing", TrackNameCanonicalizer.canonicalizeDisplayName("L_rrach VM Kart Racing"))
        assertEquals("Lörrach VM Kart Racing", TrackNameCanonicalizer.canonicalizeDisplayName("Loerrach VM Kart Racing"))
        assertEquals("Lörrach VM Kart Racing", TrackNameCanonicalizer.canonicalizeDisplayName("Lörrach"))
        assertEquals("Lörrach VM Kart Racing", TrackNameCanonicalizer.canonicalizeDisplayName("Loerrach"))
        assertEquals("Lörrach VM Kart Racing", TrackNameCanonicalizer.canonicalizeDisplayName("L_rrach"))
    }

    @Test
    fun providesLegacyStorageKeysForFallbackLoading() {
        val keys = TrackNameCanonicalizer.possibleStorageKeys("Lörrach VM Kart Racing")
        assertTrue(keys.contains("Loerrach_VM_Kart_Racing"))
        assertTrue(keys.contains("L_rrach_VM_Kart_Racing"))
        assertTrue(TrackNameCanonicalizer.possibleStorageKeys("Rheinfelden Kartbahn").contains("Rheinfelden_Kartbahn"))
    }
}
