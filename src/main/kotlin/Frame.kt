package org.example

import org.lwjgl.BufferUtils
import java.lang.AutoCloseable
import java.nio.ByteBuffer
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.ExperimentalAtomicApi

class Frame: AutoCloseable {
    val arena = Arena.ofShared()
    @OptIn(ExperimentalAtomicApi::class)
    val rendered = AtomicBoolean(false)
    val extractsByteBuffers = mutableMapOf<Component, MemorySegment>()
    fun put(componentType: Component, componentsExtracted: MemorySegment) {
        extractsByteBuffers[componentType] = componentsExtracted
    }

    override fun close() {
        extractsByteBuffers.clear()
    }
}