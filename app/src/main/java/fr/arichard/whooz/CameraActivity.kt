package fr.arichard.whooz

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings as SystemSettings
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import fr.arichard.whooz.camera.CameraSession
import fr.arichard.whooz.camera.FaceFrame
import fr.arichard.whooz.camera.FaceOverlayView
import fr.arichard.whooz.camera.FaceSource
import fr.arichard.whooz.core.Mode
import fr.arichard.whooz.core.Settings
import fr.arichard.whooz.core.Timings
import fr.arichard.whooz.ui.Chrome
import fr.arichard.whooz.ui.Haptics

/**
 * Camera mode: the selfie preview, the [FaceOverlayView] on top, and the thin host logic
 * around them: runtime permission, [CameraSession] ownership, ML Kit availability banner,
 * chrome visibility, hints, sub-mode pill and the debug toggle.
 *
 * Nothing is bound before CAMERA is granted; CameraX unbinds through the lifecycle in
 * `onStop`, `onPause` resets the overlay and stops the analyzer so no round can start or
 * survive a pause (the camera keeps streaming while the activity is merely paused: Recents, a
 * dialog, an incoming call), and `onDestroy` releases the session (detector, analysis thread).
 * The back key simply finishes: the last mode stays CAMERA so a camera-only group reopens
 * straight into this screen; only the finger icon writes FINGER back.
 *
 * Screen and battery rules mirror [MainActivity]: the screen stays on for three minutes after
 * the last interaction (tap, change of the confirmed-face set, round start, as reported by the
 * overlay), then falls back to the system timeout; the flag is also dropped when the overlay
 * puts the camera to sleep, since a released camera has nothing to keep the screen on for.
 */
class CameraActivity : AppCompatActivity(), FaceOverlayView.Host, FaceSource.Listener, CameraSession.Listener {

    private lateinit var settings: Settings
    private lateinit var session: CameraSession
    private lateinit var previewView: PreviewView
    private lateinit var overlay: FaceOverlayView
    private lateinit var hint: TextView
    private lateinit var caption: TextView
    private lateinit var banner: View
    private lateinit var bannerText: TextView
    private lateinit var bannerAction: View
    private lateinit var permissionCard: View
    private lateinit var permissionAllow: View
    private lateinit var permissionOpenSettings: View
    private lateinit var chrome: Chrome
    private lateinit var modeChips: Array<TextView>
    private lateinit var permissionLauncher: ActivityResultLauncher<String>

    /** Set after the first denial; from then on only the card's Allow button re-asks. */
    private var permissionDenied = false
    private var permissionRequested = false

    /**
     * Whether the system was still willing to show the permission dialog when it was last
     * requested. On Android 11+ `shouldShowRequestPermissionRationale` is false both before the
     * first explicit denial and after the final one, so a denial is "permanent" only when the
     * rationale flag was true before the request and false after it: the dialog was shown once
     * and the user shut the door. A first dialog dismissed with a swipe or interrupted by a call
     * reports a denial with the flag still false, and must simply be asked again.
     */
    private var rationaleBefore = false

    /**
     * Denials that came back with the rationale flag false on both sides. One is a dismissed
     * dialog; two in a row means the system is auto-denying (the door was shut in an earlier
     * session), since a dialog that was actually shown and denied sets the flag.
     */
    private var noRationaleDenials = 0

    /** True after the overlay asked for the camera to sleep; a tap on it rebinds. */
    private var sleeping = false

    private var cameraUnavailable = false

    private val handler = Handler(Looper.getMainLooper())
    private val keepScreenOffRunnable = Runnable { dropKeepScreenOn() }
    private var keepScreenOn = true

    /** Tracks the window flag locally: setting it again forces a relayout IPC for nothing. */
    private var keepScreenOnHeld = false

    private val refreshTransform = View.OnLayoutChangeListener { _, left, top, right, bottom, _, _, _, _ ->
        // The overlay shares the PreviewView's bounds: the source clips faces to this size.
        session.faceSource.setViewSize(right - left, bottom - top)
        session.refreshTransform()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = Settings(this)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_camera)

        previewView = findViewById(R.id.camera_preview)
        previewView.implementationMode = PreviewView.ImplementationMode.PERFORMANCE
        previewView.scaleType = PreviewView.ScaleType.FILL_CENTER
        session = CameraSession(this, previewView, Timings.camera(settings.stabilization))
        session.listener = this
        session.faceSource.listener = this
        previewView.addOnLayoutChangeListener(refreshTransform)

