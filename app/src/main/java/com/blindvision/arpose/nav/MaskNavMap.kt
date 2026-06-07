package com.blindvision.arpose.nav

import android.content.Context
import com.blindvision.planning.CellType
import com.blindvision.planning.Floor
import com.blindvision.planning.GridPos
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.max

/**
 * Bridges the JSON occupancy mask (res/raw) to the standalone `com.blindvision.planning`
 * module and to the on-screen [FloorPlanView].
 *
 * The mask is `data[row=y][col=x]` with codes {1: free, 2: elevator/staircase, 3: wall}.
 * The planner ([com.blindvision.planning.VoronoiGridPlanner]) works on a [Floor] indexed
 * `grid[x][y]`, so cells are addressed as `GridPos(x = col, y = row)`. The displayed
 * `floor_plan_with_rooms.png` has the *same* resolution as the mask, so a grid cell maps
 * 1:1 to a source-bitmap pixel.
 *
 * Coordinate convention: VIO `(0,0,0)` is the map origin (its centre for now, later the
 * `0` marker in the mask). [cellToLocation] and [calibrationFor] share the same
 * `metersPerCell` + origin so the planned path and the live dot live in one frame.
 */
class MaskNavMap private constructor(
    val floor: Floor,
    val rows: Int,
    val cols: Int,
    val originCol: Int,
    val originRow: Int,
    val metersPerCell: Float,
) {
    /** The VIO origin `(0,0,0)` as a grid cell. */
    val originPos: GridPos = GridPos(originCol, originRow)

    /**
     * Convert a grid cell to a [NavLocation] (metres) in the same frame as the live dot:
     * +x is East (screen-right), +y is up on the plan (rows grow downward, hence negated).
     */
    fun cellToLocation(cell: GridPos, floorZ: Float = 0f): NavLocation =
        NavLocation(
            x = (cell.x - originCol) * metersPerCell,
            y = (originRow - cell.y) * metersPerCell,
            z = floorZ,
        )

    /** Inverse of [cellToLocation]: metric location -> fractional grid (col, row). */
    fun locationToCell(location: NavLocation): FloatArray =
        floatArrayOf(
            originCol + location.x / metersPerCell,
            originRow - location.y / metersPerCell,
        )

    /**
     * A [PlanCalibration] (metres -> source-bitmap pixels) consistent with [cellToLocation]:
     * with it, cell `(col,row)` projects to bitmap pixel `(col*sx, row*sy)`. Pass the
     * dimensions of the *decoded* bitmap so density scaling cancels out.
     */
    fun calibrationFor(bitmapWidth: Int, bitmapHeight: Int): PlanCalibration {
        val sx = bitmapWidth.toFloat() / cols
        return PlanCalibration(
            originPx = originCol * sx,
            originPy = originRow * (bitmapHeight.toFloat() / rows),
            pixelsPerMeter = sx / metersPerCell,
            flipY = true,
        )
    }

    /** Nearest traversable cell to [cell] (ring search), or [cell] if none within range. */
    fun snapToTraversable(cell: GridPos, maxRadius: Int = 120): GridPos {
        if (floor.traversable(cell.x, cell.y)) return cell
        for (r in 1..maxRadius) {
            for (dx in -r..r) for (dy in -r..r) {
                if (max(abs(dx), abs(dy)) != r) continue // perimeter of the r-ring only
                val nx = cell.x + dx
                val ny = cell.y + dy
                if (floor.traversable(nx, ny)) return GridPos(nx, ny)
            }
        }
        return cell
    }

    companion object {
        // Mask label codes.
        const val FREE = 1
        const val PORTAL = 2
        const val WALL = 3

        /**
         * Placeholder physical scale until the building's real dimensions are known.
         * Only affects how far the live dot travels per metre; the planned path overlay
         * is exact regardless. ~0.035 m/cell => the 1448-cell width spans ~40 m.
         */
        const val DEFAULT_METERS_PER_CELL = 0.035f

        /** Bottom-center door — demo route start and VIO entry point. */
        val DEMO_START = GridPos(340, 206)

        /** Centre of the large room at the top-left. */
        val DEMO_GOAL = GridPos(340, 206)

        fun fromRawResource(
            context: Context,
            resId: Int,
            metersPerCell: Float = DEFAULT_METERS_PER_CELL,
        ): MaskNavMap {
            val text = context.resources.openRawResource(resId)
                .bufferedReader().use { it.readText() }
            val obj = JSONObject(text)
            val rows = obj.getInt("rows")
            val cols = obj.getInt("cols")
            val data = obj.getJSONArray("data")

            // planning.Floor expects grid[x][y]; the mask is data[row=y][col=x].
            val grid = Array(cols) { Array(rows) { CellType.NON_WALKABLE } }
            for (y in 0 until rows) {
                val line = data.getString(y)
                for (x in 0 until cols) {
                    grid[x][y] = when (line[x]) {
                        '1' -> CellType.WALKABLE
                        '2' -> CellType.PORTAL
                        else -> CellType.NON_WALKABLE // '3' = wall
                    }
                }
            }
            val floor = Floor.of(0, grid)

            val origin = obj.optJSONObject("origin")
            val originRow = origin?.optInt("row", rows / 2) ?: (rows / 2)
            val originCol = origin?.optInt("col", cols / 2) ?: (cols / 2)
            return MaskNavMap(floor, rows, cols, originCol, originRow, metersPerCell)
        }
    }
}
