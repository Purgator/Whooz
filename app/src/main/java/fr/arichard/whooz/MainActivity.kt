package fr.arichard.whooz

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import fr.arichard.whooz.core.Mode
import fr.arichard.whooz.core.Settings
import fr.arichard.whooz.core.Timings
import fr.arichard.whooz.finger.FingerChooserView
import fr.arichard.whooz.ui.Chrome
import fr.arichard.whooz.ui.Haptics
import fr.arichard.whooz.ui.UpdateBanner
import fr.arichard.whooz.update.UpdateManager

/**
 * The launcher screen: the finger arena with its ghost chrome. Everything the user can tap
 * lives here; the arena only reports state through [FingerChooserView.Listener].
 *
 * Rules enforced by this class: the chrome exists only in IDLE with no finger down (a landing
 * finger must never hit a button), the window is sticky immersive while playing, the screen
 * stays on while rounds happen and goes back to the system timeout after three idle minutes,
 * and the back gesture cancels a round before it can leave the app.
 */
class MainActivity : AppCompatActivity(), FingerChooserView.Listener {

    private lateinit var settings: Settings
    private lateinit var arena: FingerChooserView
    private lateinit var chrome: Chrome
    private lateinit var banner: UpdateBanner
    private lateinit var hint: TextView
    private lateinit var pill: TextView
    private lateinit var caption: TextView
    private lateinit var insetsController: WindowInsetsControllerCompat

    private val handler = Handler(Looper.getMainLooper())
    private val keepScreenOffRunnable = Runnable { releaseKeepScreenOn() }
    private val captionFadeRunnable = Runnable { fadeCaption() }

    private var hasCamera = false
    private var keepScreenOn = true

    /**
     * Mirror of FLAG_KEEP_SCREEN_ON on our window. `Window.addFlags` never compares with the
     * current flags: every call forces a WindowManager relayout and a full traversal, which
     * would land in the very frame that must draw the first finger's circle, so the flag is
     * touched only when this mirror says it changes.
     */
    private var keepScreenOnHeld = false
    private var mode: Mode = Mode.DEFAULT
    private var roundsCompleted = 0

    /** True when onCreate handed over to a camera screen: nothing below was initialised. */
    private var redirected = false

