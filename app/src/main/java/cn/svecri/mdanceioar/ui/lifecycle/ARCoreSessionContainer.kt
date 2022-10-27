package cn.svecri.mdanceioar.ui.lifecycle

import android.app.Activity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import cn.svecri.mdanceioar.helper.CameraPermissionHelper
import com.google.ar.core.ArCoreApk
import com.google.ar.core.Session
import com.google.ar.core.exceptions.CameraNotAvailableException

class ARCoreSessionContainer(
    private val activity: Activity,
    private val features: Set<Session.Feature>
) :
    LifecycleEventObserver {
    var installRequested = false
    var session: Session? = null
        private set
    var exceptionCallback: ((Exception) -> Unit)? = null
    var beforeSessionResume: ((Session) -> Unit)? = null

    override fun onStateChanged(source: LifecycleOwner, event: Lifecycle.Event) {
        when (event) {
            Lifecycle.Event.ON_CREATE -> {}
            Lifecycle.Event.ON_START -> {}
            Lifecycle.Event.ON_RESUME -> onResume(source)
            Lifecycle.Event.ON_PAUSE -> onPause(source)
            Lifecycle.Event.ON_STOP -> {}
            Lifecycle.Event.ON_DESTROY -> onDestroy(source)
            Lifecycle.Event.ON_ANY -> {}
        }
    }

    private fun tryCreateSession(): Session? {
        if (!CameraPermissionHelper.hasCameraPermission(activity)) {
            CameraPermissionHelper.requestCameraPermission(activity)
            return null
        }
        try {
            when (ArCoreApk.getInstance().requestInstall(activity, !installRequested)) {
                ArCoreApk.InstallStatus.INSTALLED -> {}
                ArCoreApk.InstallStatus.INSTALL_REQUESTED -> {
                    installRequested = true
                    return null
                }
            }
            return Session(activity, features)
        } catch (e: Exception) {
            exceptionCallback?.invoke(e)
            return null
        }
    }

    private fun onResume(owner: LifecycleOwner) {
        val session = this.session ?: tryCreateSession() ?: return
        try {
            beforeSessionResume?.invoke(session)
            session.resume()
            this.session = session
        } catch (e: CameraNotAvailableException) {
            exceptionCallback?.invoke(e)
        }
    }

    private fun onPause(owner: LifecycleOwner) {
        session?.pause()
    }

    private fun onDestroy(owner: LifecycleOwner) {
        session?.close()
        session = null
    }
}