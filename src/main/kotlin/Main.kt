package org.example

import kotlinx.coroutines.runBlocking
import org.jetbrains.skia.Canvas
import org.jetbrains.skia.Color
import org.jetbrains.skia.Paint
import org.jetbrains.skiko.SkiaLayer
import org.jetbrains.skiko.SkiaLayerRenderDelegate
import org.jetbrains.skiko.SkikoRenderDelegate
import java.awt.Dimension
import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment
import javax.swing.JFrame
import javax.swing.SwingUtilities
import javax.swing.WindowConstants
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

val dimension = Dimension(1280, 1024)
@OptIn(ExperimentalAtomicApi::class)
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

        systems.add(object: System {
            override fun update(deltaSeconds: Float, arena: Arena) {
                forEachIndexed<PositionVelocity> { index, component ->
                    val position = component.position
                    val velocity = component.velocity

                    position.x += velocity.x * deltaSeconds
                    position.y += velocity.y * deltaSeconds
                    if(position.x > dimension.width) {
                        position.x = 0f
                    } else if(position.x < 0) {
                        position.x = dimension.width.toFloat()
                    }
                    if(position.y > dimension.height) {
                        position.y = 0f
                    } else if(position.y < 0) {
                        position.y = dimension.height.toFloat()
                    }
//                        println("""$index: [${position.x} ${position.y}] [${velocity.x} ${velocity.y}] """)
                }
            }
        })
//        forEachIndexed<PositionVelocity> { index, archetype ->
//            println("$index ${archetype.position.print()} ${archetype.velocity.print()} ")
//        }
    }

    createSkiaRenderer(world)
    world.simulate()
}

@OptIn(ExperimentalAtomicApi::class)
private fun createSkiaRenderer(world: World) {
    val skiaLayer = SkiaLayer()
    skiaLayer.renderDelegate = SkiaLayerRenderDelegate(skiaLayer, object : SkikoRenderDelegate {
        val paint = Paint().apply {
            color = Color.RED
        }

        override fun onRender(canvas: Canvas, width: Int, height: Int, nanoTime: Long) {
            runBlocking {
//                val ts = nanoTime / 5_000_000
//                canvas.drawCircle( (ts % width).toFloat(), (ts % height).toFloat(), 20f, paint )
//                world.forEachIndexed<PositionComponent> { index, it ->
//                    canvas.drawCircle(it.x, it.y, 2f, paint)
//                }
                val frame = world.frameChannel.receive()
                canvas.clear(Color.BLACK)
                world.extractedForEachIndexed<PositionComponent>(frame) { index, it ->
                    canvas.drawCircle(it.x, it.y, 2f, paint)
                }
                frame.rendered.compareAndSet(expectedValue = false, newValue = true)
            }
        }
    })

    SwingUtilities.invokeLater {
        val window = JFrame("Skiko example").apply {
            defaultCloseOperation = WindowConstants.EXIT_ON_CLOSE
            preferredSize = dimension
        }
        skiaLayer.attachTo(window.contentPane)
        skiaLayer.needRedraw()
        window.pack()
        window.isVisible = true
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

