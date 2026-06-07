# planning — multi-floor grid path planning

Standalone module (package `com.blindvision.planning`). **Pure Kotlin / JVM, no
Android dependencies**, and it references no other package in this project — so
it builds with the app, runs/tests off-device, and could be extracted into its
own Gradle module later.

## What it does

Given a building (a stack of floors) and a `(start, target)` request, it produces
a **`Route` = ordered list of segments**:

- target on the current floor → a single `WalkSegment` to it.
- target on another floor → `WalkSegment` to an elevator/stairs, a
  `TransitionSegment` (the ride), and a walk on the arrival floor — **transferring
  between shafts** (walk + ride + walk + ride + walk) when no single shaft spans
  both floors.

## Pieces

| File | Role |
|---|---|
| `Grid.kt` | `CellType` (walkable / non-walkable / portal), `Floor`, positions |
| `Building.kt` | `BuildingGrid`: detects portal regions + links them into shafts |
| `BuildingLoader.kt` | adapts the raw "list of 3D arrays" input (codes or channels) |
| `GridPlanner.kt` | per-segment planner **interface** (the swap point) |
| `AStarGridPlanner.kt` | A* backend, 8-connectivity, optional clearance cost |
| `Route.kt` | `Segment` (`WalkSegment` / `TransitionSegment`), `Route` |
| `RoutePlanner.kt` | the multi-floor orchestrator (high-level graph + Dijkstra) |
| `MockBuilding.kt` | a 3-floor mock used by tests/demos |
| `PlanningTests.kt` | self-contained test runner (`main`) over the mock |

## Run the tests (no Gradle/JUnit needed)

```bash
kotlinc app/src/main/java/com/blindvision/planning/ -include-runtime -d /tmp/planning.jar
java -cp /tmp/planning.jar com.blindvision.planning.PlanningTestsKt
```

## Why A* (not the C++ GVG repo)

ARCore aside, this is plain grid pathfinding: pure-Kotlin A* runs in well under a
millisecond at building scale, whereas wiring the C++ `gvg` repo in would require
the NDK + JNI + CMake (`externalNativeBuild` edits to `app/build.gradle`, per-ABI
native builds, array marshalling across the JNI boundary) for no practical gain.
GVG's real benefit for guidance — paths that stay **centered, away from walls** —
is captured here by `AStarGridPlanner(clearanceWeight = …)`. If a true Voronoi
skeleton is ever needed, implement `GridPlanner` with a native/Kotlin GVG backend;
nothing else changes.

## Assumptions (easy to change)

- **Shaft linkage:** a portal occupies the same `(x, y)` footprint on every floor
  it connects; portal regions on different floors that overlap in `(x, y)` are one
  shaft (elevators may skip floors). Adjust in `Building.kt` if your data links
  shafts differently (e.g. explicit IDs).
- **Type codes:** `0 = walkable, 1 = non-walkable, 2 = portal` (override the
  classifier in `BuildingLoader`).
- **Floor transition cost** is a constant per floor crossed (`RoutePlanner`).
