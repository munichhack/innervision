package com.blindvision.planning

import java.util.PriorityQueue
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Voronoi / generalized-Voronoi-diagram (GVD) planner — an alternative to
 * [AStarGridPlanner] that routes along the **medial axis** of free space, so
 * paths run down the middle of corridors (maximally far from walls).
 *
 * This is the grid realization of the Voronoi approach for indoor pathways
 * (https://medium.com/@nickzuber/procedurally-generating-indoor-pathways-dbf7d7fe4ace):
 *
 *  1. **Brushfire**: a multi-source BFS from every wall cell labels each free
 *     cell with the nearest obstacle cell (its "site") and a distance.
 *  2. **Skeleton**: a free cell lies on the GVD when a neighbour's nearest site
 *     is far from its own (> [voronoiThreshold]) — i.e. it is equidistant from
 *     two distinct obstacles. This needs no obstacle component labels, so it
 *     works even when all walls form one connected structure.
 *  3. **Route**: connect start and goal to the nearest skeleton cell, route
 *     along the skeleton with A*, then concatenate.
 *
 * Smoothing is **off by default**: line-of-sight shortcutting would cut straight
 * across open space and jump between distant skeleton arms, abandoning the medial
 * axis. Keeping it off makes the path hug the boundaries tightly — which is the
 * whole point of Voronoi routing. (Use [AStarGridPlanner] when you want the
 * shortest, straightened path instead.)
 *
 * Where there is no usable skeleton between two points (e.g. a tiny open room),
 * it defers to [fallback] so it always returns a valid path when one exists.
 * Implements the same [GridPlanner] interface, so it is a drop-in for
 * [RoutePlanner].
 */
class VoronoiGridPlanner(
    private val voronoiThreshold: Double = 3.0,
    private val smooth: Boolean = false,
    private val smoothMinClearance: Double = 1.5,
    private val straighten: Boolean = true,
    private val fallback: GridPlanner = AStarGridPlanner(),
) : GridPlanner {

    private class VField(val w: Int, val h: Int, val dist: IntArray, val voronoi: BooleanArray)

    private val cache = HashMap<Floor, VField>()

    /** Skeleton cells for a floor — useful for visualization/debugging. */
    fun voronoiCells(floor: Floor): List<GridPos> {
        val f = fieldFor(floor); val h = f.h
        val out = ArrayList<GridPos>()
        for (i in f.voronoi.indices) if (f.voronoi[i]) out.add(GridPos(i / h, i % h))
        return out
    }

    override fun plan(floor: Floor, start: GridPos, goal: GridPos): List<GridPos>? {
        if (!floor.traversable(start.x, start.y) || !floor.traversable(goal.x, goal.y)) return null
        if (start == goal) return listOf(start)
        val f = fieldFor(floor)

        val entryStart = bfsToVoronoi(floor, f, start) ?: return fallback.plan(floor, start, goal)
        val entryGoal = bfsToVoronoi(floor, f, goal) ?: return fallback.plan(floor, start, goal)
        val skeleton = routeOnVoronoi(floor, f, entryStart.entry, entryGoal.entry)
            ?: return fallback.plan(floor, start, goal)

        val full = ArrayList<GridPos>()
        full.addAll(entryStart.path)                      // start .. entryStart
        for (k in 1 until skeleton.size) full.add(skeleton[k])  // .. entryGoal
        val tail = entryGoal.path.asReversed()            // entryGoal .. goal
        for (k in 1 until tail.size) full.add(tail[k])

        val routed = if (smooth && full.size > 2) smoothPath(floor, f, full) else full
        return if (straighten && routed.size > 2) collisionStraighten(floor, routed) else routed
    }

    // --- field construction ---------------------------------------------------

    private fun fieldFor(floor: Floor): VField = cache.getOrPut(floor) {
        val w = floor.width; val h = floor.height; val n = w * h
        val dist = IntArray(n) { -1 }
        val srcX = IntArray(n) { -1 }
        val srcY = IntArray(n) { -1 }
        val q = ArrayDeque<Int>()
        for (x in 0 until w) for (y in 0 until h) {
            if (!floor.traversable(x, y)) {
                val i = x * h + y; dist[i] = 0; srcX[i] = x; srcY[i] = y; q.addLast(i)
            }
        }
        while (q.isNotEmpty()) {
            val cur = q.removeFirst()
            val cx = cur / h; val cy = cur % h
            for ((dx, dy) in NEIGHBORS8) {
                val nx = cx + dx; val ny = cy + dy
                if (nx !in 0 until w || ny !in 0 until h) continue
                val nid = nx * h + ny
                if (dist[nid] == -1) {
                    dist[nid] = dist[cur] + 1; srcX[nid] = srcX[cur]; srcY[nid] = srcY[cur]; q.addLast(nid)
                }
            }
        }
        val voronoi = BooleanArray(n)
        val thr2 = voronoiThreshold * voronoiThreshold
        for (x in 0 until w) for (y in 0 until h) {
            val i = x * h + y
            if (!floor.traversable(x, y)) continue
            for ((dx, dy) in NEIGHBORS8) {
                val nx = x + dx; val ny = y + dy
                if (nx !in 0 until w || ny !in 0 until h) continue
                val nid = nx * h + ny
                if (!floor.traversable(nx, ny)) continue
                val ddx = (srcX[i] - srcX[nid]).toDouble()
                val ddy = (srcY[i] - srcY[nid]).toDouble()
                if (ddx * ddx + ddy * ddy > thr2) { voronoi[i] = true; break }
            }
        }
        VField(w, h, dist, voronoi)
    }

    // --- connect a point to the skeleton --------------------------------------

    private class Entry(val entry: GridPos, val path: List<GridPos>)

    private fun bfsToVoronoi(floor: Floor, f: VField, p: GridPos): Entry? {
        val w = f.w; val h = f.h
        val pid = p.x * h + p.y
        if (f.voronoi[pid]) return Entry(p, listOf(p))
        val prev = IntArray(w * h) { -2 }
        val q = ArrayDeque<Int>()
        prev[pid] = -1; q.addLast(pid)
        while (q.isNotEmpty()) {
            val cur = q.removeFirst()
            val cx = cur / h; val cy = cur % h
            for ((dx, dy) in NEIGHBORS8) {
                val nx = cx + dx; val ny = cy + dy
                if (!floor.traversable(nx, ny)) continue
                if (dx != 0 && dy != 0 && (!floor.traversable(cx + dx, cy) || !floor.traversable(cx, cy + dy))) continue
                val nid = nx * h + ny
                if (prev[nid] != -2) continue
                prev[nid] = cur
                if (f.voronoi[nid]) return Entry(GridPos(nx, ny), rebuild(prev, pid, nid, h))
                q.addLast(nid)
            }
        }
        return null
    }

    // --- route restricted to skeleton cells -----------------------------------

    private fun routeOnVoronoi(floor: Floor, f: VField, a: GridPos, b: GridPos): List<GridPos>? {
        val w = f.w; val h = f.h; val n = w * h
        fun id(x: Int, y: Int) = x * h + y
        val sId = id(a.x, a.y); val gId = id(b.x, b.y)
        if (sId == gId) return listOf(a)
        val g = DoubleArray(n) { Double.POSITIVE_INFINITY }
        val came = IntArray(n) { -1 }
        val closed = BooleanArray(n)
        g[sId] = 0.0
        fun hCost(x: Int, y: Int): Double {
            val dx = abs(x - b.x); val dy = abs(y - b.y); return (dx + dy) + (DIAG - 2.0) * min(dx, dy)
        }
        val open = PriorityQueue<Node>(compareBy { it.f })
        open.add(Node(sId, hCost(a.x, a.y)))
        while (open.isNotEmpty()) {
            val cur = open.poll(); val cid = cur.id
            if (closed[cid]) continue
            closed[cid] = true
            if (cid == gId) return rebuild(came, sId, gId, h)
            val cx = cid / h; val cy = cid % h
            for ((dx, dy) in NEIGHBORS8) {
                val nx = cx + dx; val ny = cy + dy
                if (nx !in 0 until w || ny !in 0 until h) continue
                val nid = id(nx, ny)
                if (!f.voronoi[nid]) continue
                if (dx != 0 && dy != 0 &&
                    (!floor.traversable(cx + dx, cy) || !floor.traversable(cx, cy + dy))
                ) continue
                if (closed[nid]) continue
                val step = if (dx != 0 && dy != 0) DIAG else 1.0
                val t = g[cid] + step
                if (t < g[nid]) { g[nid] = t; came[nid] = cid; open.add(Node(nid, t + hCost(nx, ny))) }
            }
        }
        return null
    }

    // --- smoothing (line-of-sight that won't cut through or shave walls) -------

    private fun smoothPath(floor: Floor, f: VField, path: List<GridPos>): List<GridPos> {
        val way = ArrayList<GridPos>()
        way.add(path[0])
        var anchor = 0
        var i = 1
        while (i < path.size - 1) {
            if (!lineOfSight(floor, f, path[anchor], path[i + 1])) { way.add(path[i]); anchor = i }
            i++
        }
        way.add(path[path.size - 1])
        val out = ArrayList<GridPos>()
        out.add(way[0])
        for (k in 1 until way.size) {
            val line = bresenham(way[k - 1], way[k])
            for (j in 1 until line.size) out.add(line[j])
        }
        return out
    }

    private fun lineOfSight(floor: Floor, f: VField, a: GridPos, b: GridPos): Boolean {
        var x = a.x; var y = a.y
        val x1 = b.x; val y1 = b.y
        val dx = abs(x1 - x); val dy = abs(y1 - y)
        val sx = if (x < x1) 1 else -1
        val sy = if (y < y1) 1 else -1
        var err = dx - dy
        while (true) {
            if (!floor.traversable(x, y)) return false
            val endpoint = (x == a.x && y == a.y) || (x == x1 && y == y1)
            if (!endpoint && f.dist[x * f.h + y] < smoothMinClearance) return false
            if (x == x1 && y == y1) break
            val e2 = 2 * err
            var mx = false; var my = false
            if (e2 > -dy) { err -= dy; x += sx; mx = true }
            if (e2 < dx) { err += dx; y += sy; my = true }
            if (mx && my && (!floor.traversable(x - sx, y) || !floor.traversable(x, y - sy))) return false
        }
        return true
    }

    private fun bresenham(a: GridPos, b: GridPos): List<GridPos> {
        val pts = ArrayList<GridPos>()
        var x = a.x; var y = a.y
        val dx = abs(b.x - x); val dy = abs(b.y - y)
        val sx = if (x < b.x) 1 else -1
        val sy = if (y < b.y) 1 else -1
        var err = dx - dy
        while (true) {
            pts.add(GridPos(x, y))
            if (x == b.x && y == b.y) break
            val e2 = 2 * err
            if (e2 > -dy) { err -= dy; x += sx }
            if (e2 < dx) { err += dx; y += sy }
        }
        return pts
    }

    // --- post-process: straighten artefact detours -----------------------------
    //
    // The skeleton route can thread through local junction vertices, producing
    // small V-shaped detours where the corridor is actually straight. A collision-
    // safe Douglas-Peucker pass removes points that lie within [eps] of the chord
    // between kept points, so genuinely straight runs collapse to a line while real
    // turns (deviation > eps) are preserved. Because every kept chord is verified
    // collision-free and the simplified curve stays within [eps] of the original,
    // it never jumps across open space to a distant boundary.

    /**
     * Iteratively skip a single vertex whenever the straight line across it stays
     * collision-free, repeating until nothing changes. Unlike a deviation-threshold
     * simplification (which keeps a big zig/zag just because it is "tall"), this
     * removes a detour of any size as long as the shortcut is walkable — so the path
     * straightens fully while walls still force every necessary corner.
     */
    private fun collisionStraighten(floor: Floor, path: List<GridPos>): List<GridPos> {
        // 1. Reduce straight runs to their corner vertices (lossless: eps = 1 px).
        val keep = BooleanArray(path.size)
        keep[0] = true; keep[path.size - 1] = true
        dpRecurse(floor, path, 0, path.size - 1, 1.0, keep)
        val corners = ArrayList<GridPos>()
        for (i in path.indices) if (keep[i]) corners.add(path[i])

        // 2. Single-vertex skips, accepted only when collision-free, until a fixpoint.
        var changed = true
        while (changed) {
            changed = false
            var i = 1
            while (i < corners.size - 1) {
                if (segmentClear(floor, corners[i - 1], corners[i + 1])) {
                    corners.removeAt(i); changed = true
                } else {
                    i++
                }
            }
        }

        // 3. Re-rasterize the simplified polyline to an adjacent-cell path.
        val out = ArrayList<GridPos>()
        out.add(corners[0])
        for (k in 1 until corners.size) {
            val line = bresenham(corners[k - 1], corners[k])
            for (j in 1 until line.size) out.add(line[j])
        }
        return out
    }

    private fun dpRecurse(floor: Floor, path: List<GridPos>, lo: Int, hi: Int, eps: Double, keep: BooleanArray) {
        if (hi <= lo + 1) return
        val a = path[lo]; val b = path[hi]
        var idx = -1; var maxD = -1.0
        for (i in lo + 1 until hi) {
            val d = perpDistance(path[i], a, b)
            if (d > maxD) { maxD = d; idx = i }
        }
        // Split while the chord deviates too much OR would cut through a wall.
        if (idx != -1 && (maxD > eps || !segmentClear(floor, a, b))) {
            keep[idx] = true
            dpRecurse(floor, path, lo, idx, eps, keep)
            dpRecurse(floor, path, idx, hi, eps, keep)
        }
    }

    private fun perpDistance(p: GridPos, a: GridPos, b: GridPos): Double {
        val dx = (b.x - a.x).toDouble(); val dy = (b.y - a.y).toDouble()
        val len2 = dx * dx + dy * dy
        if (len2 == 0.0) {
            val ex = (p.x - a.x).toDouble(); val ey = (p.y - a.y).toDouble()
            return sqrt(ex * ex + ey * ey)
        }
        return abs(dy * (p.x - a.x) - dx * (p.y - a.y)) / sqrt(len2)
    }

    /** Straight line a->b stays walkable and never shaves a wall corner. */
    private fun segmentClear(floor: Floor, a: GridPos, b: GridPos): Boolean {
        var x = a.x; var y = a.y
        val x1 = b.x; val y1 = b.y
        val dx = abs(x1 - x); val dy = abs(y1 - y)
        val sx = if (x < x1) 1 else -1
        val sy = if (y < y1) 1 else -1
        var err = dx - dy
        while (true) {
            if (!floor.traversable(x, y)) return false
            if (x == x1 && y == y1) break
            val e2 = 2 * err
            var mx = false; var my = false
            if (e2 > -dy) { err -= dy; x += sx; mx = true }
            if (e2 < dx) { err += dx; y += sy; my = true }
            if (mx && my && (!floor.traversable(x - sx, y) || !floor.traversable(x, y - sy))) return false
        }
        return true
    }

    private fun rebuild(prev: IntArray, startId: Int, endId: Int, h: Int): List<GridPos> {
        val out = ArrayList<GridPos>()
        var cur = endId
        while (cur != -1) { out.add(GridPos(cur / h, cur % h)); if (cur == startId) break; cur = prev[cur] }
        out.reverse()
        return out
    }

    private class Node(val id: Int, val f: Double)

    private companion object {
        val DIAG = sqrt(2.0)
        val NEIGHBORS8 = arrayOf(
            1 to 0, -1 to 0, 0 to 1, 0 to -1,
            1 to 1, 1 to -1, -1 to 1, -1 to -1,
        )
    }
}
