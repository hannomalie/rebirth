package org.example

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.apache.logging.log4j.LogManager
import java.lang.foreign.Arena
import java.lang.foreign.MemoryLayout
import java.lang.foreign.MemorySegment
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.reflect.full.companionObject
import kotlin.reflect.full.companionObjectInstance
import kotlin.reflect.full.isSubclassOf

interface Component {
    val layout: MemoryLayout
    val factory: () -> Component
    val identifier: Int
    companion object {
        var identifierCounter = 0
    }
}

interface Archetype: Component {
    val includedComponents: Set<Component>
}

interface System {
    fun update(deltaSeconds: Float, arena: Arena)
}
private val logger = LogManager.getLogger("Update")
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
    fun removeAll(entities: List<EntityId>) {
        entitySystems.forEach {
            it.removeAll(entities)
        }

    }
    fun set(entityId: EntityId, components: Set<Component>) {
        entities.put(entityId, components)
    }

    inline fun <reified T: Component> forEach(crossinline block: context(MemorySegment) (EntityId, T) -> Unit) {
        val clazz = T::class
        val companionObject = clazz.companionObjectInstance as Component
        val systemsForComponent = entitySystems.filter { companionObject.identifier == it.componentType.identifier }
        systemsForComponent.forEach { system ->
            system.forEach(block)
        }
        val systemsForComponentOfArchetype = entitySystems.filter {
            it.componentType is Archetype && it.componentType.includedComponents.any { companionObject.identifier == it.identifier }
        }
        systemsForComponentOfArchetype.forEach { system ->
            val component =
                (system.componentType as Archetype).includedComponents.first { companionObject.identifier == it.identifier } as T
            system.forEach<T> { entityId, archetype ->
                block(entityId, component)
            }
        }
    }
    inline fun <reified T: Component> extractedForEachIndexed(frame: Frame, crossinline block: context(MemorySegment) (Int, T) -> Unit) {
        val clazz = T::class
        val companionObject = clazz.companionObjectInstance as Component
        entitySystems.filter { companionObject.identifier == it.componentType.identifier }.forEach { system ->
            system.extractedForEach(frame, block)
        }
        entitySystems.filter {
            it.componentType is Archetype && it.componentType.includedComponents.any { companionObject.identifier == it.identifier }
        }.forEach { system ->
            val component =
                (system.componentType as Archetype).includedComponents.first { companionObject.identifier == it.identifier }
            system.extractedForEach<T>(frame) { index, archetype ->
                block(index, component as T)
            }
        }
    }

    fun register(vararg component: Component) {
        componentsAndArcheTypes.addAll(component)
        componentsAndArcheTypes.forEach { componentType ->
            entitySystems.add(EntitySystem(arena, componentType))
        }
    }

    var inFlightFrames = mutableListOf<Frame>()
    val toBeExecutedInSimulationThread = Channel<Runnable>(10)
    fun simulate() = runBlocking {
        var lastTime = java.lang.System.nanoTime()

        while (true) {
            if(inFlightFrames.size >= 3) {
                val previousFrame: Frame? = inFlightFrames.removeFirstOrNull()
                previousFrame?.waitForRenderingFinished()
                previousFrame?.close()
            }

            var message = toBeExecutedInSimulationThread.tryReceive().getOrNull()
            while(message != null) {
                message.run()
                message = toBeExecutedInSimulationThread.tryReceive().getOrNull()
            }

            val frame = Frame()

            val thisTime = java.lang.System.nanoTime()
            val deltaNs = thisTime - lastTime
            val deltaMs = deltaNs / 1E6f
            val deltaSeconds = deltaNs / 1E9f
            lastTime = thisTime

            systems.forEach { system ->
                system.update(deltaSeconds, frame.arena)
            }
            entitySystems.forEach { system ->
                system.update(deltaSeconds, frame.arena)

                system.extract(frame)
            }
            if(inFlightFrames.size < 3) {
                frameChannel.send(frame)
                inFlightFrames.add(frame)
            }
            logger.info("Update took {} ms \n", deltaMs)
        }
    }
}

@OptIn(ExperimentalAtomicApi::class)
private suspend inline fun Frame.waitForRenderingFinished() {
    while (!rendered.load()) {
        delay(1)
    }
}

typealias EntityId = Int
fun Entity(): EntityId = entityIdCounter.apply {
    entityIdCounter += 1
}
private var entityIdCounter = 0