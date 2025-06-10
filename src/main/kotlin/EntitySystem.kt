package org.example

import java.lang.foreign.Arena
import java.lang.foreign.MemoryLayout
import java.lang.foreign.MemorySegment
import java.util.BitSet

open class EntitySystem(private val arena: Arena, val componentType: Component) {
    val _entities = mutableListOf<EntityId>()
    val entities: List<EntityId> = _entities
    val baseLayout = componentType.layout
    var componentsLayout = MemoryLayout.sequenceLayout(entities.size.toLong(), baseLayout)
        private set
    var components = arena.allocate(componentsLayout)
        private set

    context(world: World)
    open fun add(entityId: EntityId): Boolean {
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
    context(world: World)
    open fun addAll(entityIds: List<EntityId>): List<EntityId> = entityIds.filter { entityId ->
        if (!_entities.contains(entityId)) {
            _entities.add(entityId)
        } else {
            false
        }
    }.apply {
        if(isNotEmpty()) {
            componentsLayout = MemoryLayout.sequenceLayout(entities.size.toLong(), baseLayout)
            val newComponents = arena.allocate(componentsLayout)
            newComponents.copyFrom(components)
            components = newComponents
        }
    }

    open fun update(deltaSeconds: Float, perFrameArena: Arena) { }

    fun extract(frame: Frame) {
        val componentsExtracted = frame.arena.allocate(componentsLayout)
//        extractionLock.withLock {
            componentsExtracted.copyFrom(components)
            frame.put(componentType, componentsExtracted)
//        }
    }

    inline fun <T> forEachIndexed(crossinline block: context(MemorySegment) (Int, T) -> Unit) {
        var counter = 0
        components.elements(baseLayout).forEach {
            context(it) {
                block(counter++, componentType as T)
            }
        }
//        (0 until componentsLayout.elementCount().toInt()).map {
//            context(components.asSlice(baseLayout.byteSize() * it, baseLayout)) {
//                block(it, componentType as T)
//            }
//        }
    }
    inline fun <T> extractedForEachIndexed(frame: Frame, crossinline block: context(MemorySegment) (Int, T) -> Unit) {
        var counter = 0
        frame.extracts[componentType]!!.elements(baseLayout)!!.forEach {
            context(it) {
                block(counter++, componentType as T)
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