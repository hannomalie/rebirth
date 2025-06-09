package org.example

import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment
import kotlin.reflect.full.isSubclassOf

class World {
    val arena = Arena.ofAuto()

    val entities = mutableMapOf<EntityId, Set<Component>>()
    val systems = mutableListOf<EntitySystem>()
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
            val systemOrNull = systems.firstOrNull { it.componentType == components.first() }
            systemOrNull ?: throw IllegalStateException("currently unsupported component: $components")
            systemOrNull.add(entityId)
        } else {
            val systemOrNull = systems.firstOrNull { it.componentType is Archetype && it.componentType.includedComponents == components }
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
            val systemOrNull = systems.firstOrNull { it.componentType == components.first() }
            systemOrNull ?: throw IllegalStateException("currently unsupported component: $components")
            systemOrNull.addAll(entities)
        } else {
            val systemOrNull = systems.firstOrNull { it.componentType is Archetype && it.componentType.includedComponents == components }
            systemOrNull ?: throw IllegalStateException("currently unsupported set of components, no archetype for: $components")
            systemOrNull.addAll(entities)
        }

    }
    fun set(entityId: EntityId, components: Set<Component>) {
        entities.put(entityId, components)
    }

    inline fun <reified T: Component> forEachIndexed(crossinline block: context(MemorySegment) (Int, T) -> Unit) {
        systems.filter { T::class.isSubclassOf(it.componentType::class) }.forEach { system ->
            system.forEachIndexed(block)
        }
        systems.filter {
            it.componentType is Archetype && it.componentType.includedComponents.any { it::class.isSubclassOf(T::class) }
        }.forEach { system ->
            val component =
                (system.componentType as Archetype).includedComponents.first { it::class.isSubclassOf(T::class) }
            system.forEachIndexed<T> { index, archetype ->
                block(index, component as T)
            }
        }
    }

    fun register(vararg component: Component) {
        componentsAndArcheTypes.addAll(component)
        componentsAndArcheTypes.forEach {
            systems.add(BaseEntitySystem(componentType = it, arena = arena))
        }
    }
}

typealias EntityId = Int
fun Entity(): EntityId = entityIdCounter.apply {
    entityIdCounter += 1
}
private var entityIdCounter = 0