package cn.svecri.mdanceioar.ui.render

import android.content.res.AssetManager
import android.opengl.GLES11Ext
import android.opengl.GLES30
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
            val shaderId = GLES30.glCreateShader(type)
            maybeThrowGLException("Shader creation failed", "glCreateShader")
            GLES30.glShaderSource(shaderId, code)
            maybeThrowGLException("Shader source failed", "glShaderSource")
            GLES30.glCompileShader(shaderId)
            maybeThrowGLException("Shader compilation failed", "glCompileShader")

            val compileStatus = IntArray(1)
            GLES30.glGetShaderiv(shaderId, GLES30.GL_COMPILE_STATUS, compileStatus, 0)
            if (compileStatus[0] == GLES30.GL_FALSE) {
                val infoLog = GLES30.glGetShaderInfoLog(shaderId)
                GLES30.glDeleteShader(shaderId)
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
                        +1f, +0.5f,
                    )) }
    }

    private val cameraTexCoords =
        ByteBuffer.allocateDirect(COORDS_BUFFER_SIZE).order(ByteOrder.nativeOrder()).asFloatBuffer()
    private var viewportWidth = 1
    private var viewportHeight = 1

    private val cameraColorTextureId = intArrayOf(0)
    private val cameraColorTarget = GLES11Ext.GL_TEXTURE_EXTERNAL_OES;
