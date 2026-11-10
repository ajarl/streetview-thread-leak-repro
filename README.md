# StreetViewPanorama thread leak reproducer

Every panorama a `StreetViewPanorama` loads starts a new `ThreadPoolExecutor` with four core
threads, named `androidmapsapi-sv-mesh-1` to `androidmapsapi-sv-mesh-4`. The pools are never shut
down. Their threads stay parked in `LinkedBlockingQueue.take()` for the rest of the process's life,
even after `onPause()`, `onStop()` and `onDestroy()` on the `StreetViewPanoramaView`, removing the
view, and a GC.

An app that shows Street View for a long time, where the user moves around or the app sets many
positions, keeps adding threads until thread creation fails:

```
java.lang.OutOfMemoryError: pthread_create (1040KB stack) failed: Try again
```

## Setup

1. Put a Maps SDK for Android key in `local.properties`:
   ```
   MAPS_API_KEY=your-key
   ```
2. Build and install: `./gradlew :app:installDebug`

The app has a full-screen `StreetViewPanoramaView` and an overlay that refreshes every second
with the process's thread counts. Logcat tag `SvThreadLeak` logs every change:

```
adb logcat -s SvThreadLeak:I
```

## Steps

1. Launch the app. The first panorama loads, and `androidmapsapi-sv-mesh-*` goes from 0 to 4.
2. Tap **Walk**. Every 2 seconds the app calls `setPosition(panoId)` with the first link of the
   current panorama, like a user tapping the arrows. Each new panorama adds 4 more `sv-mesh`
   threads. **Next location**, which calls `setPosition(LatLng, radius, source)`, does the same.
3. Tap **Destroy view**. The app calls `onPause()`, `onStop()` and `onDestroy()`, removes the view
   from the hierarchy and calls `Runtime.gc()`. The `sv-mesh` count doesn't go down.
4. Tap **Create view**. A new `StreetViewPanoramaView` loads its first panorama, and 4 more threads
   are added on top of the old ones.

## Observed

Pixel 10a emulator, Android 17 (API 37), Google Play services 26.40.32, maps dynamite module
263020511, `play-services-maps` 20.0.0:

```
Created StreetViewPanoramaView
threads: process=40  java=37  svMesh=0  panoramasLoaded=0
threads: process=48  java=44  svMesh=4  panoramasLoaded=0
threads: process=50  java=46  svMesh=8  panoramasLoaded=2
...
threads: process=116 java=112 svMesh=72 panoramasLoaded=18
threads: process=126 java=122 svMesh=84 panoramasLoaded=20
Destroyed StreetViewPanoramaView        <- count stays at 84
Created StreetViewPanoramaView
threads: process=132 java=128 svMesh=88 panoramasLoaded=22
```

Pixel 10a (physical), Android 17 (CP3A.261005.005), Google Play services 26.43.30: the same
result. 25 panoramas left 104 `sv-mesh` threads, all 104 were still there 20 seconds after
Destroy view, and a new view added 4 more (108).

Every leaked thread has the same stack:

```
"androidmapsapi-sv-mesh-1" waiting
  jdk.internal.misc.Unsafe.park
  java.util.concurrent.locks.LockSupport.park
  java.util.concurrent.locks.AbstractQueuedSynchronizer$ConditionObject.await
  java.util.concurrent.LinkedBlockingQueue.take
  java.util.concurrent.ThreadPoolExecutor.getTask
  java.util.concurrent.ThreadPoolExecutor.runWorker
  java.util.concurrent.ThreadPoolExecutor$Worker.run
  java.lang.Thread.run
```

## Expected

Panorama mesh loading reuses a fixed pool, or shuts down a pool when it is no longer needed, at the
latest when the `StreetViewPanoramaView` is destroyed.
