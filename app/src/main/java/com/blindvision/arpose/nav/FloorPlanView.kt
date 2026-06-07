package com.blindvision.arpose.nav

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import kotlin.math.min
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * Renders a floor plan with a red "you are here" dot, an optional planned path,
 * and a floor label. Supports pinch-zoom and drag-pan like mainstream map apps.
 *
 * Coordinates come in as [NavLocation]s (planar metres on a floor); [PlanCalibration]
 * maps them to bitmap pixels and this view applies fit + user transform on top.
 */
class FloorPlanView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    enum class MapViewMode { VIEW_25D, VIEW_3D }

    private var bitmap25d: Bitmap? = null
    private var bitmap3d: Bitmap? = null
    private var viewMode = MapViewMode.VIEW_25D
    private var bitmap: Bitmap? = null
    private var calibration: PlanCalibration? = null
    private val floorModel = FloorModel()

    private var userLocation: NavLocation? = null
    private var userHeadingRad: Float = 0f
    private var hasHeading = false
    private var headingRefLocation: NavLocation? = null
    private var path: List<NavLocation> = emptyList()

    private var displayedFloor: Int = floorModel.groundFloorIndex
    private var userFloorKnown = false

    /** Contain-fit: entire map visible at [MIN_USER_SCALE]. */
    private val fitMatrix = Matrix()
    /** Pinch / pan applied on top of the fit transform. */
    private val userMatrix = Matrix()
    private val displayMatrix = Matrix()
    private val mappedPoint = FloatArray(2)
    private var userScale = 1f
    /** When true the map pans under a fixed centre dot (navigation mode). */
    private var followUser = true

    private val scaleDetector = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val proposed = userScale * detector.scaleFactor
                val clamped = proposed.coerceIn(MIN_USER_SCALE, MAX_USER_SCALE)
                val factor = clamped / userScale
                if (factor != 1f) {
                    followUser = false
                    userScale = clamped
                    userMatrix.postScale(factor, factor, detector.focusX, detector.focusY)
                    invalidate()
                }
                return true
            }
        }
    )

    private val gestureDetector = GestureDetector(
        context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onScroll(
                e1: MotionEvent?,
                e2: MotionEvent,
                distanceX: Float,
                distanceY: Float
            ): Boolean {
                followUser = false
                userMatrix.postTranslate(-distanceX, -distanceY)
                invalidate()
                return true
            }
        }
    )

    private var panAnimator: ValueAnimator? = null

    private val bgPaint = Paint().apply { color = Color.parseColor("#14181E") }
    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val pathGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#402563EB")
        style = Paint.Style.STROKE
        strokeWidth = 14f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val pathPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#2563EB")
        style = Paint.Style.STROKE
        strokeWidth = 6f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val pathDotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#2563EB")
        style = Paint.Style.FILL
    }
    private val dotFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#E53935")
        style = Paint.Style.FILL
    }
    private val dotHaloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#33E53935")
        style = Paint.Style.FILL
    }
    private val dotStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 5f
    }
    private val headingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#E53935")
        style = Paint.Style.FILL
    }
    private val labelBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#CC101418")
    }
    private val labelTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 38f
        isFakeBoldText = true
    }
    private val scratchPath = Path()

    init {
        isClickable = true
    }

    fun setFloorPlan(bmp: Bitmap?, calibration: PlanCalibration? = null) {
        bitmap25d = bmp
        bitmap3d = null
        applyActiveBitmap()
        this.calibration = calibration ?: bmp?.let {
            PlanCalibration.centered(it.width, it.height)
        }
        resetUserTransform()
        followUser = true
        recomputeFit()
        centerMapOnUser()
        invalidate()
    }

    /** Supply both rendered styles; they must share the same pixel dimensions. */
    fun setMapBitmaps(
        bmp25d: Bitmap,
        bmp3d: Bitmap,
        calibration: PlanCalibration,
    ) {
        this.bitmap25d = bmp25d
        this.bitmap3d = bmp3d
        this.calibration = calibration
        applyActiveBitmap()
        resetUserTransform()
        followUser = true
        recomputeFit()
        centerMapOnUser()
        invalidate()
    }

    fun toggleViewMode(): MapViewMode {
        if (bitmap3d == null) return viewMode
        viewMode = if (viewMode == MapViewMode.VIEW_25D) MapViewMode.VIEW_3D
        else MapViewMode.VIEW_25D
        applyActiveBitmap()
        recomputeFit()
        if (followUser) centerMapOnUser() else invalidate()
        return viewMode
    }

    fun currentViewMode(): MapViewMode = viewMode

    private fun applyActiveBitmap() {
        bitmap = when (viewMode) {
            MapViewMode.VIEW_25D -> bitmap25d
            MapViewMode.VIEW_3D -> bitmap3d ?: bitmap25d
        }
    }

    fun setCalibration(calibration: PlanCalibration) {
        this.calibration = calibration
        invalidate()
    }

    fun applyMaskCalibration(map: MaskNavMap) {
        val bmp = bitmap ?: return
        calibration = map.calibrationFor(bmp.width, bmp.height)
        invalidate()
    }

    fun setPath(points: List<NavLocation>) {
        path = points
        invalidate()
    }

    fun setUserLocation(location: NavLocation) {
        val ref = headingRefLocation
        if (ref == null) {
            headingRefLocation = location
        } else {
            val dx = location.x - ref.x
            val dy = location.y - ref.y
            if (dx * dx + dy * dy >= MOVE_EPS_SQ_METERS) {
                userHeadingRad = atan2(dx, dy)
                hasHeading = true
                headingRefLocation = location
            }
        }
        userLocation = location
        displayedFloor = floorModel.floorOf(location)
        userFloorKnown = true
        if (followUser) centerMapOnUser()
        invalidate()
    }

    fun showFloor(floor: Int) {
        displayedFloor = floor
        invalidate()
    }

    fun currentFloor(): Int = displayedFloor

    /** Re-enable follow mode and pan the map so the user sits at the centre. */
    fun recenterOnUser(animated: Boolean = true) {
        followUser = true
        if (width == 0 || height == 0 || userLocation == null || calibration == null) return

        panAnimator?.cancel()
        if (!animated) {
            centerMapOnUser()
            invalidate()
            return
        }

        val src = calibration!!.toPixels(userLocation!!)
        val pt = floatArrayOf(src[0], src[1])
        updateDisplayMatrix()
        displayMatrix.mapPoints(pt)
        val dx = width / 2f - pt[0]
        val dy = height / 2f - pt[1]
        if (dx == 0f && dy == 0f) return

        var lastT = 0f
        panAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = RECENTER_ANIM_MS
            addUpdateListener { anim ->
                val t = anim.animatedValue as Float
                userMatrix.postTranslate(dx * (t - lastT), dy * (t - lastT))
                lastT = t
                invalidate()
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    centerMapOnUser()
                }
            })
            start()
        }
    }

    /** Rebuild pan/zoom so the user's position maps to the view centre. */
    private fun centerMapOnUser() {
        val loc = userLocation ?: return
        val cal = calibration ?: return
        if (width == 0 || height == 0) return

        val src = cal.toPixels(loc)
        val pt = floatArrayOf(src[0], src[1])
        userMatrix.reset()
        userMatrix.postScale(userScale, userScale, width / 2f, height / 2f)
        val m = Matrix()
        m.set(fitMatrix)
        m.postConcat(userMatrix)
        m.mapPoints(pt)

        userMatrix.postTranslate(width / 2f - pt[0], height / 2f - pt[1])
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        panAnimator?.cancel()
        var handled = scaleDetector.onTouchEvent(event)
        handled = gestureDetector.onTouchEvent(event) || handled
        return handled || super.onTouchEvent(event)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        recomputeFit()
    }

    private fun resetUserTransform() {
        panAnimator?.cancel()
        userMatrix.reset()
        userScale = 1f
    }

    private fun recomputeFit() {
        val bmp = bitmap ?: return
        if (width == 0 || height == 0) return
        val scale = min(width.toFloat() / bmp.width, height.toFloat() / bmp.height)
        val dx = (width - bmp.width * scale) / 2f
        val dy = (height - bmp.height * scale) / 2f
        fitMatrix.reset()
        fitMatrix.setScale(scale, scale)
        fitMatrix.postTranslate(dx, dy)
        updateDisplayMatrix()
    }

    private fun updateDisplayMatrix() {
        displayMatrix.set(fitMatrix)
        displayMatrix.postConcat(userMatrix)
    }

    private fun project(location: NavLocation): FloatArray {
        val cal = calibration!!
        val src = cal.toPixels(location)
        mappedPoint[0] = src[0]
        mappedPoint[1] = src[1]
        updateDisplayMatrix()
        displayMatrix.mapPoints(mappedPoint)
        return mappedPoint
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bgPaint)

        val bmp = bitmap
        if (bmp != null) {
            updateDisplayMatrix()
            canvas.drawBitmap(bmp, displayMatrix, bitmapPaint)
        }
        if (calibration == null) return

        drawPath(canvas)
        drawUser(canvas)
        drawFloorLabel(canvas)
    }

    private fun drawPath(canvas: Canvas) {
        if (path.size < 2) return
        scratchPath.reset()
        var started = false
        for (loc in path) {
            if (floorModel.floorOf(loc) != displayedFloor) {
                started = false
                continue
            }
            val p = project(loc)
            if (!started) {
                scratchPath.moveTo(p[0], p[1])
                started = true
            } else {
                scratchPath.lineTo(p[0], p[1])
            }
        }
        canvas.drawPath(scratchPath, pathGlowPaint)
        canvas.drawPath(scratchPath, pathPaint)

        path.firstOrNull { floorModel.floorOf(it) == displayedFloor }?.let {
            val p = project(it)
            canvas.drawCircle(p[0], p[1], 12f, pathDotPaint)
        }
        path.lastOrNull { floorModel.floorOf(it) == displayedFloor }?.let {
            val p = project(it)
            canvas.drawCircle(p[0], p[1], 12f, pathDotPaint)
        }
    }

    private fun drawUser(canvas: Canvas) {
        val loc = userLocation ?: return
        val onThisFloor = !userFloorKnown || floorModel.floorOf(loc) == displayedFloor
        val cx: Float
        val cy: Float
        if (followUser && width > 0 && height > 0) {
            cx = width / 2f
            cy = height / 2f
        } else {
            val p = project(loc)
            cx = p[0]
            cy = p[1]
        }

        val alpha = if (onThisFloor) 255 else 70
        dotFillPaint.alpha = alpha
        dotStrokePaint.alpha = alpha
        headingPaint.alpha = alpha

        if (onThisFloor && hasHeading) {
            canvas.drawCircle(cx, cy, 34f, dotHaloPaint)
            val len = 46f
            val tipX = cx + len * sin(userHeadingRad)
            val tipY = cy - len * cos(userHeadingRad)
            val baseX = cx - 16f * sin(userHeadingRad)
            val baseY = cy + 16f * cos(userHeadingRad)
            val leftX = baseX + 14f * cos(userHeadingRad)
            val leftY = baseY + 14f * sin(userHeadingRad)
            val rightX = baseX - 14f * cos(userHeadingRad)
            val rightY = baseY - 14f * sin(userHeadingRad)
            scratchPath.reset()
            scratchPath.moveTo(tipX, tipY)
            scratchPath.lineTo(leftX, leftY)
            scratchPath.lineTo(rightX, rightY)
            scratchPath.close()
            canvas.drawPath(scratchPath, headingPaint)
        }

        canvas.drawCircle(cx, cy, 18f, dotFillPaint)
        canvas.drawCircle(cx, cy, 18f, dotStrokePaint)
    }

    private fun drawFloorLabel(canvas: Canvas) {
        val text = "Floor ${floorLabel(displayedFloor)}"
        val pad = 18f
        val tw = labelTextPaint.measureText(text)
        val rect = RectF(24f, 24f, 24f + tw + pad * 2, 24f + 56f + pad)
        canvas.drawRoundRect(rect, 16f, 16f, labelBgPaint)
        canvas.drawText(text, rect.left + pad, rect.top + 50f, labelTextPaint)
    }

    private fun floorLabel(floor: Int): String = when {
        floor == 0 -> "G"
        floor > 0 -> "$floor"
        else -> "B${-floor}"
    }

    private companion object {
        const val MOVE_EPS_SQ_METERS = 0.0025f
        const val MIN_USER_SCALE = 1f
        const val MAX_USER_SCALE = 6f
        const val RECENTER_ANIM_MS = 280L
    }
}
