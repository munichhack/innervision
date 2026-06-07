package com.blindvision.arpose.gl

import android.graphics.Bitmap
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.GLUtils
import android.opengl.Matrix
import com.blindvision.arpose.nav.WallMesher
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.cos
import kotlin.math.PI
import kotlin.math.sin

/**
 * Real-time 3D renderer for the floor plan: a textured wood floor plus extruded
 * charcoal wall prisms, lit by a single directional light and viewed through a
 * perspective camera the user can orbit / tilt / zoom.
 *
 * The world is normalised so the larger grid axis spans 2 units centred on the
 * origin; the camera orbits that centre.
 */
class Map3DRenderer : GLSurfaceView.Renderer {

    @Volatile private var cols = 1
    @Volatile private var rows = 1
    @Volatile private var pendingFloor: Bitmap? = null
    @Volatile private var wallRects: List<WallMesher.Rect> = emptyList()
    @Volatile private var routeCells: List<com.blindvision.planning.GridPos> = emptyList()
    @Volatile private var geometryDirty = false

    // Live user marker (grid coords + heading). null until first pose.
    @Volatile private var userCol = -1f
    @Volatile private var userRow = -1f
    @Volatile private var userHeading = 0f

    // Destination pin (grid coords). Hidden until a destination is chosen.
    @Volatile private var destCol = -1f
    @Volatile private var destRow = -1f

    // Portal icons.
    @Volatile private var stairsPositions: List<Pair<Float, Float>> = emptyList()
    @Volatile private var elevatorPositions: List<Pair<Float, Float>> = emptyList()
    @Volatile private var portalsDirty = false

    // Orbit camera state.
    @Volatile private var azimuth = 0.5f
    @Volatile private var elevation = (44.0 * PI / 180.0).toFloat()
    @Volatile private var radius = 2.5f
    @Volatile private var cameraCenterX = 0f
    @Volatile private var cameraCenterZ = 0f
    /** When true, the orbit centre tracks [setUser] updates (navigation mode). */
    @Volatile private var followUser = false

    private var cell = 0.001f

    private var program = 0
    private var aPos = 0
    private var aNormal = 0
    private var aUv = 0
    private var uMvp = 0
    private var uLightDir = 0
    private var uUseTex = 0
    private var uColor = 0
    private var uSampler = 0

    private var floorTex = 0
    private var floorBuf: FloatBuffer? = null
    private var wallBuf: FloatBuffer? = null
    private var pathCoreBuf: FloatBuffer? = null
    private var pathCasingBuf: FloatBuffer? = null
    private var markerBuf: FloatBuffer? = null
    private var destMarkerBuf: FloatBuffer? = null
    private var stairsIconBuf: FloatBuffer? = null
    private var elevatorIconBuf: FloatBuffer? = null
    private var wallVertCount = 0
    private var pathCoreCount = 0
    private var pathCasingCount = 0
    private var markerVertCount = 0
    private var destMarkerVertCount = 0
    private var stairsIconVertCount = 0
    private var elevatorIconVertCount = 0

    private val proj = FloatArray(16)
    private val view = FloatArray(16)
    private val vp = FloatArray(16)
    private val mvp = FloatArray(16)
    private val model = FloatArray(16)

    fun setData(
        floor: Bitmap,
        rects: List<WallMesher.Rect>,
        route: List<com.blindvision.planning.GridPos>,
        cols: Int,
        rows: Int,
    ) {
        this.cols = cols
        this.rows = rows
        this.cell = 2f / maxOf(cols, rows)
        this.wallRects = rects
        this.routeCells = route
        this.pendingFloor = floor
        this.geometryDirty = true
    }

    fun setUser(col: Float, row: Float, headingRad: Float) {
        userCol = col
        userRow = row
        userHeading = headingRad
        if (followUser) {
            cameraCenterX = wx(col)
            cameraCenterZ = wz(row)
        }
    }

    fun setDestination(col: Float, row: Float) {
        destCol = col
        destRow = row
    }

    fun clearDestination() {
        destCol = -1f
        destRow = -1f
    }

    fun setPortals(stairs: List<Pair<Float, Float>>, elevators: List<Pair<Float, Float>>) {
        stairsPositions = stairs
        elevatorPositions = elevators
        portalsDirty = true
    }

    fun recenterOnUser() {
        if (userCol < 0f) return
        followUser = true
        cameraCenterX = wx(userCol)
        cameraCenterZ = wz(userRow)
    }

