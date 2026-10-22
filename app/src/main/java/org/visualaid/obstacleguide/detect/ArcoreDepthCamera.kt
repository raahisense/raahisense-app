package org.visualaid.obstacleguide.detect

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.Image
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.util.Log
import android.view.Surface
import com.google.ar.core.ArCoreApk
import com.google.ar.core.CameraConfig
import com.google.ar.core.CameraConfigFilter
import com.google.ar.core.Config
import com.google.ar.core.Session
import com.google.ar.core.TrackingState
import com.google.ar.core.exceptions.NotYetAvailableException
import org.visualaid.obstacleguide.logic.DepthField
import org.visualaid.obstacleguide.logic.DepthFields
import java.io.Closeable
import java.nio.ByteOrder

/**
 * Metric depth from ARCore, used only when that service is already installed.
 * Nothing here downloads ARCore. Millimetres are larger when the surface is farther.
 * Call [start], [poll], and [close] from the same thread.
 */
class ArcoreDepthCamera(private val context: Context) : Closeable {
    private var session: Session? = null
    private var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
    private var surface: EGLSurface = EGL14.EGL_NO_SURFACE
    private var textureId = 0
    private var rotationDegrees = 0

    fun start(displayRotation: Int): Boolean {
        if (!installed()) return false
        try {
            val created = Session(context)
            if (!created.isDepthModeSupported(Config.DepthMode.AUTOMATIC)) {
                created.close()
                return false
            }
            chooseSmallCamera(created)
            val config = Config(created).apply {
                depthMode = Config.DepthMode.AUTOMATIC
                updateMode = Config.UpdateMode.LATEST_CAMERA_IMAGE
                focusMode = Config.FocusMode.AUTO
                planeFindingMode = Config.PlaneFindingMode.DISABLED
                lightEstimationMode = Config.LightEstimationMode.DISABLED
            }
            created.configure(config)
            if (!prepareGl()) {
                created.close()
                return false
            }
            created.setCameraTextureName(textureId)
            created.setDisplayGeometry(displayRotation, 640, 480)
            created.resume()
            rotationDegrees = uprightDegrees(created, displayRotation)
            session = created
            return true
        } catch (error: Exception) {
            Log.w(TAG, "ARCore depth did not start", error)
            close()
            return false
        }
    }

    fun poll(): DepthShot? {
        val current = session ?: return null
        val frame = current.update()
        if (frame.camera.trackingState != TrackingState.TRACKING) return null
        var cameraImage: Image? = null
        var depthImage: Image? = null
        return try {
            cameraImage = frame.acquireCameraImage()
            depthImage = try {
                frame.acquireDepthImage16Bits()
            } catch (_: NotYetAvailableException) {
                null
            }
            val bitmap = rotateBitmap(yuvToBitmap(cameraImage), rotationDegrees)
            val depth = depthImage?.let { DepthFields.rotate(depth16ToField(it), rotationDegrees) }
            DepthShot(bitmap, depth)
        } finally {
            cameraImage?.close()
            depthImage?.close()
        }
    }

    override fun close() {
        try {
            session?.pause()
        } catch (_: Exception) {
        }
        try {
            session?.close()
        } catch (_: Exception) {
        }
        session = null
        if (textureId != 0 && display != EGL14.EGL_NO_DISPLAY) {
            GLES20.glDeleteTextures(1, intArrayOf(textureId), 0)
        }
        textureId = 0
        if (display != EGL14.EGL_NO_DISPLAY) {
            EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            if (surface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, surface)
            if (eglContext != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, eglContext)
            EGL14.eglTerminate(display)
        }
        display = EGL14.EGL_NO_DISPLAY
        surface = EGL14.EGL_NO_SURFACE
        eglContext = EGL14.EGL_NO_CONTEXT
    }

    private fun installed(): Boolean {
        var availability = ArCoreApk.getInstance().checkAvailability(context)
        var spins = 0
        while (availability.isTransient && spins < 8) {
            try {
                Thread.sleep(50)
            } catch (_: InterruptedException) {
                return false
            }
            availability = ArCoreApk.getInstance().checkAvailability(context)
            spins += 1
        }
        return availability == ArCoreApk.Availability.SUPPORTED_INSTALLED
    }

    private fun chooseSmallCamera(created: Session) {
        try {
            val filter = CameraConfigFilter(created)
            filter.facingDirection = CameraConfig.FacingDirection.BACK
            val configs = created.getSupportedCameraConfigs(filter)
            val chosen = configs
                .filter { it.imageSize.width >= 480 }
                .minByOrNull { it.imageSize.width.toLong() * it.imageSize.height }
                ?: configs.minByOrNull { it.imageSize.width.toLong() * it.imageSize.height }
            if (chosen != null) created.cameraConfig = chosen
        } catch (error: Exception) {
            Log.w(TAG, "ARCore camera size left at the default", error)
        }
    }

