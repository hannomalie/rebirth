package org.example

import kotlinx.coroutines.runBlocking
import org.apache.logging.log4j.Level
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.core.config.Configurator
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
fun main() = runBlocking {

    Configurator.setAllLevels(LogManager.getRootLogger().name, Level.INFO)

    val world = World().apply {
        register(PositionComponent, VelocityComponent, PositionVelocity)

        Thread { simulate() }.start()

        toBeExecutedInSimulationThread.send {
            val maxEntityCount = 120000
            val allEntities = (0 until maxEntityCount).map {
                Entity()
            }
            addAll(allEntities.take(maxEntityCount/2), setOf(PositionComponent))
            addAll(allEntities.subList(maxEntityCount/2, allEntities.size), setOf(PositionVelocity))

            forEach<PositionComponent> { _, position ->
                position.initRandom()
            }
            forEach<PositionVelocity> { _, archetype ->
                archetype.velocity.initRandom()
            }
        }

        systems.add(object: System {
            override fun update(deltaSeconds: Float, arena: Arena) {
                parallelForEach<PositionVelocity> { index, component ->
                    val position = component.position
                    val velocity = component.velocity

                    var resultingX = position.x + velocity.x * deltaSeconds
                    var resultingY = position.y + velocity.y * deltaSeconds

                    if(resultingX > dimension.width.toFloat()) {
                        resultingX = 0f
                    } else if(resultingX < 0) {
                        resultingX = dimension.width.toFloat()
                    }
                    if(resultingY > dimension.height.toFloat()) {
                        resultingY = 0f
                    } else if(resultingY < 0) {
                        resultingY = dimension.height.toFloat()
                    }

                    position.x = resultingX
                    position.y = resultingY
                }
            }
        })
//        forEachIndexed<PositionVelocity> { index, archetype ->
//            println("$index ${archetype.position.print()} ${archetype.velocity.print()} ")
//        }
    }

    val useOpenGL = true
    if(useOpenGL) {
        Multithreaded(world, dimension.width, dimension.height).run()
    } else {
        createSkiaRenderer(world)
    }
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
fun PositionComponent.initRandom() {
    var resultingX = Random.nextFloat() * dimension.width.toFloat()
    var resultingY = Random.nextFloat() * dimension.height.toFloat()

    if(resultingX < 0f) {
        resultingX = dimension.width.toFloat()
    } else if(resultingX > dimension.width.toFloat()) {
        resultingX = 0f
    }
    if(resultingY < 0f) {
        resultingY = dimension.height.toFloat()
    } else if(resultingY > dimension.height.toFloat()) {
        resultingY = 0f
    }
    this.x = resultingX
    this.y = resultingY
}

context(segment: MemorySegment) fun VelocityComponent.initRandom() {
    this.x = (Random.nextFloat() - 0.5f) * 10.toFloat()
    this.y = (Random.nextFloat() - 0.5f) * 10.toFloat()
}
