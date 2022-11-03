package cn.svecri.mdanceioar.ui.render

import android.content.res.AssetManager
import android.opengl.GLES11Ext
import android.opengl.GLES32
import android.opengl.GLException
import android.opengl.GLSurfaceView
import android.util.Log
import cn.svecri.mdanceioar.ui.render.GLError.maybeThrowGLException
import com.google.ar.core.Coordinates2d
import com.google.ar.core.Frame
import java.io.BufferedReader
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

class BackgroundRenderer(private val assets: AssetManager): GLSurfaceView.Renderer {
    companion object {
        private val TAG: String = BackgroundRenderer::class.java.simpleName

        private fun createShader(type: Int, code: String): Int {
            val shaderId = GLES32.glCreateShader(type)
            maybeThrowGLException("Shader creation failed", "glCreateShader")
            GLES32.glShaderSource(shaderId, code)
            maybeThrowGLException("Shader source failed", "glShaderSource")
            GLES32.glCompileShader(shaderId)
            maybeThrowGLException("Shader compilation failed", "glCompileShader")

            val compileStatus = IntArray(1)
            GLES32.glGetShaderiv(shaderId, GLES32.GL_COMPILE_STATUS, compileStatus, 0)
            if (compileStatus[0] == GLES32.GL_FALSE) {
                val infoLog = GLES32.glGetShaderInfoLog(shaderId)
                GLES32.glDeleteShader(shaderId)
                throw GLException(0, "Shader compilation failed: $infoLog")
            }
            return shaderId
        }

        // components_per_vertex * number_of_vertices * float_size
        private const val COORDS_BUFFER_SIZE = 2 * 4 * 4

        private val NDC_QUAD_COORDS_BUFFER =
            ByteBuffer.allocateDirect(COORDS_BUFFER_SIZE).order(ByteOrder.nativeOrder())
                .asFloatBuffer().apply { put(
                    floatArrayOf(
                        -1f, -1f,
                        +1f, -1f,
                        -1f, +1f,
                        +1f, +1f,
                    )) }
    }

    private val cameraTexCoords =
        ByteBuffer.allocateDirect(COORDS_BUFFER_SIZE).order(ByteOrder.nativeOrder()).asFloatBuffer()
    private var viewportWidth = 1
    private var viewportHeight = 1

    private val cameraColorTextureId = intArrayOf(0)
    private val cameraColorTarget = GLES11Ext.GL_TEXTURE_EXTERNAL_OES
    private val cameraSamplerId = intArrayOf(0)
    private var backgroundCameraProgramId = 0
    private var cameraColorTexLocation = 0
    private val screenCoordsVertexBufferId = intArrayOf(0)
    private val cameraTexCoordsVertexBufferId = intArrayOf(0)
    private val backgroundCameraVertexArrayId = intArrayOf(0)

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES32.glGenTextures(1, cameraColorTextureId, 0)
        maybeThrowGLException("Texture creation failed", "glGenTextures")
        try {
            GLES32.glBindTexture(cameraColorTarget, cameraColorTextureId[0])
            maybeThrowGLException("Failed to bind texture", "glBindTexture")
            GLES32.glTexParameteri(cameraColorTarget, GLES32.GL_TEXTURE_MIN_FILTER, GLES32.GL_LINEAR)
            GLES32.glTexParameteri(cameraColorTarget, GLES32.GL_TEXTURE_MAG_FILTER, GLES32.GL_LINEAR)
            GLES32.glTexParameteri(cameraColorTarget, GLES32.GL_TEXTURE_WRAP_S, GLES32.GL_CLAMP_TO_EDGE)
            GLES32.glTexParameteri(cameraColorTarget, GLES32.GL_TEXTURE_WRAP_T, GLES32.GL_CLAMP_TO_EDGE)
            maybeThrowGLException("Failed to set texture parameter", "glTexParameteri")
        } catch (t: Throwable) {
            GLES32.glDeleteTextures(1, cameraColorTextureId, 0)
            throw t
        }

