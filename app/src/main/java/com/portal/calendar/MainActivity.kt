package com.portal.calendar

import android.app.Activity
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Surface
import android.view.View
import android.view.WindowManager
import kotlin.math.abs

/**
 * Thin fullscreen host for [BoardController]. Holds the screen on, so once
 * launched (manually or by [App] after a dream bounce) the board stays up
 * until the user exits via ✕ or Back.
 */
class MainActivity : Activity() {

    private lateinit var board: BoardController
    private var lastOrientation = Configuration.ORIENTATION_UNDEFINED
    private var lastNightMode = Configuration.UI_MODE_NIGHT_UNDEFINED
    // True when this panel's natural (rotation-0) orientation is portrait. The
    // accelerometer's axes are fixed to that natural frame, so [orientListener]
    // needs it to tell "turned sideways" from "upright". Null until the first
    // usable reading; see [latchNaturalOrientation].
    private var naturalPortrait: Boolean? = null

    private val idleHandler = Handler(Looper.getMainLooper())
    // YIELD mode: after this fires, drop keep-screen-on so the device idles into
    // its own screensaver (Immortal's photo frame, or the Portal's stock dream).
    private val yieldRunnable = Runnable {
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    /**
     * Re-assert the screen-on policy for the current idle mode. TAKEOVER/OFF
     * hold the screen on while the board is up (unchanged behavior); YIELD holds
     * it but arms a timer to release it after the configured idle period, so a
     * touch keeps the board awake and inactivity lets the screensaver take over.
     */
    private fun armScreenPolicy() {
        idleHandler.removeCallbacks(yieldRunnable)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (Screensaver.isYield(this))
            idleHandler.postDelayed(yieldRunnable, Screensaver.yieldMinutes(this) * 60_000L)
    }

    override fun onUserInteraction() {
        super.onUserInteraction()
        if (Screensaver.isYield(this)) armScreenPolicy()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        armScreenPolicy()
        // Surface over any lock layer when relaunched out of sleep by the takeover.
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        lastOrientation = resources.configuration.orientation
        lastNightMode = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        latchNaturalOrientation(lastOrientation)
        attachBoard(reopenSettings = false)
        hideSystemUi()
    }

    /**
     * In "auto" orientation the accelerometer listener flips us between explicit
     * PORTRAIT/LANDSCAPE locks, which fires a config change here (the manifest's
     * configChanges keeps us from being recreated). Rebuild the board so its
     * layout reflows tall⇄wide to match.
     */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        val night = newConfig.uiMode and Configuration.UI_MODE_NIGHT_MASK
        // Orientation change (auto-rotate) OR system night-mode change (the
        // "system" theme) both need the board rebuilt to reflow / recolor.
        if (newConfig.orientation != lastOrientation || night != lastNightMode) {
            lastOrientation = newConfig.orientation
            lastNightMode = night
            board.stop()
            attachBoard(reopenSettings = false)
            hideSystemUi()
        }
    }

    /**
     * Works out whether the panel's NATURAL orientation (the one at display
     * rotation 0, which is the frame the accelerometer reports in) is portrait.
     *
     * The window's current orientation is natural XOR the display being turned
     * a quarter-turn (ROTATION_90/270), so natural-portrait is "currently
     * portrait" XOR "rotated". The gen-1 Portal+ is natural-landscape; the
     * Portal Gen 2 10" evidently isn't, which is why the raw-axis mapping in
     * [orientListener] came out inverted there.
     *
     * Latched ONCE (first valid reading wins), never re-derived: it's a fixed
     * property of the hardware, and re-reading after our own explicit
     * PORTRAIT/LANDSCAPE locks could oscillate on firmware that doesn't report
     * the display rotation faithfully (lock → re-read flips the mapping → lock
     * the other way → …). A wrong-but-stable answer is recoverable with the
     * "swap" setting; flapping isn't. UNDEFINED/SQUARE say nothing about the
     * axis, so those leave it unset for a later call (onResume) to latch.
     */
    @Suppress("DEPRECATION") // Activity.display is API 30+; minSdk is 28
    private fun latchNaturalOrientation(configOrientation: Int) {
        if (naturalPortrait != null) return
        val portraitNow = when (configOrientation) {
            Configuration.ORIENTATION_PORTRAIT -> true
            Configuration.ORIENTATION_LANDSCAPE -> false
            else -> return
        }
        val rotation = windowManager.defaultDisplay.rotation
        val quarterTurned = rotation == Surface.ROTATION_90 || rotation == Surface.ROTATION_270
        naturalPortrait = portraitNow != quarterTurned
    }

