package org.example

import java.util.*
import java.util.concurrent.CompletableFuture

class EntitySystem(private val arena: Arena, val componentType: Component) {
    val _entities = mutableListOf<EntityId>() // TODO: Use set that preserves order
    val entities: List<EntityId> get() = _entities
    val baseLayout = componentType.layout
    val componentsLayout: Int get() = baseLayout * entities.size
//    var componentsByteBuffer: ByteBuffer = BufferUtils.createByteBuffer(1)
    var components = arena.allocate(baseLayout * entities.size)
        private set(value) {
            field = value
//            componentsByteBuffer = value
        }
    val slidingWindows = (0 until 16).map { componentType.factory() }

    fun add(entityId: EntityId): Boolean {
        return if (!_entities.contains(entityId)) {
            _entities.add(entityId).apply {
                val newComponents = arena.allocate(componentsLayout)
                newComponents.buffer.put(components.buffer)
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
            val newComponents = arena.allocate(componentsLayout)
            newComponents.buffer.put(components.buffer)
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
                    components.buffer.slice(baseLayout * toDeleteIndex, baseLayout).put(components.buffer.slice(baseLayout * (_entities.size-1), baseLayout))
                }
                _entities.removeLast()
                removedSome = true
            }
        }
        if(removedSome) {
            val newComponents = arena.allocate(componentsLayout)
            newComponents.buffer.put(components.buffer.slice(0, componentsLayout))
            components = newComponents
        }
    }

    fun extract(frame: Frame) {
        val componentsExtracted = frame.arena.allocate(components.buffer.capacity())
//        extractionLock.withLock {
            components.buffer.rewind()
            componentsExtracted.buffer.put(components.buffer)
        frame.put(componentType, componentsExtracted)
//        }
    }

    inline fun <T> parallelForEach(crossinline block: context(MemorySegment) (EntityId, T) -> Unit) {
        //components.elements(baseLayout).forEach {
        val entityCountPerSlidingWindow = entities.size / slidingWindows.size
        val futures = (0 until entities.size).chunked(entityCountPerSlidingWindow).mapIndexed { chunkIndex, chunkElements ->
            CompletableFuture.supplyAsync {
                val slidingWindow = slidingWindows[chunkIndex] as T
                var counter = chunkIndex * entityCountPerSlidingWindow
                val segment = components.copy(position = chunkIndex * entityCountPerSlidingWindow * baseLayout)
                chunkElements.forEach { chunkElement ->
                    context(segment) {
                        block(entities.elementAt(counter++), slidingWindow)
                    }
                    segment.position += baseLayout
                }
            }
        }
        CompletableFuture.allOf(*(futures.toTypedArray()))
            .join()
        (0 until entities.size).map {
            context(components.copy(position = baseLayout * it)) {
                block(it, componentType as T)
            }
        }
    }
    inline fun <T> forEach(crossinline block: context(MemorySegment) (EntityId, T) -> Unit) {
        //components.elements(baseLayout).forEach {
        repeat(entities.size) { counter ->
            components.position = baseLayout * counter
            context(components) {
                block(entities.elementAt(counter), componentType as T)
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
//        frame.extracts[componentType]!!.elements(baseLayout)!!.forEach {
//            context(it) {
//                block(entities.elementAt(counter++), componentType as T)
//            }
//        }
        while(counter <= entities.size) {
            val it = frame.extractsByteBuffers[componentType]!!
            it.position = baseLayout * counter
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