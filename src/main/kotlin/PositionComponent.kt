package org.example


open class PositionComponent: Component {
    override val layout = Float.SIZE_BYTES * 2
    context(segment: MemorySegment)
    var x: Float
        get() = segment.buffer.getFloat(segment.position)
        set(value) { segment.buffer.putFloat(segment.position, value) }

    context(segment: MemorySegment)
    var y: Float
        get() = segment.buffer.getFloat(segment.position + Float.SIZE_BYTES)
        set(value) { segment.buffer.putFloat(segment.position + Float.SIZE_BYTES, value) }

    context(segment: MemorySegment)
    fun print() = "Position [$x, $y]"

    override val factory: () -> Component = { PositionComponent() as Component }
    final override val identifier = 0
    companion object: PositionComponent()
}