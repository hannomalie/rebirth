package org.example

import kotlinx.coroutines.runBlocking
import org.jetbrains.skia.Canvas
import org.jetbrains.skia.Color
import org.jetbrains.skia.Font
import org.jetbrains.skia.Paint
import org.jetbrains.skia.TextLine
import org.jetbrains.skiko.SkiaLayer
import org.jetbrains.skiko.SkiaLayerRenderDelegate
import org.jetbrains.skiko.SkikoRenderDelegate
import java.awt.Dimension
import java.lang.foreign.*
import java.lang.foreign.MemoryLayout.PathElement.groupElement
import java.lang.foreign.MemorySegment
import java.lang.invoke.VarHandle
import java.util.BitSet
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import javax.swing.JFrame
import javax.swing.SwingUtilities
import javax.swing.WindowConstants
import kotlin.concurrent.withLock
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random


interface Component {
    val layout: MemoryLayout
}

open class PositionComponent: Component {
    override val layout = MemoryLayout.structLayout(
        ValueLayout.JAVA_FLOAT.withName("x"),
        ValueLayout.JAVA_FLOAT.withName("y")
    )
    open val xHandle: VarHandle = layout.varHandle(groupElement("x"))
    open val yHandle: VarHandle = layout.varHandle(groupElement("y"))

    context(segment: MemorySegment)
    var x: Float
        get() = xHandle.get(segment, 0) as Float
        set(value) = xHandle.set(segment, 0, value)

    context(segment: MemorySegment)
    var y: Float
        get() = yHandle.get(segment, 0) as Float
        set(value) = yHandle.set(segment, 0, value)

    context(segment: MemorySegment)
    fun print() = "Position [$x, $y]"

    companion object: PositionComponent()
}
open class VelocityComponent : Component {
    override val layout = MemoryLayout.structLayout(
        ValueLayout.JAVA_FLOAT.withName("x"),
        ValueLayout.JAVA_FLOAT.withName("y")
    )
    open val xHandle: VarHandle = layout.varHandle(groupElement("x"))
    open val yHandle: VarHandle = layout.varHandle(groupElement("y"))

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

interface Archetype: Component {
    val includedComponents: Set<Component>
}
object PositionVelocity: Archetype {
    override val layout = MemoryLayout.structLayout(PositionComponent.layout.withName("position"), VelocityComponent.layout.withName("velocity"))

    val archetypeLayout = layout
    val position = object: PositionComponent() {
        override val xHandle: VarHandle = archetypeLayout.varHandle(groupElement("position"), groupElement("x"))
        override val yHandle: VarHandle = archetypeLayout.varHandle(groupElement("position"), groupElement("y"))
    }
    val velocity = object: PositionComponent() {
        override val xHandle: VarHandle = archetypeLayout.varHandle(groupElement("velocity"), groupElement("x"))
        override val yHandle: VarHandle = archetypeLayout.varHandle(groupElement("velocity"), groupElement("y"))
    }
    override val includedComponents = setOf(position, velocity)
}

abstract class EntitySystem(private val arena: Arena, entities: List<EntityId>, val componentType: Component) {
    val _entities = mutableListOf<EntityId>().apply {
        addAll(entities)
    }
    val entities: List<EntityId> = _entities

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
    val baseLayout = componentType.layout
    var componentsLayout = MemoryLayout.sequenceLayout(entities.size.toLong(), baseLayout)
        private set
    var components = arena.allocate(componentsLayout)
        private set

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
    private val bitSet = BitSet(32)
    init {
        bitSet.set(counter, true)
        counter += 1
    }

    companion object {
        private var counter = 0
    }
}
open class BaseEntitySystem(componentType: Component, entities: List<EntityId> = emptyList(), private val arena: Arena): EntitySystem(arena, entities, componentType) {
    // TOOD: Use
    fun extract(arena: Arena) {
        val componentsExtracted = arena.allocate(componentsLayout)
        extractionLock.withLock {
            componentsExtracted.copyFrom(components)
        }
    }
}

val extractionLock = ReentrantLock()

val dimension = Dimension(800, 600)
fun main() {

    val world = World().apply {
        register(PositionComponent, VelocityComponent, PositionVelocity)

        val maxEntityCount = 2000
        val allEntities = (0 until maxEntityCount).map {
            Entity()
        }
        addAll(allEntities.take(maxEntityCount/2), setOf(PositionComponent))
        addAll(allEntities.subList(maxEntityCount/2, allEntities.size), setOf(PositionVelocity))

        forEachIndexed<PositionComponent> { index, position ->
            position.initRandom()
        }
        forEachIndexed<PositionVelocity> { index, archetype ->
            archetype.velocity.x = (Random.nextFloat()-0.5f) * 10.toFloat()
            archetype.velocity.y = (Random.nextFloat()-0.5f) * 10.toFloat()
        }

//        forEachIndexed<PositionVelocity> { index, archetype ->
//            println("$index ${archetype.position.print()} ${archetype.velocity.print()} ")
//        }
    }


    val skiaLayer = SkiaLayer()
    skiaLayer.renderDelegate = SkiaLayerRenderDelegate(skiaLayer, object : SkikoRenderDelegate {
        val paint = Paint().apply {
            color = Color.RED
        }

        override fun onRender(canvas: Canvas, width: Int, height: Int, nanoTime: Long) {
            extractionLock.withLock {
                canvas.clear(Color.BLACK)

//                val ts = nanoTime / 5_000_000
//                canvas.drawCircle( (ts % width).toFloat(), (ts % height).toFloat(), 20f, paint )

                world.forEachIndexed<PositionComponent> { index, it ->
                    canvas.drawCircle(it.x, it.y, 2f, paint)
                }
                world.forEachIndexed<PositionVelocity> { index, it ->
                    canvas.drawCircle(it.position.x, it.position.y, 2f, paint)
                }
            }
        }
    })
    var _window: JFrame? = null
    SwingUtilities.invokeLater {
        val window = JFrame("Skiko example").apply {
            defaultCloseOperation = WindowConstants.EXIT_ON_CLOSE
            preferredSize = dimension
        }
        skiaLayer.attachTo(window.contentPane)
        skiaLayer.needRedraw()
        window.pack()
        window.isVisible = true
        _window = window
    }

    runBlocking {
        var lastNanoTime = System.nanoTime()

        while (true) {
            Arena.ofShared().use { perFrameArena ->

                val ns = System.nanoTime() - lastNanoTime
                var deltaSeconds = TimeUnit.NANOSECONDS.toSeconds(ns).toFloat()
                deltaSeconds = max(0.001f, deltaSeconds)
                world.systems.forEach { system ->
                    system.update(deltaSeconds, perFrameArena)

                    world.forEachIndexed<PositionVelocity> { index, component ->
                        val position = component.position
                        val velocity = component.velocity

                        position.x += velocity.x * deltaSeconds
                        position.y += velocity.y * deltaSeconds
                        if(position.x > dimension.width) {
                            position.x = 0f
                        }
                        if(position.y > dimension.height) {
                            position.y = 0f
                        }
//                        println("""$index: [${position.x} ${position.y}] [${velocity.x} ${velocity.y}] """)
                    }
                }

                lastNanoTime = System.nanoTime()
            }
        }
    }
}

context(segment: MemorySegment)
private fun PositionComponent.initRandom() {
    this.x += Random.nextFloat() * dimension.width.toFloat()
    this.y += Random.nextFloat() * dimension.height.toFloat()
    this.x = max(0f, this.x)
    this.y = max(0f, this.y)
    this.x = min(dimension.width.toFloat(), this.x)
    this.y = min(dimension.height.toFloat(), this.y)
}