//    private val cameraColorTarget = GLES30.GL_TEXTURE_2D
    private val cameraSamplerId = intArrayOf(0)
    private val cameraSamplerTarget = GLES11Ext.GL_SAMPLER_EXTERNAL_OES
    private var backgroundCameraProgramId = 0
    private var cameraColorTexLocation = 0
    private val screenCoordsVertexBufferId = intArrayOf(0)
    private val cameraTexCoordsVertexBufferId = intArrayOf(0)
    private val backgroundCameraVertexArrayId = intArrayOf(0)

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES30.glGenTextures(1, cameraColorTextureId, 0)
        maybeThrowGLException("Texture creation failed", "glGenTextures");
        try {
            GLES30.glBindTexture(cameraColorTarget, cameraColorTextureId[0])
            maybeThrowGLException("Failed to bind texture", "glBindTexture")
            GLES30.glTexParameteri(cameraColorTarget, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
            GLES30.glTexParameteri(cameraColorTarget, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
            GLES30.glTexParameteri(cameraColorTarget, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
            GLES30.glTexParameteri(cameraColorTarget, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
            maybeThrowGLException("Failed to set texture parameter", "glTexParameteri")
            Log.i(TAG, "Successfully Create Camera Texture(Id: ${cameraColorTextureId[0]})")
            for (i in 0..10) {
                Log.i(TAG, "Texture $i Exist: ${GLES30.glIsTexture(i)}")
            }
        } catch (t: Throwable) {
            GLES30.glDeleteTextures(1, cameraColorTextureId, 0)
            throw t
        }

        var vertexShaderId = 0
        var fragmentShaderId = 0
        try {
            vertexShaderId = createShader(
                GLES30.GL_VERTEX_SHADER,
                assets.open("shaders/background_camera.vert").bufferedReader().use(
                    BufferedReader::readText
                )
            )
            fragmentShaderId = createShader(
                GLES30.GL_FRAGMENT_SHADER,
                assets.open("shaders/background_camera.frag").bufferedReader().use(
                    BufferedReader::readText
                )
            )

            val programId = GLES30.glCreateProgram()
            maybeThrowGLException("Shader program creation failed", "glCreateProgram")
            GLES30.glAttachShader(programId, vertexShaderId)
            maybeThrowGLException("Failed to attach vertex shader", "glAttachShader")
            GLES30.glAttachShader(programId, fragmentShaderId)
            maybeThrowGLException("Failed to attach fragment shader", "glAttachShader")
            GLES30.glLinkProgram(programId)
            maybeThrowGLException("Failed to link shader program", "glLinkProgram")
            backgroundCameraProgramId = programId

            val linkStatus = IntArray(1)
            GLES30.glGetProgramiv(programId, GLES30.GL_LINK_STATUS, linkStatus, 0)
            if (linkStatus[0] == GLES30.GL_FALSE) {
                val infoLog = GLES30.glGetProgramInfoLog(programId)
                throw GLException(0, "Shader link failed: $infoLog")
            }
        } catch (t: Throwable) {
            if (backgroundCameraProgramId != 0) {
                GLES30.glDeleteProgram(backgroundCameraProgramId)
            }
            throw t
        } finally {
            if (vertexShaderId != 0) {
                GLES30.glDeleteShader(vertexShaderId)
            }
            if (fragmentShaderId != 0) {
                GLES30.glDeleteShader(fragmentShaderId)
            }
        }

        GLES30.glGenSamplers(1, cameraSamplerId,0)
        GLES30.glSamplerParameteri(cameraSamplerId[0],GLES30.GL_TEXTURE_MIN_FILTER,GLES30.GL_LINEAR)
        GLES30.glSamplerParameteri(cameraSamplerId[0],GLES30.GL_TEXTURE_MAG_FILTER,GLES30.GL_LINEAR)
        GLES30.glSamplerParameteri(cameraSamplerId[0],GLES30.GL_TEXTURE_WRAP_S,GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glSamplerParameteri(cameraSamplerId[0],GLES30.GL_TEXTURE_WRAP_T,GLES30.GL_CLAMP_TO_EDGE)
        cameraColorTexLocation = GLES30.glGetUniformLocation(backgroundCameraProgramId, "u_CameraColorTexture")
        maybeThrowGLException("Failed to find uniform", "glGetUniformLocation")
        require(cameraColorTexLocation != -1) { "Shader uniform does not exist: u_CameraColorTexture" }

        try {
            GLES30.glBindVertexArray(0)
            GLES30.glGenBuffers(1, screenCoordsVertexBufferId, 0)
            maybeThrowGLException("Failed to generate buffers", "glGenBuffers")
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, screenCoordsVertexBufferId[0])
            maybeThrowGLException("Failed to bind buffer object", "glBindBuffer")
            NDC_QUAD_COORDS_BUFFER.rewind()
            GLES30.glBufferData(
                GLES30.GL_ARRAY_BUFFER,
                NDC_QUAD_COORDS_BUFFER.limit() * 4,
                NDC_QUAD_COORDS_BUFFER,
                GLES30.GL_DYNAMIC_DRAW
            )

            GLES30.glGenBuffers(1, cameraTexCoordsVertexBufferId, 0)
            maybeThrowGLException("Failed to generate buffers", "glGenBuffers")
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, cameraTexCoordsVertexBufferId[0])
            maybeThrowGLException("Failed to bind buffer object", "glBindBuffer")

            GLES30.glGenVertexArrays(1, backgroundCameraVertexArrayId, 0)
            maybeThrowGLException("Failed to generate a vertex array", "glGenVertexArrays")
            GLES30.glBindVertexArray(backgroundCameraVertexArrayId[0])
            maybeThrowGLException("Failed to bind vertex array object", "glBindVertexArray")

            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, screenCoordsVertexBufferId[0])
            GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, 0, 0)
            maybeThrowGLException(
                "Failed to associate vertex buffer with vertex array", "glVertexAttribPointer"
            )
            GLES30.glEnableVertexAttribArray(0)
            maybeThrowGLException(
                "Failed to enable vertex buffer", "glEnableVertexAttribArray"
            )

            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, cameraTexCoordsVertexBufferId[0])
            GLES30.glVertexAttribPointer(1, 2, GLES30.GL_FLOAT, false, 0, 0)
            maybeThrowGLException(
                "Failed to associate vertex buffer with vertex array", "glVertexAttribPointer"
            )
            GLES30.glEnableVertexAttribArray(1)
            maybeThrowGLException(
                "Failed to enable vertex buffer", "glEnableVertexAttribArray"
            )
            GLES30.glBindVertexArray(0)
        }catch (t: Throwable) {
            if (cameraTexCoordsVertexBufferId[0] != 0) {
                GLES30.glDeleteBuffers(1, cameraTexCoordsVertexBufferId, 0)
                cameraTexCoordsVertexBufferId[0] = 0
            }
            if (screenCoordsVertexBufferId[0] != 0) {
                GLES30.glDeleteBuffers(1, screenCoordsVertexBufferId, 0)
                screenCoordsVertexBufferId[0] = 0
            }
            if (backgroundCameraVertexArrayId[0] != 0) {
                GLES30.glDeleteVertexArrays(1, backgroundCameraVertexArrayId, 0)
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
        GLES30.glUseProgram(0)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        GLES30.glDisable(GLES30.GL_DEPTH_TEST)
        GLES30.glDisable(GLES30.GL_STENCIL_TEST)
        GLES30.glDisable(GLES30.GL_SCISSOR_TEST)
        GLES30.glDisable(GLES30.GL_BLEND)
        GLES30.glDisable(GLES30.GL_CULL_FACE)
        GLES30.glDisable(GLES30.GL_POLYGON_OFFSET_FILL)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)
        GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, 0)
        GLES30.glBindBufferRange(GLES30.GL_UNIFORM_BUFFER, 0, 0, 0, 0)

        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        maybeThrowGLException("Failed to bind framebuffer", "glBindFramebuffer")
        GLES30.glColorMask(true, true, true, true)
        GLES30.glDepthMask(true)
        GLES30.glDisable(GLES30.GL_DEPTH_TEST)
        GLES30.glDisable(GLES30.GL_STENCIL_TEST)
        GLES30.glDisable(GLES30.GL_SCISSOR_TEST)
