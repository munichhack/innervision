package com.blindvision.planning.tools

import com.blindvision.planning.BuildingLoader
import com.blindvision.planning.BuildingPos
import com.blindvision.planning.Reachability
import com.blindvision.planning.RoutePlanner
import com.blindvision.planning.VoronoiGridPlanner
import com.blindvision.planning.WalkSegment
import java.io.File
import kotlin.math.abs
import kotlin.system.exitProcess

/**
 * Runs the Voronoi planner over a floor-plan CSV, dumps the skeleton and the
 * planned path for visualization, and validates the path.
 *
 *   kotlinc app/src/main/java/com/blindvision/planning/ tools/VoronoiRun.kt -include-runtime -d /tmp/voronoi.jar
 *   java -Xmx2g -cp /tmp/voronoi.jar com.blindvision.planning.tools.VoronoiRunKt data/floor_plan.csv
 */
fun main(args: Array<String>) {
    val path = args.getOrNull(0) ?: "data/floor_plan.csv"
    val rows = File(path).readLines().filter { it.isNotBlank() }
    val h = rows.size
    val codes = Array(h) { y -> rows[y].split(",").map { it.trim().toInt() }.toIntArray() }
    val w = codes[0].size
    val floorXY = Array(w) { x -> IntArray(h) { y -> codes[y][x] } }
    val building = BuildingLoader.fromCodes(listOf(floorXY))
    val floor = building.floors[0]
    println("Loaded ${w} x ${h}")

    val vp = VoronoiGridPlanner()
    val t0 = System.nanoTime()
    val skel = vp.voronoiCells(floor)
    println("skeleton cells: ${skel.size}  (built in %.0f ms)".format((System.nanoTime() - t0) / 1e6))
    File("/tmp/voronoi_skeleton.csv").printWriter().use { pw -> for (p in skel) pw.println("${p.x},${p.y}") }

    val start = Reachability.firstWalkable(floor) ?: error("no walkable")
    val (target, _) = Reachability.farthestReachable(floor, start)
    val t1 = System.nanoTime()
    val route = RoutePlanner(building, vp)
        .plan(BuildingPos(0, start.x, start.y), BuildingPos(0, target.x, target.y))
    val ms = (System.nanoTime() - t1) / 1e6
    if (route == null) { println("FAIL: no route"); exitProcess(1) }
    println(route.describe())
    println("planned via Voronoi in %.1f ms".format(ms))

    val wp = (route.segments[0] as WalkSegment).path
    require(wp.first() == start && wp.last() == target) { "endpoints mismatch" }
    for (i in 1 until wp.size) {
        val a = wp[i - 1]; val b = wp[i]
        require(abs(a.x - b.x) <= 1 && abs(a.y - b.y) <= 1) { "non-adjacent step at $i" }
        require(floor.traversable(b.x, b.y)) { "path crosses non-walkable at $b" }
    }
    println("PASS: ${wp.size}-cell Voronoi path, every step adjacent and walkable")
    File("/tmp/voronoi_path.csv").printWriter().use { pw -> for (p in wp) pw.println("${p.x},${p.y}") }
    println("wrote /tmp/voronoi_skeleton.csv and /tmp/voronoi_path.csv")
}
