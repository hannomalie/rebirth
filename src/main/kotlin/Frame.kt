package org.example

import java.lang.AutoCloseable
import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.ExperimentalAtomicApi

class Frame: AutoCloseable {
    val arena = Arena.ofShared()
    @OptIn(ExperimentalAtomicApi::class)
    val rendered = AtomicBoolean(false)
    val extracts = mutableMapOf<Component, MemorySegment>()
    fun put(componentType: Component, componentsExtracted: MemorySegment) {
        extracts[componentType] = componentsExtracted
    }

    override fun close() {
        arena.close()
    }
}