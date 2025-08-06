package org.example

open class PositionVelocity: Archetype {
    override val layout = PositionComponent.layout + VelocityComponent.layout

    val archetypeLayout = layout
    val position = object: PositionComponent() {}
    val velocity = object: VelocityComponent() {
        context(segment: MemorySegment)
        override var x: Float
            get() = segment.buffer.getFloat(segment.position + PositionComponent.layout)
            set(value) { segment.buffer.putFloat(segment.position + PositionComponent.layout, value) }

        context(segment: MemorySegment)
        override var y: Float
            get() = segment.buffer.getFloat(segment.position + PositionComponent.layout + Float.SIZE_BYTES)
            set(value) { segment.buffer.putFloat(segment.position + PositionComponent.layout + Float.SIZE_BYTES, value) }

    }
    override val includedComponents = setOf(position, velocity)

    override val factory: () -> Component = { PositionVelocity() as Component }

    final override val identifier = 1
    companion object: PositionVelocity()
}