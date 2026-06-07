package com.blindvision.planning

/** One piece of a planned route. */
sealed interface Segment

/** Walking within a single floor along [path] (inclusive of both endpoints). */
data class WalkSegment(val floor: Int, val path: List<GridPos>) : Segment

/** Riding an elevator / taking stairs from [fromFloor] to [toFloor] at portal [at]. */
data class TransitionSegment(
    val fromFloor: Int,
    val toFloor: Int,
    val at: GridPos,
    val shaftId: Int,
) : Segment

/** A complete plan: an ordered list of walk and transition [segments]. */
data class Route(
    val from: BuildingPos,
    val to: BuildingPos,
    val segments: List<Segment>,
) {
    /** Total walked cells across all walk segments. */
    val walkCells: Int
        get() = segments.filterIsInstance<WalkSegment>().sumOf { maxOf(0, it.path.size - 1) }

    /** Number of floor transitions (elevator/stair rides). */
    val floorChanges: Int
        get() = segments.count { it is TransitionSegment }

    fun describe(): String = buildString {
        appendLine("Route ${from.floor}:(${from.x},${from.y}) -> ${to.floor}:(${to.x},${to.y})  " +
            "segments=${segments.size} walkCells=$walkCells rides=$floorChanges")
        segments.forEachIndexed { i, s ->
            when (s) {
                is WalkSegment -> appendLine(
                    "  [$i] WALK  floor=${s.floor}  cells=${s.path.size}  " +
                        "(${s.path.first().x},${s.path.first().y}) -> (${s.path.last().x},${s.path.last().y})"
                )
                is TransitionSegment -> appendLine(
                    "  [$i] RIDE  floor ${s.fromFloor} -> ${s.toFloor}  " +
                        "shaft#${s.shaftId} at (${s.at.x},${s.at.y})"
                )
            }
        }
    }
}
