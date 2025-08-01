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
import kotlin.system.measureNanoTime
import kotlin.system.measureTimeMillis
import kotlin.time.measureTimedValue

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
    inline fun <reified T: Component> parallelForEach(crossinline block: context(MemorySegment) (EntityId, T) -> Unit) {
        val clazz = T::class
        val companionObject = clazz.companionObjectInstance as Component
        val systemsForComponent = entitySystems.filter { companionObject.identifier == it.componentType.identifier }
        systemsForComponent.forEach { system ->
            system.parallelForEach(block)
        }
        val systemsForComponentOfArchetype = entitySystems.filter {
            it.componentType is Archetype && it.componentType.includedComponents.any { companionObject.identifier == it.identifier }
        }
        systemsForComponentOfArchetype.forEach { system ->
            val component =
                (system.componentType as Archetype).includedComponents.first { companionObject.identifier == it.identifier } as T
            system.parallelForEach<T> { entityId, archetype ->
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
    @OptIn(ExperimentalAtomicApi::class)
    fun simulate() = runBlocking {
        var lastTime = java.lang.System.nanoTime()

        while (true) {
            val wholeCycleTimeMs = measureNanoTime {
                val waitForRendering = false
                if(waitForRendering) {
                    val waitingForRenderingTimeMs = measureNanoTime {
                        if(inFlightFrames.size >= 3) {
                            val previousFrame: Frame? = inFlightFrames.removeFirstOrNull()
                            previousFrame?.waitForRenderingFinished()
                            previousFrame?.close()
                        }
                    } / 1E9f
                    logger.info("waiting for rendering took {} ms \n", waitingForRenderingTimeMs)
                }

                val processMessagesTimeMs = measureNanoTime {
                    var message = toBeExecutedInSimulationThread.tryReceive().getOrNull()
                    while(message != null) {
                        message.run()
                        message = toBeExecutedInSimulationThread.tryReceive().getOrNull()
                    }
                } / 1E9f
                logger.info("messages took {} ms \n", processMessagesTimeMs)


                val thisTime = java.lang.System.nanoTime()
                val deltaNs = thisTime - lastTime
                val deltaSeconds = deltaNs / 1E9f
                lastTime = thisTime

                val (frame, frameCreationTimeNs) = measureTimedValue { Frame() }
                logger.info("frame creation took {} ms \n", frameCreationTimeNs.inWholeMilliseconds)
//                val frame = Frame()
                val updateTimeMs = measureNanoTime {
                    systems.forEach { system ->
                        system.update(deltaSeconds, frame.arena)
                    }
                } / 1E9f
                logger.info("update took {} ms \n", updateTimeMs)
                val extractionTimeMs = measureNanoTime {
                    entitySystems.forEach { system ->
                        system.extract(frame)
                    }
                    if(inFlightFrames.size < 3 || !waitForRendering) {
                        frameChannel.send(frame)
                        inFlightFrames.add(frame)
                    }
                } / 1E9f
                logger.info("extraction took {} ms \n", extractionTimeMs)
            } / 1E9f
            logger.info("Whole cycle took {} ms \n", wholeCycleTimeMs)
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