package com.blindvision.arpose.nav

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import com.blindvision.planning.CellType
import com.blindvision.planning.Floor
import com.blindvision.planning.GridPos
import kotlin.math.max
import kotlin.math.min

/**
 * One-shot renderer that turns the occupancy [Floor] into styled navigation maps.
 *
 * Shared look: a warm wood floor (subtle plank seams) with blackish-charcoal walls.
 * [render25d] is flat with a soft drop shadow; [render3d] extrudes the walls into
 * oblique prisms (lit top, shaded faces, ground shadow) over a dark, atmospheric
 * background — inspired by Apple Maps' 3D city view.
 */
object MaskMapRenderer {

    private const val EXTRUDE_CELLS = 2
    private const val DISPLAY_SCALE = 2

    // 3D oblique wall prism. Camera looks from the south-east, light from top-left;
    // walls lean up-left, so their front (down-right) faces and ground shadow show.
    private const val WALL_3D_HEIGHT = 11
    private const val WALL_3D_LEAN_X = 2
    private const val WALL_3D_LEAN_Y = 3
    private const val GROUND_SHADOW_LEN = 5

    // Wood plank texture.
    private const val PLANK_H = 26      // plank thickness in cells
    private const val PLANK_LEN = 150   // board length in cells

    // Floor (wood).
    private val WOOD_BASE = Color.parseColor("#BE9367")
    private val WOOD_SEAM = Color.parseColor("#A37C53")
    private val WOOD_BASE_3D = Color.parseColor("#A9825A") // a touch darker under 3D light
    private val WOOD_SEAM_3D = Color.parseColor("#8F6C46")

    // Background (outside the building footprint).
    private val COLOR_VOID_25D = Color.parseColor("#E7E0D4")
    private val COLOR_VOID_3D = Color.parseColor("#14181E")

    // Walls (charcoal).
    private val COLOR_WALL = Color.parseColor("#33373D")
    private val COLOR_WALL_SHADOW = Color.parseColor("#20242A")
    private val COLOR_WALL_TOP = Color.parseColor("#454B53")
    private val COLOR_WALL_FACE = Color.parseColor("#2C3036")
    private val COLOR_WALL_FACE_DARK = Color.parseColor("#191C20")
    private val WOOD_GROUND_SHADOW = Color.parseColor("#8A6A48")

    // Portals — cool accents that pop against the warm wood.
    private val COLOR_STAIRS = Color.parseColor("#5EEAD4")
    private val COLOR_STAIRS_BORDER = Color.parseColor("#0F766E")
    private val COLOR_ELEVATOR = Color.parseColor("#C4B5FD")
    private val COLOR_ELEVATOR_BORDER = Color.parseColor("#6D28D9")

    /** Flat 2.5D map: wood floor + charcoal walls with a soft drop shadow. */
    fun render25d(map: MaskNavMap): Bitmap = render(map)

    // Clean flat floor for the 3D view (no wood grain).
    private val COLOR_FLOOR_3D = Color.parseColor("#ECEEF2")
    private val COLOR_PORTAL_TILE = Color.parseColor("#DDE2EA")

    /**
     * Floor texture for the OpenGL 3D view: a clean flat floor + subtle portal tiles,
     * with the exterior left fully transparent so the GL clear colour shows through.
     * Walls and the route are real 3D geometry in the renderer, not baked here.
     */
    fun render3dFloorTexture(map: MaskNavMap): Bitmap {
        val floor = map.floor
        val w = floor.width
        val h = floor.height
        val pixels = IntArray(w * h) // 0 = transparent

        for (x in 0 until w) {
            for (y in 0 until h) {
                when (floor.typeAt(x, y)) {
                    CellType.WALKABLE -> pixels[pixelIndex(w, x, y)] = COLOR_FLOOR_3D
                    CellType.PORTAL -> pixels[pixelIndex(w, x, y)] = COLOR_PORTAL_TILE
                    else -> Unit // leave transparent
                }
            }
        }

        val full = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        full.setPixels(pixels, 0, w, 0, 0, w, h)
        drawPortalMarkers(full, floor)
        return downscale(full)
    }

    /** Alias kept for existing call sites. */
    fun render(map: MaskNavMap): Bitmap {
        val floor = map.floor
        val w = floor.width
        val h = floor.height
        val pixels = IntArray(w * h)

        for (x in 0 until w) {
            for (y in 0 until h) {
                pixels[pixelIndex(w, x, y)] = when (floor.typeAt(x, y)) {
                    CellType.WALKABLE -> woodColor(x, y, dim = false)
                    CellType.PORTAL -> COLOR_STAIRS
                    CellType.NON_WALKABLE -> COLOR_VOID_25D
                }
            }
        }

        for (x in 0 until w) {
            for (y in 0 until h) {
                if (floor.typeAt(x, y) != CellType.NON_WALKABLE) continue
                if (!touchesTraversable(floor, x, y)) continue
                val shadowX = x + EXTRUDE_CELLS
                val shadowY = y + EXTRUDE_CELLS
                if (shadowX < w && shadowY < h && !floor.traversable(shadowX, shadowY)) {
                    pixels[pixelIndex(w, shadowX, shadowY)] = COLOR_WALL_SHADOW
                }
                pixels[pixelIndex(w, x, y)] = COLOR_WALL
            }
        }

        val full = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        full.setPixels(pixels, 0, w, 0, 0, w, h)
        drawPortalMarkers(full, floor)
        return downscale(full)
    }

