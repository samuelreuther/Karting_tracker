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

    @Test
    fun `latest returns null on empty buffer`() {
        val buffer = CircularBuffer<Int>(capacity = 3)
        assertNull(buffer.latest())
    }

    @Test
    fun `capacity 1 evicts on second add`() {
        val buffer = CircularBuffer<Int>(capacity = 1)
        buffer.add(1)
        buffer.add(2)
        assertEquals(1, buffer.size)
        assertEquals(listOf(2), buffer.toList())
        assertEquals(2, buffer.latest())
    }

    @Test
    fun `multiple full wraps preserve last capacity elements`() {
        val buffer = CircularBuffer<Int>(capacity = 3)
        (1..9).forEach { buffer.add(it) }
        assertEquals(3, buffer.size)
        assertEquals(listOf(7, 8, 9), buffer.toList())
    }

    @Test
    fun `latest after wrap returns most recent`() {
        val buffer = CircularBuffer<Int>(capacity = 3)
        buffer.add(1)
        buffer.add(2)
        buffer.add(3)
        buffer.add(4)  // wraps: evicts 1
        assertEquals(4, buffer.latest())
    }
}