        var vertexShaderId = 0
        var fragmentShaderId = 0
        try {
            vertexShaderId = createShader(
                GLES32.GL_VERTEX_SHADER,
                assets.open("shaders/background_camera.vert").bufferedReader().use(
                    BufferedReader::readText
                )
            )
            fragmentShaderId = createShader(
                GLES32.GL_FRAGMENT_SHADER,
                assets.open("shaders/background_camera.frag").bufferedReader().use(
                    BufferedReader::readText
                )
            )

            val programId = GLES32.glCreateProgram()
            maybeThrowGLException("Shader program creation failed", "glCreateProgram")
            GLES32.glAttachShader(programId, vertexShaderId)
            maybeThrowGLException("Failed to attach vertex shader", "glAttachShader")
            GLES32.glAttachShader(programId, fragmentShaderId)
            maybeThrowGLException("Failed to attach fragment shader", "glAttachShader")
            GLES32.glLinkProgram(programId)
            maybeThrowGLException("Failed to link shader program", "glLinkProgram")
            backgroundCameraProgramId = programId

            val linkStatus = IntArray(1)
            GLES32.glGetProgramiv(programId, GLES32.GL_LINK_STATUS, linkStatus, 0)
            if (linkStatus[0] == GLES32.GL_FALSE) {
                val infoLog = GLES32.glGetProgramInfoLog(programId)
                throw GLException(0, "Shader link failed: $infoLog")
            }
        } catch (t: Throwable) {
            if (backgroundCameraProgramId != 0) {
                GLES32.glDeleteProgram(backgroundCameraProgramId)
            }
            throw t
        } finally {
            if (vertexShaderId != 0) {
                GLES32.glDeleteShader(vertexShaderId)
            }
            if (fragmentShaderId != 0) {
                GLES32.glDeleteShader(fragmentShaderId)
            }
        }

        GLES32.glGenSamplers(1, cameraSamplerId,0)
        GLES32.glSamplerParameteri(cameraSamplerId[0],GLES32.GL_TEXTURE_MIN_FILTER,GLES32.GL_LINEAR)
        GLES32.glSamplerParameteri(cameraSamplerId[0],GLES32.GL_TEXTURE_MAG_FILTER,GLES32.GL_LINEAR)
        GLES32.glSamplerParameteri(cameraSamplerId[0],GLES32.GL_TEXTURE_WRAP_S,GLES32.GL_CLAMP_TO_EDGE)
        GLES32.glSamplerParameteri(cameraSamplerId[0],GLES32.GL_TEXTURE_WRAP_T,GLES32.GL_CLAMP_TO_EDGE)
        cameraColorTexLocation = GLES32.glGetUniformLocation(backgroundCameraProgramId, "u_CameraColorTexture")
        maybeThrowGLException("Failed to find uniform", "glGetUniformLocation")
        require(cameraColorTexLocation != -1) { "Shader uniform does not exist: u_CameraColorTexture" }

