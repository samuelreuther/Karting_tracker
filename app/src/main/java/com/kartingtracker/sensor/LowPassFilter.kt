package com.kartingtracker.sensor

class LowPassFilter(
    private val alpha: Float = 0.18f
) {
    private val state = FloatArray(3)
    private var initialized = false

    fun apply(input: FloatArray): FloatArray {
        if (!initialized) {
            input.copyInto(state)
            initialized = true
            return state.copyOf()
        }

        for (index in state.indices) {
            state[index] += alpha * (input[index] - state[index])
        }
        return state.copyOf()
    }
}
