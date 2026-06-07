package com.blindvision.planning

import java.util.PriorityQueue
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sqrt

/**
 * A* over a single floor grid.
 *
 * @param allowDiagonal 8-connectivity with no corner-cutting through walls when true.
 * @param clearanceWeight when > 0, adds a cost penalty inversely proportional to a
 *   cell's distance from the nearest obstacle/border. This pushes paths toward the
 *   middle of corridors — the practical benefit of a generalized Voronoi graph
 *   (GVG) for safe guidance — without leaving pure Kotlin. 0 = plain shortest path.
 */
class AStarGridPlanner(
    private val allowDiagonal: Boolean = true,
    private val clearanceWeight: Double = 0.0,
) : GridPlanner {

    override fun plan(floor: Floor, start: GridPos, goal: GridPos): List<GridPos>? {
        if (!floor.traversable(start.x, start.y)) return null
        if (!floor.traversable(goal.x, goal.y)) return null
        val w = floor.width
        val h = floor.height
        val n = w * h
        fun id(x: Int, y: Int) = x * h + y
        val startId = id(start.x, start.y)
        val goalId = id(goal.x, goal.y)
        if (startId == goalId) return listOf(start)

        val penalty: DoubleArray? = if (clearanceWeight > 0.0) clearancePenalty(floor) else null

        val gScore = DoubleArray(n) { Double.POSITIVE_INFINITY }
        val cameFrom = IntArray(n) { -1 }
        val closed = BooleanArray(n)
        gScore[startId] = 0.0

        fun heuristic(x: Int, y: Int): Double {
            val dx = abs(x - goal.x); val dy = abs(y - goal.y)
            return if (allowDiagonal) (dx + dy) + (DIAG - 2.0) * min(dx, dy) else (dx + dy).toDouble()
        }

        val open = PriorityQueue<Node>(compareBy { it.f })
        open.add(Node(startId, heuristic(start.x, start.y)))
        val dirs = if (allowDiagonal) NEIGHBORS8 else NEIGHBORS4

        while (open.isNotEmpty()) {
            val cur = open.poll()
            val cid = cur.id
            if (closed[cid]) continue
            closed[cid] = true
            if (cid == goalId) return reconstruct(cameFrom, startId, goalId, h)
            val cx = cid / h; val cy = cid % h
            for ((dx, dy) in dirs) {
                val nx = cx + dx; val ny = cy + dy
                if (!floor.traversable(nx, ny)) continue
                if (dx != 0 && dy != 0) {
                    // Disallow cutting diagonally through a wall corner.
                    if (!floor.traversable(cx + dx, cy) || !floor.traversable(cx, cy + dy)) continue
                }
                val nid = id(nx, ny)
                if (closed[nid]) continue
                val step = if (dx != 0 && dy != 0) DIAG else 1.0
                val extra = penalty?.get(nid) ?: 0.0
                val tentative = gScore[cid] + step + extra
                if (tentative < gScore[nid]) {
                    gScore[nid] = tentative
                    cameFrom[nid] = cid
                    open.add(Node(nid, tentative + heuristic(nx, ny)))
                }
            }
        }
        return null
    }

    private fun reconstruct(cameFrom: IntArray, startId: Int, goalId: Int, h: Int): List<GridPos> {
        val path = ArrayList<GridPos>()
        var cur = goalId
        while (cur != -1) {
            path.add(GridPos(cur / h, cur % h))
            if (cur == startId) break
            cur = cameFrom[cur]
        }
        path.reverse()
        return path
    }

    /** Multi-source BFS distance to nearest obstacle/border; penalty = weight / clearance. */
    private fun clearancePenalty(floor: Floor): DoubleArray {
        val w = floor.width; val h = floor.height; val n = w * h
        val dist = IntArray(n) { Int.MAX_VALUE }
        val queue = ArrayDeque<Int>()
        for (x in 0 until w) for (y in 0 until h) {
            if (!floor.traversable(x, y)) { dist[x * h + y] = 0; queue.addLast(x * h + y) }
        }
        while (queue.isNotEmpty()) {
            val curId = queue.removeFirst()
            val x = curId / h; val y = curId % h
            for ((dx, dy) in NEIGHBORS8) {
                val nx = x + dx; val ny = y + dy
                if (nx !in 0 until w || ny !in 0 until h) continue
                val nid = nx * h + ny
                if (dist[nid] > dist[curId] + 1) { dist[nid] = dist[curId] + 1; queue.addLast(nid) }
            }
        }
        val penalty = DoubleArray(n)
        for (x in 0 until w) for (y in 0 until h) {
            val cellId = x * h + y
            if (!floor.traversable(x, y)) { penalty[cellId] = 0.0; continue }
            val border = min(min(x + 1, w - x), min(y + 1, h - y)) // steps to outside the grid
            val bfs = if (dist[cellId] == Int.MAX_VALUE) Int.MAX_VALUE else dist[cellId]
            val d = min(bfs, border)
            penalty[cellId] = if (d <= 0) clearanceWeight else clearanceWeight / d
        }
        return penalty
    }

    private class Node(val id: Int, val f: Double)

    private companion object {
        val DIAG = sqrt(2.0)
        val NEIGHBORS4 = arrayOf(1 to 0, -1 to 0, 0 to 1, 0 to -1)
        val NEIGHBORS8 = arrayOf(
            1 to 0, -1 to 0, 0 to 1, 0 to -1,
            1 to 1, 1 to -1, -1 to 1, -1 to -1,
        )
    }
}
