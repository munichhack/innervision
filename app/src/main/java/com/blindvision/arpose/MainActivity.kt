package com.blindvision.arpose

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.widget.ImageButton
import android.widget.TextView
import com.blindvision.arpose.gl.Map3DView
import com.blindvision.arpose.nav.FloorPlanView
import com.blindvision.arpose.nav.MaskMapRenderer
import com.blindvision.arpose.nav.MaskNavMap
import com.blindvision.arpose.nav.NavLocation
import com.blindvision.arpose.nav.WallMesher
import com.blindvision.arpose.pose.ArCorePoseProvider
import com.blindvision.arpose.pose.PoseProvider
import com.blindvision.arpose.pose.SimulatedPoseProvider
import com.blindvision.arpose.pose.WorldPoseConsumer
import com.blindvision.planning.AStarGridPlanner
import com.google.ar.core.ArCoreApk
import com.google.ar.core.exceptions.UnavailableException

/**
 * Entry point. Decides at runtime which [PoseProvider] to use:
 *
 *   - On a certified device with Google Play Services for AR → [ArCorePoseProvider]
 *     (real world positions from ARCore).
 *   - Otherwise (the arm64 emulator, or an uncertified phone) → [SimulatedPoseProvider].
 *
 * Either way the world poses flow into [WorldPoseConsumer], the downstream
 * application, which derives metrics and logs them under tag "WorldPose".
 */
class MainActivity : Activity() {

    private lateinit var poseText: TextView
    private lateinit var loadingText: TextView
    private lateinit var floorPlanView: FloorPlanView
    private lateinit var map3dView: Map3DView
    private lateinit var recenterButton: ImageButton
    private lateinit var glSurfaceView: GLSurfaceView

    private var navMap: MaskNavMap? = null
    private var show3d = false

    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var consumer: WorldPoseConsumer
    private var provider: PoseProvider? = null
    private var started = false
    private var arInstallRequested = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        poseText = findViewById(R.id.pose_text)
        loadingText = findViewById(R.id.loading_text)
        floorPlanView = findViewById(R.id.floor_plan)
        map3dView = findViewById(R.id.map_3d)
        glSurfaceView = findViewById(R.id.gl_surface)
        recenterButton = findViewById(R.id.recenter_button)
        recenterButton.setOnClickListener { floorPlanView.recenterOnUser() }
        findViewById<ImageButton>(R.id.view_mode_button).setOnClickListener { toggle3d(it) }

        consumer = WorldPoseConsumer { readout ->
            runOnUiThread { render(readout) }
        }

