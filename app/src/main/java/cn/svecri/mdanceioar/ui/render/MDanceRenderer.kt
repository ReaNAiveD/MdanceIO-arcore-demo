package cn.svecri.mdanceioar.ui.render

import android.content.Context
import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.util.Log
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.WindowManager
import cn.svecri.mdanceio.AndroidProxy
import cn.svecri.mdanceioar.ui.lifecycle.ARCoreSessionContainer
import cn.svecri.mdanceioar.ui.render.GLError.maybeThrowGLException
import com.google.ar.core.*
import com.google.ar.core.Point.OrientationMode
import com.google.ar.core.exceptions.CameraNotAvailableException
import java.io.File
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.BlockingQueue
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

class MDanceRenderer(private val sessionContainer: ARCoreSessionContainer, private val context: Context) : GLSurfaceView.Renderer {
    companion object {
        const val TAG = "MDanceIOAR"

        private const val Z_NEAR = 0.1f
        private const val Z_FAR = 100f

        @OptIn(ExperimentalUnsignedTypes::class)
        private fun readFile(context: Context, path: String): List<UByte>? {
            val filesDir = context.filesDir
            val file = File(filesDir, path)
            return if (!file.isFile) {
                return null
            } else {
                file.readBytes().asUByteArray().asList()
            }
        }

        @OptIn(ExperimentalUnsignedTypes::class)
        private fun mapFiles(context: Context, dirPath: String, onFile: (String, List<UByte>) -> Unit, prefix: String="") {
            val filesDir = context.filesDir
            val dir = File(filesDir, dirPath)
            if (!dir.exists() || dir.isFile) return
            for (file in dir.listFiles()!!) {
                val nPrefix = if (prefix.isEmpty()) {file.name} else {"$prefix/${file.name}"}
                if (file.isFile) {
                    onFile(nPrefix, file.readBytes().asUByteArray().asList())
                } else {
                    mapFiles(context, "$dirPath/${file.name}", onFile, nPrefix)
                }
            }
        }

        fun calculateDistanceToPlane(planePose: Pose, cameraPose: Pose): Float {
            val normal = FloatArray(3)
            val cameraX = cameraPose.tx()
            val cameraY = cameraPose.ty()
            val cameraZ = cameraPose.tz()
            planePose.getTransformedAxis(1, 1.0f, normal, 0)
            return (cameraX - planePose.tx()) * normal[0] + (cameraY - planePose.ty()) * normal[1] + (cameraZ - planePose.tz()) * normal[2]
        }
    }
    private var viewportWidth = 1080
    private var viewportHeight = 2400
    private lateinit var backgroundRenderer: BackgroundRenderer
    private lateinit var proxy: AndroidProxy

    private var modelAnchor: Anchor? = null
    private val modelInitPose = Pose(floatArrayOf(0f, -1.5f, -2f), floatArrayOf(0f, 0f, 0f, 1f))
    private val modelMatrix = floatArrayOf(1f, 0f, 0f, 0f, /**/0f, 1f, 0f, 0f, /**/0f, 0f, 1f, 0f, /**/0f, 0f, 0f, 1f)
    private val viewMatrix = FloatArray(16)
    private val projectionMatrix = FloatArray(16)

    private val scaleMatrix = floatArrayOf(.1f, 0f, 0f, 0f, /**/0f, .1f, 0f, 0f, /**/0f, 0f, .1f, 0f, /**/0f, 0f, 0f, 1f)
    private val reverseYMatrix = floatArrayOf(1f, 0f, 0f, 0f, /**/0f, -1f, 0f, 0f, /**/0f, 0f, 1f, 0f, /**/0f, 0f, 0f, 1f)
    private val rotationYMatrix = floatArrayOf(-1f, 0f, 0f, 0f, /**/0f, 1f, 0f, 0f, /**/0f, 0f, -1f, 0f, /**/0f, 0f, 0f, 1f)

    private var hasSetTextureNames = false

    private val singleTapQueue: BlockingQueue<MotionEvent> = ArrayBlockingQueue(8)
    private val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onSingleTapUp(e: MotionEvent): Boolean {
            singleTapQueue.offer(e)
            return true
        }

