package com.blindvision.arpose.pose

import android.app.Activity
import android.graphics.PixelFormat
import android.opengl.GLES11Ext
import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.util.Log
import com.google.ar.core.Config
import com.google.ar.core.Session
import com.google.ar.core.TrackingState
import com.google.ar.core.exceptions.CameraNotAvailableException
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * Real ARCore 6-DoF tracking. Produces the device's world pose on a certified
 * physical device (e.g. a Redmi Note 11 with Google Play Services for AR).
 *
 * ARCore needs a GL surface and a camera texture to advance frames, so this
 * provider doubles as the [GLSurfaceView.Renderer]. We do not draw the camera
 * feed — we only need the texture bound so `session.update()` succeeds — and on
 * every frame we read `frame.camera.pose` and forward it downstream.
 *
 * The caller is responsible for ensuring, before [start], that:
 *   - ARCore is installed (ArCoreApk.requestInstall), and
 *   - the CAMERA permission has been granted.
 */
class ArCorePoseProvider(
    private val activity: Activity,
    private val glSurfaceView: GLSurfaceView
) : PoseProvider, GLSurfaceView.Renderer {

    override val sourceName: String = "ARCORE"

    private var session: Session? = null
    private var onPose: ((Pose6Dof) -> Unit)? = null
    private var cameraTextureId = -1
    @Volatile private var viewportWidth = 1
    @Volatile private var viewportHeight = 1
    @Volatile private var viewportChanged = false

    override fun start(onPose: (Pose6Dof) -> Unit) {
        this.onPose = onPose

        val s = Session(activity)
        s.configure(
            Config(s).apply {
                // Pure motion tracking is all we need for world position.
                focusMode = Config.FocusMode.AUTO
                updateMode = Config.UpdateMode.LATEST_CAMERA_IMAGE
                depthMode = Config.DepthMode.DISABLED
            }
        )
        s.resume()
        session = s

        // Keep the surface full-size (ARCore needs a real, continuously rendering
        // surface to stay in TRACKING) but transparent, so the floor-plan view
        // behind it remains visible. On some devices a full-screen SurfaceView is
        // composited above the window; a translucent media-overlay surface that we
        // clear to alpha 0 lets the UI show through regardless of z-order.
        glSurfaceView.preserveEGLContextOnPause = true
        glSurfaceView.setEGLContextClientVersion(3)
        glSurfaceView.setEGLConfigChooser(8, 8, 8, 8, 16, 0)
        glSurfaceView.holder.setFormat(PixelFormat.TRANSLUCENT)
        glSurfaceView.setZOrderMediaOverlay(true)
        glSurfaceView.setRenderer(this)
        glSurfaceView.renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
        glSurfaceView.onResume()
    }

    override fun stop() {
        glSurfaceView.onPause()
        session?.pause()
        session?.close()
        session = null
        onPose = null
    }

    // --- GLSurfaceView.Renderer ------------------------------------------------

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        // Allocate the external texture ARCore writes the camera image into.
        val ids = IntArray(1)
        GLES30.glGenTextures(1, ids, 0)
        cameraTextureId = ids[0]
        GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, cameraTextureId)
        GLES30.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR
        )
        GLES30.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR
        )
        session?.setCameraTextureName(cameraTextureId)
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        viewportWidth = width
        viewportHeight = height
        viewportChanged = true
        GLES30.glViewport(0, 0, width, height)
    }

    override fun onDrawFrame(gl: GL10?) {
        val s = session ?: return
        // Clear to fully transparent so the floor plan behind shows through.
        GLES30.glClearColor(0f, 0f, 0f, 0f)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT or GLES30.GL_DEPTH_BUFFER_BIT)

        if (viewportChanged) {
            val display = activity.windowManager.defaultDisplay
            s.setDisplayGeometry(display.rotation, viewportWidth, viewportHeight)
            viewportChanged = false
        }

        try {
            if (cameraTextureId != -1) s.setCameraTextureName(cameraTextureId)
            val frame = s.update()
            val camera = frame.camera
            if (camera.trackingState == TrackingState.TRACKING) {
                val p = camera.pose
                onPose?.invoke(
                    Pose6Dof(
                        tx = p.tx(), ty = p.ty(), tz = p.tz(),
                        qx = p.qx(), qy = p.qy(), qz = p.qz(), qw = p.qw(),
                        timestampNanos = frame.timestamp,
                        source = sourceName
                    )
                )
            }
        } catch (e: CameraNotAvailableException) {
            Log.w(TAG, "Camera not available this frame", e)
        }
    }

    private companion object {
        const val TAG = "ArCorePoseProvider"
    }
}
