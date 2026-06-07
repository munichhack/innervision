# BlindVision ARPose

A sample Android app that reads **ARCore world positions (6-DoF poses)** and feeds
them to a downstream consumer. It is structured so the same pipeline runs both on
a real ARCore phone (genuine tracking) and on the **Apple-Silicon arm64 emulator**
(synthetic poses), via a single `PoseProvider` abstraction.

## Why the abstraction exists

ARCore's emulator support is **x86-only**. Apple-Silicon Macs can only
hardware-accelerate **arm64** system images, and ARCore explicitly does *not*
support arm64 emulators — so a fast emulator on this machine cannot produce a
real AR pose. Rather than force a slow, often-unbootable x86 image, pose-reading
is hidden behind `PoseProvider`:

| Environment | Provider | Source of poses |
|---|---|---|
| Certified phone (e.g. Redmi Note 11 *if* ARCore-certified) | `ArCorePoseProvider` | Real ARCore `frame.camera.pose` |
| arm64 emulator / uncertified device | `SimulatedPoseProvider` | Synthetic Lissajous trajectory @ ~30 Hz |

The downstream application (`WorldPoseConsumer`) is identical in both cases.

> **Redmi Note 11 note:** "Redmi Note 11" covers two different phones. The Pro / Pro+ /
> 11S / 11T variants are ARCore-certified; the global Snapdragon-680 base model
> ("spes") generally is **not**. Verify by trying to install *Google Play Services
> for AR* from the Play Store on the device. If ARCore is unavailable the app simply
> uses the simulated provider.

## Architecture

```
PoseProvider (interface)
 ├─ ArCorePoseProvider   real ARCore Session + GLSurfaceView renderer → frame.camera.pose
 └─ SimulatedPoseProvider HandlerThread emitting a moving 6-DoF trajectory

Pose6Dof            immutable {translation, quaternion, timestamp, source}
WorldPoseConsumer   the "downstream app": path length, speed; structured logcat
MainActivity        picks the provider via ArCoreApk.checkAvailability(), wires UI
```

## Build & run on the emulator

Prerequisites (already installed on this machine): Android Studio, the Android SDK
at `~/Library/Android/sdk`, an arm64 system image, and an AVD named
`blindvision_arm64`.

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

Example output (emulator → simulated provider):

```
WorldPose: src=SIMULATED n=60 pos=[ 1.740, 0.057, 0.806]m quat=[0,0.410,0,0.912] yaw=48.4 ... path=1.763m speed=0.540m/s
```

To stop the emulator: `"$ADB" emu kill`.

## Resolve a spoken destination

`tools/DestinationResolver.kt` is a small Gemini-backed CLI that maps a user
request to a floor-plan bounding box from `json_map/message.json`. It prints only
`[minX,minY,maxX,maxY]` or `-1`, where `-1` means the request was not understood.

```bash
set -a; source .env; set +a
kotlinc tools/DestinationResolver.kt -include-runtime -d /tmp/destination-resolver.jar

java -jar /tmp/destination-resolver.jar "I want to get to room 31"
java -jar /tmp/destination-resolver.jar --current 900,850 "nearest staircase"
```

The script reads `GEMINI_API_KEY` or `GOOGLE_API_KEY`; `GEMINI_MODEL` is optional
and defaults to `gemini-2.5-flash-lite`. `--current X,Y` should use the same
pixel coordinate system as `message.json`.

## Run on a physical Redmi Note 11 (real ARCore)

```bash
# Enable Developer Options + USB debugging on the phone, connect via USB.
"$ADB" devices                      # confirm the phone shows up
"$ADB" install -r app/build/outputs/apk/debug/app-debug.apk
"$ADB" shell am start -n com.blindvision.arpose/.MainActivity
"$ADB" logcat -s WorldPose:I
```

Grant the camera permission when prompted. If the device is ARCore-certified the
status reads `Source: ARCORE` and the poses are real; move the phone and `pos`
changes. Otherwise it falls back to `Source: SIMULATED`.

## Consuming world positions downstream

Replace or extend `WorldPoseConsumer.onPose(Pose6Dof)` — every pose carries world
translation (meters), an orientation quaternion, a timestamp, and its source. That
is the single integration point for any downstream application.
```