        override fun onDown(e: MotionEvent): Boolean {
            return true
        }
    })

    private val session
        get() = sessionContainer.session

    override fun onSurfaceCreated(gl: GL10, config: EGLConfig?) {
        GLES30.glEnable(GLES30.GL_BLEND)
        maybeThrowGLException("Failed to enable blending", "glEnable")
        backgroundRenderer = BackgroundRenderer(context.assets)
        backgroundRenderer.onSurfaceCreated(gl, config)

        proxy = AndroidProxy(viewportWidth.toUInt(), viewportHeight.toUInt())
        Log.i(TAG, "onSurfaceCreated: File Dir ${context.filesDir}")
        val model = readFile(context, "model/??????/??????.pmx")
        if (model != null) {
            proxy.loadModel(model)
        } else {
            Log.e(TAG, "onSurfaceCreated: Fail to load model")
            return
        }
        mapFiles(context, "model/??????", { name, bytes ->
            proxy.loadTexture(name, bytes, false)
        })
        proxy.updateBindTexture()
        val motion = readFile(context, "motion/????????????_????????????.vmd")
        if (motion != null) {
            proxy.loadModelMotion(motion)
        } else {
            Log.e(TAG, "onSurfaceCreated: Fail to load motion")
            return
        }
        proxy.play()
    }

    override fun onSurfaceChanged(gl: GL10, w: Int, h: Int) {
        viewportWidth = w
        viewportHeight = h
        backgroundRenderer.onSurfaceChanged(gl, w, h)
        val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val display = windowManager.defaultDisplay
//        val display = context.display
        val displayRotation = display.rotation
        session?.setDisplayGeometry(displayRotation, viewportWidth, viewportHeight)
        Log.d(TAG, "onSurfaceChanged: $w, $h")
    }

    override fun onDrawFrame(gl: GL10) {
        clear(0f, 0f, 0f, 1f)

        val session = session ?: return
        if (!hasSetTextureNames) {
            session.setCameraTextureNames(intArrayOf(backgroundRenderer.cameraColorTextureId()))
            hasSetTextureNames = true
        }

        val frame =
            try {
                session.update()
            } catch (e: CameraNotAvailableException) {
                Log.e(TAG, "Camera not available during onDrawFrame", e)
                return
            }
        val camera = frame.camera
        Log.i(TAG, "Camera Texture Name: ${frame.cameraTextureName}")

        backgroundRenderer.updateDisplayGeometry(frame)

        handleTap(frame, camera)

        if (frame.timestamp != 0L) {
            backgroundRenderer.onDrawFrame(gl)
        }

        val tmpMatrix = FloatArray(16)
        camera.getProjectionMatrix(tmpMatrix, 0, Z_NEAR, Z_FAR)
        Matrix.multiplyMM(projectionMatrix, 0, reverseYMatrix, 0, tmpMatrix, 0)
        camera.getViewMatrix(viewMatrix, 0)
        tryInitModelAnchor(session, camera)
        modelAnchor?.pose?.let {
            it.toMatrix(tmpMatrix, 0)
//            Log.i(TAG, "Anchor Pos (${it.tx()}, ${it.ty()}, ${it.tz()})")
            Matrix.multiplyMM(tmpMatrix, 0, tmpMatrix, 0, scaleMatrix, 0)
            Matrix.multiplyMM(modelMatrix, 0, tmpMatrix, 0, rotationYMatrix, 0)
        }

        proxy.redrawFrom(modelMatrix.toList(), viewMatrix.toList(), projectionMatrix.toList())
    }

    private fun clear(r: Float, g: Float, b: Float, a: Float) {
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        maybeThrowGLException("Failed to bind framebuffer", "glBindFramebuffer")
        GLES30.glViewport(0, 0, viewportWidth, viewportHeight)
        maybeThrowGLException("Failed to set viewport dimensions", "glViewport")
        GLES30.glClearColor(r, g, b, a)
        maybeThrowGLException("Failed to set clear color", "glClearColor")
        GLES30.glDepthMask(true)
        maybeThrowGLException("Failed to set depth write mask", "glDepthMask")
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT or GLES30.GL_DEPTH_BUFFER_BIT)
        maybeThrowGLException("Failed to clear framebuffer", "glClear")
    }

    fun onSurfaceTouchEvent(motionEvent: MotionEvent) = gestureDetector.onTouchEvent(motionEvent)

    private fun handleTap(frame: Frame, camera: Camera) {
        val tap = singleTapQueue.poll() ?: return
        if (camera.trackingState != TrackingState.TRACKING) return

        Log.d(TAG, "Tap ${tap.x} ${tap.y}")

        val hitResult = frame.hitTest(tap)
        val firstHitResult = hitResult.firstOrNull { hit ->
            when(val trackable = hit.trackable) {
                is Plane -> trackable.isPoseInPolygon(hit.hitPose) && calculateDistanceToPlane(hit.hitPose, camera.pose) > 0
                is Point -> trackable.orientationMode == OrientationMode.ESTIMATED_SURFACE_NORMAL
                else -> false
            }
        }

        if (firstHitResult != null) {
            modelAnchor?.detach()
            modelAnchor = firstHitResult.createAnchor()
        }
    }

    private fun tryInitModelAnchor(session: Session, camera: Camera) {
        if (camera.trackingState != TrackingState.TRACKING) return
        if (modelAnchor == null) {
            modelAnchor = session.createAnchor(modelInitPose)
        }
    }
}