package com.kartingtracker.data

import org.junit.Assert.assertEquals
import org.junit.Test

class FileNameNormalizerTest {
    @Test
    fun normalizesUmlautsToReadableAscii() {
        assertEquals("Loerrach_VM_Kart_Racing", FileNameNormalizer.normalize("Lörrach VM Kart Racing"))
    }

    @Test
    fun keepsSafeCharactersAndCollapsesSeparators() {
        assertEquals("Track_One_Layout", FileNameNormalizer.normalize(" Track   One/Layout "))
    }
}
