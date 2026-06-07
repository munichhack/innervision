package com.blindvision.arpose.gl

import android.content.Context
import android.graphics.Bitmap
import android.opengl.GLSurfaceView
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import com.blindvision.arpose.nav.WallMesher

/**
 * [GLSurfaceView] hosting the [Map3DRenderer]. One finger orbits/tilts the camera,
 * two fingers pinch to zoom — like a typical 3D map.
 */
class Map3DView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : GLSurfaceView(context, attrs) {

    private val renderer = Map3DRenderer()
    private val scaleDetector = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                renderer.zoomBy(detector.scaleFactor)
                return true
            }
        }
    )

    /** When true, all touch interaction is blocked (until destination is selected). */
    var interactionLocked = false

    private var lastX = 0f
    private var lastY = 0f
    private var pointerId = -1

    init {
        setEGLContextClientVersion(2)
        setRenderer(renderer)
        renderMode = RENDERMODE_CONTINUOUSLY
    }

    fun setData(
        floor: Bitmap,
        rects: List<WallMesher.Rect>,
        route: List<com.blindvision.planning.GridPos>,
        cols: Int,
        rows: Int,
    ) {
        renderer.setData(floor, rects, route, cols, rows)
    }

    fun setUser(col: Float, row: Float, headingRad: Float) {
        renderer.setUser(col, row, headingRad)
    }

    fun setDestination(col: Float, row: Float) {
        renderer.setDestination(col, row)
    }

    fun clearDestination() {
        renderer.clearDestination()
    }

    fun setPortals(stairs: List<Pair<Float, Float>>, elevators: List<Pair<Float, Float>>) {
        renderer.setPortals(stairs, elevators)
    }

    fun recenterOnUser() {
        renderer.recenterOnUser()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (interactionLocked) return false
        scaleDetector.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastX = event.x
                lastY = event.y
                pointerId = event.getPointerId(0)
            }
            MotionEvent.ACTION_MOVE -> {
                if (!scaleDetector.isInProgress && event.pointerCount == 1) {
                    val dx = event.x - lastX
                    val dy = event.y - lastY
                    // Vertical drag: finger down tilts toward top-down; horizontal drag orbits.
                    renderer.orbitBy(-dx * 0.003f, dy * 0.003f)
                    lastX = event.x
                    lastY = event.y
                }
            }
            MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_UP -> {
                // Reset anchor to the remaining finger to avoid a jump.
                val idx = if (event.actionMasked == MotionEvent.ACTION_POINTER_UP) {
                    if (event.actionIndex == 0) 1 else 0
                } else 0
                if (idx < event.pointerCount) {
                    lastX = event.getX(idx)
                    lastY = event.getY(idx)
                }
            }
        }
        return true
    }
}