    private val backCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            arena.cancelRound()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = Settings(this)
        hasCamera = packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)

        // Mode memory: a camera-only group opens straight into its camera screen, zero friction.
        val lastMode = settings.lastMode
        if (savedInstanceState == null && hasCamera && settings.rememberLastMode &&
            lastMode != Settings.ScreenMode.FINGER
        ) {
            redirected = true
            val target = if (lastMode == Settings.ScreenMode.ROLLCALL) RollCallActivity::class.java else CameraActivity::class.java
            // This activity is singleTask and finishes right away, so the task's root record
            // is gone while CameraActivity lives on. A later launcher tap finds the task but
            // no MainActivity to deliver to and creates a fresh one ON TOP of that camera
            // screen; without these flags its redirect would stack a second CameraActivity
            // (and a third on the next tap). CLEAR_TOP | SINGLE_TOP instead collapses the
            // task onto the existing CameraActivity, which gets onNewIntent and resumes.
            startActivity(
                Intent(this, target)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            )
            // Camera-only groups never show this screen, so the daily update check would
            // never run for them: kick it off here too (download only; the banner shows the
            // next time the finger screen is on screen).
            startDailyCheck(offer = false)
            finish()
            return
        }

        WindowCompat.setDecorFitsSystemWindows(window, false)
        insetsController = WindowInsetsControllerCompat(window, window.decorView)
        setContentView(R.layout.activity_main)

        arena = findViewById(R.id.arena)
        hint = findViewById(R.id.hint)
        pill = findViewById(R.id.mode_pill)
        caption = findViewById(R.id.caption)
        val chromeLayer = findViewById<FrameLayout>(R.id.chrome_layer)
        val btnSettings = findViewById<ImageButton>(R.id.btn_settings)
        val btnCamera = findViewById<ImageButton>(R.id.btn_camera)
        val btnRollCall = findViewById<ImageButton>(R.id.btn_rollcall)

        applyInsetsAsPadding(chromeLayer)

        btnSettings.setOnClickListener { startActivity(Intent(this, SettingsActivity::class.java)) }
        if (hasCamera) {
            btnCamera.setOnClickListener {
                settings.lastMode = Settings.ScreenMode.CAMERA
                startActivity(Intent(this, CameraActivity::class.java))
            }
            btnRollCall.setOnClickListener {
                settings.lastMode = Settings.ScreenMode.ROLLCALL
                startActivity(Intent(this, RollCallActivity::class.java))
            }
        } else {
            btnCamera.visibility = View.GONE
            btnRollCall.visibility = View.GONE
        }
        pill.setOnClickListener { cycleMode() }
        pill.setOnLongClickListener {
            selectMode(Mode.DEFAULT)
            true
        }

        chrome = Chrome(listOf(btnSettings, btnCamera, btnRollCall, pill))
        banner = UpdateBanner(this, chromeLayer)
        arena.listener = this
        onBackPressedDispatcher.addCallback(this, backCallback)

        showFirstLaunchCaption()
    }

    /** The chrome layer pads itself by the cutout and bar insets; the arena stays edge-to-edge. */
    private fun applyInsetsAsPadding(layer: View) {
        ViewCompat.setOnApplyWindowInsetsListener(layer) { v, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout(),
            )
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
    }

    override fun onResume() {
        super.onResume()
        if (redirected) return
        applyImmersive()

        // One immutable snapshot of the preferences per resume; the draw loop never reads them.
        arena.setTimings(Timings.finger(settings.stabilization))
        arena.setHaptics(Haptics(this, settings.haptics))
        keepScreenOn = settings.keepScreenOn
        if (keepScreenOn) armKeepScreenOn()
        roundsCompleted = settings.completedRounds
        mode = settings.fingerMode
        arena.setMode(mode)
        updatePill()
        applyHint(arena.hintState)
        onChromeAllowed(arena.isChromeAllowed)

        startUpdateChecks()
    }

    override fun onPause() {
        if (!redirected) {
            arena.resetAll()
            banner.onHostPaused()
            handler.removeCallbacks(keepScreenOffRunnable)
            releaseKeepScreenOn()
        }
        super.onPause()
    }

    override fun onDestroy() {
        handler.removeCallbacks(captionFadeRunnable)
        handler.removeCallbacks(keepScreenOffRunnable)
        super.onDestroy()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && !redirected) applyImmersive()
    }

    /** Sticky immersive: bars hidden, a swipe shows them transiently, then they hide again. */
    private fun applyImmersive() {
        insetsController.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        insetsController.hide(WindowInsetsCompat.Type.systemBars())
    }

    /**
     * Keeps the screen on for three minutes after the last touch, then lets the system
     * timeout apply so a phone forgotten on the table does not burn its battery.
     */
    private fun armKeepScreenOn() {
        if (!keepScreenOn) return
        if (!keepScreenOnHeld) {
            keepScreenOnHeld = true
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        // Rescheduling is a cheap message-queue swap; the flag itself stays as it is.
        handler.removeCallbacks(keepScreenOffRunnable)
        handler.postDelayed(keepScreenOffRunnable, KEEP_SCREEN_ON_MS)
    }

    private fun releaseKeepScreenOn() {
        if (!keepScreenOnHeld) return
        keepScreenOnHeld = false
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    // ---- Chrome ---------------------------------------------------------------------------

    private fun cycleMode() {
        val modes = Mode.FINGER_MODES
        val i = modes.indexOf(mode)
        selectMode(modes[(i + 1) % modes.size])
    }

    private fun selectMode(next: Mode) {
        if (next == mode) return
        mode = next
        settings.fingerMode = next
        arena.setMode(next)
        updatePill()
    }

    private fun updatePill() {
        pill.setText(labelFor(mode))
    }

    private fun labelFor(mode: Mode): Int = when (mode) {
        is Mode.Winner -> when (mode.count) {
            1 -> R.string.mode_winner_1
            2 -> R.string.mode_winner_2
            else -> R.string.mode_winner_3
        }
        Mode.Order -> R.string.mode_order
        is Mode.Teams -> if (mode.count == 2) R.string.mode_teams_2 else R.string.mode_teams_3
    }

    /**
     * Shown once per install for 2.5 s: the one thing a first-time group cannot guess is that
     * the bottom edge belongs to the system. Not part of the chrome set so the two fades never
     * fight over its alpha.
     */
    private fun showFirstLaunchCaption() {
        if (settings.firstLaunchHintShown) return
        settings.firstLaunchHintShown = true
        caption.alpha = 1f
        caption.visibility = View.VISIBLE
        handler.postDelayed(captionFadeRunnable, CAPTION_MS)
    }

    private fun fadeCaption() {
        caption.animate().alpha(0f).setDuration(CAPTION_FADE_MS).withEndAction { caption.visibility = View.GONE }.start()
    }

    private fun applyHint(state: Int) {
        when (state) {
            FingerChooserView.HINT_IDLE -> {
                // Once a group has played, the idle hint becomes a quiet reminder.
                val short = roundsCompleted > 0
                hint.setText(if (short) R.string.hint_idle_short else R.string.hint_idle)
                hint.setTextSize(
                    TypedValue.COMPLEX_UNIT_PX,
                    resources.getDimension(if (short) R.dimen.hint_text_small else R.dimen.hint_text),
                )
                hint.alpha = if (short) HINT_ALPHA_SHORT else HINT_ALPHA
                hint.visibility = View.VISIBLE
            }
            FingerChooserView.HINT_ONE_MORE -> showHint(R.string.hint_one_more)
            FingerChooserView.HINT_ARMING -> showHint(R.string.hint_hold_still)
            FingerChooserView.HINT_RESULT_HOLD -> showHint(R.string.hint_lift_to_replay)
            FingerChooserView.HINT_TOO_MANY -> showHint(R.string.hint_too_many)
            else -> hint.visibility = View.INVISIBLE
        }
    }

    private fun showHint(resId: Int) {
        hint.setText(resId)
        hint.setTextSize(TypedValue.COMPLEX_UNIT_PX, resources.getDimension(R.dimen.hint_text))
        hint.alpha = HINT_ALPHA
        hint.visibility = View.VISIBLE
    }

    // ---- FingerChooserView.Listener -----------------------------------------------------

    override fun onHintChanged(state: Int) {
        applyHint(state)
    }

    override fun onChromeAllowed(allowed: Boolean) {
        chrome.setVisible(allowed)
        banner.setAllowed(allowed)
        // Back leaves the app only when there is nothing to cancel.
        backCallback.isEnabled = !allowed
    }

    override fun onRoundCompleted() {
        roundsCompleted++
        settings.completedRounds = roundsCompleted
    }

    override fun onTouchActivity() {
        armKeepScreenOn()
    }

    // ---- Updates --------------------------------------------------------------------------

    /**
     * The daily GitHub check runs once per process after the first frame; every resume also
     * re-offers an APK that is already downloaded (the banner may have been dismissed by a
     * round) and resumes an install that bounced to the unknown-sources screen.
     *
     * The retry goes first: when it starts the install, the strip must stay down, so the
     * re-offer thread is not started at all (the banner also ignores offers while installing,
     * which covers the daily thread started on an earlier resume).
     */
    private fun startUpdateChecks() {
        val app = applicationContext
        if (!dailyCheckStarted) arena.post { startDailyCheck(offer = true) }
        if (banner.retryIfPending()) return
        runDaemon("whooz-update-ready") {
            val ready = UpdateManager.readyUpdate(app)
            if (ready != null) offerOnUiThread(ready)
        }
    }

    /**
     * Once per process: the network check (and the stale-session cleanup it carries). With
     * [offer] the result goes to the banner; the redirect path has no banner and just lets
     * the download land in the cache.
     */
    private fun startDailyCheck(offer: Boolean) {
        if (dailyCheckStarted) return
        dailyCheckStarted = true
        val app = applicationContext
        runDaemon("whooz-update-check") {
            val ready = UpdateManager.maybeDailyCheck(app)
            if (offer && ready != null) offerOnUiThread(ready)
        }
    }

    private fun offerOnUiThread(ready: UpdateManager.ReadyUpdate) {
        runOnUiThread {
            if (!isFinishing && !isDestroyed) banner.offer(ready)
        }
    }

    private fun runDaemon(name: String, body: () -> Unit) {
        val thread = Thread(body, name)
        thread.isDaemon = true
        thread.start()
    }

    companion object {
        /** Screen stays on this long after the last touch. */
        private const val KEEP_SCREEN_ON_MS = 3L * 60L * 1000L
        private const val CAPTION_MS = 2_500L
        private const val CAPTION_FADE_MS = 400L
        private const val HINT_ALPHA = 0.85f
        private const val HINT_ALPHA_SHORT = 0.55f

        /** Once per process, not per activity instance. */
        @Volatile
        private var dailyCheckStarted = false
    }
}