        try {
            GLES32.glBindVertexArray(0)
            GLES32.glGenBuffers(1, screenCoordsVertexBufferId, 0)
            maybeThrowGLException("Failed to generate buffers", "glGenBuffers")
            GLES32.glBindBuffer(GLES32.GL_ARRAY_BUFFER, screenCoordsVertexBufferId[0])
            maybeThrowGLException("Failed to bind buffer object", "glBindBuffer")
            NDC_QUAD_COORDS_BUFFER.rewind()
            GLES32.glBufferData(
                GLES32.GL_ARRAY_BUFFER,
                NDC_QUAD_COORDS_BUFFER.limit() * 4,
                NDC_QUAD_COORDS_BUFFER,
                GLES32.GL_DYNAMIC_DRAW
            )

            GLES32.glGenBuffers(1, cameraTexCoordsVertexBufferId, 0)
            maybeThrowGLException("Failed to generate buffers", "glGenBuffers")
            GLES32.glBindBuffer(GLES32.GL_ARRAY_BUFFER, cameraTexCoordsVertexBufferId[0])
            maybeThrowGLException("Failed to bind buffer object", "glBindBuffer")

            GLES32.glGenVertexArrays(1, backgroundCameraVertexArrayId, 0)
            maybeThrowGLException("Failed to generate a vertex array", "glGenVertexArrays")
            GLES32.glBindVertexArray(backgroundCameraVertexArrayId[0])
            maybeThrowGLException("Failed to bind vertex array object", "glBindVertexArray")
            Log.i(TAG, "The real background VA Id is ${backgroundCameraVertexArrayId[0]}")

            GLES32.glBindBuffer(GLES32.GL_ARRAY_BUFFER, screenCoordsVertexBufferId[0])
            GLES32.glVertexAttribPointer(0, 2, GLES32.GL_FLOAT, false, 0, 0)
            maybeThrowGLException(
                "Failed to associate vertex buffer with vertex array", "glVertexAttribPointer"
            )
            GLES32.glEnableVertexAttribArray(0)

            GLES32.glBindBuffer(GLES32.GL_ARRAY_BUFFER, cameraTexCoordsVertexBufferId[0])
            GLES32.glVertexAttribPointer(1, 2, GLES32.GL_FLOAT, false, 0, 0)
            maybeThrowGLException(
                "Failed to associate vertex buffer with vertex array", "glVertexAttribPointer"
            )
            GLES32.glEnableVertexAttribArray(1)
            GLES32.glBindVertexArray(0)
        }catch (t: Throwable) {
            if (cameraTexCoordsVertexBufferId[0] != 0) {
                GLES32.glDeleteBuffers(1, cameraTexCoordsVertexBufferId, 0)
                cameraTexCoordsVertexBufferId[0] = 0
            }
            if (screenCoordsVertexBufferId[0] != 0) {
                GLES32.glDeleteBuffers(1, screenCoordsVertexBufferId, 0)
                screenCoordsVertexBufferId[0] = 0
            }
            if (backgroundCameraVertexArrayId[0] != 0) {
                GLES32.glDeleteVertexArrays(1, backgroundCameraVertexArrayId, 0)
                backgroundCameraVertexArrayId[0] = 0
            }
            throw t
        }
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        viewportWidth = width
        viewportHeight = height
    }

    override fun onDrawFrame(gl: GL10?) {
        Log.i(TAG, "Drawing Background")
        GLES32.glPushDebugGroup(GLES32.GL_DEBUG_SOURCE_APPLICATION, 1, 10, "Background")
        maybeThrowGLException("Failed to push debug group", "glPushDebugGroup")
        GLES32.glUseProgram(0)
        GLES32.glBindFramebuffer(GLES32.GL_FRAMEBUFFER, 0)
        GLES32.glDisable(GLES32.GL_DEPTH_TEST)
        GLES32.glDisable(GLES32.GL_STENCIL_TEST)
        GLES32.glDisable(GLES32.GL_SCISSOR_TEST)
        GLES32.glDisable(GLES32.GL_BLEND)
        GLES32.glDisable(GLES32.GL_CULL_FACE)
        GLES32.glDisable(GLES32.GL_POLYGON_OFFSET_FILL)
        GLES32.glBindBuffer(GLES32.GL_ARRAY_BUFFER, 0)
        GLES32.glBindBuffer(GLES32.GL_ELEMENT_ARRAY_BUFFER, 0)
        GLES32.glBindBufferRange(GLES32.GL_UNIFORM_BUFFER, 0, 0, 0, 0)

        GLES32.glBindFramebuffer(GLES32.GL_FRAMEBUFFER, 0)
        maybeThrowGLException("Failed to bind framebuffer", "glBindFramebuffer")
        GLES32.glColorMask(true, true, true, true)
        GLES32.glDepthMask(true)
        GLES32.glDisable(GLES32.GL_DEPTH_TEST)
        GLES32.glDisable(GLES32.GL_STENCIL_TEST)
        GLES32.glDisable(GLES32.GL_SCISSOR_TEST)
//        GLES32.glScissor(0, 0, viewportWidth, viewportHeight)
//        GLES32.glEnable(GLES32.GL_SCISSOR_TEST)
        GLES32.glViewport(0, 0, viewportWidth, viewportHeight)
        maybeThrowGLException("Failed to set viewport dimensions", "glViewport")
        GLES32.glDepthRangef(0f, 1f)

        GLES32.glUseProgram(backgroundCameraProgramId)
        maybeThrowGLException("Failed to use shader program", "glUseProgram")
        GLES32.glFrontFace(GLES32.GL_CCW)
        GLES32.glDisable(GLES32.GL_CULL_FACE)
        GLES32.glDepthFunc(GLES32.GL_LEQUAL)
        GLES32.glDepthMask(true)
        GLES32.glEnable(GLES32.GL_BLEND)
        GLES32.glBlendEquationSeparate(GLES32.GL_FUNC_ADD, GLES32.GL_FUNC_ADD)
        GLES32.glBlendFuncSeparate(
            GLES32.GL_ONE,
            GLES32.GL_ZERO,
            GLES32.GL_ONE,
            GLES32.GL_ZERO
        )
        maybeThrowGLException("Failed to set blend mode", "glBlendFuncSeparate")
        GLES32.glDepthMask(false)
        maybeThrowGLException("Failed to set depth write mask", "glDepthMask")
        GLES32.glDisable(GLES32.GL_DEPTH_TEST)
        maybeThrowGLException("Failed to disable depth test", "glDisable")
        GLES32.glColorMask(true, true, true, true)

        GLES32.glActiveTexture(GLES32.GL_TEXTURE0)
        maybeThrowGLException("Failed to set active texture", "glActiveTexture")
        GLES32.glBindTexture(cameraColorTarget, cameraColorTextureId[0])
        maybeThrowGLException("Failed to bind texture", "glBindTexture")
        GLES32.glBindSampler(0, cameraSamplerId[0])
        maybeThrowGLException("Failed to bind Sampler", "glBindSampler")
        GLES32.glUniform1i(cameraColorTexLocation, 0)
        maybeThrowGLException("Failed to set shader texture uniform", "glUniform1i")

        GLES32.glActiveTexture(GLES32.GL_TEXTURE1)
        GLES32.glBindTexture(GLES32.GL_TEXTURE_2D, 0)
        GLES32.glActiveTexture(GLES32.GL_TEXTURE2)
        GLES32.glBindTexture(GLES32.GL_TEXTURE_2D, 0)
        GLES32.glActiveTexture(GLES32.GL_TEXTURE3)
        GLES32.glBindTexture(GLES32.GL_TEXTURE_2D, 0)
        GLES32.glBindSampler(1, 0)
        GLES32.glBindSampler(2, 0)
        GLES32.glBindSampler(3, 0)

        GLES32.glBindVertexArray(backgroundCameraVertexArrayId[0])
        maybeThrowGLException("Failed to bind vertex array object", "glBindVertexArray")

        GLES32.glBindBuffer(GLES32.GL_ARRAY_BUFFER, screenCoordsVertexBufferId[0])
        GLES32.glVertexAttribPointer(0, 2, GLES32.GL_FLOAT, false, 0, 0)
        maybeThrowGLException(
            "Failed to associate vertex buffer with vertex array", "glVertexAttribPointer"
        )

        GLES32.glBindBuffer(GLES32.GL_ARRAY_BUFFER, cameraTexCoordsVertexBufferId[0])
        GLES32.glVertexAttribPointer(1, 2, GLES32.GL_FLOAT, false, 0, 0)
        maybeThrowGLException(
            "Failed to associate vertex buffer with vertex array", "glVertexAttribPointer"
        )
        GLES32.glDrawArrays(GLES32.GL_TRIANGLE_STRIP, 0, NDC_QUAD_COORDS_BUFFER.limit() / 2)
        maybeThrowGLException("Failed to draw vertex array object", "glDrawArrays")
        GLES32.glPopDebugGroup()
    }

    fun updateDisplayGeometry(frame: Frame) {
        if (frame.hasDisplayGeometryChanged()) {
            frame.transformCoordinates2d(
                Coordinates2d.OPENGL_NORMALIZED_DEVICE_COORDINATES,
                NDC_QUAD_COORDS_BUFFER,
                Coordinates2d.TEXTURE_NORMALIZED,
                cameraTexCoords
            )

            GLES32.glBindBuffer(GLES32.GL_ARRAY_BUFFER, cameraTexCoordsVertexBufferId[0])
            maybeThrowGLException("Failed to bind buffer object", "glBindBuffer")
            cameraTexCoords.rewind()
            GLES32.glBufferData(GLES32.GL_ARRAY_BUFFER, cameraTexCoords.limit() * 4, cameraTexCoords, GLES32.GL_DYNAMIC_DRAW)
            maybeThrowGLException("Failed to populate vertex buffer object", "glBufferSubData")
        }
    }

    fun cameraColorTextureId(): Int {
        return cameraColorTextureId[0]
    }
}