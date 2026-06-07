package com.blindvision.planning

import java.util.PriorityQueue
import kotlin.math.abs

/**
 * Decomposes a building-wide (start -> target) request into a list of path
 * segments, as specified:
 *
 *  - target on the current floor  -> a single walk segment to it.
 *  - target on another floor      -> walk to an elevator/stairs, ride, and
 *                                     (transferring between shafts if needed)
 *                                     walk to the target on the arrival floor.
 *
 * It builds a small high-level graph whose nodes are {start, target, every
 * portal region}. Intra-floor edges are weighted by an actual [gridPlanner]
 * path; shaft edges (same shaft, different floor) cost [floorTransitionCost]
 * per floor crossed. Dijkstra over that graph picks the portal sequence, then
 * the chosen edges are materialized into [WalkSegment]/[TransitionSegment]s.
 */
class RoutePlanner(
    private val building: BuildingGrid,
    private val gridPlanner: GridPlanner = AStarGridPlanner(),
    private val floorTransitionCost: Double = 5.0,
) {
    fun plan(start: BuildingPos, goal: BuildingPos): Route? {
        val sFloor = building.floors.getOrNull(start.floor) ?: return null
        val gFloor = building.floors.getOrNull(goal.floor) ?: return null
        if (!sFloor.traversable(start.x, start.y)) return null
        if (!gFloor.traversable(goal.x, goal.y)) return null

        val regions = building.portalRegions
        val nNodes = 2 + regions.size
        val nodeFloor = IntArray(nNodes)
        val nodePos = arrayOfNulls<GridPos>(nNodes)
        nodeFloor[0] = start.floor; nodePos[0] = start.gridPos
        nodeFloor[1] = goal.floor; nodePos[1] = goal.gridPos
        regions.forEachIndexed { i, r -> nodeFloor[2 + i] = r.floor; nodePos[2 + i] = r.anchor }

        val adj = Array(nNodes) { mutableListOf<Pair<Int, Double>>() }
        val walkPaths = HashMap<Long, List<GridPos>>()
        fun key(u: Int, v: Int) = u.toLong() * nNodes + v

        // Intra-floor walk edges between every pair of nodes sharing a floor.
        val byFloor = (0 until nNodes).groupBy { nodeFloor[it] }
        for ((f, ids) in byFloor) {
            val floor = building.floors[f]
            for (i in ids.indices) for (j in i + 1 until ids.size) {
                val a = ids[i]; val b = ids[j]
                val path = gridPlanner.plan(floor, nodePos[a]!!, nodePos[b]!!) ?: continue
                val wgt = pathLength(path)
                adj[a].add(b to wgt); adj[b].add(a to wgt)
                walkPaths[key(a, b)] = path
                walkPaths[key(b, a)] = path.asReversed()
            }
        }

        // Shaft transition edges (same shaft, different floor).
        val byShaft = regions.indices.groupBy { regions[it].shaftId }
        for ((_, idxs) in byShaft) {
            for (i in idxs.indices) for (j in i + 1 until idxs.size) {
                val ra = regions[idxs[i]]; val rb = regions[idxs[j]]
                if (ra.floor == rb.floor) continue
                val a = 2 + idxs[i]; val b = 2 + idxs[j]
                val wgt = floorTransitionCost * abs(ra.floor - rb.floor)
                adj[a].add(b to wgt); adj[b].add(a to wgt)
            }
        }

        // Dijkstra: start (0) -> goal (1).
        val dist = DoubleArray(nNodes) { Double.POSITIVE_INFINITY }
        val prev = IntArray(nNodes) { -1 }
        val done = BooleanArray(nNodes)
        dist[0] = 0.0
        val pq = PriorityQueue<Pair<Int, Double>>(compareBy { it.second })
        pq.add(0 to 0.0)
        while (pq.isNotEmpty()) {
            val (u, _) = pq.poll()
            if (done[u]) continue
            done[u] = true
            if (u == 1) break
            for ((v, wgt) in adj[u]) {
                if (dist[u] + wgt < dist[v]) {
                    dist[v] = dist[u] + wgt
                    prev[v] = u
                    pq.add(v to dist[v])
                }
            }
        }
        if (dist[1].isInfinite()) return null

        // Reconstruct the node chain start -> goal.
        val chain = ArrayList<Int>()
        var c = 1
        while (c != -1) { chain.add(c); c = prev[c] }
        chain.reverse()

        // Materialize segments from consecutive nodes.
        val segments = ArrayList<Segment>()
        for (k in 1 until chain.size) {
            val u = chain[k - 1]; val v = chain[k]
            if (nodeFloor[u] == nodeFloor[v]) {
                val p = walkPaths[key(u, v)] ?: listOf(nodePos[u]!!, nodePos[v]!!)
                segments.add(WalkSegment(nodeFloor[u], p))
            } else {
                // Shaft edges only ever connect two portal-region nodes (index >= 2).
                val shaftId = regions[u - 2].shaftId
                segments.add(TransitionSegment(nodeFloor[u], nodeFloor[v], nodePos[u]!!, shaftId))
            }
        }
        return Route(start, goal, mergeAdjacentWalks(segments))
    }

    /** Collapse consecutive walk segments on the same floor (e.g. a shaft transfer). */
    private fun mergeAdjacentWalks(segs: List<Segment>): List<Segment> {
        val out = ArrayList<Segment>()
        for (s in segs) {
            val last = out.lastOrNull()
            if (s is WalkSegment && last is WalkSegment && last.floor == s.floor) {
                out[out.size - 1] = WalkSegment(s.floor, last.path + s.path.drop(1))
            } else {
                out.add(s)
            }
        }
        return out
    }
}
