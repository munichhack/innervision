package com.blindvision.planning

/**
 * A connected group of PORTAL cells on one floor (e.g. a single elevator car or
 * a stair landing). [anchor] is the representative cell agents walk to/from.
 *
 * Regions on different floors that share a footprint cell belong to the same
 * [shaftId] — i.e. you can ride between them.
 */
class PortalRegion(
    val id: Int,
    val floor: Int,
    val cells: List<GridPos>,
    val anchor: GridPos,
    val footprint: Set<GridPos>,
    val shaftId: Int,
)

/**
 * The whole building: a stack of [Floor]s plus the derived portal/shaft graph.
 *
 * Shaft linkage assumption: a portal (elevator/stairs) occupies the same (x, y)
 * footprint on every floor it connects. Portal regions on different floors that
 * overlap in (x, y) are treated as one shaft (so an elevator may also skip
 * floors — it only needs to share the footprint on the floors it serves).
 */
class BuildingGrid(val floors: List<Floor>) {

    val portalRegions: List<PortalRegion>
    private val regionsByFloor: Map<Int, List<PortalRegion>>

    init {
        // 1. Connected components (4-neighbour) of PORTAL cells, per floor.
        data class Raw(val floor: Int, val cells: List<GridPos>)
        val raws = ArrayList<Raw>()
        for (floor in floors) {
            val seen = HashSet<GridPos>()
            for (p in floor.portalCells()) {
                if (p in seen) continue
                val comp = ArrayList<GridPos>()
                val stack = ArrayDeque<GridPos>()
                stack.addLast(p); seen.add(p)
                while (stack.isNotEmpty()) {
                    val c = stack.removeLast()
                    comp.add(c)
                    for ((dx, dy) in NEIGHBORS4) {
                        val q = GridPos(c.x + dx, c.y + dy)
                        if (floor.isPortal(q.x, q.y) && q !in seen) { seen.add(q); stack.addLast(q) }
                    }
                }
                raws.add(Raw(floor.index, comp))
            }
        }

        // 2. Union regions whose footprints overlap -> same shaft (across floors).
        val parent = IntArray(raws.size) { it }
        fun find(a: Int): Int {
            var r = a
            while (parent[r] != r) { parent[r] = parent[parent[r]]; r = parent[r] }
            return r
        }
        fun union(a: Int, b: Int) {
            val ra = find(a); val rb = find(b); if (ra != rb) parent[ra] = rb
        }
        val coordOwner = HashMap<GridPos, Int>()
        raws.forEachIndexed { i, raw ->
            for (c in raw.cells) {
                val prev = coordOwner[c]
                if (prev == null) coordOwner[c] = i else union(prev, i)
            }
        }

        // 3. Materialize regions with normalized shaft ids.
        val shaftNorm = HashMap<Int, Int>()
        var nextShaft = 0
        val regions = ArrayList<PortalRegion>()
        raws.forEachIndexed { i, raw ->
            val shaft = shaftNorm.getOrPut(find(i)) { nextShaft++ }
            regions.add(
                PortalRegion(
                    id = i,
                    floor = raw.floor,
                    cells = raw.cells,
                    anchor = centroidAnchor(raw.cells),
                    footprint = raw.cells.toSet(),
                    shaftId = shaft,
                )
            )
        }
        portalRegions = regions
        regionsByFloor = regions.groupBy { it.floor }
    }

    fun regionsOnFloor(f: Int): List<PortalRegion> = regionsByFloor[f] ?: emptyList()

    /** The region cell closest to the region's centroid (always inside the region). */
    private fun centroidAnchor(cells: List<GridPos>): GridPos {
        val cx = cells.sumOf { it.x }.toDouble() / cells.size
        val cy = cells.sumOf { it.y }.toDouble() / cells.size
        return cells.minByOrNull { (it.x - cx) * (it.x - cx) + (it.y - cy) * (it.y - cy) }!!
    }

    private companion object {
        val NEIGHBORS4 = arrayOf(1 to 0, -1 to 0, 0 to 1, 0 to -1)
    }
}
