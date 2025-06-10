package org.example

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.lang.foreign.Arena
import java.lang.foreign.MemoryLayout
import java.lang.foreign.MemorySegment
import java.util.concurrent.TimeUnit
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.math.max
import kotlin.reflect.full.isSubclassOf

interface Component {
    val layout: MemoryLayout
}

interface Archetype: Component {
    val includedComponents: Set<Component>
}

interface System {
    fun update(deltaSeconds: Float, arena: Arena)
}
class World {
    val arena = Arena.ofAuto()
    val frameChannel = Channel<Frame>()

    val entities = mutableMapOf<EntityId, Set<Component>>()
    val entitySystems = mutableListOf<EntitySystem>()
    val systems = mutableListOf<System>()
    val componentsAndArcheTypes = mutableListOf<Component>()

    fun add(entityId: EntityId, components: Set<Component>) {
        val oldComponents = entities[entityId]
        val newComponents = if (oldComponents == null) {
            components
        } else {
            components + oldComponents
        }
        set(entityId, newComponents)

        if (newComponents.size == 1)  {
            val systemOrNull = entitySystems.firstOrNull { it.componentType == components.first() }
            systemOrNull ?: throw IllegalStateException("currently unsupported component: $components")
            systemOrNull.add(entityId)
        } else {
            val systemOrNull = entitySystems.firstOrNull { it.componentType is Archetype && it.componentType.includedComponents == components }
            systemOrNull ?: throw IllegalStateException("currently unsupported set of components, no archetype for: $components")
            systemOrNull.add(entityId)
        }
    }

    fun addAll(entities: List<EntityId>, components: Set<Component>) {
        val entityToComponents = entities.associateWith {  entityId ->
            this@World.entities[entityId]
        }.map { (entityId, oldComponents) ->
            val newComponents = if (oldComponents == null) {
                components
            } else {
                components + oldComponents
            }
            set(entityId, newComponents)
            Pair(entityId, newComponents)
        }

        // TODO: All entities will have the same archetype, this is not entirely true
        // when any of them has a different components already before, please fix me but keep the
        // optimization
        if (entityToComponents.first().second.size == 1)  {
            val systemOrNull = entitySystems.firstOrNull { it.componentType == components.first() }
            systemOrNull ?: throw IllegalStateException("currently unsupported component: $components")
            systemOrNull.addAll(entities)
        } else {
            val systemOrNull = entitySystems.firstOrNull { it.componentType is Archetype && it.componentType.includedComponents == components }
            systemOrNull ?: throw IllegalStateException("currently unsupported set of components, no archetype for: $components")
            systemOrNull.addAll(entities)
        }

    }
    fun set(entityId: EntityId, components: Set<Component>) {
        entities.put(entityId, components)
    }

    inline fun <reified T: Component> forEachIndexed(crossinline block: context(MemorySegment) (Int, T) -> Unit) {
        entitySystems.filter { T::class.isSubclassOf(it.componentType::class) }.forEach { system ->
            system.forEachIndexed(block)
        }
        entitySystems.filter {
            it.componentType is Archetype && it.componentType.includedComponents.any { it::class.isSubclassOf(T::class) }
        }.forEach { system ->
            val component =
                (system.componentType as Archetype).includedComponents.first { it::class.isSubclassOf(T::class) }
            system.forEachIndexed<T> { index, archetype ->
                block(index, component as T)
            }
        }
    }
    inline fun <reified T: Component> extractedForEachIndexed(frame: Frame, crossinline block: context(MemorySegment) (Int, T) -> Unit) {
        entitySystems.filter { T::class.isSubclassOf(it.componentType::class) }.forEach { system ->
            system.extractedForEachIndexed(frame, block)
        }
        entitySystems.filter {
            it.componentType is Archetype && it.componentType.includedComponents.any { it::class.isSubclassOf(T::class) }
        }.forEach { system ->
            val component =
                (system.componentType as Archetype).includedComponents.first { it::class.isSubclassOf(T::class) }
            system.extractedForEachIndexed<T>(frame) { index, archetype ->
                block(index, component as T)
            }
        }
    }

    fun register(vararg component: Component) {
        componentsAndArcheTypes.addAll(component)
        componentsAndArcheTypes.forEach {
            entitySystems.add(EntitySystem(componentType = it, arena = arena))
        }
    }

    fun simulate() {
        runBlocking {
            var lastNanoTime = java.lang.System.nanoTime()

            var previousFrame: Frame? = null
            while (true) {
                previousFrame?.waitForRenderingFinished()
                previousFrame?.close()

                val frame = Frame()

                val ns = java.lang.System.nanoTime() - lastNanoTime
                var deltaSeconds = TimeUnit.NANOSECONDS.toSeconds(ns).toFloat()
                deltaSeconds = max(0.1f, deltaSeconds)

                systems.forEach { system ->
                    system.update(deltaSeconds, frame.arena)
                }
                entitySystems.forEach { system ->
                    system.update(deltaSeconds, frame.arena)

                    system.extract(frame)
                }
                frameChannel.send(frame)
                previousFrame = frame

                lastNanoTime = java.lang.System.nanoTime()
            }
        }
    }
}

@OptIn(ExperimentalAtomicApi::class)
private suspend fun Frame.waitForRenderingFinished() {
    while (!rendered.load()) {
        delay(1)
    }
}

typealias EntityId = Int
fun Entity(): EntityId = entityIdCounter.apply {
    entityIdCounter += 1
}
private var entityIdCounter = 0