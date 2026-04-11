package com.kartingtracker.data

import org.junit.Assert.*
import org.junit.Test

class CircularBufferTest {

    @Test
    fun `add elements under capacity`() {
        val buffer = CircularBuffer<Int>(capacity = 3)

        buffer.add(1)
        buffer.add(2)

        assertEquals(2, buffer.size)
        assertEquals(listOf(1, 2), buffer.toList())
    }

    @Test
    fun `add elements over capacity removes oldest`() {
        val buffer = CircularBuffer<Int>(capacity = 3)

        buffer.add(1)
        buffer.add(2)
        buffer.add(3)
        buffer.add(4)  // Should evict 1

        assertEquals(3, buffer.size)
        assertEquals(listOf(2, 3, 4), buffer.toList())
    }

    @Test
    fun `clear removes all elements`() {
        val buffer = CircularBuffer<Int>(capacity = 3)
        buffer.add(1)
        buffer.add(2)

        buffer.clear()

        assertEquals(0, buffer.size)
        assertTrue(buffer.toList().isEmpty())
    }

    @Test
    fun `latest returns most recent element`() {
        val buffer = CircularBuffer<Int>(capacity = 3)
        buffer.add(1)
        buffer.add(2)
        buffer.add(3)

        assertEquals(3, buffer.latest())
    }
}
