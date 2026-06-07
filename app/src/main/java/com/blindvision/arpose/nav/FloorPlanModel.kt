package com.blindvision.arpose.nav

import kotlin.math.roundToInt

/**
 * A location expressed in the navigation/floor-plan coordinate frame that the
 * (upcoming) A* / Dijkstra planner will produce: planar metres on a given floor.
 *
 *  - [x], [y] are metres in the plane of the floor plan.
 *  - [z] is the vertical coordinate in metres. A change in [z] between two
 *    consecutive locations means the user is moving up or down (stairs / lift),
 *    i.e. potentially changing floor. The floor index is derived from [z] via
 *    [FloorModel.floorHeightMeters].
 *
 * Note on the live VIO source: ARCore's world frame is Y-up, so the horizontal
 * plane is (tx, tz) and the vertical axis is ty. [fromArCore] adapts that into
 * this z-up planner convention so the same view can render either source.
 */
data class NavLocation(
    val x: Float,
    val y: Float,
    val z: Float
) {
    companion object {
        /**
         * Adapt an ARCore Y-up world position (metres) into the planner's z-up
         * floor-plan convention: planar (x, y) <- (tx, -tz), height z <- ty.
         *
         * The -tz is important: ARCore's "forward" (camera view direction) is
         * world -Z, and the floor plan is viewed top-down with forward pointing
         * up. With y = +tz the trajectory came out mirrored (a real right turn
         * rendered as a left turn); y = -tz makes screen-up = forward so turns
         * match reality. (+X stays screen-right, i.e. East.)
         */
        fun fromArCore(tx: Float, ty: Float, tz: Float): NavLocation =
            NavLocation(x = tx, y = -tz, z = ty)
    }
}

/**
 * Describes how the multi-floor building is laid out vertically. The current
 * floor index is computed from a location's height ([NavLocation.z]).
 */
data class FloorModel(
    val floorHeightMeters: Float = 3.0f,
    val groundFloorIndex: Int = 0
) {
    fun floorOf(location: NavLocation): Int =
        groundFloorIndex + (location.z / floorHeightMeters).roundToInt()
}

/**
 * Maps planar metres (a [NavLocation]'s x/y) onto pixel coordinates of the
 * floor-plan *source bitmap*. This is the single calibration knob that ties the
 * planner's metric frame to a particular floor-plan image.
 *
 *   px = originPx + x * pixelsPerMeter
 *   py = originPy - y * pixelsPerMeter   (world y is up; image y grows downward)
 *
 * [FloorPlanView] then applies the bitmap->view fit transform on top, so points
 * always stay aligned with the rendered image regardless of screen size.
 */
data class PlanCalibration(
    val originPx: Float,
    val originPy: Float,
    val pixelsPerMeter: Float,
    val flipY: Boolean = true
) {
    /** Project a location to source-bitmap pixel coordinates. */
    fun toPixels(location: NavLocation): FloatArray {
        val px = originPx + location.x * pixelsPerMeter
        val py = if (flipY) originPy - location.y * pixelsPerMeter
                 else originPy + location.y * pixelsPerMeter
        return floatArrayOf(px, py)
    }

    companion object {
        /**
         * A reasonable default for a [bitmapWidth] x [bitmapHeight] plan: the
         * world origin sits at the image centre and one metre spans
         * [pixelsPerMeter] pixels. Good enough to make the live/simulated pose
         * visibly move across the plan until a surveyed calibration exists.
         */
        fun centered(
            bitmapWidth: Int,
            bitmapHeight: Int,
            pixelsPerMeter: Float = 120f
        ): PlanCalibration = PlanCalibration(
            originPx = bitmapWidth / 2f,
            originPy = bitmapHeight / 2f,
            pixelsPerMeter = pixelsPerMeter
        )
    }
}
