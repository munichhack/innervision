package com.blindvision.arpose.nav

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import com.blindvision.arpose.R
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Renders a floor plan with a red "you are here" dot, an optional planned path,
 * and a floor label. Coordinates come in as [NavLocation]s (planar metres on a
 * floor); [PlanCalibration] maps them to bitmap pixels and this view applies the
 * bitmap->view fit on top so the overlay always stays aligned with the image.
 *
 * The planned path and current location are floor-aware: only the segment of the
 * path on the [currentFloor] is drawn solid, and the dot dims when the user is
 * not on the floor currently shown.
 */
class FloorPlanView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var bitmap: Bitmap? = null
    private var calibration: PlanCalibration? = null
    private val floorModel = FloorModel()

    private var userLocation: NavLocation? = null
    private var userHeadingRad: Float = 0f
    private var path: List<NavLocation> = emptyList()

    /** Floor currently shown on screen (defaults to the user's floor). */
    private var displayedFloor: Int = floorModel.groundFloorIndex
    private var userFloorKnown = false

    // Bitmap -> view fit transform (contain, centred). Recomputed on size change.
    private val fitMatrix = Matrix()
    private val mappedPoint = FloatArray(2)

    private val bgPaint = Paint().apply { color = Color.parseColor("#EEF1F4") }
    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val pathPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#2962FF")
        style = Paint.Style.STROKE
        strokeWidth = 8f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val pathDotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#2962FF")
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
        // Default to the bundled floor plan so the view is useful out of the box.
        setFloorPlan(
            BitmapFactory.decodeResource(resources, R.drawable.floor_plan)
        )
    }

    /** Replace the floor-plan image and reset the calibration to a centred default. */
    fun setFloorPlan(bmp: Bitmap?, calibration: PlanCalibration? = null) {
        bitmap = bmp
        this.calibration = calibration ?: bmp?.let {
            PlanCalibration.centered(it.width, it.height)
        }
        recomputeFit()
        invalidate()
    }

    /** Override the metres->pixels calibration (e.g. once a surveyed map exists). */
    fun setCalibration(calibration: PlanCalibration) {
        this.calibration = calibration
        invalidate()
    }

    /** The planned route from A* / Dijkstra. May span multiple floors. */
    fun setPath(points: List<NavLocation>) {
        path = points
        invalidate()
    }

    /** Update the user's current location and facing direction (radians, plan frame). */
    fun setUserLocation(location: NavLocation, headingRad: Float = userHeadingRad) {
        userLocation = location
        userHeadingRad = headingRad
        val floor = floorModel.floorOf(location)
        // Follow the user's floor automatically.
        displayedFloor = floor
        userFloorKnown = true
        invalidate()
    }

    /** Manually choose which floor is shown (e.g. a floor switcher in the UI). */
    fun showFloor(floor: Int) {
        displayedFloor = floor
        invalidate()
    }

    fun currentFloor(): Int = displayedFloor

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        recomputeFit()
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
    }

    /** Map a location to on-screen pixels via calibration + fit transform. */
    private fun project(location: NavLocation): FloatArray {
        val cal = calibration!!
        val src = cal.toPixels(location)
        mappedPoint[0] = src[0]
        mappedPoint[1] = src[1]
        fitMatrix.mapPoints(mappedPoint)
        return mappedPoint
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bgPaint)

        val bmp = bitmap
        if (bmp != null) canvas.drawBitmap(bmp, fitMatrix, bitmapPaint)
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
                // Break the polyline where the route leaves this floor.
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
        canvas.drawPath(scratchPath, pathPaint)

        // Endpoints (start / destination) on this floor.
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
        val p = project(loc)
        val cx = p[0]
        val cy = p[1]

        val alpha = if (onThisFloor) 255 else 70
        dotFillPaint.alpha = alpha
        dotStrokePaint.alpha = alpha
        headingPaint.alpha = alpha

        if (onThisFloor) {
            canvas.drawCircle(cx, cy, 34f, dotHaloPaint)
            // Heading arrow: 0 rad points up (north on the plan).
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
}
