package org.example

import org.lwjgl.BufferUtils
import java.lang.foreign.Arena
import java.lang.foreign.MemoryLayout
import java.lang.foreign.MemorySegment
import java.nio.ByteBuffer
import java.util.*
import java.util.concurrent.CompletableFuture

class EntitySystem(private val arena: Arena, val componentType: Component) {
    val _entities = mutableListOf<EntityId>() // TODO: Use set that preserves order
    val entities: List<EntityId> get() = _entities
    val baseLayout = componentType.layout
    var componentsLayout = MemoryLayout.sequenceLayout(entities.size.toLong(), baseLayout)
        private set
    var componentsList: List<MemorySegment> = emptyList()
    var componentsByteBuffer: ByteBuffer = BufferUtils.createByteBuffer(1)
    var components = arena.allocate(componentsLayout)
        private set(value) {
            field = value
            componentsByteBuffer = value.asByteBuffer()
            componentsList = field.elements(baseLayout).toList()
        }
    val slidingWindows = (0 until 16).map { componentType.factory() }

    fun add(entityId: EntityId): Boolean {
        return if (!_entities.contains(entityId)) {
            _entities.add(entityId).apply {
                componentsLayout = MemoryLayout.sequenceLayout(entities.size.toLong(), baseLayout)
                val newComponents = arena.allocate(componentsLayout)
                newComponents.copyFrom(components)
                components = newComponents
            }
        } else {
            false
        }
    }
    fun addAll(entityIds: List<EntityId>): List<EntityId> = entityIds.filter { entityId ->
        !_entities.contains(entityId)
    }.apply {
        _entities.addAll(this)
        if(isNotEmpty()) {
            componentsLayout = MemoryLayout.sequenceLayout(entities.size.toLong(), baseLayout)
            val newComponents = arena.allocate(componentsLayout)
            newComponents.copyFrom(components)
            components = newComponents
        }
    }
    fun removeAll(entityIds: List<EntityId>) {
        if(_entities.isEmpty())  return

        var removedSome = false
        entityIds.forEach { toDelete ->
            if(_entities.contains(toDelete)) {
                val toDeleteIndex = _entities.indexOf(toDelete)
                val isLastElement = toDeleteIndex == _entities.size - 1
                if (!isLastElement) {
                    _entities[toDeleteIndex] = _entities.last()
                    components.asSlice(baseLayout.byteSize() * toDeleteIndex, baseLayout).copyFrom(
                        components.asSlice(baseLayout.byteSize() * (_entities.size-1), baseLayout)
                    )
                }
                _entities.removeLast()
                removedSome = true
            }
        }
        if(removedSome) {
            componentsLayout = componentsLayout.withElementCount(entities.size.toLong())
            val newComponents = arena.allocate(componentsLayout)
            newComponents.copyFrom(components.asSlice(0, componentsLayout))
            components = newComponents
        }
    }

    fun extract(frame: Frame) {
        val componentsExtracted = frame.arena.allocate(componentsLayout)
//        extractionLock.withLock {
            componentsExtracted.copyFrom(components)
            frame.put(componentType, componentsExtracted)
            frame.put(componentType, componentsByteBuffer) // TODO: Copy data!
//        }
    }

    inline fun <T> parallelForEach(crossinline block: context(MemorySegment) (EntityId, T) -> Unit) {
        //components.elements(baseLayout).forEach {
        val chunkSize = componentsList.size / slidingWindows.size
        val futures = componentsList.chunked(chunkSize).mapIndexed { chunkIndex, chunkElements ->
            val slidingWindow = slidingWindows[chunkIndex] as T
            var counter = chunkIndex * chunkSize
            CompletableFuture.supplyAsync {
                chunkElements.map { segment ->
                    context(segment) {
                        block(entities.elementAt(counter++), slidingWindow)
                    }
                }
            }
        }
        CompletableFuture.allOf(*(futures.toTypedArray()))
            .join()
//        (0 until componentsLayout.elementCount().toInt()).map {
//            context(components.asSlice(baseLayout.byteSize() * it, baseLayout)) {
//                block(it, componentType as T)
//            }
//        }
    }
    inline fun <T> forEach(crossinline block: context(MemorySegment) (EntityId, T) -> Unit) {
        var counter = 0
        //components.elements(baseLayout).forEach {
        componentsList.forEach {
            context(it) {
                block(entities.elementAt(counter++), componentType as T)
            }
        }
//        (0 until componentsLayout.elementCount().toInt()).map {
//            context(components.asSlice(baseLayout.byteSize() * it, baseLayout)) {
//                block(it, componentType as T)
//            }
//        }
    }
    inline fun <T> extractedForEach(frame: Frame, crossinline block: context(MemorySegment) (Int, T) -> Unit) {
        var counter = 0
        frame.extracts[componentType]!!.elements(baseLayout)!!.forEach {
            context(it) {
                block(entities.elementAt(counter++), componentType as T)
            }
        }
//        (0 until componentsLayout.elementCount().toInt()).map {
//            context(components.asSlice(baseLayout.byteSize() * it, baseLayout)) {
//                block(it, componentType as T)
//            }
//        }
    }
    private val bitSet = BitSet(32)
    init {
        bitSet.set(counter, true)
        counter += 1
    }

    companion object {
        private var counter = 0
    }
}