package com.blindvision.planning

/**
 * Multi-floor grid path planning — completely standalone module.
 *
 * Pure Kotlin / JVM (no Android dependencies), so it is unit-testable off-device
 * and could be extracted into its own Gradle module later. It does not reference
 * any other package in this project.
 *
 * Occupancy classification of a single grid cell.
 */
enum class CellType {
    WALKABLE,
    NON_WALKABLE,

    /** Elevator / stairs: traversable, and links to the same footprint on other floors. */
    PORTAL;

    /** Whether an agent may stand on / move through this cell. */
    val traversable: Boolean get() = this != NON_WALKABLE
}

/** Integer (x, y) position within a single floor. */
data class GridPos(val x: Int, val y: Int)

/** Position within the whole building (floor + x + y). */
data class BuildingPos(val floor: Int, val x: Int, val y: Int) {
    val gridPos: GridPos get() = GridPos(x, y)
}

/**
 * One floor of the building: a width x height grid of [CellType].
 *
 * Internally stored as a flat array indexed `x * height + y` for cache-friendly
 * A* traversal.
 */
class Floor private constructor(
    val index: Int,
    val width: Int,
    val height: Int,
    private val types: Array<CellType>,
) {
    private fun idx(x: Int, y: Int) = x * height + y

    fun inBounds(x: Int, y: Int) = x in 0 until width && y in 0 until height

    fun typeAt(x: Int, y: Int): CellType = types[idx(x, y)]

    fun traversable(x: Int, y: Int): Boolean = inBounds(x, y) && types[idx(x, y)].traversable

    fun isPortal(x: Int, y: Int): Boolean = inBounds(x, y) && types[idx(x, y)] == CellType.PORTAL

    fun portalCells(): List<GridPos> {
        val out = ArrayList<GridPos>()
        for (x in 0 until width) for (y in 0 until height) {
            if (types[idx(x, y)] == CellType.PORTAL) out.add(GridPos(x, y))
        }
        return out
    }

    companion object {
        /** Build from a 2D array indexed `grid[x][y]`. */
        fun of(index: Int, grid: Array<Array<CellType>>): Floor {
            val w = grid.size
            require(w > 0) { "floor $index is empty" }
            val h = grid[0].size
            val types = Array(w * h) { CellType.NON_WALKABLE }
            for (x in 0 until w) {
                require(grid[x].size == h) { "floor $index is not rectangular at column $x" }
                for (y in 0 until h) types[x * h + y] = grid[x][y]
            }
            return Floor(index, w, h, types)
        }
    }
}