    fun orbitBy(dAzimuth: Float, dElevation: Float) {
        followUser = false
        azimuth += dAzimuth
        elevation = (elevation + dElevation).coerceIn(0.2f, 1.45f)
    }

    fun zoomBy(factor: Float) {
        followUser = false
        radius = (radius / factor).coerceIn(0.9f, 6f)
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0.678f, 0.847f, 0.902f, 1f) // #ADD8E6 light blue
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
        GLES20.glDisable(GLES20.GL_CULL_FACE)

        program = buildProgram(VERTEX_SHADER, FRAGMENT_SHADER)
        aPos = GLES20.glGetAttribLocation(program, "aPos")
        aNormal = GLES20.glGetAttribLocation(program, "aNormal")
        aUv = GLES20.glGetAttribLocation(program, "aUv")
        uMvp = GLES20.glGetUniformLocation(program, "uMvp")
        uLightDir = GLES20.glGetUniformLocation(program, "uLightDir")
        uUseTex = GLES20.glGetUniformLocation(program, "uUseTex")
        uColor = GLES20.glGetUniformLocation(program, "uColor")
        uSampler = GLES20.glGetUniformLocation(program, "uSampler")

        buildMarker()
        buildDestMarker()
        // Force re-upload of texture/geometry on a fresh context.
        geometryDirty = true
        portalsDirty = true
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES20.glViewport(0, 0, width, height)
        val aspect = width.toFloat() / height.toFloat()
        Matrix.perspectiveM(proj, 0, 45f, aspect, 0.02f, 30f)
    }

    override fun onDrawFrame(gl: GL10?) {
        if (geometryDirty) uploadGeometry()
        if (portalsDirty) uploadPortals()

        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
        GLES20.glUseProgram(program)
        GLES20.glUniform3f(uLightDir, -0.4f, 1.0f, -0.55f)

        // Camera orbits (cameraCenterX, 0, cameraCenterZ).
        val cx = cameraCenterX; val cz = cameraCenterZ
        val ex = cx + radius * cos(elevation) * sin(azimuth)
        val ey = radius * sin(elevation)
        val ez = cz + radius * cos(elevation) * cos(azimuth)
        Matrix.setLookAtM(view, 0, ex, ey, ez, cx, 0f, cz, 0f, 1f, 0f)
        Matrix.multiplyMM(vp, 0, proj, 0, view, 0)

        // Floor (identity model).
        Matrix.setIdentityM(model, 0)
        Matrix.multiplyMM(mvp, 0, vp, 0, model, 0)
        GLES20.glUniformMatrix4fv(uMvp, 1, false, mvp, 0)

        floorBuf?.let { buf ->
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, floorTex)
            GLES20.glUniform1i(uSampler, 0)
            GLES20.glUniform1f(uUseTex, 1f)
            drawBuffer(buf, 6)
        }

        // Walls (solid, lit by normals).
        wallBuf?.let { buf ->
            GLES20.glUniform1f(uUseTex, 0f)
            GLES20.glUniform4f(uColor, 0.255f, 0.275f, 0.318f, 1f)
            drawBuffer(buf, wallVertCount)
        }

        // Route: spaced chevron arrows along the planned path.
        pathCoreBuf?.let { buf ->
            GLES20.glUniform1f(uUseTex, 0f)
            GLES20.glUniform4f(uColor, 0.133f, 0.773f, 0.369f, 1f) // #22C55E
            drawBuffer(buf, pathCoreCount)
        }

        // Staircase icons (teal).
        stairsIconBuf?.let { buf ->
            GLES20.glUniform1f(uUseTex, 0f)
            GLES20.glUniform4f(uColor, 0.369f, 0.918f, 0.831f, 1f) // #5EEAD4
            for ((col, row) in stairsPositions) {
                Matrix.setIdentityM(model, 0)
                Matrix.translateM(model, 0, wx(col), 0f, wz(row))
                Matrix.multiplyMM(mvp, 0, vp, 0, model, 0)
                GLES20.glUniformMatrix4fv(uMvp, 1, false, mvp, 0)
                drawBuffer(buf, stairsIconVertCount)
            }
        }

        // Elevator icons (lavender).
        elevatorIconBuf?.let { buf ->
            GLES20.glUniform1f(uUseTex, 0f)
            GLES20.glUniform4f(uColor, 0.769f, 0.710f, 0.992f, 1f) // #C4B5FD
            for ((col, row) in elevatorPositions) {
                Matrix.setIdentityM(model, 0)
                Matrix.translateM(model, 0, wx(col), 0f, wz(row))
                Matrix.multiplyMM(mvp, 0, vp, 0, model, 0)
                GLES20.glUniformMatrix4fv(uMvp, 1, false, mvp, 0)
                drawBuffer(buf, elevatorIconVertCount)
            }
        }

        // Destination marker (pin).
        if (destCol >= 0f && destMarkerBuf != null) {
            Matrix.setIdentityM(model, 0)
            Matrix.translateM(model, 0, wx(destCol), 0f, wz(destRow))
            Matrix.multiplyMM(mvp, 0, vp, 0, model, 0)
            GLES20.glUniformMatrix4fv(uMvp, 1, false, mvp, 0)
            GLES20.glUniform1f(uUseTex, 0f)
            GLES20.glUniform4f(uColor, 0.898f, 0.224f, 0.208f, 1f) // #E53935 red
            drawBuffer(destMarkerBuf!!, destMarkerVertCount)
        }

        // User marker.
        if (userCol >= 0f && markerBuf != null) {
            Matrix.setIdentityM(model, 0)
            Matrix.translateM(model, 0, wx(userCol), 0f, wz(userRow))
            Matrix.rotateM(model, 0, -userHeading * 180f / PI.toFloat(), 0f, 1f, 0f)
            Matrix.multiplyMM(mvp, 0, vp, 0, model, 0)
            GLES20.glUniformMatrix4fv(uMvp, 1, false, mvp, 0)
            GLES20.glUniform1f(uUseTex, 0f)
            GLES20.glUniform4f(uColor, 0.678f, 0.847f, 0.902f, 1f) // #ADD8E6 light blue
            drawBuffer(markerBuf!!, markerVertCount)
        }
    }

    private fun drawBuffer(buf: FloatBuffer, vertCount: Int) {
        if (vertCount <= 0 || buf.capacity() == 0) return
        val stride = 8 * 4
        buf.position(0)
        GLES20.glEnableVertexAttribArray(aPos)
        GLES20.glVertexAttribPointer(aPos, 3, GLES20.GL_FLOAT, false, stride, buf)
        buf.position(3)
        GLES20.glEnableVertexAttribArray(aNormal)
        GLES20.glVertexAttribPointer(aNormal, 3, GLES20.GL_FLOAT, false, stride, buf)
        buf.position(6)
        GLES20.glEnableVertexAttribArray(aUv)
        GLES20.glVertexAttribPointer(aUv, 2, GLES20.GL_FLOAT, false, stride, buf)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, vertCount)
    }

    private fun uploadGeometry() {
        geometryDirty = false
        buildFloor()
        buildWalls()
        buildPath()
        pendingFloor?.let { bmp ->
            if (floorTex != 0) GLES20.glDeleteTextures(1, intArrayOf(floorTex), 0)
            val ids = IntArray(1)
            GLES20.glGenTextures(1, ids, 0)
            floorTex = ids[0]
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, floorTex)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
            GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bmp, 0)
        }
    }

    private fun uploadPortals() {
        portalsDirty = false
        buildStairsIcon()
        buildElevatorIcon()
    }

    private fun wx(col: Float) = (col - cols / 2f) * cell
    private fun wz(row: Float) = (row - rows / 2f) * cell

    private fun buildFloor() {
        val x0 = wx(0f); val x1 = wx(cols.toFloat())
        val z0 = wz(0f); val z1 = wz(rows.toFloat())
        val v = floatArrayOf(
            // pos                 normal        uv
            x0, 0f, z0, 0f, 1f, 0f, 0f, 0f,
            x1, 0f, z0, 0f, 1f, 0f, 1f, 0f,
            x1, 0f, z1, 0f, 1f, 0f, 1f, 1f,
            x0, 0f, z0, 0f, 1f, 0f, 0f, 0f,
            x1, 0f, z1, 0f, 1f, 0f, 1f, 1f,
            x0, 0f, z1, 0f, 1f, 0f, 0f, 1f,
        )
        floorBuf = v.toBuffer()
    }

    private fun buildPath() {
        if (routeCells.size < 2) {
            pathCoreBuf = null
            pathCasingBuf = null
            pathCoreCount = 0
            pathCasingCount = 0
            return
        }
        pathCasingBuf = null
        pathCasingCount = 0
        val built = buildArrowSequence()
        if (built == null) {
            pathCoreBuf = null
            pathCoreCount = 0
            return
        }
        pathCoreBuf = built.first
        pathCoreCount = built.second
    }

    /** Places flat chevron arrows along the route at regular spacing. */
    private fun buildArrowSequence(): Pair<FloatBuffer, Int>? {
        val data = ArrayList<Float>(routeCells.size * 3 * 8)
        val y = cell * 6.5f
        val arrowLen = cell * 12f
        val arrowHalfW = cell * 3.5f
        val spacing = cell * 20f
        var distSinceLast = spacing

        for (i in 0 until routeCells.size - 1) {
            val ax = wx(routeCells[i].x.toFloat()); val az = wz(routeCells[i].y.toFloat())
            val bx = wx(routeCells[i + 1].x.toFloat()); val bz = wz(routeCells[i + 1].y.toFloat())
            var dx = bx - ax; var dz = bz - az
            val segLen = kotlin.math.sqrt(dx * dx + dz * dz).takeIf { it > 1e-7f } ?: continue
            dx /= segLen; dz /= segLen

            var t = 0f
            while (t < segLen) {
                if (distSinceLast >= spacing) {
                    addArrow(data, ax + dx * t, y, az + dz * t, dx, dz, arrowLen, arrowHalfW)
                    distSinceLast = 0f
                }
                val step = minOf(spacing - distSinceLast, segLen - t)
                distSinceLast += step
                t += step
            }
        }

        if (data.isEmpty()) return null
        val arr = FloatArray(data.size)
        for (i in data.indices) arr[i] = data[i]
        return arr.toBuffer() to data.size / 8
    }

    private fun addArrow(
        out: ArrayList<Float>,
        cx: Float, y: Float, cz: Float,
        dx: Float, dz: Float,
        length: Float, halfWidth: Float,
    ) {
        val px = -dz * halfWidth
        val pz = dx * halfWidth
        val backX = cx - dx * length * 0.45f
        val backZ = cz - dz * length * 0.45f
        val tipX = cx + dx * length * 0.55f
        val tipZ = cz + dz * length * 0.55f
        tri(out, tipX, y, tipZ, backX + px, y, backZ + pz, backX - px, y, backZ - pz)
    }

    private fun buildWalls() {
        val wallH = cell * 55f
        val data = ArrayList<Float>(wallRects.size * 5 * 6 * 8)
        for (r in wallRects) {
            val x0 = wx(r.x.toFloat()); val x1 = wx((r.x + r.w).toFloat())
            val z0 = wz(r.y.toFloat()); val z1 = wz((r.y + r.h).toFloat())
            val y0 = 0f; val y1 = wallH
            // +X
            quad(data, x1, y0, z0, x1, y1, z0, x1, y1, z1, x1, y0, z1, 1f, 0f, 0f)
            // -X
            quad(data, x0, y0, z1, x0, y1, z1, x0, y1, z0, x0, y0, z0, -1f, 0f, 0f)
            // +Z
            quad(data, x1, y0, z1, x1, y1, z1, x0, y1, z1, x0, y0, z1, 0f, 0f, 1f)
            // -Z
            quad(data, x0, y0, z0, x0, y1, z0, x1, y1, z0, x1, y0, z0, 0f, 0f, -1f)
            // top
            quad(data, x0, y1, z0, x0, y1, z1, x1, y1, z1, x1, y1, z0, 0f, 1f, 0f)
        }
        if (data.isEmpty()) {
            wallBuf = null
            wallVertCount = 0
            return
        }
        val arr = FloatArray(data.size)
        for (i in data.indices) arr[i] = data[i]
        wallBuf = arr.toBuffer()
        wallVertCount = data.size / 8
    }

    /** 3-step ascending staircase icon, centered at origin, sized relative to [cell]. */
    private fun buildStairsIcon() {
        val sw = cell * 18f    // width per step
        val sd = cell * 26f    // depth of each step
        val sh = cell * 20f    // height of one step riser
        val steps = 3
        val totalW = sw * steps
        val data = ArrayList<Float>()
        for (i in 0 until steps) {
            val x0 = -totalW / 2f + i * sw
            addBox(data, x0, 0f, -sd / 2f, sw, sh * (i + 1), sd)
        }
        val arr = FloatArray(data.size)
        for (i in data.indices) arr[i] = data[i]
        stairsIconBuf = arr.toBuffer()
        stairsIconVertCount = data.size / 8
    }

    /** Doorframe-shaped elevator icon, centered at origin, sized relative to [cell]. */
    private fun buildElevatorIcon() {
        val ph = cell * 60f    // pillar height
        val pw = cell * 10f    // pillar width & depth
        val gap = cell * 20f   // opening between pillars
        val totalW = pw * 2 + gap
        val barH = cell * 13f  // header bar height
        val data = ArrayList<Float>()
        // Left pillar
        addBox(data, -totalW / 2f, 0f, -pw / 2f, pw, ph, pw)
        // Right pillar
        addBox(data, totalW / 2f - pw, 0f, -pw / 2f, pw, ph, pw)
        // Top header bar
        addBox(data, -totalW / 2f, ph, -pw / 2f, totalW, barH, pw)
        val arr = FloatArray(data.size)
        for (i in data.indices) arr[i] = data[i]
        elevatorIconBuf = arr.toBuffer()
        elevatorIconVertCount = data.size / 8
    }

    /** Adds a box with 5 faces (no bottom) to [data]. Origin at (x0, y0, z0), size (w, h, d). */
    private fun addBox(data: ArrayList<Float>, x0: Float, y0: Float, z0: Float, w: Float, h: Float, d: Float) {
        val x1 = x0 + w; val y1 = y0 + h; val z1 = z0 + d
        quad(data, x0, y1, z0, x0, y1, z1, x1, y1, z1, x1, y1, z0, 0f, 1f, 0f)  // top
        quad(data, x0, y0, z1, x1, y0, z1, x1, y1, z1, x0, y1, z1, 0f, 0f, 1f)  // front +Z
        quad(data, x1, y0, z0, x0, y0, z0, x0, y1, z0, x1, y1, z0, 0f, 0f, -1f) // back -Z
        quad(data, x1, y0, z1, x1, y0, z0, x1, y1, z0, x1, y1, z1, 1f, 0f, 0f)  // right +X
        quad(data, x0, y0, z0, x0, y0, z1, x0, y1, z1, x0, y1, z0, -1f, 0f, 0f) // left -X
    }

    private fun buildDestMarker() {
        val ringR = 0.028f
        val ringY = 0.012f
        val seg = 24
        val data = ArrayList<Float>()
        // Flat ring on the floor.
        for (i in 0 until seg) {
            val a0 = (2 * PI * i / seg).toFloat()
            val a1 = (2 * PI * (i + 1) / seg).toFloat()
            val ix0 = ringR * 0.55f * cos(a0); val iz0 = ringR * 0.55f * sin(a0)
            val ox0 = ringR * cos(a0); val oz0 = ringR * sin(a0)
            val ix1 = ringR * 0.55f * cos(a1); val iz1 = ringR * 0.55f * sin(a1)
            val ox1 = ringR * cos(a1); val oz1 = ringR * sin(a1)
            quad(data, ox0, ringY, oz0, ox1, ringY, oz1, ix1, ringY, iz1, ix0, ringY, iz0, 0f, 1f, 0f)
        }
        // Upward pin cone.
        val mr = 0.014f
        val mh = 0.09f
        val base = 0.018f
        for (i in 0 until seg) {
            val a0 = (2 * PI * i / seg).toFloat()
            val a1 = (2 * PI * (i + 1) / seg).toFloat()
            val bx0 = mr * cos(a0); val bz0 = mr * sin(a0)
            val bx1 = mr * cos(a1); val bz1 = mr * sin(a1)
            tri(data, 0f, mh, 0f, bx0, base, bz0, bx1, base, bz1)
            tri(data, 0f, base, 0f, bx1, base, bz1, bx0, base, bz0)
        }
        val arr = FloatArray(data.size)
        for (i in data.indices) arr[i] = data[i]
        destMarkerBuf = arr.toBuffer()
        destMarkerVertCount = data.size / 8
    }

    private fun buildMarker() {
        val mr = 0.018f
        val mh = 0.07f
        val seg = 16
        val data = ArrayList<Float>()
        // Cone side: apex up at (0,mh,0), base circle radius mr at y=0.02 (float above floor).
        val base = 0.015f
        for (i in 0 until seg) {
            val a0 = (2 * PI * i / seg).toFloat()
            val a1 = (2 * PI * (i + 1) / seg).toFloat()
            val bx0 = mr * cos(a0); val bz0 = mr * sin(a0)
            val bx1 = mr * cos(a1); val bz1 = mr * sin(a1)
            // side triangle (apex, b0, b1)
            tri(data, 0f, mh, 0f, bx0, base, bz0, bx1, base, bz1)
            // base triangle (center, b1, b0)
            tri(data, 0f, base, 0f, bx1, base, bz1, bx0, base, bz0)
        }
        val arr = FloatArray(data.size)
        for (i in data.indices) arr[i] = data[i]
        markerBuf = arr.toBuffer()
        markerVertCount = data.size / 8
    }

    private fun quad(
        out: ArrayList<Float>,
        ax: Float, ay: Float, az: Float,
        bx: Float, by: Float, bz: Float,
        cx: Float, cy: Float, cz: Float,
        dx: Float, dy: Float, dz: Float,
        nx: Float, ny: Float, nz: Float,
    ) {
        vert(out, ax, ay, az, nx, ny, nz)
        vert(out, bx, by, bz, nx, ny, nz)
        vert(out, cx, cy, cz, nx, ny, nz)
        vert(out, ax, ay, az, nx, ny, nz)
        vert(out, cx, cy, cz, nx, ny, nz)
        vert(out, dx, dy, dz, nx, ny, nz)
    }

    private fun tri(
        out: ArrayList<Float>,
        ax: Float, ay: Float, az: Float,
        bx: Float, by: Float, bz: Float,
        cx: Float, cy: Float, cz: Float,
    ) {
        // Flat normal from the triangle.
        val ux = bx - ax; val uy = by - ay; val uz = bz - az
        val vx = cx - ax; val vy = cy - ay; val vz = cz - az
        var nx = uy * vz - uz * vy
        var ny = uz * vx - ux * vz
        var nz = ux * vy - uy * vx
        val len = kotlin.math.sqrt(nx * nx + ny * ny + nz * nz).takeIf { it > 1e-6f } ?: 1f
        nx /= len; ny /= len; nz /= len
        vert(out, ax, ay, az, nx, ny, nz)
        vert(out, bx, by, bz, nx, ny, nz)
        vert(out, cx, cy, cz, nx, ny, nz)
    }

    private fun vert(out: ArrayList<Float>, x: Float, y: Float, z: Float, nx: Float, ny: Float, nz: Float) {
        out.add(x); out.add(y); out.add(z)
        out.add(nx); out.add(ny); out.add(nz)
        out.add(0f); out.add(0f)
    }

    private fun FloatArray.toBuffer(): FloatBuffer =
        ByteBuffer.allocateDirect(size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
            put(this@toBuffer)
            position(0)
        }

    private fun buildProgram(vsrc: String, fsrc: String): Int {
        val vs = compile(GLES20.GL_VERTEX_SHADER, vsrc)
        val fs = compile(GLES20.GL_FRAGMENT_SHADER, fsrc)
        val p = GLES20.glCreateProgram()
        GLES20.glAttachShader(p, vs)
        GLES20.glAttachShader(p, fs)
        GLES20.glLinkProgram(p)
        return p
    }

    private fun compile(type: Int, src: String): Int {
        val s = GLES20.glCreateShader(type)
        GLES20.glShaderSource(s, src)
        GLES20.glCompileShader(s)
        return s
    }

    private companion object {
        const val VERTEX_SHADER = """
            uniform mat4 uMvp;
            attribute vec4 aPos;
            attribute vec3 aNormal;
            attribute vec2 aUv;
            varying vec3 vNormal;
            varying vec2 vUv;
            void main() {
                vNormal = aNormal;
                vUv = aUv;
                gl_Position = uMvp * aPos;
            }
        """

        const val FRAGMENT_SHADER = """
            precision mediump float;
            uniform vec3 uLightDir;
            uniform float uUseTex;
            uniform vec4 uColor;
            uniform sampler2D uSampler;
            varying vec3 vNormal;
            varying vec2 vUv;
            void main() {
                float diff = max(dot(normalize(vNormal), normalize(uLightDir)), 0.0);
                float light = 0.40 + 0.60 * diff;
                vec4 base = (uUseTex > 0.5) ? texture2D(uSampler, vUv) : uColor;
                if (base.a < 0.1) discard;
                gl_FragColor = vec4(base.rgb * light, base.a);
            }
        """
    }
}
