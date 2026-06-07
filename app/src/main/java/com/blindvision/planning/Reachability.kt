package com.blindvision.planning

/** Helpers to pick demonstrable (start, target) pairs on a floor. */
object Reachability {

    /** First WALKABLE cell in column-major order, or null if none. */
    fun firstWalkable(floor: Floor): GridPos? {
        for (x in 0 until floor.width) for (y in 0 until floor.height) {
            if (floor.typeAt(x, y) == CellType.WALKABLE) return GridPos(x, y)
        }
        return null
    }

    /**
     * BFS over traversable cells (no diagonal corner-cutting, matching the
     * planner); returns the farthest reachable cell from [start] and its hop
     * distance. Guarantees the returned cell is connected to [start].
     */
    fun farthestReachable(floor: Floor, start: GridPos): Pair<GridPos, Int> {
        val w = floor.width; val h = floor.height
        val dist = IntArray(w * h) { -1 }
        fun id(x: Int, y: Int) = x * h + y
        val q = ArrayDeque<Int>()
        dist[id(start.x, start.y)] = 0
        q.addLast(id(start.x, start.y))
        var best = start; var bestD = 0
        while (q.isNotEmpty()) {
            val cur = q.removeFirst()
            val cx = cur / h; val cy = cur % h
            if (dist[cur] > bestD) { bestD = dist[cur]; best = GridPos(cx, cy) }
            for ((dx, dy) in DIRS8) {
                val nx = cx + dx; val ny = cy + dy
                if (!floor.traversable(nx, ny)) continue
                if (dx != 0 && dy != 0 &&
                    (!floor.traversable(cx + dx, cy) || !floor.traversable(cx, cy + dy))
                ) continue
                val nid = id(nx, ny)
                if (dist[nid] == -1) { dist[nid] = dist[cur] + 1; q.addLast(nid) }
            }
        }
        return best to bestD
    }

    private val DIRS8 = arrayOf(
        1 to 0, -1 to 0, 0 to 1, 0 to -1,
        1 to 1, 1 to -1, -1 to 1, -1 to -1,
    )
}
