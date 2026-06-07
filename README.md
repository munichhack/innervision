# BlindVision

An Android navigation app for visually impaired users. It renders an interactive
3D floor plan, lets the user search for a destination in natural language, plans a
route, and overlays a live position dot driven by ARCore (real device) or a
synthetic trajectory (emulator / uncertified device).

## Architecture

```
PoseProvider (interface)
 ├─ ArCorePoseProvider   real ARCore Session + hidden GLSurfaceView → frame.camera.pose
 └─ SimulatedPoseProvider HandlerThread emitting a moving 6-DoF trajectory

Pose6Dof            immutable {translation, quaternion, timestamp, source}
WorldPoseConsumer   derives path length and speed; emits throttled Readout callbacks
                    and structured logcat under tag "WorldPose"
MainActivity        wires everything together

nav/
  MaskNavMap        loads floor_plan_mask_labels.json (occupancy mask + room labels)
  MaskMapRenderer   renders the mask into a styled 3D floor-texture bitmap
  WallMesher        extracts wall rectangles for the GL scene
  FloorPlanModel    NavLocation, FloorModel, PlanCalibration (shared data types)

gl/
  Map3DView         GLSurfaceView with orbit/zoom touch handling
  Map3DRenderer     OpenGL ES 2 renderer: floor texture, extruded walls, route
                    polyline, user dot, destination pin, staircase/elevator icons

planning/           pure-Kotlin/JVM, no Android deps — builds and tests off-device
  VoronoiGridPlanner  used by the app for route planning (clearance-weighted A*)
  AStarGridPlanner    underlying A* with optional clearance cost
  RoutePlanner        multi-floor orchestrator (not yet wired to the UI)
  Building/Grid/Route supporting data types and tests

planning/tools/
  DestinationResolver  Gemini-backed resolver: natural-language query → bounding box
```

## UI flow

1. App loads the occupancy mask from `res/raw/floor_plan_mask_labels.json` and
   renders it as a 3D scene. The map is read-only until a destination is chosen.
2. User types a natural-language destination in the search bar (e.g. "room 31" or
   "nearest staircase"). `DestinationResolver` calls the Gemini API and returns a
   `[minX,minY,maxX,maxY]` bounding box.
3. `VoronoiGridPlanner` plans a route from the current position (or the demo entry
   point) to the box centre. The route is drawn as a blue polyline on the 3D map.
4. The map unlocks for interaction: one-finger orbit/tilt, two-finger pinch-zoom,
   recenter button bottom-right.
5. As the phone moves, ARCore poses flow through `WorldPoseConsumer` →
   `updateUserOnMap` → red dot repositioned on the map in real time.

## Build prerequisites

- Android Studio with the Android SDK at `~/Library/Android/sdk`
- A `GEMINI_API_KEY` — place it in `.env` at the project root:
  ```
  GEMINI_API_KEY="your-key-here"
  ```
  The build reads `.env` automatically; the key is baked into `BuildConfig`.
- Gemini model: `gemini-3.1-flash-lite-preview` (hardcoded in `app/build.gradle`).

## Build & run on the arm64 emulator

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
export ANDROID_HOME="$HOME/Library/Android/sdk"
ADB="$ANDROID_HOME/platform-tools/adb"

# 1. Build
./gradlew :app:assembleDebug

# 2. Boot the emulator (headless)
"$ANDROID_HOME/emulator/emulator" -avd blindvision_arm64 \
    -no-window -no-audio -no-boot-anim -gpu swiftshader_indirect &
"$ADB" wait-for-device

# 3. Install + launch
"$ADB" install -r app/build/outputs/apk/debug/app-debug.apk
"$ADB" shell am start -n com.blindvision.arpose/.MainActivity

# 4. Watch the world-position stream
"$ADB" logcat -s WorldPose:I
```

ARCore is **x86-only** on the emulator. The arm64 AVD cannot produce real AR poses
and falls back to `SimulatedPoseProvider` automatically — the 3D map still renders
and you can search for destinations and see the route.

To stop the emulator: `"$ADB" emu kill`.

## Run on a physical device (real ARCore)

```bash
# Enable Developer Options + USB debugging on the phone, connect via USB.
"$ADB" devices                      # confirm the phone shows up
"$ADB" install -r app/build/outputs/apk/debug/app-debug.apk
"$ADB" shell am start -n com.blindvision.arpose/.MainActivity
"$ADB" logcat -s WorldPose:I
```

Grant the camera permission when prompted. On an ARCore-certified device the log
reads `Pose source = ARCORE` and the dot moves with the phone. Otherwise it reads
`Pose source = SIMULATED`.

> **Redmi Note 11 note:** "Redmi Note 11" covers several variants. The Pro / Pro+ /
> 11S / 11T are ARCore-certified; the global Snapdragon-680 base model ("spes") is
> generally **not**. Verify by installing *Google Play Services for AR* from the
> Play Store. If unavailable, the app falls back to the simulated provider.

## Destination resolver CLI

`app/src/main/java/com/blindvision/planning/tools/DestinationResolver.kt` also
compiles as a standalone CLI tool:

```bash
set -a; source .env; set +a
kotlinc app/src/main/java/com/blindvision/planning/tools/DestinationResolver.kt \
    -include-runtime -d /tmp/destination-resolver.jar

java -jar /tmp/destination-resolver.jar "I want to get to room 31"
java -jar /tmp/destination-resolver.jar --current 900,850 "nearest staircase"
```

Reads `GEMINI_API_KEY` or `GOOGLE_API_KEY`; `GEMINI_MODEL` overrides the default
model. `--current X,Y` uses the same pixel coordinate system as the map JSON.
Stdout is always `[minX,minY,maxX,maxY]` or `-1`.

## Run the planning tests (no Gradle / JUnit needed)

```bash
kotlinc app/src/main/java/com/blindvision/planning/ -include-runtime -d /tmp/planning.jar
java -cp /tmp/planning.jar com.blindvision.planning.PlanningTestsKt
```

## Map data

`app/src/main/res/raw/floor_plan_mask_labels.json` — the occupancy mask used at
runtime. Cell codes: `1` = free, `2` = staircase/elevator portal, `3` = wall.
The mask dimensions define the grid coordinate system shared by the planner and the
3D renderer (1 cell ≈ 0.035 m by default).