        planDemoRoute()
    }

    /**
     * Load the occupancy mask, run A* from the bottom-center door to the large
     * top-left room, and draw the resulting route over the plan. Done off the UI
     * thread because parsing the ~1.6 MB mask and planning over a 1448x1086 grid
     * is heavy.
     */
    private fun planDemoRoute() {
        Thread {
            try {
                val map = MaskNavMap.fromRawResource(applicationContext, R.raw.floor_plan_mask_labels)
                val start = map.snapToTraversable(MaskNavMap.DEMO_START)
                val goal = map.snapToTraversable(MaskNavMap.DEMO_GOAL)
                val cells = AStarGridPlanner().plan(map.floor, start, goal) ?: emptyList()

                // Decimate to keep per-frame path drawing light.
                val step = maxOf(1, cells.size / 400)
                val route = ArrayList<NavLocation>(cells.size / step + 1)
                var i = 0
                while (i < cells.size) { route.add(map.cellToLocation(cells[i])); i += step }
                if (cells.isNotEmpty()) route.add(map.cellToLocation(cells.last()))

                val t0 = System.currentTimeMillis()
                val map25d = MaskMapRenderer.render25d(map)
                val calibration = map.calibrationFor(map25d.width, map25d.height)

                // 3D assets: wall geometry + a wood floor texture with the route baked in.
                val routeCellsDecimated = ArrayList<com.blindvision.planning.GridPos>()
                var k = 0
                while (k < cells.size) { routeCellsDecimated.add(cells[k]); k += step }
                if (cells.isNotEmpty()) routeCellsDecimated.add(cells.last())
                val wallRects = WallMesher.wallRects(map.floor)
                val floor3d = MaskMapRenderer.render3dFloorTexture(map, routeCellsDecimated)

                Log.i(
                    WorldPoseConsumer.LOG_TAG,
                    "Planned route: ${cells.size} cells start=$start goal=$goal " +
                        "(assets ${System.currentTimeMillis() - t0} ms, " +
                        "walls=${wallRects.size})"
                )
                runOnUiThread {
                    loadingText.visibility = View.GONE
                    navMap = map
                    floorPlanView.setMapBitmaps(map25d, map25d, calibration)
                    floorPlanView.setPath(route)
                    map3dView.setData(floor3d, wallRects, map.cols, map.rows)
                }
            } catch (e: Exception) {
                Log.e(WorldPoseConsumer.LOG_TAG, "Route planning failed", e)
                runOnUiThread {
                    loadingText.text = "Map failed to load.\n${e.javaClass.simpleName}: ${e.message}"
                }
            }
        }.start()
    }

    override fun onResume() {
        super.onResume()
        if (show3d) map3dView.onResume()
        maybeStart()
    }

    override fun onPause() {
        super.onPause()
        if (show3d) map3dView.onPause()
        provider?.stop()
        provider = null
        started = false
    }

    /** Resolve the pose source and begin streaming, retrying transient states. */
    private fun maybeStart() {
        if (started) return
        when (ArCoreApk.getInstance().checkAvailability(this)) {
            ArCoreApk.Availability.UNKNOWN_CHECKING ->
                mainHandler.postDelayed({ maybeStart() }, 200)
            ArCoreApk.Availability.SUPPORTED_INSTALLED ->
                startArCoreOrRequestPermission()
            ArCoreApk.Availability.SUPPORTED_NOT_INSTALLED,
            ArCoreApk.Availability.SUPPORTED_APK_TOO_OLD ->
                requestArInstall()
            ArCoreApk.Availability.UNSUPPORTED_DEVICE_NOT_CAPABLE,
            ArCoreApk.Availability.UNKNOWN_TIMED_OUT,
            ArCoreApk.Availability.UNKNOWN_ERROR ->
                startSimulated("ARCore not supported on this device.")
        }
    }

    private fun startArCoreOrRequestPermission() {
        if (checkSelfPermission(Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), REQ_CAMERA)
            return
        }
        try {
            glSurfaceView.visibility = View.VISIBLE
            val p = ArCorePoseProvider(this, glSurfaceView)
            p.start { pose -> consumer.onPose(pose) }
            provider = p
            started = true
            Log.i(WorldPoseConsumer.LOG_TAG, "Pose source = ARCORE")
        } catch (e: UnavailableException) {
            Log.e(WorldPoseConsumer.LOG_TAG, "ARCore init failed", e)
            startSimulated("ARCore failed to start (${e.javaClass.simpleName}).")
        }
    }

    private fun requestArInstall() {
        try {
            when (ArCoreApk.getInstance().requestInstall(this, !arInstallRequested)) {
                ArCoreApk.InstallStatus.INSTALL_REQUESTED ->
                    arInstallRequested = true
                ArCoreApk.InstallStatus.INSTALLED ->
                    startArCoreOrRequestPermission()
            }
        } catch (e: UnavailableException) {
            Log.e(WorldPoseConsumer.LOG_TAG, "ARCore install unavailable", e)
            startSimulated("ARCore install unavailable (${e.javaClass.simpleName}).")
        }
    }

    private fun startSimulated(reason: String) {
        glSurfaceView.visibility = View.GONE
        val p = SimulatedPoseProvider()
        p.start { pose -> consumer.onPose(pose) }
        provider = p
        started = true
        Log.i(WorldPoseConsumer.LOG_TAG, "Pose source = SIMULATED ($reason)")
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_CAMERA) {
            if (grantResults.isNotEmpty() &&
                grantResults[0] == PackageManager.PERMISSION_GRANTED
            ) {
                maybeStart()
            } else {
                startSimulated("Camera permission denied.")
            }
        }
    }

    /** Switch between the flat 2.5D canvas map and the OpenGL 3D map. */
    private fun toggle3d(button: View) {
        show3d = !show3d
        if (show3d) {
            map3dView.visibility = View.VISIBLE
            floorPlanView.visibility = View.GONE
            map3dView.onResume()
            recenterButton.visibility = View.GONE
            button.contentDescription = "Showing 3D map — tap for 2.5D"
        } else {
            map3dView.visibility = View.GONE
            floorPlanView.visibility = View.VISIBLE
            map3dView.onPause()
            recenterButton.visibility = View.VISIBLE
            button.contentDescription = "Showing 2.5D map — tap for 3D"
        }
    }

    private fun render(r: WorldPoseConsumer.Readout) {
        // ARCore world frame is Y-up; adapt into the planner's z-up floor-plan
        // convention so the dot lands in the plane of the floor plan and the
        // height (ty) drives the floor index.
        val location = NavLocation.fromArCore(r.tx, r.ty, r.tz)
        // The view derives the travel-direction arrow from movement itself; we no
        // longer pass device yaw (which pointed opposite to the direction of travel).
        floorPlanView.setUserLocation(location)
        navMap?.let { m ->
            val cell = m.locationToCell(location)
            map3dView.setUser(cell[0], cell[1], 0f)
        }

        poseText.text = buildString {
            append("floor ${floorPlanView.currentFloor()}  •  ${r.source}\n")
            append("x=% .2f  y=% .2f  z=% .2f m\n".format(location.x, location.y, location.z))
            append("yaw=% .0f°  speed=%.2f m/s".format(r.yawDeg, r.speedMetersPerSec))
        }
    }

    private companion object {
        const val REQ_CAMERA = 1001
    }
}
