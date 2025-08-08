package org.example

import kotlinx.coroutines.runBlocking
import org.apache.logging.log4j.LogManager
import org.lwjgl.BufferUtils
import org.lwjgl.glfw.GLFW.*
import org.lwjgl.glfw.GLFWErrorCallback
import org.lwjgl.glfw.GLFWFramebufferSizeCallback
import org.lwjgl.glfw.GLFWKeyCallback
import org.lwjgl.opengl.ARBShaderStorageBufferObject.GL_SHADER_STORAGE_BUFFER
import org.lwjgl.opengl.ARBVertexArrayObject.glBindVertexArray
import org.lwjgl.opengl.ARBVertexArrayObject.glGenVertexArrays
import org.lwjgl.opengl.GL
import org.lwjgl.opengl.GL11.GL_FALSE
import org.lwjgl.opengl.GL20.*
import org.lwjgl.opengl.GL30.glBindBufferBase
import org.lwjgl.opengl.GL31.GL_UNIFORM_BUFFER
import org.lwjgl.opengl.GL31.glDrawArraysInstanced
import org.lwjgl.opengl.GL44.GL_DYNAMIC_STORAGE_BIT
import org.lwjgl.opengl.GL45.glCreateBuffers
import org.lwjgl.opengl.GL45.glNamedBufferStorage
import org.lwjgl.opengl.GLUtil
import org.lwjgl.system.Callback
import org.lwjgl.system.MemoryStack
import org.lwjgl.system.MemoryUtil
import java.lang.System
import kotlin.Any
import kotlin.Boolean
import kotlin.IllegalStateException
import kotlin.Int
import kotlin.Long
import kotlin.OptIn
import kotlin.RuntimeException
import kotlin.also
import kotlin.check
import kotlin.collections.first
import kotlin.collections.map
import kotlin.collections.setOf
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.intArrayOf
import kotlin.reflect.full.isSubclassOf
import kotlin.synchronized
import kotlin.system.measureNanoTime
import kotlin.use

private val logger = LogManager.getLogger("Rendering")
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
                glfwDestroyWindow(window)
            }
            if (debugProc != null) debugProc!!.free()
            keyCallback!!.free()
            fsCallback!!.free()
        } finally {
            glfwTerminate()
            glfwSetErrorCallback(null)!!.free()
        }
    }

    fun init() {
        glfwSetErrorCallback(GLFWErrorCallback.createPrint(System.err).also { errorCallback = it })
        check(glfwInit()) { "Unable to initialize GLFW" }

        glfwDefaultWindowHints()
        glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE)
        glfwWindowHint(GLFW_RESIZABLE, GLFW_TRUE)
        glfwWindowHint(GLFW_DECORATED, GLFW_TRUE)
        glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 4)
        glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 3)

        window = glfwCreateWindow(width, height, "Hello World!", MemoryUtil.NULL, MemoryUtil.NULL)
        if (window == MemoryUtil.NULL) throw RuntimeException("Failed to create the GLFW window")

        glfwSetKeyCallback(window, object : GLFWKeyCallback() {
            override fun invoke(window: Long, key: Int, scancode: Int, action: Int, mods: Int) {
                if (key == GLFW_KEY_ESCAPE && action == GLFW_RELEASE) glfwSetWindowShouldClose(
                    window,
                    true
                ) else if(key == GLFW_KEY_A && action == GLFW_RELEASE) {
                    val allEntities = (0 until 10).map {
                        Entity()
                    }
                    runBlocking {
                        world.toBeExecutedInSimulationThread.send {
                            world.addAll(allEntities, setOf(PositionVelocity))
                            world.forEach<PositionVelocity> { entityId, component ->
                                if(allEntities.contains(entityId)) {
                                    component.position.initRandom()
                                    component.velocity.initRandom()
                                }
                            }
                        }
                    }
                } else if(key == GLFW_KEY_D && action == GLFW_RELEASE) {
                    val allEntities = (0..10).map { world.entities.keys.random() }
                    runBlocking {
                        world.toBeExecutedInSimulationThread.send {
                            world.removeAll(allEntities)
                        }
                    }
                }
            }
        }.also { keyCallback = it })
        glfwSetFramebufferSizeCallback(window, object : GLFWFramebufferSizeCallback() {
            override fun invoke(window: Long, w: Int, h: Int) {
                if (w > 0 && h > 0) {
                    width = w
                    height = h
                }
            }
        }.also { fsCallback = it })

        val vidmode = glfwGetVideoMode(glfwGetPrimaryMonitor())