        overlay = findViewById(R.id.camera_overlay)
        overlay.host = this
        hint = findViewById(R.id.camera_hint)
        caption = findViewById(R.id.camera_caption)
        banner = findViewById(R.id.camera_banner)
        bannerText = findViewById(R.id.camera_banner_text)
        bannerAction = findViewById(R.id.camera_banner_action)
        bannerAction.setOnClickListener { goFinger() }
        permissionCard = findViewById(R.id.perm_card)
        permissionAllow = findViewById(R.id.perm_allow)
        permissionOpenSettings = findViewById(R.id.perm_open_settings)
        permissionAllow.setOnClickListener { requestPermission() }
        permissionOpenSettings.setOnClickListener { openAppSettings() }
        findViewById<View>(R.id.perm_use_finger).setOnClickListener { goFinger() }

        val fingerButton = findViewById<View>(R.id.btn_finger)
        val settingsButton = findViewById<View>(R.id.btn_settings)
        fingerButton.setOnClickListener { goFinger() }
        settingsButton.setOnClickListener { startActivity(Intent(this, SettingsActivity::class.java)) }

        val pill = findViewById<View>(R.id.camera_pill)
        modeChips = arrayOf(findViewById(R.id.chip_pick_1), findViewById(R.id.chip_pick_2))
        val modes = Mode.CAMERA_MODES
        for (i in modeChips.indices) {
            val mode = modes[i]
            modeChips[i].setOnClickListener { selectMode(mode, persist = true) }
        }
        chrome = Chrome(listOf(fingerButton, settingsButton, pill, modeChips[0], modeChips[1]))

        if (BuildConfig.DEBUG) {
            // Long-press shows the mapped analysis bounds and raw boxes; a plain tap still
            // reaches the overlay's re-arm so the hint does not steal "Tap to play again".
            hint.setOnLongClickListener {
                session.faceSource.debugEnabled = overlay.toggleDebug()
                true
            }
            hint.setOnClickListener { overlay.tap() }
        }

        applyInsets(findViewById(R.id.camera_chrome))

        permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                permissionDenied = false
                noRationaleDenials = 0
                permissionCard.visibility = View.GONE
                bindIfGranted()
            } else {
                permissionDenied = true
                val rationaleAfter = shouldShowRequestPermissionRationale(Manifest.permission.CAMERA)
                if (!rationaleBefore && !rationaleAfter) noRationaleDenials++ else noRationaleDenials = 0
                val permanent = !rationaleAfter && (rationaleBefore || noRationaleDenials >= 2)
                showPermissionCard(permanent)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        if (!hasCameraPermission() && !permissionDenied && !permissionRequested) requestPermission()
    }

    override fun onResume() {
        super.onResume()
        applyImmersive()
        keepScreenOn = settings.keepScreenOn
        if (keepScreenOn) armKeepScreenOn() else dropKeepScreenOn()
        overlay.configure(Timings.camera(settings.stabilization), Haptics(this, settings.haptics), settings.cameraMode)
        selectMode(settings.cameraMode, persist = false)
        chrome.setVisibleNow(true)
        bindIfGranted()
    }

    override fun onPause() {
        super.onPause()
        dropKeepScreenOn()
        // The overlay first: its reset re-syncs the analyzer request to "wanted", which the
        // session then overrides until the next bind. CameraX keeps streaming while paused, and
        // a Recents thumbnail with two faces in it must not start a round in the background.
        overlay.resetAll()
        session.setAnalyzerEnabled(false)
        session.faceSource.pause()
        sleeping = false
    }

    override fun onDestroy() {
        handler.removeCallbacks(keepScreenOffRunnable)
        previewView.removeOnLayoutChangeListener(refreshTransform)
        session.release()
        super.onDestroy()
    }

    // ---- Keep screen on ----------------------------------------------------------------

    /**
     * Keeps the screen on for three minutes after the last interaction, then lets the system
     * timeout apply so a phone forgotten in camera mode does not burn its battery at full
     * brightness. Re-arming only reschedules the runnable: the flag is added once.
     */
    private fun armKeepScreenOn() {
        if (!keepScreenOn) return
        if (!keepScreenOnHeld) {
            keepScreenOnHeld = true
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        handler.removeCallbacks(keepScreenOffRunnable)
        handler.postDelayed(keepScreenOffRunnable, KEEP_SCREEN_ON_MS)
    }

    private fun dropKeepScreenOn() {
        handler.removeCallbacks(keepScreenOffRunnable)
        if (keepScreenOnHeld) {
            keepScreenOnHeld = false
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) applyImmersive()
    }

    // ---- Permission --------------------------------------------------------------------

    private fun hasCameraPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    private fun requestPermission() {
        permissionRequested = true
        rationaleBefore = shouldShowRequestPermissionRationale(Manifest.permission.CAMERA)
        permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    private fun showPermissionCard(permanent: Boolean) {
        permissionCard.visibility = View.VISIBLE
        permissionAllow.visibility = if (permanent) View.GONE else View.VISIBLE
        permissionOpenSettings.visibility = if (permanent) View.VISIBLE else View.GONE
        hint.text = ""
    }

    private fun openAppSettings() {
        try {
            startActivity(
                Intent(SystemSettings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")),
            )
        } catch (_: Exception) {
            // No settings app: the card stays, finger mode is one tap away.
        }
    }

    /**
     * Binds the camera once the permission is there; idempotent (a session already bound to
     * this activity is left alone, see [CameraSession.bind]). Enabling the analyzer first undoes
     * the pause and, on a session that stays bound, clears the old tracks and the throttle,
     * which is all a resume needs.
     */
    private fun bindIfGranted() {
        if (!hasCameraPermission() || cameraUnavailable || sleeping) return
        permissionCard.visibility = View.GONE
        if (hint.text.isEmpty()) hint.setText(R.string.hint_camera_idle)
        session.faceSource.start()
        session.setAnalyzerEnabled(true)
        session.bind(this)
    }

    // ---- Navigation --------------------------------------------------------------------

    /** The explicit way back to finger mode: remembered, unlike the system back. */
    private fun goFinger() {
        settings.lastMode = Settings.ScreenMode.FINGER
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }

    private fun selectMode(mode: Mode, persist: Boolean) {
        overlay.mode = mode
        if (persist) settings.cameraMode = mode
        val accent = ContextCompat.getColor(this, R.color.accent)
        val muted = ContextCompat.getColor(this, R.color.text_secondary)
        val modes = Mode.CAMERA_MODES
        for (i in modeChips.indices) {
            val on = modes[i] == mode
            modeChips[i].isSelected = on
            modeChips[i].setTextColor(if (on) accent else muted)
        }
    }

    // ---- Window ------------------------------------------------------------------------

    private fun applyImmersive() {
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(WindowInsetsCompat.Type.systemBars())
    }

    /** Chrome keeps clear of the bars and the cutout even while the bars are hidden. */
    private fun applyInsets(chromeRoot: View) {
        ViewCompat.setOnApplyWindowInsetsListener(chromeRoot) { v, insets ->
            val bars = insets.getInsetsIgnoringVisibility(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout(),
            )
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
    }

    // ---- FaceOverlayView.Host ----------------------------------------------------------

    override fun onHintChanged(hint: Int) {
        val text = when (hint) {
            FaceOverlayView.HINT_IDLE -> R.string.hint_camera_idle
            FaceOverlayView.HINT_ARMING -> R.string.hint_hold_still
            FaceOverlayView.HINT_RESULT -> R.string.hint_camera_tap_again
            FaceOverlayView.HINT_COME_CLOSER -> R.string.hint_come_closer
            FaceOverlayView.HINT_SLEEPING -> R.string.hint_camera_sleeping
            else -> 0
        }
        if (text == 0) this.hint.text = "" else this.hint.setText(text)
    }

    override fun onChromeVisible(visible: Boolean) {
        chrome.setVisible(visible)
    }

    override fun onAnalyzerEnabled(enabled: Boolean) {
        session.setAnalyzerEnabled(enabled)
    }

    override fun onCameraSleep() {
        sleeping = true
        session.unbindAll()
        // A released camera has nothing to keep the screen on for.
        dropKeepScreenOn()
    }

    override fun onCameraWake() {
        sleeping = false
        bindIfGranted()
    }

    override fun onInteraction() {
        armKeepScreenOn()
    }

    // ---- FaceSource.Listener -----------------------------------------------------------

    override fun onFaceFrame(frame: FaceFrame) {
        // The preview transform can arrive after layout: refresh it with every frame so a
        // late one is picked up by the next analysis instead of never.
        session.refreshTransform()
        overlay.onFrameArrived(frame, if (overlay.debugEnabled) session.faceSource.uiDebug else null)
    }

    override fun onDetectorState(state: Int) {
        when (state) {
            FaceSource.STATE_READY -> banner.visibility = View.GONE
            FaceSource.STATE_DOWNLOADING -> showBanner(R.string.mlkit_downloading, action = false)
            FaceSource.STATE_UNAVAILABLE -> showBanner(R.string.mlkit_unavailable, action = true)
        }
    }

    // ---- CameraSession.Listener --------------------------------------------------------

    override fun onCameraBound(front: Boolean) {
        caption.visibility = if (front) View.GONE else View.VISIBLE
    }

    override fun onCameraUnavailable() {
        cameraUnavailable = true
        showBanner(R.string.camera_unavailable, action = true)
    }

    private fun showBanner(text: Int, action: Boolean) {
        banner.visibility = View.VISIBLE
        bannerText.setText(text)
        bannerAction.visibility = if (action) View.VISIBLE else View.GONE
    }

    companion object {
        /** Screen stays on this long after the last interaction, as in the finger arena. */
        private const val KEEP_SCREEN_ON_MS = 3L * 60L * 1000L
    }
}
