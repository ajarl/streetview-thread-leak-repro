package com.example.svthreadleak

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.gms.maps.StreetViewPanorama
import com.google.android.gms.maps.StreetViewPanoramaView
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.StreetViewSource
import java.io.File

/**
 * Reproduces a thread leak in StreetViewPanorama: every panorama it loads starts a new pool of four
 * "androidmapsapi-sv-mesh-N" threads that is never shut down, not even after the view is destroyed.
 *
 * - "Next location" jumps to another city.
 * - "Walk" follows the first link of the current panorama every 2 seconds, like a user tapping the
 *   arrows.
 * - "Destroy view" calls onPause/onStop/onDestroy, removes the view and runs a GC; "Create view" adds
 *   a new one.
 *
 * The overlay and logcat (tag SvThreadLeak) show the live thread counts.
 */
class MainActivity : Activity() {

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var container: FrameLayout
    private lateinit var stats: TextView
    private lateinit var walkButton: Button

    private var streetView: StreetViewPanoramaView? = null
    private var panorama: StreetViewPanorama? = null
    private var locationIndex = 0
    private var started = false
    private var resumed = false
    private var walking = false
    private var panoramasLoaded = 0
    private var lastLoggedSvMesh = -1

    private val refreshStats = object : Runnable {
        override fun run() {
            updateStats()
            handler.postDelayed(this, 1_000)
        }
    }

    private val walkStep = object : Runnable {
        override fun run() {
            if (!walking) return
            val link = panorama?.location?.links?.firstOrNull()
            if (link != null) {
                panorama?.setPosition(link.panoId)
            } else {
                nextLocation()
            }
            handler.postDelayed(this, 2_000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        container = FrameLayout(this)
        stats = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            textSize = 12f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.argb(200, 0, 0, 0))
            setPadding(24, 16, 24, 16)
        }
        walkButton = button("Walk") { toggleWalk() }
        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(button("Next location") { nextLocation() }, weighted())
            addView(walkButton, weighted())
            addView(button("Destroy view") { destroyStreetView() }, weighted())
            addView(button("Create view") { createStreetView() }, weighted())
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
            addView(container, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
            addView(stats, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            addView(buttons, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            setOnApplyWindowInsetsListener { view, insets ->
                @Suppress("DEPRECATION")
                view.setPadding(0, insets.systemWindowInsetTop, 0, insets.systemWindowInsetBottom)
                insets
            }
        }
        setContentView(root)
        createStreetView()
    }

    override fun onStart() {
        super.onStart()
        started = true
        streetView?.onStart()
        handler.post(refreshStats)
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        streetView?.onResume()
    }

    override fun onPause() {
        resumed = false
        streetView?.onPause()
        super.onPause()
    }

    override fun onStop() {
        started = false
        streetView?.onStop()
        handler.removeCallbacks(refreshStats)
        super.onStop()
    }

    override fun onDestroy() {
        walking = false
        handler.removeCallbacksAndMessages(null)
        streetView?.onDestroy()
        super.onDestroy()
    }

    override fun onLowMemory() {
        super.onLowMemory()
        streetView?.onLowMemory()
    }

    private fun createStreetView() {
        if (streetView != null) return
        val view = StreetViewPanoramaView(this)
        view.onCreate(null)
        if (started) view.onStart()
        if (resumed) view.onResume()
        container.addView(view, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        streetView = view
        log("Created StreetViewPanoramaView")
        view.getStreetViewPanoramaAsync { streetViewPanorama ->
            panorama = streetViewPanorama
            streetViewPanorama.setOnStreetViewPanoramaChangeListener { location ->
                if (location != null) {
                    panoramasLoaded++
                    log("Panorama loaded: ${location.panoId}")
                }
            }
            showLocation()
        }
    }

    private fun destroyStreetView() {
        val view = streetView ?: return
        setWalking(false)
        view.onPause()
        view.onStop()
        view.onDestroy()
        container.removeView(view)
        streetView = null
        panorama = null
        log("Destroyed StreetViewPanoramaView")
        Runtime.getRuntime().gc()
    }

    private fun nextLocation() {
        locationIndex = (locationIndex + 1) % LOCATIONS.size
        showLocation()
    }

    private fun showLocation() {
        panorama?.setPosition(LOCATIONS[locationIndex], 100, StreetViewSource.OUTDOOR)
    }

    private fun toggleWalk() = setWalking(!walking)

    private fun setWalking(enabled: Boolean) {
        walking = enabled
        walkButton.text = if (enabled) "Stop" else "Walk"
        handler.removeCallbacks(walkStep)
        if (enabled) handler.post(walkStep)
    }

    private fun updateStats() {
        val javaThreads = Thread.getAllStackTraces().keys.filter { it.isAlive }
        val svMesh = javaThreads.count { it.name.startsWith(SV_MESH_PREFIX) }
        val mapsThreads = javaThreads.count { it.name.startsWith("androidmapsapi-") }
        val nativeThreads = File("/proc/self/task").list()?.size ?: -1
        val topGroups = javaThreads
            .groupingBy { it.name.replace(Regex("\\d+"), "#") }
            .eachCount()
            .entries
            .sortedByDescending { it.value }
            .take(4)
            .joinToString("\n") { (name, count) -> "  %4d  %s".format(count, name) }
        stats.text = buildString {
            appendLine("view: ${if (streetView != null) "alive" else "destroyed"}   panoramas loaded: $panoramasLoaded")
            appendLine("process threads (/proc/self/task): $nativeThreads")
            appendLine("java threads: ${javaThreads.size}   androidmapsapi-*: $mapsThreads")
            appendLine("$SV_MESH_PREFIX*: $svMesh")
            append(topGroups)
        }
        if (svMesh != lastLoggedSvMesh) {
            lastLoggedSvMesh = svMesh
            log("threads: process=$nativeThreads java=${javaThreads.size} svMesh=$svMesh panoramasLoaded=$panoramasLoaded")
        }
    }

    private fun log(message: String) {
        Log.i(TAG, message)
    }

    private fun button(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        textSize = 12f
        setOnClickListener { onClick() }
    }

    private fun weighted() = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)

    private companion object {
        const val TAG = "SvThreadLeak"
        const val SV_MESH_PREFIX = "androidmapsapi-sv-mesh-"

        val LOCATIONS = listOf(
            LatLng(48.858093, 2.294694), // Paris
            LatLng(40.758896, -73.985130), // New York
            LatLng(35.659487, 139.700560), // Tokyo
            LatLng(59.332580, 18.064900), // Stockholm
            LatLng(-33.856784, 151.215297), // Sydney
            LatLng(51.500729, -0.124625), // London
        )
    }
}
