package com.blindvision.planning

import kotlin.math.sqrt

/**
 * Plans a path between two cells within a single floor's grid.
 *
 * This is the seam that decouples the multi-floor orchestration ([RoutePlanner])
 * from the per-segment algorithm. [AStarGridPlanner] is the built-in backend; a
 * native or Kotlin GVG (generalized Voronoi graph) backend can implement this
 * interface and drop in without changing anything else.
 */
interface GridPlanner {
    /** @return cell path from [start] to [goal] (inclusive), or null if unreachable. */
    fun plan(floor: Floor, start: GridPos, goal: GridPos): List<GridPos>?
}

/** Geometric length of a cell path (orthogonal step = 1, diagonal = sqrt(2)). */
fun pathLength(path: List<GridPos>): Double {
    if (path.size < 2) return 0.0
    var s = 0.0
    for (i in 1 until path.size) {
        val a = path[i - 1]; val b = path[i]
        s += if (a.x != b.x && a.y != b.y) sqrt(2.0) else 1.0
    }
    return s
}
