package com.blindvision.planning.tools

import com.blindvision.planning.BuildingLoader
import com.blindvision.planning.BuildingPos
import com.blindvision.planning.CellType
import com.blindvision.planning.Floor
import com.blindvision.planning.GridPos
import com.blindvision.planning.RoutePlanner
import com.blindvision.planning.WalkSegment
import java.io.File
import kotlin.math.abs
import kotlin.system.exitProcess

/**
 * Runs the real planner against a CSV exported from the floor-plan mask
 * (data/floor_plan.csv): one row per y, comma-separated type codes per x,
 * where 0 = walkable, 1 = non-walkable, 2 = portal.
 *
 *   kotlinc app/src/main/java/com/blindvision/planning/ tools/RealFloorTest.kt -include-runtime -d /tmp/realtest.jar
 *   java -Xmx1g -cp /tmp/realtest.jar com.blindvision.planning.tools.RealFloorTestKt data/floor_plan.csv
 */
fun main(args: Array<String>) {
    val path = args.getOrNull(0) ?: "data/floor_plan.csv"
    val codes = readCsv(path) // codes[y][x]
    val h = codes.size
    val w = codes[0].size
    println("Loaded grid ${w} x ${h} from $path")

    val floorXY = Array(w) { x -> IntArray(h) { y -> codes[y][x] } }
    val building = BuildingLoader.fromCodes(listOf(floorXY))
    val floor = building.floors[0]

    var walk = 0; var wall = 0; var portal = 0
    for (y in 0 until h) for (x in 0 until w) when (codes[y][x]) {
        0 -> walk++; 1 -> wall++; else -> portal++
    }
    println("cells: walkable=$walk non-walkable=$wall portal=$portal  portalRegions=${building.portalRegions.size}")

    // Start = first walkable cell; target = farthest reachable cell (guarantees a path).
    val start = firstWalkable(floor) ?: run { println("no walkable cell"); exitProcess(1) }
    val (target, bfsDist) = farthestReachable(floor, start)
    println("start=(${start.x},${start.y})  target=(${target.x},${target.y})  bfsDist=$bfsDist")

    val t0 = System.nanoTime()
    val route = RoutePlanner(building)
        .plan(BuildingPos(0, start.x, start.y), BuildingPos(0, target.x, target.y))
    val ms = (System.nanoTime() - t0) / 1e6

    if (route == null) {
        println("FAIL: no route (unexpected — target was chosen as reachable)")
        exitProcess(1)
    }
    println(route.describe())
    println("planned in %.1f ms".format(ms))

    // Assertions: a single connected, walkable walk segment from start to target.
    val seg = route.segments
    require(seg.size == 1 && seg[0] is WalkSegment) { "expected one walk segment, got $seg" }
    val wp = (seg[0] as WalkSegment).path
    require(wp.first() == start && wp.last() == target) { "path endpoints mismatch" }
    for (i in 1 until wp.size) {
        val a = wp[i - 1]; val b = wp[i]
        require(abs(a.x - b.x) <= 1 && abs(a.y - b.y) <= 1) { "non-adjacent step at $i" }
        require(floor.traversable(b.x, b.y)) { "path crosses non-walkable at $b" }
    }
    println("PASS: ${wp.size}-cell path, every step adjacent and walkable")

    File("/tmp/real_path.csv").printWriter().use { pw -> for (p in wp) pw.println("${p.x},${p.y}") }
    println("wrote /tmp/real_path.csv (${wp.size} points)")
}

private fun readCsv(path: String): Array<IntArray> {
    val lines = File(path).readLines().filter { it.isNotBlank() }
    return Array(lines.size) { i -> lines[i].split(",").map { it.trim().toInt() }.toIntArray() }
}

private fun firstWalkable(floor: Floor): GridPos? {
    for (x in 0 until floor.width) for (y in 0 until floor.height) {
        if (floor.typeAt(x, y) == CellType.WALKABLE) return GridPos(x, y)
    }
    return null
}

/** BFS over traversable cells; returns the farthest reachable cell and its hop distance. */
private fun farthestReachable(floor: Floor, start: GridPos): Pair<GridPos, Int> {
    val w = floor.width; val h = floor.height
    val dist = IntArray(w * h) { -1 }
    fun id(x: Int, y: Int) = x * h + y
    val q = ArrayDeque<Int>()
    dist[id(start.x, start.y)] = 0
    q.addLast(id(start.x, start.y))
    var best = start; var bestD = 0
    val dirs = arrayOf(1 to 0, -1 to 0, 0 to 1, 0 to -1, 1 to 1, 1 to -1, -1 to 1, -1 to -1)
    while (q.isNotEmpty()) {
        val cur = q.removeFirst()
        val cx = cur / h; val cy = cur % h
        if (dist[cur] > bestD) { bestD = dist[cur]; best = GridPos(cx, cy) }
        for ((dx, dy) in dirs) {
            val nx = cx + dx; val ny = cy + dy
            if (!floor.traversable(nx, ny)) continue
            if (dx != 0 && dy != 0 && (!floor.traversable(cx + dx, cy) || !floor.traversable(cx, cy + dy))) continue
            val nid = id(nx, ny)
            if (dist[nid] == -1) { dist[nid] = dist[cur] + 1; q.addLast(nid) }
        }
    }
    return best to bestD
}
