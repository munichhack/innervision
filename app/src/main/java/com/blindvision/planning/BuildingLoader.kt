package com.blindvision.planning

/**
 * Adapts the raw building input — "a list of 3D arrays, each a floor, with two
 * spatial dimensions (x, y) and a third per-pixel occupancy-type dimension" —
 * into a [BuildingGrid].
 *
 * Two encodings are supported:
 *  - [fromCodes]: occupancy is a single integer code per pixel  (floor[x][y]).
 *  - [fromChannels]: occupancy is a vector per pixel (floor[x][y][channel]),
 *    reduced to a type (default: arg-max, channels 0/1/2 = walkable/non/portal).
 *
 * Default code mapping: 0 = walkable, 1 = non-walkable, 2 = portal. Override the
 * classifier to match your own encoding.
 */
object BuildingLoader {
    const val WALKABLE = 0
    const val NON_WALKABLE = 1
    const val PORTAL = 2

    fun classifyCode(code: Int): CellType = when (code) {
        WALKABLE -> CellType.WALKABLE
        PORTAL -> CellType.PORTAL
        else -> CellType.NON_WALKABLE
    }

    /** Each floor a 2D array `floor[x][y]` of type codes. */
    fun fromCodes(
        floors: List<Array<IntArray>>,
        classify: (Int) -> CellType = ::classifyCode,
    ): BuildingGrid {
        val parsed = floors.mapIndexed { f, g ->
            val w = g.size
            val h = if (w > 0) g[0].size else 0
            Floor.of(f, Array(w) { x -> Array(h) { y -> classify(g[x][y]) } })
        }
        return BuildingGrid(parsed)
    }

    /** Each floor a 3D array `floor[x][y][channel]`. */
    fun fromChannels(
        floors: List<Array<Array<IntArray>>>,
        classify: (IntArray) -> CellType = ::argMaxClassify,
    ): BuildingGrid {
        val parsed = floors.mapIndexed { f, g ->
            val w = g.size
            val h = if (w > 0) g[0].size else 0
            Floor.of(f, Array(w) { x -> Array(h) { y -> classify(g[x][y]) } })
        }
        return BuildingGrid(parsed)
    }

    private fun argMaxClassify(channels: IntArray): CellType {
        if (channels.size == 1) return classifyCode(channels[0])
        var best = 0
        for (i in channels.indices) if (channels[i] > channels[best]) best = i
        return when (best) {
            WALKABLE -> CellType.WALKABLE
            PORTAL -> CellType.PORTAL
            else -> CellType.NON_WALKABLE
        }
    }
}
