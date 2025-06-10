package org.example

import kotlinx.coroutines.runBlocking
import org.lwjgl.glfw.GLFW
import org.lwjgl.glfw.GLFWErrorCallback
import org.lwjgl.glfw.GLFWFramebufferSizeCallback
import org.lwjgl.glfw.GLFWKeyCallback
import org.lwjgl.opengl.GL
import org.lwjgl.opengl.GL11
import org.lwjgl.opengl.GLUtil
import org.lwjgl.system.Callback
import org.lwjgl.system.MemoryStack
import org.lwjgl.system.MemoryUtil
import java.lang.System
import kotlin.Any
import kotlin.Boolean
import kotlin.Int
import kotlin.Long
import kotlin.RuntimeException
import kotlin.also
import kotlin.check
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.synchronized
import kotlin.use

class Multithreaded(
    private val world: World,
    var width: Int,
    var height: Int,
) {
    var errorCallback: GLFWErrorCallback? = null
    var keyCallback: GLFWKeyCallback? = null
    var fsCallback: GLFWFramebufferSizeCallback? = null
    var debugProc: Callback? = null

    var window: Long = 0
    var lock: Any = Any()
    var destroyed: Boolean = false

    fun run() {
        try {
            init()
            winProcLoop()

            synchronized(lock) {
                destroyed = true
                GLFW.glfwDestroyWindow(window)
            }
            if (debugProc != null) debugProc!!.free()
            keyCallback!!.free()
            fsCallback!!.free()
        } finally {
            GLFW.glfwTerminate()
            GLFW.glfwSetErrorCallback(null)!!.free()
        }
    }

    fun init() {
        GLFW.glfwSetErrorCallback(GLFWErrorCallback.createPrint(System.err).also { errorCallback = it })
        check(GLFW.glfwInit()) { "Unable to initialize GLFW" }

        GLFW.glfwDefaultWindowHints()
        GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE, GLFW.GLFW_FALSE)
        GLFW.glfwWindowHint(GLFW.GLFW_RESIZABLE, GLFW.GLFW_TRUE)

        window = GLFW.glfwCreateWindow(width, height, "Hello World!", MemoryUtil.NULL, MemoryUtil.NULL)
        if (window == MemoryUtil.NULL) throw RuntimeException("Failed to create the GLFW window")

        GLFW.glfwSetKeyCallback(window, object : GLFWKeyCallback() {
            override fun invoke(window: Long, key: Int, scancode: Int, action: Int, mods: Int) {
                if (key == GLFW.GLFW_KEY_ESCAPE && action == GLFW.GLFW_RELEASE) GLFW.glfwSetWindowShouldClose(
                    window,
                    true
                )
            }
        }.also { keyCallback = it })
        GLFW.glfwSetFramebufferSizeCallback(window, object : GLFWFramebufferSizeCallback() {
            override fun invoke(window: Long, w: Int, h: Int) {
                if (w > 0 && h > 0) {
                    width = w
                    height = h
                }
            }
        }.also { fsCallback = it })

        val vidmode = GLFW.glfwGetVideoMode(GLFW.glfwGetPrimaryMonitor())
        GLFW.glfwSetWindowPos(window, (vidmode!!.width() - width) / 2, (vidmode.height() - height) / 2)
        MemoryStack.stackPush().use { frame ->
            val framebufferSize = frame.mallocInt(2)
            GLFW.nglfwGetFramebufferSize(
                window,
                MemoryUtil.memAddress(framebufferSize),
                MemoryUtil.memAddress(framebufferSize) + 4
            )
            width = framebufferSize.get(0)
            height = framebufferSize.get(1)
        }
        GLFW.glfwShowWindow(window)
    }

    @OptIn(ExperimentalAtomicApi::class)
    fun renderLoop() = runBlocking {
        GLFW.glfwMakeContextCurrent(window)
        GL.createCapabilities()
        debugProc = GLUtil.setupDebugMessageCallback()
        GL11.glClearColor(0.3f, 0.5f, 0.7f, 0.0f)

        var lastTime = System.nanoTime()
        while (!destroyed) {
            GL11.glClear(GL11.GL_COLOR_BUFFER_BIT)
            GL11.glViewport(0, 0, width, height)

            val thisTime = System.nanoTime()
            val elapsed = (lastTime - thisTime) / 1E9f
            lastTime = thisTime

            val aspect = width.toFloat() / height
            GL11.glMatrixMode(GL11.GL_PROJECTION)
            GL11.glLoadIdentity()
            GL11.glOrtho((-1.0f * aspect).toDouble(), (+1.0f * aspect).toDouble(), -1.0, +1.0, -1.0, +1.0)

//            GL11.glMatrixMode(GL11.GL_MODELVIEW)
//            GL11.glRotatef(elapsed * 10.0f, 0f, 0f, 1f)
//            GL11.glBegin(GL11.GL_QUADS)
//            GL11.glVertex2f(-0.5f, -0.5f)
//            GL11.glVertex2f(+0.5f, -0.5f)
//            GL11.glVertex2f(+0.5f, +0.5f)
//            GL11.glVertex2f(-0.5f, +0.5f)
//            GL11.glEnd()
            val frame = world.frameChannel.receive()

            world.extractedForEachIndexed<PositionComponent>(frame) { index, it ->
//                canvas.drawCircle(it.x, it.y, 2f, paint)
                val pixelSize = 6
                val x = ((it.x/width) * 2) - 1
                val y = ((it.y/height) * 2) - 1
                val entityWidthHalf = pixelSize.toFloat() / width / 2
                val entityHeightHalf = pixelSize.toFloat() / height / 2
                GL11.glBegin(GL11.GL_QUADS)
                GL11.glVertex2f(x-entityWidthHalf, y-entityHeightHalf)
                GL11.glVertex2f(x+entityWidthHalf, y-entityHeightHalf)
                GL11.glVertex2f(x+entityWidthHalf, y+entityHeightHalf)
                GL11.glVertex2f(x-entityWidthHalf, y+entityHeightHalf)

//                GL11.glVertex2f(-0.5f, -0.5f)
//                GL11.glVertex2f(+0.5f, -0.5f)
//                GL11.glVertex2f(+0.5f, +0.5f)
//                GL11.glVertex2f(-0.5f, +0.5f)
                GL11.glEnd()
            }

            synchronized(lock) {
                if (!destroyed) {
                    GLFW.glfwSwapBuffers(window)
                }
            }
            frame.rendered.compareAndSet(expectedValue = false, newValue = true)
        }
    }

    fun winProcLoop() {
        /*
         * Start new thread to have the OpenGL context current in and which does
         * the rendering.
         */
        Thread { renderLoop() }.start()

        while (!GLFW.glfwWindowShouldClose(window)) {
            GLFW.glfwWaitEvents()
        }
    }
}