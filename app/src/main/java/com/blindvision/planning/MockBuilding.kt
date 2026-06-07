package com.blindvision.planning

/**
 * A hand-authored 3-floor mock building used by tests and demos.
 *
 * Legend per cell:  '#' non-walkable wall,  '.' walkable,  'E' portal (elevator/stairs).
 * Rows are y (top to bottom), columns are x (left to right). 10 wide x 7 tall.
 *
 * Two shafts, deliberately NOT spanning all floors, so a floor-0 -> floor-2 trip
 * is forced to transfer between them:
 *   - Shaft A (elevator) at (8,1): floors 0 and 1.
 *   - Shaft B (stairs)   at (8,5): floors 1 and 2.
 * Floor 2 also has a fully walled-off pocket at (4,3) to test unreachability.
 */
object MockBuilding {

    val FLOOR_0 = listOf(
        "##########",
        "#.......E#", // E = shaft A (8,1)
        "#........#",
        "#........#",
        "#........#",
        "#........#",
        "##########",
    )

    val FLOOR_1 = listOf(
        "##########",
        "#.......E#", // E = shaft A (8,1)
        "#........#",
        "#........#",
        "#........#",
        "#.......E#", // E = shaft B (8,5)
        "##########",
    )

    val FLOOR_2 = listOf(
        "##########",
        "#........#",
        "#..###...#",
        "#..#.#...#", // (4,3) is a walled-off pocket
        "#..###...#",
        "#.......E#", // E = shaft B (8,5)
        "##########",
    )

    fun build(): BuildingGrid =
        BuildingGrid(listOf(parseFloor(0, FLOOR_0), parseFloor(1, FLOOR_1), parseFloor(2, FLOOR_2)))

    fun parseFloor(index: Int, rows: List<String>): Floor {
        val h = rows.size
        val w = rows[0].length
        val grid = Array(w) { x ->
            Array(h) { y ->
                when (rows[y][x]) {
                    '#' -> CellType.NON_WALKABLE
                    'E' -> CellType.PORTAL
                    else -> CellType.WALKABLE
                }
            }
        }
        return Floor.of(index, grid)
    }
}
