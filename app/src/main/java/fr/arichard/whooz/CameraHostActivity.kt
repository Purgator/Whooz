package fr.arichard.whooz

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings as SystemSettings
import android.view.OrientationEventListener
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
import fr.arichard.whooz.camera.FaceSource
import fr.arichard.whooz.core.Settings
import fr.arichard.whooz.core.Timings
import fr.arichard.whooz.ui.Chrome

/**
 * Everything the two camera screens (group camera mode and the roll call) share: the selfie
 * [PreviewView] and its [CameraSession], the runtime permission flow, the ML Kit availability
 * banner, sticky immersive bars, insets, the keep-screen-on timer and the sleep/wake dance.
 * Subclasses provide their layout, their overlay and the way back to finger mode.
 *
 * Nothing is bound before CAMERA is granted; CameraX unbinds through the lifecycle in
 * `onStop`, `onPause` stops the analyzer so no round can start or survive a pause (the camera
 * keeps streaming while the activity is merely paused: Recents, a dialog, an incoming call),
 * and `onDestroy` releases the session (detector, analysis thread).
 *
 * The screen stays on for three minutes after the last interaction (reported by the overlay),
 * then falls back to the system timeout; the flag is also dropped when the overlay puts the
 * camera to sleep, since a released camera has nothing to keep the screen on for.
 *
 * The permission "permanent denial" rule: on Android 11+ `shouldShowRequestPermissionRationale`
 * is false both before the first explicit denial and after the final one, so a denial is
 * permanent only when the rationale flag was true before the request and false after it (the
 * dialog was shown once and the user shut the door), or when two requests in a row came back
 * denied with the flag false on both sides (the system is auto-denying). A first dialog
 * dismissed with a swipe reports a denial with the flag still false and is simply asked again.
 */
abstract class CameraHostActivity : AppCompatActivity(), FaceSource.Listener, CameraSession.Listener {

    protected lateinit var settings: Settings
    protected lateinit var session: CameraSession
    protected lateinit var previewView: PreviewView
    protected lateinit var hint: TextView
    protected lateinit var chrome: Chrome
    private lateinit var caption: TextView
    private lateinit var banner: View
    private lateinit var bannerText: TextView
    private lateinit var bannerAction: View
    private lateinit var permissionCard: View
    private lateinit var permissionAllow: View
    private lateinit var permissionOpenSettings: View
    private lateinit var permissionLauncher: ActivityResultLauncher<String>

    private var permissionDenied = false
    private var permissionRequested = false
    private var rationaleBefore = false
    private var noRationaleDenials = 0

    /** True after the overlay asked for the camera to sleep; a tap on it rebinds. */
    protected var sleeping = false
        private set

    private var cameraUnavailable = false

    private val handler = Handler(Looper.getMainLooper())
    private val keepScreenOffRunnable = Runnable { dropKeepScreenOn() }
    private var keepScreenOn = true

    private lateinit var orientationListener: OrientationEventListener

    /** Tracks the window flag locally: setting it again forces a relayout IPC for nothing. */
    private var keepScreenOnHeld = false

    private val refreshTransform = View.OnLayoutChangeListener { _, left, top, right, bottom, _, _, _, _ ->
        // The overlay shares the PreviewView's bounds: the source clips faces to this size.
        session.faceSource.setViewSize(right - left, bottom - top)
        session.refreshTransform()
    }

    /** The layout; it must contain `include_camera_status` and a `camera_preview` PreviewView. */
    protected abstract val layoutRes: Int

    /** The subclass finds its views here, after `setContentView`; returns its chrome views. */
    protected abstract fun onCreateContent(): List<View>

    /** The settings snapshot is applied here on every resume, before the camera binds. */
    protected abstract fun onResumeContent()

    /** The overlay is reset here before the analyzer stops. */
    protected abstract fun onPauseContent()

    /** A new face frame (UI thread); the transform was already refreshed. */
    protected abstract fun onFrame(frame: FaceFrame)

    /** Whether the overlay should re-arm the analyzer when the camera binds; usually true. */
    protected open fun analyzerWantedOnBind(): Boolean = true