    private fun prepareGl(): Boolean {
        val eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        if (eglDisplay == EGL14.EGL_NO_DISPLAY) return false
        val version = IntArray(2)
        if (!EGL14.eglInitialize(eglDisplay, version, 0, version, 1)) return false
        val attribs = intArrayOf(
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_NONE,
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val count = IntArray(1)
        if (!EGL14.eglChooseConfig(eglDisplay, attribs, 0, configs, 0, 1, count, 0) || configs[0] == null) {
            EGL14.eglTerminate(eglDisplay)
            return false
        }
        val contextAttribs = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE)
        val context = EGL14.eglCreateContext(
            eglDisplay,
            configs[0],
            EGL14.EGL_NO_CONTEXT,
            contextAttribs,
            0,
        )
        val surfaceAttribs = intArrayOf(EGL14.EGL_WIDTH, 16, EGL14.EGL_HEIGHT, 16, EGL14.EGL_NONE)
        val pbuffer = EGL14.eglCreatePbufferSurface(eglDisplay, configs[0], surfaceAttribs, 0)
        if (context == null || context == EGL14.EGL_NO_CONTEXT || pbuffer == null || pbuffer == EGL14.EGL_NO_SURFACE) {
            EGL14.eglTerminate(eglDisplay)
            return false
        }
        if (!EGL14.eglMakeCurrent(eglDisplay, pbuffer, pbuffer, context)) {
            EGL14.eglDestroySurface(eglDisplay, pbuffer)
            EGL14.eglDestroyContext(eglDisplay, context)
            EGL14.eglTerminate(eglDisplay)
            return false
        }
        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        textureId = textures[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        display = eglDisplay
        eglContext = context
        surface = pbuffer
        return textureId != 0
    }

    private fun uprightDegrees(created: Session, displayRotation: Int): Int {
        val device = when (displayRotation) {
            Surface.ROTATION_90 -> 90
            Surface.ROTATION_180 -> 180
            Surface.ROTATION_270 -> 270
            else -> 0
        }
        val sensor = try {
            val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            manager.getCameraCharacteristics(created.cameraConfig.cameraId)
                .get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
        } catch (error: Exception) {
            Log.w(TAG, "Camera orientation unavailable", error)
            90
        }
        return (sensor - device + 360) % 360
    }

    private fun rotateBitmap(bitmap: Bitmap, degrees: Int): Bitmap {
        val turns = ((degrees % 360) + 360) % 360
        if (turns == 0) return bitmap
        val matrix = Matrix().apply { postRotate(turns.toFloat()) }
        val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        if (rotated != bitmap) bitmap.recycle()
        return rotated
    }

    private fun yuvToBitmap(image: Image): Bitmap {
        val width = image.width
        val height = image.height
        val yPlane = image.planes[0]
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]
        val yBuffer = yPlane.buffer
        val uBuffer = uPlane.buffer
        val vBuffer = vPlane.buffer
        val pixels = IntArray(width * height)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val yValue = yBuffer.get(y * yPlane.rowStride + x * yPlane.pixelStride).toInt() and 0xFF
                val uvIndex = (y / 2) * uPlane.rowStride + (x / 2) * uPlane.pixelStride
                val vIndex = (y / 2) * vPlane.rowStride + (x / 2) * vPlane.pixelStride
                val uValue = (uBuffer.get(uvIndex).toInt() and 0xFF) - 128
                val vValue = (vBuffer.get(vIndex).toInt() and 0xFF) - 128
                val red = (yValue + 1.370705f * vValue).toInt().coerceIn(0, 255)
                val green = (yValue - 0.337633f * uValue - 0.698001f * vValue).toInt().coerceIn(0, 255)
                val blue = (yValue + 1.732446f * uValue).toInt().coerceIn(0, 255)
                pixels[y * width + x] = (0xFF shl 24) or (red shl 16) or (green shl 8) or blue
            }
        }
        return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
    }

    private fun depth16ToField(image: Image): DepthField {
        val width = image.width
        val height = image.height
        val plane = image.planes[0]
        val buffer = plane.buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        val raw = FloatArray(width * height)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val offset = y * plane.rowStride + x * plane.pixelStride
                val sample = buffer.getShort(offset).toInt() and 0xFFFF
                val millimetres = sample and 0x1FFF
                val confidence = sample ushr 13
                raw[y * width + x] = if (millimetres == 0 || confidence >= 6) {
                    Float.NaN
                } else {
                    millimetres.toFloat()
                }
            }
        }
        return DepthField(width, height, raw)
    }

    companion object {
        private const val TAG = "ObstacleGuide"
    }
}

class DepthShot(
    val bitmap: Bitmap,
    val depth: DepthField?,
)
