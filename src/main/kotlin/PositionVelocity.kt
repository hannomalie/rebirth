package org.example

import java.lang.foreign.MemoryLayout
import java.lang.invoke.VarHandle

object PositionVelocity: Archetype {
    override val layout = MemoryLayout.structLayout(PositionComponent.layout.withName("position"), VelocityComponent.layout.withName("velocity"))

    val archetypeLayout = layout
    val position = object: PositionComponent() {
        override val xHandle: VarHandle = archetypeLayout.varHandle(
            MemoryLayout.PathElement.groupElement("position"),
            MemoryLayout.PathElement.groupElement("x")
        )
        override val yHandle: VarHandle = archetypeLayout.varHandle(
            MemoryLayout.PathElement.groupElement("position"),
            MemoryLayout.PathElement.groupElement("y")
        )
    }
    val velocity = object: VelocityComponent() {
        override val xHandle: VarHandle = archetypeLayout.varHandle(
            MemoryLayout.PathElement.groupElement("velocity"),
            MemoryLayout.PathElement.groupElement("x")
        )
        override val yHandle: VarHandle = archetypeLayout.varHandle(
            MemoryLayout.PathElement.groupElement("velocity"),
            MemoryLayout.PathElement.groupElement("y")
        )
    }
    override val includedComponents = setOf(position, velocity)
}