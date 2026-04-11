package com.kartingtracker.data

/**
 * Fixed-capacity circular buffer that evicts oldest elements when full.
 * Thread-safe: all methods synchronize on this instance.
 */
class CircularBuffer<T>(private val capacity: Int) {

    init {
        require(capacity > 0) { "CircularBuffer capacity must be greater than 0, was $capacity" }
    }

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
            // Elements are stored out-of-order; iterate from oldest (writeIndex) using modular arithmetic
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