    /** The bottom bar inset in pixels, for overlays that lay out against the chrome row. */
    protected open fun onBottomInset(px: Int) = Unit

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = Settings(this)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(layoutRes)

        previewView = findViewById(R.id.camera_preview)
        previewView.implementationMode = PreviewView.ImplementationMode.PERFORMANCE
        previewView.scaleType = PreviewView.ScaleType.FILL_CENTER
        session = CameraSession(this, previewView, Timings.camera(settings.stabilization))
        session.listener = this
        session.faceSource.listener = this
        previewView.addOnLayoutChangeListener(refreshTransform)

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

        orientationListener = object : OrientationEventListener(this) {
            override fun onOrientationChanged(orientation: Int) {
                if (orientation == OrientationEventListener.ORIENTATION_UNKNOWN) return
                // Map the 0..359 degrees to the nearest 90-degree step.
                val rounded = ((orientation + 45) / 90 * 90) % 360
                session.setDeviceRotation(rounded)
            }
        }

        val fingerButton = findViewById<View>(R.id.btn_finger)
        val settingsButton = findViewById<View>(R.id.btn_settings)
        fingerButton.setOnClickListener { goFinger() }
        settingsButton.setOnClickListener { startActivity(Intent(this, SettingsActivity::class.java)) }

        val own = onCreateContent()
        chrome = Chrome(listOf(fingerButton, settingsButton) + own)
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
        orientationListener.enable()
    }

    override fun onStop() {
        orientationListener.disable()
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        applyImmersive()
        keepScreenOn = settings.keepScreenOn
        if (keepScreenOn) armKeepScreenOn() else dropKeepScreenOn()
        onResumeContent()
        chrome.setVisibleNow(true)
        bindIfGranted()
    }

    override fun onPause() {
        super.onPause()
        dropKeepScreenOn()
        // The overlay first: its reset re-syncs the analyzer request to "wanted", which the
        // session then overrides until the next bind. CameraX keeps streaming while paused.
        onPauseContent()
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

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) applyImmersive()
    }

    // ---- Keep screen on ----------------------------------------------------------------

    /** Re-arming only reschedules the runnable: the flag is added once. */
    protected fun armKeepScreenOn() {
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
     * Binds the camera once the permission is there; idempotent (see [CameraSession.bind]).
     * Enabling the analyzer first undoes the pause and, on a session that stays bound, clears
     * the old tracks and the throttle, which is all a resume needs.
     */
    protected fun bindIfGranted() {
        if (!hasCameraPermission() || cameraUnavailable || sleeping) return
        permissionCard.visibility = View.GONE
        session.faceSource.start()
        session.setAnalyzerEnabled(analyzerWantedOnBind())
        session.bind(this)
    }

    // ---- Navigation --------------------------------------------------------------------

    /** The explicit way back to finger mode: remembered, unlike the system back. */
    protected fun goFinger() {
        settings.lastMode = Settings.ScreenMode.FINGER
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }

    /** Switches to the other camera screen and remembers it. */
    protected fun goTo(target: Class<out CameraHostActivity>, mode: Settings.ScreenMode) {
        settings.lastMode = mode
        startActivity(Intent(this, target))
        finish()
    }

    // ---- Sleep -------------------------------------------------------------------------

    /** The overlay found nobody for a while: release the camera until a tap. */
    protected fun sleepCamera() {
        sleeping = true
        session.unbindAll()
        dropKeepScreenOn()
    }

    protected fun wakeCamera() {
        sleeping = false
        bindIfGranted()
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
            onBottomInset(bars.bottom)
            insets
        }
    }

    // ---- FaceSource.Listener -----------------------------------------------------------

    override fun onFaceFrame(frame: FaceFrame) {
        // The preview transform can arrive after layout: refresh it with every frame so a
        // late one is picked up by the next analysis instead of never.
        session.refreshTransform()
        onFrame(frame)
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
        const val KEEP_SCREEN_ON_MS = 3L * 60L * 1000L
    }
}