//        GLES30.glScissor(0, 0, viewportWidth, viewportHeight)
//        GLES30.glEnable(GLES30.GL_SCISSOR_TEST)
        GLES30.glViewport(0, 0, viewportWidth, viewportHeight)
        maybeThrowGLException("Failed to set viewport dimensions", "glViewport")
        GLES30.glDepthRangef(0f, 1f)

        GLES30.glUseProgram(backgroundCameraProgramId)
        maybeThrowGLException("Failed to use shader program", "glUseProgram")
        GLES30.glFrontFace(GLES30.GL_CCW)
        GLES30.glDisable(GLES30.GL_CULL_FACE)
        GLES30.glDepthFunc(GLES30.GL_LEQUAL)
        GLES30.glDepthMask(true)
        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendEquationSeparate(GLES30.GL_FUNC_ADD, GLES30.GL_FUNC_ADD)
        GLES30.glBlendFuncSeparate(
            GLES30.GL_ONE,
            GLES30.GL_ZERO,
            GLES30.GL_ONE,
            GLES30.GL_ZERO
        )
        maybeThrowGLException("Failed to set blend mode", "glBlendFuncSeparate")
        GLES30.glDepthMask(false)
        maybeThrowGLException("Failed to set depth write mask", "glDepthMask")
        GLES30.glDisable(GLES30.GL_DEPTH_TEST)
        maybeThrowGLException("Failed to disable depth test", "glDisable")
        GLES30.glColorMask(true, true, true, true)

        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        maybeThrowGLException("Failed to set active texture", "glActiveTexture")
        GLES30.glBindTexture(cameraColorTarget, cameraColorTextureId[0])
        maybeThrowGLException("Failed to bind texture", "glBindTexture")
        GLES30.glBindSampler(0, cameraSamplerId[0])
        maybeThrowGLException("Failed to bind Sampler", "glBindSampler")
        GLES30.glUniform1i(cameraColorTexLocation, 0)
        maybeThrowGLException("Failed to set shader texture uniform", "glUniform1i")

        GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE2)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE3)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0)
        GLES30.glBindSampler(1, 0)
        GLES30.glBindSampler(2, 0)
        GLES30.glBindSampler(3, 0)

        GLES30.glBindVertexArray(backgroundCameraVertexArrayId[0])
        maybeThrowGLException("Failed to bind vertex array object", "glBindVertexArray")
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, NDC_QUAD_COORDS_BUFFER.limit() / 2)
        maybeThrowGLException("Failed to draw vertex array object", "glDrawArrays")
    }

    fun updateDisplayGeometry(frame: Frame) {
        if (frame.hasDisplayGeometryChanged()) {
            frame.transformCoordinates2d(
                Coordinates2d.OPENGL_NORMALIZED_DEVICE_COORDINATES,
                NDC_QUAD_COORDS_BUFFER,
                Coordinates2d.TEXTURE_NORMALIZED,
                cameraTexCoords
            )

            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, cameraTexCoordsVertexBufferId[0])
            maybeThrowGLException("Failed to bind buffer object", "glBindBuffer")
            cameraTexCoords.rewind()
            GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, cameraTexCoords.limit() * 4, cameraTexCoords, GLES30.GL_DYNAMIC_DRAW)
            maybeThrowGLException("Failed to populate vertex buffer object", "glBufferSubData")
        }
    }

    fun cameraColorTextureId(): Int {
        return cameraColorTextureId[0]
    }
}