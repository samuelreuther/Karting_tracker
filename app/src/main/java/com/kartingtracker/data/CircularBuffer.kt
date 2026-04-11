package com.kartingtracker.data

/**
 * Fixed-capacity circular buffer that evicts oldest elements when full.
 * Thread-safe for single writer, multiple readers.
 */
class CircularBuffer<T>(val capacity: Int) {

    private val buffer = ArrayList<T>(capacity)
    private var writeIndex = 0

    val size: Int
        @Synchronized get() = buffer.size

    @Synchronized
    fun add(element: T) {
        if (buffer.size < capacity) {
            buffer.add(element)
        } else {
            buffer[writeIndex] = element
        }
        writeIndex = (writeIndex + 1) % capacity
    }

    @Synchronized
    fun clear() {
        buffer.clear()
        writeIndex = 0
    }

    @Synchronized
    fun toList(): List<T> {
        return if (buffer.size < capacity) {
            buffer.toList()
        } else {
            // Reorder: from writeIndex to end, then from start to writeIndex
            val result = ArrayList<T>(capacity)
            for (i in 0 until capacity) {
                val index = (writeIndex + i) % capacity
                result.add(buffer[index])
            }
            result
        }
    }

    @Synchronized
    fun latest(): T? {
        return if (buffer.isEmpty()) null
        else if (buffer.size < capacity) buffer.last()
        else buffer[(writeIndex - 1 + capacity) % capacity]
    }
}
