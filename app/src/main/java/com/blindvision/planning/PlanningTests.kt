package com.blindvision.planning

import kotlin.system.exitProcess

/**
 * Self-contained test runner for the planning module — no JUnit/Gradle test
 * config required (so it adds no dependencies and edits no existing file).
 *
 * Run standalone (compile every .kt under the planning package, then run):
 *   kotlinc app/src/main/java/com/blindvision/planning/ -include-runtime -d /tmp/planning.jar
 *   java -cp /tmp/planning.jar com.blindvision.planning.PlanningTestsKt
 *
 * Each case is a (start, target) tuple that must resolve to a specific list of
 * path segments over the 3-floor [MockBuilding].
 */

private var passed = 0
private var failed = 0

private fun check(name: String, cond: Boolean, detail: String = "") {
    if (cond) {
        passed++
        println("  PASS  $name")
    } else {
        failed++
        println("  FAIL  $name  ${if (detail.isNotEmpty()) "-> $detail" else ""}")
    }
}

private fun walk(s: Segment?) = s as? WalkSegment
private fun ride(s: Segment?) = s as? TransitionSegment

fun main() {
    val building = MockBuilding.build()
    val planner = RoutePlanner(building)

    // Sanity: shaft topology detected as designed (A = floors {0,1}, B = floors {1,2}).
    run {
        val byShaft = building.portalRegions.groupBy { it.shaftId }
        check("topology: two shafts detected", byShaft.size == 2, "got ${byShaft.size}")
        val floorSets = byShaft.values.map { regs -> regs.map { it.floor }.toSet() }.toSet()
        check(
            "topology: shafts span {0,1} and {1,5->2}",
            floorSets == setOf(setOf(0, 1), setOf(1, 2)),
            "got $floorSets",
        )
    }

    // Case 1 — same floor: a single walk segment, no rides.
    run {
        val r = planner.plan(BuildingPos(0, 1, 1), BuildingPos(0, 5, 5))
        println("Case 1 (same floor):\n${r?.describe()}")
        check("c1: route exists", r != null)
        if (r != null) {
            check("c1: one segment", r.segments.size == 1, "${r.segments.size}")
            check("c1: no rides", r.floorChanges == 0)
            val w = walk(r.segments.getOrNull(0))
            check("c1: walk on floor 0", w?.floor == 0)
            check("c1: starts at (1,1)", w?.path?.first() == GridPos(1, 1))
            check("c1: ends at (5,5)", w?.path?.last() == GridPos(5, 5))
        }
    }

    // Case 2 — adjacent floor via shaft A: walk, ride 0->1, walk.
    run {
        val r = planner.plan(BuildingPos(0, 1, 1), BuildingPos(1, 1, 5))
        println("Case 2 (0 -> 1 via shaft A):\n${r?.describe()}")
        check("c2: route exists", r != null)
        if (r != null) {
            check("c2: three segments", r.segments.size == 3, "${r.segments.size}")
            check("c2: one ride", r.floorChanges == 1)
            check("c2: walk f0 to elevator (8,1)", walk(r.segments[0])?.let { it.floor == 0 && it.path.last() == GridPos(8, 1) } == true)
            check("c2: ride 0 -> 1", ride(r.segments[1])?.let { it.fromFloor == 0 && it.toFloor == 1 } == true)
            check("c2: ride at portal (8,1)", ride(r.segments[1])?.at == GridPos(8, 1))
            check("c2: walk f1 to target (1,5)", walk(r.segments[2])?.let { it.floor == 1 && it.path.last() == GridPos(1, 5) } == true)
        }
    }

    // Case 3 — two floors up, forces a transfer A->B on floor 1: walk, ride, walk, ride, walk.
    run {
        val r = planner.plan(BuildingPos(0, 1, 1), BuildingPos(2, 1, 1))
        println("Case 3 (0 -> 2, transfer on floor 1):\n${r?.describe()}")
        check("c3: route exists", r != null)
        if (r != null) {
            check("c3: five segments", r.segments.size == 5, "${r.segments.size}")
            check("c3: two rides", r.floorChanges == 2)
            check("c3: seg0 walk f0", walk(r.segments[0])?.floor == 0)
            check("c3: seg1 ride 0->1", ride(r.segments[1])?.let { it.fromFloor == 0 && it.toFloor == 1 } == true)
            check("c3: seg2 transfer walk f1 (8,1)->(8,5)",
                walk(r.segments[2])?.let { it.floor == 1 && it.path.first() == GridPos(8, 1) && it.path.last() == GridPos(8, 5) } == true)
            check("c3: seg3 ride 1->2", ride(r.segments[3])?.let { it.fromFloor == 1 && it.toFloor == 2 } == true)
            check("c3: seg4 walk f2 to (1,1)", walk(r.segments[4])?.let { it.floor == 2 && it.path.last() == GridPos(1, 1) } == true)
        }
    }

    // Case 4 — unreachable: target is the walled-off pocket on floor 2.
    run {
        val r = planner.plan(BuildingPos(2, 8, 5), BuildingPos(2, 4, 3))
        println("Case 4 (unreachable pocket): ${if (r == null) "no route" else "ROUTE FOUND (unexpected)"}")
        check("c4: no route to sealed pocket", r == null)
    }

    // Case 5 — start == target: trivial single-cell walk, no rides.
    run {
        val r = planner.plan(BuildingPos(1, 3, 3), BuildingPos(1, 3, 3))
        println("Case 5 (start == target):\n${r?.describe()}")
        check("c5: route exists", r != null)
        check("c5: no rides", r?.floorChanges == 0)
    }

    // Case 6 — every path segment stays connected end-to-end across floors.
    run {
        val r = planner.plan(BuildingPos(0, 2, 2), BuildingPos(2, 6, 1))
        check("c6: route exists", r != null)
        if (r != null) {
            check("c6: continuity", segmentsConnected(r), "segments do not chain")
        }
    }

    println("\n==== planning tests: $passed passed, $failed failed ====")
    if (failed > 0) exitProcess(1)
}

/** Verify each segment hands off to the next at a consistent place/floor. */
private fun segmentsConnected(r: Route): Boolean {
    for (i in 1 until r.segments.size) {
        val prev = r.segments[i - 1]
        val cur = r.segments[i]
        val prevEnd: Pair<Int, GridPos> = when (prev) {
            is WalkSegment -> prev.floor to prev.path.last()
            is TransitionSegment -> prev.toFloor to prev.at
        }
        val curStart: Pair<Int, GridPos> = when (cur) {
            is WalkSegment -> cur.floor to cur.path.first()
            is TransitionSegment -> cur.fromFloor to cur.at
        }
        if (prevEnd != curStart) return false
    }
    return true
}
