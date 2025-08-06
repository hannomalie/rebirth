package org.example

open class VelocityComponent : Component {
    override val layout = Float.SIZE_BYTES * 2
    context(segment: MemorySegment)
    open var x: Float
        get() = segment.buffer.getFloat(segment.position)
        set(value) { segment.buffer.putFloat(segment.position, value) }

    context(segment: MemorySegment)
    open var y: Float
        get() = segment.buffer.getFloat(segment.position + Float.SIZE_BYTES)
        set(value) { segment.buffer.putFloat(segment.position + Float.SIZE_BYTES, value) }

    context(segment: MemorySegment)
    fun print() = "Velocity [${PositionComponent.x}, ${PositionComponent.y}]"

    override val factory: () -> Component = { VelocityComponent() as Component }

    final override val identifier = 2
    companion object: VelocityComponent()
}