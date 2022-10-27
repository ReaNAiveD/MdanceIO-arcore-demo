package cn.svecri.mdanceioar.ui.render

import android.opengl.GLES30
import android.opengl.GLException
import android.opengl.GLU

object GLError {

    /** Throws a [GLException] if a GL error occurred.  */
    fun maybeThrowGLException(reason: String, api: String) {
        val errorCodes: List<Int> = getGlErrors()
        if (errorCodes.isNotEmpty()) {
            throw GLException(
                errorCodes[0],
                formatErrorMessage(
                    reason,
                    api,
                    errorCodes
                )
            )
        }
    }

    private fun formatErrorMessage(reason: String, api: String, errorCodes: List<Int>): String {
        val builder = StringBuilder(String.format("%s: %s: ", reason, api))
        val iterator = errorCodes.iterator()
        while (iterator.hasNext()) {
            val errorCode = iterator.next()
            builder.append(String.format("%s (%d)", GLU.gluErrorString(errorCode), errorCode))
            if (iterator.hasNext()) {
                builder.append(", ")
            }
        }
        return builder.toString()
    }

    private fun getGlErrors(): List<Int> {
        val errorCodes: MutableList<Int> = ArrayList()
        while (true) {
            val errorCode = GLES30.glGetError()
            if (errorCode == GLES30.GL_NO_ERROR) {
                break
            }
            errorCodes.add(errorCode)
        }
        return errorCodes
    }
}