    /**
     * Oblique 3D map: wood floor with wall prisms extruded above the plane, soft
     * ground shadows, and a dark background for depth.
     */
    fun render3d(map: MaskNavMap): Bitmap {
        val floor = map.floor
        val w = floor.width
        val h = floor.height
        val pixels = IntArray(w * h) { COLOR_VOID_3D }

        for (x in 0 until w) {
            for (y in 0 until h) {
                when (floor.typeAt(x, y)) {
                    CellType.WALKABLE -> pixels[pixelIndex(w, x, y)] = woodColor(x, y, dim = true)
                    CellType.PORTAL -> pixels[pixelIndex(w, x, y)] = COLOR_STAIRS
                    else -> Unit
                }
            }
        }

        val walls = ArrayList<GridPos>()
        for (x in 0 until w) {
            for (y in 0 until h) {
                if (floor.typeAt(x, y) == CellType.NON_WALKABLE && touchesTraversable(floor, x, y)) {
                    walls.add(GridPos(x, y))
                }
            }
        }

        // Soft ground shadow cast down-right onto the floor (drawn before prisms).
        for (cell in walls) {
            for (s in 1..GROUND_SHADOW_LEN) {
                val sx = cell.x + s
                val sy = cell.y + s
                if (sx >= w || sy >= h) break
                if (!floor.traversable(sx, sy)) continue
                val i = pixelIndex(w, sx, sy)
                pixels[i] = blend(pixels[i], WOOD_GROUND_SHADOW, 0.45f * (1f - s.toFloat() / (GROUND_SHADOW_LEN + 1)))
            }
        }

        // Back-to-front painter's order: far cells (small x+y) first.
        walls.sortBy { it.x + it.y }
        for (cell in walls) {
            drawWallPrism(pixels, w, h, cell.x, cell.y)
        }

        val full = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        full.setPixels(pixels, 0, w, 0, 0, w, h)
        drawPortalMarkers(full, floor)
        return downscale(full)
    }

    /** Stack oblique slices so the wall rises above the floor with shaded faces. */
    private fun drawWallPrism(pixels: IntArray, w: Int, h: Int, x: Int, y: Int) {
        for (layer in 0 until WALL_3D_HEIGHT) {
            val ox = x - layer * WALL_3D_LEAN_X
            val oy = y - layer * WALL_3D_LEAN_Y
            if (ox < 0 || oy < 0 || ox >= w || oy >= h) continue
            val color = when (layer) {
                WALL_3D_HEIGHT - 1 -> COLOR_WALL_TOP
                else -> COLOR_WALL_FACE
            }
            pixels[pixelIndex(w, ox, oy)] = color
            // Thicken the slice so faces read as solid; right edge lit, bottom edge in shade.
            if (ox + 1 < w) pixels[pixelIndex(w, ox + 1, oy)] = color
            if (oy + 1 < h) pixels[pixelIndex(w, ox, oy + 1)] = COLOR_WALL_FACE_DARK
        }
        // Anchor (base) cell sits darkest, grounding the wall.
        pixels[pixelIndex(w, x, y)] = COLOR_WALL_FACE_DARK
    }

    /** Warm wood with horizontal plank seams and gentle per-plank tint variation. */
    private fun woodColor(x: Int, y: Int, dim: Boolean): Int {
        val base = if (dim) WOOD_BASE_3D else WOOD_BASE
        val seam = if (dim) WOOD_SEAM_3D else WOOD_SEAM
        if (y % PLANK_H == 0) return seam
        val plank = y / PLANK_H
        if ((x + plank * 37) % PLANK_LEN == 0) return seam
        // Deterministic ±10 brightness wobble per plank so boards differ slightly.
        val hash = (plank * -1640531527) ushr 25
        val delta = (hash % 21).toInt() - 10
        return shift(base, delta)
    }

    private fun shift(color: Int, delta: Int): Int {
        val r = (Color.red(color) + delta).coerceIn(0, 255)
        val g = (Color.green(color) + delta).coerceIn(0, 255)
        val b = (Color.blue(color) + delta).coerceIn(0, 255)
        return Color.rgb(r, g, b)
    }