//        GLFW.glfwSetWindowPos(window, (vidmode!!.width() - width) / 2, (vidmode.height() - height) / 2)
        MemoryStack.stackPush().use { frame ->
            val framebufferSize = frame.mallocInt(2)
            nglfwGetFramebufferSize(
                window,
                MemoryUtil.memAddress(framebufferSize),
                MemoryUtil.memAddress(framebufferSize) + 4
            )
            width = framebufferSize.get(0)
            height = framebufferSize.get(1)
        }
        glfwShowWindow(window)
    }

    enum class DataStrategy {
        Uniform, UBO, SSBO
    }
    @OptIn(ExperimentalAtomicApi::class)
    fun renderLoop() = runBlocking {
        glfwMakeContextCurrent(window)
        GL.createCapabilities()
        debugProc = GLUtil.setupDebugMessageCallback()
        glClearColor(0.3f, 0.5f, 0.7f, 0.0f)

        var lastTime = System.nanoTime()

        val vao = glGenVertexArrays()
        glBindVertexArray(vao)
        val dataStrategy = DataStrategy.SSBO
        val vertexShaderSource = """
            #version 430 core
            out vec4 vertexColor;
            
            layout(location = 0) uniform float positions[10000];
            struct PositionVelocity {
                vec2 position;
                vec2 velocity;
            };
            layout (std140, binding = 1) uniform PositionVelocities {
                PositionVelocity positionVelocities[10000];
            };
            layout(binding = 2, std430) readonly buffer _positionVelocitiesSSBO {
                PositionVelocity positionVelocitiesSSBO[];
            };
            
            void main()
            {
                float width = 1280;
                float height = 1024;
                ${
                    when(dataStrategy) {
                        DataStrategy.UBO -> "vec4 position = vec4(positionVelocities[gl_InstanceID].position, 0, 0);"
                        DataStrategy.SSBO -> "vec4 position = vec4(positionVelocitiesSSBO[gl_InstanceID].position, 0, 0);"
                        DataStrategy.Uniform -> "vec4 position = vec4(positions[gl_InstanceID*4], positions[(gl_InstanceID*4)+1], 0, 0);"
                    }
                }
                float pixelSize = 10;
                float x = ((position.x/width) * 2) - 1;
                float y = ((position.y/height) * 2) - 1;
                float entityWidthHalf = pixelSize / width / 2;
                float entityHeightHalf = pixelSize / height / 2;
                
                vec3 aPos;
                if(gl_VertexID == 0) {
                    aPos.xy = vec2(x-entityWidthHalf, y-entityHeightHalf);
                } else if(gl_VertexID == 1) {
                    aPos.xy = vec2(x+entityWidthHalf, y-entityHeightHalf);
                } else if(gl_VertexID == 2) {
                    aPos.xy = vec2(x+entityWidthHalf, y+entityHeightHalf);
                } else if(gl_VertexID == 3) {
                    aPos.xy = vec2(x-entityWidthHalf, y+entityHeightHalf);
                }

                gl_Position = vec4(aPos, 1.0);
                vertexColor = vec4(0.5, 0.0, 0.0, 1.0);
            }
        """.trimIndent()
        val fragmentShaderSource = """
            #version 430 core
            out vec4 FragColor;
            in vec4 vertexColor;

            void main()
            {
                FragColor = vertexColor;
            } 
        """.trimIndent()

        val vertexShader = glCreateShader(GL_VERTEX_SHADER)
        glShaderSource(vertexShader, vertexShaderSource)
        glCompileShader(vertexShader)

        val isCompiled = intArrayOf(0)
        glGetShaderiv(vertexShader, GL_COMPILE_STATUS, isCompiled)
        if(isCompiled[0] == GL_FALSE) {
            val infoLog = glGetShaderInfoLog(vertexShader)
            glDeleteShader(vertexShader)
            throw IllegalStateException("Cannot compile shader $infoLog")
        }
        val fragmentShader = glCreateShader(GL_FRAGMENT_SHADER)

        glShaderSource(fragmentShader, fragmentShaderSource)
        glCompileShader(fragmentShader)

        glGetShaderiv(fragmentShader, GL_COMPILE_STATUS, isCompiled)
        if (isCompiled[0] == GL_FALSE)
        {
            val infoLog = glGetShaderInfoLog(fragmentShader)
            glDeleteShader(fragmentShader)
            glDeleteShader(vertexShader)

            throw IllegalStateException("Cannot compile shader $infoLog")
        }

        val program = glCreateProgram()

        glAttachShader(program, vertexShader)
        glAttachShader(program, fragmentShader)
        glLinkProgram(program)
        glUseProgram(program)

        val positionVelocitiesBlockIndex = glGenBuffers()

        val ssbo = glCreateBuffers()
        glNamedBufferStorage(ssbo, BufferUtils.createFloatBuffer(2600000), GL_DYNAMIC_STORAGE_BIT)

        glfwSwapInterval(0) // 0 disable vsync, 1 enable vsync

        glViewport(0, 0, width, height)
        while (!destroyed) {
            logMs("glClear") {
                glClear(GL_COLOR_BUFFER_BIT)
            }

            val thisTime = System.nanoTime()
            val deltaMs = (thisTime - lastTime) / 1E6f
//            val deltaSeconds = deltaNs / 1E9f
//            glfwSetWindowTitle(window, deltaSeconds.toString())
            lastTime = thisTime

            val frame = logMs("receive frame") { world.frameChannel.receive() }

//            val extract = frame.extracts.entries.first { it.key::class.isSubclassOf(PositionVelocity::class) }
//            val extractPositionBuffer = extract.value.asByteBuffer().order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
            val extract = logMs("select extract") { frame.extractsByteBuffers.entries.first { it.key::class.isSubclassOf(PositionVelocity::class) } }
            when (dataStrategy) {
                DataStrategy.UBO -> {
                    val extractPositionBuffer = extract.value.asFloatBuffer()
                    glBindBuffer(GL_UNIFORM_BUFFER, positionVelocitiesBlockIndex)
                    glBufferData(GL_UNIFORM_BUFFER, extractPositionBuffer, GL_DYNAMIC_DRAW)
                    glBindBufferBase(GL_UNIFORM_BUFFER, 1, positionVelocitiesBlockIndex)
                }
                DataStrategy.SSBO -> logMs("buffer subdata") {
                    val extractPositionBuffer = extract.value
                    glBindBuffer(GL_SHADER_STORAGE_BUFFER, ssbo)
                    glBufferSubData(GL_SHADER_STORAGE_BUFFER, 0, extractPositionBuffer)
                    glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 2, ssbo)
                }
                DataStrategy.Uniform -> {
                    val extractPositionBuffer = extract.value.asFloatBuffer()
                    glUniform1fv(0, extractPositionBuffer)
                }
            }
            logMs("glDrawArraysInstanced") {
                glDrawArraysInstanced(GL_TRIANGLES, 0, 3, extract.value.capacity()/4/4)
            }

            logMs("glfwSwapBuffers") {
                synchronized(lock) {
                    if (!destroyed) {
                        glfwSwapBuffers(window)
                    }
                }
            }
            logger.info("Frame took {} ms \n", deltaMs)
            logMs("frame set rendered") {
                frame.rendered.compareAndSet(expectedValue = false, newValue = true)
            }
        }
    }

    fun winProcLoop() {
        /*
         * Start new thread to have the OpenGL context current in and which does
         * the rendering.
         */
        Thread { renderLoop() }.start()

        while (!glfwWindowShouldClose(window)) {
            glfwWaitEvents()
        }
    }
}

private inline fun <T> logMs(label: String, block: () -> T): T {
    var result: T? = null
    val timeMs = measureNanoTime {
        result = block()
    } / 1E9f
    logger.info("$label {} ms \n", timeMs)
    return result!!
}