    /**
     * Honors the configured orientation. "auto" is special: this device's
     * rotation policy ignores sensor/USER rotation for apps (it's locked as a
     * fixed display), but it DOES honor an explicit PORTRAIT/LANDSCAPE request.
     * So in auto mode we read the accelerometer ourselves ([orientListener])
     * and set an explicit lock to match how the Portal is physically turned.
     * The raw sensor axes belong to the panel's natural orientation, so the
     * listener maps them through [naturalPortrait] (unset is treated as
     * natural-landscape, the gen-1 behavior) rather than assuming it.
     */
    private fun applyOrientation() {
        requestedOrientation = when (App.instance.store.orientation()) {
            "portrait" -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            "landscape" -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            // auto: keep whatever the accelerometer listener has chosen so a
            // rebuild doesn't fight it; default to landscape until it speaks.
            else -> if (requestedOrientation == ActivityInfo.SCREEN_ORIENTATION_PORTRAIT ||
                        requestedOrientation == ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE)
                requestedOrientation
            else ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        }
    }

    // --- accelerometer-driven auto-rotate (auto mode only) ---
    private val sensorMgr by lazy { getSystemService(SensorManager::class.java) }
    private var accelRegistered = false
    private var pendingOrient = 0
    private var pendingCount = 0

    private val orientListener = object : SensorEventListener {
        override fun onSensorChanged(e: SensorEvent) {
            val ax = abs(e.values[0]); val ay = abs(e.values[1])
            if (ax + ay < 4.5f) return // lying too flat to tell — leave as-is
            // Gravity along the natural Y axis means the Portal is held the way
            // its panel is naturally built (landscape on a gen-1, portrait on
            // a natural-portrait panel); along natural X it's on its side.
            val np = naturalPortrait == true // not latched yet → natural-landscape, as before
            val natural = if (np) ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                          else ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            val sideways = if (np) ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                           else ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            var want = when {
                ax > ay + 2f -> sideways
                ay > ax + 2f -> natural
                else -> return // ~45° dead zone, avoid flapping
            }
            // Safety net for hardware whose sensor is mounted off-axis from the
            // panel (so detection above can't know): the user-set "swap" flips
            // the answer. Read per reading so toggling it applies on the next
            // sample; if it now disagrees with the current lock, the debounce
            // below flips us — no restart, nothing left stuck on the old lock.
            if (App.instance.store.orientationSwap())
                want = if (want == ActivityInfo.SCREEN_ORIENTATION_PORTRAIT)
                    ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                else ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            if (want == requestedOrientation) { pendingCount = 0; return }
            // Require a few consistent readings before flipping (debounce jitter).
            if (want == pendingOrient) {
                if (++pendingCount >= 6) {
                    pendingCount = 0
                    requestedOrientation = want // explicit lock → display rotates → onConfigurationChanged → reflow
                }
            } else { pendingOrient = want; pendingCount = 1 }
        }
        override fun onAccuracyChanged(s: Sensor?, a: Int) {}
    }

    private fun updateSensorListener() {
        val auto = App.instance.store.orientation() == "auto"
        if (auto && !accelRegistered) {
            sensorMgr?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let {
                sensorMgr?.registerListener(orientListener, it, SensorManager.SENSOR_DELAY_UI)
                accelRegistered = true
            }
        } else if (!auto && accelRegistered) {
            sensorMgr?.unregisterListener(orientListener); accelRegistered = false
        }
    }

    /**
     * (Re)build the board in place — one frame swap instead of an activity
     * recreate, so committing a new display size doesn't flash.
     */
    private fun attachBoard(reopenSettings: Boolean) {
        applyOrientation() // re-applied on every (re)build, incl. orientation changes
        board = BoardController(this)
        board.onExit = { goHome() }
        board.onScaleCommitted = { fromSettings ->
            board.stop()
            attachBoard(reopenSettings = fromSettings)
        }
        setContentView(board.view)
        board.start()
        if (reopenSettings) board.openSettings()
        updateSensorListener() // (de)register accel watch to match the current mode
    }

    override fun onResume() {
        super.onResume()
        // Fallback only: a no-op once latched; matters if onCreate's reading
        // was UNDEFINED/SQUARE.
        latchNaturalOrientation(resources.configuration.orientation)
        updateSensorListener()
        armScreenPolicy() // re-arm after waking back from the screensaver
    }

    override fun onPause() {
        if (accelRegistered) { sensorMgr?.unregisterListener(orientListener); accelRegistered = false }
        idleHandler.removeCallbacks(yieldRunnable)
        super.onPause()
    }

    override fun onDestroy() {
        idleHandler.removeCallbacks(yieldRunnable)
        board.stop()
        super.onDestroy()
    }

    /** Relaunched by the idle takeover — always land on the current week. */
    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        board.resetView()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) { hideSystemUi(); armScreenPolicy() } // dream dismissed → board awake again
    }

    override fun onBackPressed() {
        if (!board.closeOverlays()) goHome()
    }

    /**
     * Explicit home intent instead of finish(): after a dream bounce,
     * Immortal's relaunched photo frame may sit directly beneath this
     * activity — finish() would reveal it instead of the launcher.
     */
    private fun goHome() {
        runCatching {
            startActivity(Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_HOME)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        finish()
    }

    private fun hideSystemUi() {
        window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
            View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
    }
}
