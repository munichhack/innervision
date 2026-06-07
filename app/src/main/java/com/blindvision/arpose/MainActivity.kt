package com.blindvision.arpose

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.ImageButton
import android.widget.TextView
import com.blindvision.arpose.gl.Map3DView
import com.blindvision.arpose.nav.MaskMapRenderer
import com.blindvision.arpose.nav.MaskNavMap
import com.blindvision.arpose.nav.NavLocation
import com.blindvision.arpose.nav.WallMesher
import com.blindvision.arpose.pose.ArCorePoseProvider
import com.blindvision.arpose.pose.PoseProvider
import com.blindvision.arpose.pose.SimulatedPoseProvider
import com.blindvision.arpose.pose.WorldPoseConsumer
import com.blindvision.planning.GridPos
import com.blindvision.planning.VoronoiGridPlanner
import com.blindvision.planning.tools.DestinationResolver
import com.google.ar.core.ArCoreApk
import com.google.ar.core.exceptions.UnavailableException
import kotlin.math.roundToInt

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

    private lateinit var loadingText: TextView
    private lateinit var searchInput: EditText
    private lateinit var searchButton: ImageButton
    private lateinit var micButton: ImageButton
    private lateinit var destinationLabel: TextView
    private lateinit var searchStatus: TextView
    private lateinit var destinationPrompt: TextView
    private lateinit var recenterButton: ImageButton
    private lateinit var map3dView: Map3DView
    private lateinit var glSurfaceView: GLSurfaceView

    private var navMap: MaskNavMap? = null
    private var cachedFloor3d: Bitmap? = null
    private var cachedWallRects: List<WallMesher.Rect> = emptyList()
    private var lastUserCell: GridPos? = null
    private var resolving = false

    private var speechRecognizer: SpeechRecognizer? = null
    private var listeningActive = false

    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var consumer: WorldPoseConsumer
    private var provider: PoseProvider? = null
    private var started = false
    private var arInstallRequested = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        loadingText = findViewById(R.id.loading_text)
        searchInput = findViewById(R.id.search_input)
        searchButton = findViewById(R.id.search_button)
        micButton = findViewById(R.id.mic_button)
        destinationLabel = findViewById(R.id.destination_label)
        searchStatus = findViewById(R.id.search_status)
        destinationPrompt = findViewById(R.id.destination_prompt)
        recenterButton = findViewById(R.id.recenter_button)
        map3dView = findViewById(R.id.map_3d)
        glSurfaceView = findViewById(R.id.gl_surface)

        searchButton.setOnClickListener { submitSearch() }
        searchInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                submitSearch()
                true
            } else {
                false
            }
        }
        micButton.setOnClickListener { onMicClicked() }
        recenterButton.setOnClickListener { map3dView.recenterOnUser() }

        consumer = WorldPoseConsumer { readout ->
            runOnUiThread { updateUserOnMap(readout) }
        }

        // Lock interaction until the user picks a destination.
        map3dView.interactionLocked = true
        loadMapOnly()
    }

    private fun onMicClicked() {
        if (listeningActive) {
            stopListening()
            return
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_AUDIO)
            return
        }
        startListening()
    }

    private fun startListening() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            showSearchError(getString(R.string.mic_error))
            return
        }
        val recognizer = SpeechRecognizer.createSpeechRecognizer(this)
        speechRecognizer = recognizer
        recognizer.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: android.os.Bundle?) {
                listeningActive = true
                runOnUiThread {
                    micButton.setImageResource(android.R.drawable.presence_audio_online)
                    searchStatus.visibility = View.VISIBLE
                    searchStatus.setTextColor(0xFF555555.toInt())
                    searchStatus.text = getString(R.string.mic_listening)
                }
            }
            override fun onResults(results: android.os.Bundle?) {
                val matches = results
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val text = matches?.firstOrNull()?.trim() ?: ""
                stopListening()
                if (text.isNotEmpty()) {
                    runOnUiThread {
                        searchInput.setText(text)
                        resolveAndPlan(text)
                    }
                } else {
                    runOnUiThread { showSearchError(getString(R.string.mic_error)) }
                }
            }
            override fun onError(error: Int) {
                stopListening()
                runOnUiThread { showSearchError(getString(R.string.mic_error)) }
            }
            override fun onBeginningOfSpeech() {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onEvent(eventType: Int, params: android.os.Bundle?) {}
            override fun onPartialResults(partialResults: android.os.Bundle?) {}
            override fun onRmsChanged(rmsdB: Float) {}
        })

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }
        recognizer.startListening(intent)
    }

    private fun stopListening() {
        listeningActive = false
        speechRecognizer?.destroy()
        speechRecognizer = null
        runOnUiThread {
            micButton.setImageResource(android.R.drawable.ic_btn_speak_now)
        }
    }

    private fun submitSearch() {
        val query = searchInput.text.toString().trim()
        if (query.isEmpty() || resolving) return
        hideKeyboard()
        resolveAndPlan(query)
    }

    private fun hideKeyboard() {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(searchInput.windowToken, 0)
    }

    /**
     * Load the occupancy mask and build the 3D scene without a route.
     * The user is prompted to search for a destination.
     */
    private fun loadMapOnly() {
        Thread {
            try {
                val map = MaskNavMap.fromRawResource(applicationContext, R.raw.floor_plan_mask_labels)
                val floor3d = MaskMapRenderer.render3dFloorTexture(map)
                val wallRects = WallMesher.wallRects(map.floor, dilate = 2)
                cachedFloor3d = floor3d
                cachedWallRects = wallRects
                runOnUiThread { applyMapOnly(map, floor3d, wallRects) }
            } catch (e: Exception) {
                Log.e(WorldPoseConsumer.LOG_TAG, "Map load failed", e)
                runOnUiThread {
                    loadingText.text = "Map failed to load.\n${e.javaClass.simpleName}: ${e.message}"
                }
            }
        }.start()
    }

    private fun applyMapOnly(map: MaskNavMap, floor3d: Bitmap, wallRects: List<WallMesher.Rect>) {
        loadingText.visibility = View.GONE
        navMap = map
        map3dView.setData(floor3d, wallRects, emptyList(), map.cols, map.rows)
        val groups = MaskMapRenderer.portalGroups(map.floor)
        map3dView.setPortals(
            stairs = groups.filter { it.isStairs }.map { it.cx to it.cy },
            elevators = groups.filter { !it.isStairs }.map { it.cx to it.cy },
        )
    }

    /**
     * Ask Gemini to resolve the query to a floor-plan bounding box, then replan
     * the route to that destination.
     */
    private fun resolveAndPlan(query: String) {
        val apiKey = BuildConfig.GEMINI_API_KEY.trim()
        if (apiKey.isEmpty()) {
            showSearchError(getString(R.string.search_no_api_key))
            return
        }

        resolving = true
        searchStatus.visibility = View.VISIBLE
        searchStatus.setTextColor(0xFF555555.toInt())
        searchStatus.text = getString(R.string.search_resolving)
        destinationLabel.visibility = View.GONE
        searchButton.isEnabled = false

        Thread {
            try {
                val roomsJson = resources.openRawResource(R.raw.message)
                    .bufferedReader().use { it.readText() }
                val current = lastUserCell
                val box = DestinationResolver.resolve(
                    mapJson = roomsJson,
                    query = query,
                    currentX = current?.x?.toDouble(),
                    currentY = current?.y?.toDouble(),
                    apiKey = apiKey,
                    model = BuildConfig.GEMINI_MODEL,
                )
                val goal = parseGoalFromBox(box)
                    ?: run {
                        runOnUiThread {
                            resolving = false
                            searchButton.isEnabled = true
                            showSearchError(getString(R.string.search_not_found))
                        }
                        return@Thread
                    }

                replanRoute(goal, query)
            } catch (e: Exception) {
                Log.e(WorldPoseConsumer.LOG_TAG, "Destination search failed", e)
                runOnUiThread {
                    resolving = false
                    searchButton.isEnabled = true
                    showSearchError(e.message ?: getString(R.string.search_not_found))
                }
            }
        }.start()
    }

    private fun parseGoalFromBox(box: String): GridPos? {
        if (box.trim() == "-1") return null
        val nums = Regex("-?\\d+").findAll(box).map { it.value.toInt() }.toList()
        if (nums.size < 4) return null
        val minX = nums[0]; val minY = nums[1]; val maxX = nums[2]; val maxY = nums[3]
        if (minX > maxX || minY > maxY) return null
        return GridPos((minX + maxX) / 2, (minY + maxY) / 2)
    }

    private fun replanRoute(goal: GridPos, label: String) {
        Thread {
            try {
                val map = navMap ?: return@Thread
                val start = routeStart(map)
                val planned = planRoute(map, start, goal)
                runOnUiThread {
                    resolving = false
                    searchButton.isEnabled = true
                    applyPlannedRoute(map, planned, goal, label)
                    searchStatus.visibility = View.GONE
                }
            } catch (e: Exception) {
                Log.e(WorldPoseConsumer.LOG_TAG, "Route replan failed", e)
                runOnUiThread {
                    resolving = false
                    searchButton.isEnabled = true
                    showSearchError(e.message ?: "Route planning failed.")
                }
            }
        }.start()
    }

    private fun routeStart(map: MaskNavMap): GridPos {
        val user = lastUserCell
        return if (user != null) map.snapToTraversable(user) else map.snapToTraversable(MaskNavMap.DEMO_START)
    }

    private data class PlannedRoute(
        val routeCellsDecimated: List<GridPos>,
        val floor3d: Bitmap,
        val wallRects: List<WallMesher.Rect>,
        val cellCount: Int,
        val start: GridPos,
        val goal: GridPos,
    )

    private fun planRoute(map: MaskNavMap, start: GridPos, goal: GridPos): PlannedRoute {
        val snappedGoal = map.snapToTraversable(goal)
        val cells = VoronoiGridPlanner().plan(map.floor, start, snappedGoal) ?: emptyList()
        val step = maxOf(1, cells.size / 400)
        val routeCellsDecimated = ArrayList<GridPos>()
        var k = 0
        while (k < cells.size) { routeCellsDecimated.add(cells[k]); k += step }
        if (cells.isNotEmpty()) routeCellsDecimated.add(cells.last())

        val floor3d = cachedFloor3d ?: MaskMapRenderer.render3dFloorTexture(map).also { cachedFloor3d = it }
        val wallRects = cachedWallRects.takeIf { it.isNotEmpty() }
            ?: WallMesher.wallRects(map.floor, dilate = 2).also { cachedWallRects = it }

        Log.i(
            WorldPoseConsumer.LOG_TAG,
            "Planned route: ${cells.size} cells start=$start goal=$snappedGoal"
        )
        return PlannedRoute(routeCellsDecimated, floor3d, wallRects, cells.size, start, snappedGoal)
    }

    private fun applyPlannedRoute(
        map: MaskNavMap,
        planned: PlannedRoute,
        goal: GridPos,
        label: String?,
    ) {
        loadingText.visibility = View.GONE
        navMap = map
        map3dView.setData(
            planned.floor3d,
            planned.wallRects,
            planned.routeCellsDecimated,
            map.cols,
            map.rows,
        )
        map3dView.setDestination(planned.goal.x.toFloat(), planned.goal.y.toFloat())

        if (label != null) {
            // Destination selected — reveal the map and unlock interaction.
            destinationPrompt.visibility = View.GONE
            map3dView.interactionLocked = false
            destinationLabel.visibility = View.VISIBLE
            destinationLabel.text = getString(R.string.destination_selected, label)
        } else {
            destinationLabel.visibility = View.GONE
        }
    }

    private fun showSearchError(message: String) {
        searchStatus.visibility = View.VISIBLE
        searchStatus.setTextColor(0xFFC62828.toInt())
        searchStatus.text = message
    }

    override fun onResume() {
        super.onResume()
        map3dView.onResume()
        maybeStart()
    }

    override fun onPause() {
        super.onPause()
        stopListening()
        map3dView.onPause()
        provider?.stop()
        provider = null
        started = false
    }

    override fun onDestroy() {
        super.onDestroy()
        speechRecognizer?.destroy()
        speechRecognizer = null
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
        when (requestCode) {
            REQ_CAMERA -> {
                if (grantResults.isNotEmpty() &&
                    grantResults[0] == PackageManager.PERMISSION_GRANTED
                ) {
                    maybeStart()
                } else {
                    startSimulated("Camera permission denied.")
                }
            }
            REQ_AUDIO -> {
                if (grantResults.isNotEmpty() &&
                    grantResults[0] == PackageManager.PERMISSION_GRANTED
                ) {
                    startListening()
                } else {
                    showSearchError(getString(R.string.mic_permission_denied))
                }
            }
        }
    }

    private fun updateUserOnMap(r: WorldPoseConsumer.Readout) {
        val location = NavLocation.fromArCore(r.tx, r.ty, r.tz)
        navMap?.let { m ->
            val cell = m.locationToCell(location)
            map3dView.setUser(cell[0], cell[1], 0f)
            lastUserCell = GridPos(cell[0].roundToInt(), cell[1].roundToInt())
        }
    }

    private companion object {
        const val REQ_CAMERA = 1001
        const val REQ_AUDIO = 1002
    }
}
