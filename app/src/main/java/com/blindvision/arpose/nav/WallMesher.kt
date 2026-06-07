package com.blindvision.arpose.nav

import com.blindvision.planning.CellType
import com.blindvision.planning.Floor

/**
 * Turns the occupancy [Floor] into a small set of axis-aligned wall rectangles for
 * 3D extrusion. Two things make this tractable for ~1.5M cells:
 *
 *  1. The vast exterior (NON_WALKABLE reachable from the image border) is excluded,
 *     so only real interior walls + the building's inner perimeter become geometry.
 *  2. The remaining wall cells are greedy-meshed into maximal rectangles, collapsing
 *     long corridors of wall pixels into a handful of boxes.
 */
object WallMesher {

    /** Rectangle in grid cells: [x], [y] top-left, [w] x [h] size. */
    data class Rect(val x: Int, val y: Int, val w: Int, val h: Int)

    fun wallRects(floor: Floor): List<Rect> {
        val w = floor.width
        val h = floor.height
        val isWall = booleanWallMask(floor, w, h)
        return greedyRects(isWall, w, h)
    }

    private fun booleanWallMask(floor: Floor, w: Int, h: Int): BooleanArray {
        val nonWalkable = BooleanArray(w * h)
        for (x in 0 until w) for (y in 0 until h) {
            if (floor.typeAt(x, y) == CellType.NON_WALKABLE) nonWalkable[y * w + x] = true
        }

        // Flood fill the exterior from the border over NON_WALKABLE cells.
        val exterior = BooleanArray(w * h)
        val stack = ArrayDeque<Int>()
        fun pushIfVoid(x: Int, y: Int) {
            if (x < 0 || y < 0 || x >= w || y >= h) return
            val i = y * w + x
            if (nonWalkable[i] && !exterior[i]) {
                exterior[i] = true
                stack.addLast(i)
            }
        }
        for (x in 0 until w) { pushIfVoid(x, 0); pushIfVoid(x, h - 1) }
        for (y in 0 until h) { pushIfVoid(0, y); pushIfVoid(w - 1, y) }
        while (stack.isNotEmpty()) {
            val i = stack.removeLast()
            val x = i % w
            val y = i / w
            pushIfVoid(x - 1, y); pushIfVoid(x + 1, y)
            pushIfVoid(x, y - 1); pushIfVoid(x, y + 1)
        }

        // Wall = NON_WALKABLE that is either enclosed (not exterior) or forms the
        // inner perimeter (an exterior-touching cell adjacent to walkable space).
        val isWall = BooleanArray(w * h)
        for (x in 0 until w) for (y in 0 until h) {
            val i = y * w + x
            if (!nonWalkable[i]) continue
            if (!exterior[i] || touchesTraversable(floor, x, y)) isWall[i] = true
        }
        return isWall
    }

    private fun touchesTraversable(floor: Floor, x: Int, y: Int): Boolean =
        floor.traversable(x - 1, y) || floor.traversable(x + 1, y) ||
            floor.traversable(x, y - 1) || floor.traversable(x, y + 1)

    /** Standard greedy meshing: expand width then height from each free top-left cell. */
    private fun greedyRects(isWall: BooleanArray, w: Int, h: Int): List<Rect> {
        val used = BooleanArray(w * h)
        val rects = ArrayList<Rect>()
        for (y in 0 until h) {
            var x = 0
            while (x < w) {
                val i = y * w + x
                if (!isWall[i] || used[i]) { x++; continue }

                // Extend right.
                var rw = 1
                while (x + rw < w && isWall[y * w + x + rw] && !used[y * w + x + rw]) rw++

                // Extend down while the whole row span is wall & unused.
                var rh = 1
                outer@ while (y + rh < h) {
                    for (dx in 0 until rw) {
                        val j = (y + rh) * w + x + dx
                        if (!isWall[j] || used[j]) break@outer
                    }
                    rh++
                }

                for (dy in 0 until rh) for (dx in 0 until rw) used[(y + dy) * w + x + dx] = true
                rects.add(Rect(x, y, rw, rh))
                x += rw
            }
        }
        return rects
    }
}
