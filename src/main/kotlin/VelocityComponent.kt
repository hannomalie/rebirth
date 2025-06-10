package org.example

import java.lang.foreign.MemoryLayout
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout
import java.lang.invoke.VarHandle

open class VelocityComponent : Component {
    override val layout = MemoryLayout.structLayout(
        ValueLayout.JAVA_FLOAT.withName("x"),
        ValueLayout.JAVA_FLOAT.withName("y")
    )
    open val xHandle: VarHandle = layout.varHandle(MemoryLayout.PathElement.groupElement("x"))
    open val yHandle: VarHandle = layout.varHandle(MemoryLayout.PathElement.groupElement("y"))

    context(segment: MemorySegment)
    var x: Float
        get() = xHandle.get(segment, 0) as Float
        set(value) = xHandle.set(segment, 0, value)

    context(segment: MemorySegment)
    var y: Float
        get() = yHandle.get(segment, 0) as Float
        set(value) = yHandle.set(segment, 0, value)

    context(segment: MemorySegment)
    fun print() = "Velocity [${PositionComponent.x}, ${PositionComponent.y}]"

    companion object: VelocityComponent()
}