    private fun blend(base: Int, over: Int, t: Float): Int {
        val k = t.coerceIn(0f, 1f)
        val r = (Color.red(base) * (1 - k) + Color.red(over) * k).toInt()
        val g = (Color.green(base) * (1 - k) + Color.green(over) * k).toInt()
        val b = (Color.blue(base) * (1 - k) + Color.blue(over) * k).toInt()
        return Color.rgb(r, g, b)
    }

    private fun downscale(full: Bitmap): Bitmap {
        val dw = full.width / DISPLAY_SCALE
        val dh = full.height / DISPLAY_SCALE
        val display = Bitmap.createScaledBitmap(full, dw, dh, true)
        full.recycle()
        return display
    }

    private fun touchesTraversable(floor: Floor, x: Int, y: Int): Boolean {
        if (floor.traversable(x - 1, y)) return true
        if (floor.traversable(x + 1, y)) return true
        if (floor.traversable(x, y - 1)) return true
        if (floor.traversable(x, y + 1)) return true
        return false
    }

    private fun pixelIndex(width: Int, x: Int, y: Int) = y * width + x

    private fun drawPortalMarkers(bitmap: Bitmap, floor: Floor) {
        val canvas = Canvas(bitmap)
        val seen = HashSet<GridPos>()
        for (seed in floor.portalCells()) {
            if (seed in seen) continue
            val cells = floodPortal(floor, seed, seen)
            if (cells.isEmpty()) continue

            var minX = cells[0].x
            var maxX = cells[0].x
            var minY = cells[0].y
            var maxY = cells[0].y
            for (c in cells) {
                minX = min(minX, c.x)
                maxX = max(maxX, c.x)
                minY = min(minY, c.y)
                maxY = max(maxY, c.y)
            }
            val spanX = maxX - minX + 1
            val spanY = maxY - minY + 1
            val isStairs = spanX > spanY * 1.35f

            val fill = if (isStairs) COLOR_STAIRS else COLOR_ELEVATOR
            val border = if (isStairs) COLOR_STAIRS_BORDER else COLOR_ELEVATOR_BORDER

            val cx = (minX + maxX) / 2f + 0.5f
            val cy = (minY + maxY) / 2f + 0.5f
            val pad = 2f
            val rect = RectF(
                minX + pad,
                minY + pad,
                maxX + 1 - pad,
                maxY + 1 - pad,
            )

            val tilePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = fill
                style = Paint.Style.FILL
            }
            val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = border
                style = Paint.Style.STROKE
                strokeWidth = max(1.5f, min(spanX, spanY) * 0.12f)
            }
            canvas.drawRoundRect(rect, 3f, 3f, tilePaint)
            canvas.drawRoundRect(rect, 3f, 3f, borderPaint)

            if (isStairs) {
                drawStairsGlyph(canvas, rect, border)
            } else {
                drawElevatorGlyph(canvas, cx, cy, min(spanX, spanY) * 0.35f, border)
            }
        }
    }

    private fun floodPortal(floor: Floor, seed: GridPos, seen: MutableSet<GridPos>): List<GridPos> {
        val out = ArrayList<GridPos>()
        val stack = ArrayDeque<GridPos>()
        stack.addLast(seed)
        seen.add(seed)
        while (stack.isNotEmpty()) {
            val c = stack.removeLast()
            out.add(c)
            for ((dx, dy) in PORTAL_NEIGHBORS) {
                val n = GridPos(c.x + dx, c.y + dy)
                if (n in seen) continue
                if (!floor.isPortal(n.x, n.y)) continue
                seen.add(n)
                stack.addLast(n)
            }
        }
        return out
    }

    private fun drawStairsGlyph(canvas: Canvas, bounds: RectF, color: Int) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            style = Paint.Style.STROKE
            strokeWidth = max(1.2f, bounds.height() * 0.14f)
            strokeCap = Paint.Cap.ROUND
        }
        val steps = 4
        val stepH = bounds.height() / (steps + 1)
        for (i in 0 until steps) {
            val y = bounds.bottom - stepH * (i + 1)
            val xStart = bounds.left + bounds.width() * 0.15f
            val xEnd = bounds.right - bounds.width() * 0.15f
            canvas.drawLine(xStart, y, xEnd, y, paint)
        }
    }

    private fun drawElevatorGlyph(canvas: Canvas, cx: Float, cy: Float, half: Float, color: Int) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            style = Paint.Style.STROKE
            strokeWidth = max(1.5f, half * 0.35f)
        }
        val rect = RectF(cx - half, cy - half, cx + half, cy + half)
        canvas.drawRect(rect, paint)
        canvas.drawLine(rect.centerX(), rect.top, rect.centerX(), rect.bottom, paint)
        canvas.drawLine(rect.left, rect.centerY(), rect.right, rect.centerY(), paint)
    }

    private val PORTAL_NEIGHBORS = arrayOf(
        intArrayOf(1, 0),
        intArrayOf(-1, 0),
        intArrayOf(0, 1),
        intArrayOf(0, -1),
    )
}
