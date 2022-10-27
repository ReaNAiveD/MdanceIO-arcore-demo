package cn.svecri.mdanceioar.ui.view

import android.app.Activity
import android.content.Context
import android.opengl.GLSurfaceView
import android.util.Log
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.LifecycleOwner
import cn.svecri.mdanceioar.R
import cn.svecri.mdanceioar.ui.lifecycle.ARCoreSessionContainer
import cn.svecri.mdanceioar.ui.render.MDanceRenderer
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.PermissionStatus
import com.google.accompanist.permissions.rememberPermissionState
import com.google.ar.core.Config
import com.google.ar.core.exceptions.*
import kotlinx.coroutines.launch

@Composable
fun ArView(
    context: Context = LocalContext.current,
    lifecycleOwner: LifecycleOwner = LocalLifecycleOwner.current,
    snackbarHostState: SnackbarHostState = remember {
        SnackbarHostState()
    },
) {
    CameraPermissionWrapper {
        InnerArView(context, lifecycleOwner, snackbarHostState)
    }
}

@Composable
fun InnerArView(
    context: Context = LocalContext.current,
    lifecycleOwner: LifecycleOwner = LocalLifecycleOwner.current,
    snackbarHostState: SnackbarHostState = remember {
        SnackbarHostState()
    },
) {
    val scope = rememberCoroutineScope()
    val sessionContainer = remember {
        ARCoreSessionContainer(context as Activity, setOf()).apply {
            exceptionCallback = { e ->
                val message =
                    when (e) {
                        is UnavailableUserDeclinedInstallationException ->
                            "Please install Google Play Services for AR"
                        is UnavailableApkTooOldException -> "Please update ARCore"
                        is UnavailableSdkTooOldException -> "Please update this app"
                        is UnavailableDeviceNotCompatibleException -> "This device does not support AR"
                        is CameraNotAvailableException -> "Camera not available. Try restarting the app."
                        else -> "Failed to create AR session: $e"
                    }
                Log.e("ArView", "ARCore threw an exception", e)
                scope.launch {
                    if (snackbarHostState.showSnackbar(
                            message,
                            withDismissAction = true
                        ) == SnackbarResult.Dismissed
                    ) {
                        (context as? Activity)?.finish()
                    }
                }
            }
            beforeSessionResume = { session ->
                session.configure(session.config.apply {
                    lightEstimationMode = Config.LightEstimationMode.ENVIRONMENTAL_HDR
                    depthMode =
                        if (session.isDepthModeSupported(Config.DepthMode.AUTOMATIC)) {
                            Config.DepthMode.AUTOMATIC
                        } else {
                            Config.DepthMode.DISABLED
                        }
                    focusMode = Config.FocusMode.AUTO
                })
            }
        }
    }
    DisposableEffect(lifecycleOwner) {
        lifecycleOwner.lifecycle.addObserver(sessionContainer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(sessionContainer) }
    }
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx ->
            GLSurfaceView(ctx).apply {
                val mDanceRenderer = MDanceRenderer(sessionContainer, context)
                setOnTouchListener { _, event -> mDanceRenderer.onSurfaceTouchEvent(event) }
                ViewCompat.setOnApplyWindowInsetsListener(this) { view, windowInsets ->
//                    val systemBarsInsets =
//                        windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
//                    Log.d("ArView", "ArView: OnApplyWindowInsets $layoutParams")
//                    view.updateLayoutParams<ViewGroup.MarginLayoutParams> {
//                        leftMargin = systemBarsInsets.left
//                        bottomMargin = systemBarsInsets.bottom
//                        rightMargin = systemBarsInsets.right
//                    }
                    val systemGesturesInsets =
                        windowInsets.getInsets(WindowInsetsCompat.Type.systemGestures())
                    view.updatePadding(
                        systemGesturesInsets.left,
                        systemGesturesInsets.top,
                        systemGesturesInsets.right,
                        systemGesturesInsets.bottom
                    )
                    WindowInsetsCompat.CONSUMED
                }
                preserveEGLContextOnPause = true
                setEGLContextClientVersion(3)
                setEGLConfigChooser(8, 8, 8, 8, 16, 0)
                setRenderer(mDanceRenderer)
                renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
                setWillNotDraw(false)
            }
        }, update = { view ->
            Unit
        }
    )
}

@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun CameraPermissionWrapper(content: @Composable () -> Unit) {
    val cameraPermissionState =
        rememberPermissionState(permission = android.Manifest.permission.CAMERA)
    val context = LocalContext.current

    when (cameraPermissionState.status) {
        PermissionStatus.Granted -> {
            content()
        }
        is PermissionStatus.Denied -> {
            val textResId =
                if ((cameraPermissionState.status as PermissionStatus.Denied).shouldShowRationale) R.string.request_permission_rationale else R.string.request_permission_denied
            val textToShow = context.getString(textResId)
            Column(
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text(modifier = Modifier.padding(20.dp), text = textToShow)
                Button(onClick = { cameraPermissionState.launchPermissionRequest() }) {
                    Text(text = context.getString(R.string.request_permission))
                }
            }
        }
    }
}
