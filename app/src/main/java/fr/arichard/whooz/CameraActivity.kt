package fr.arichard.whooz

import android.view.View
import android.widget.TextView
import androidx.core.content.ContextCompat
import fr.arichard.whooz.camera.FaceFrame
import fr.arichard.whooz.camera.FaceOverlayView
import fr.arichard.whooz.core.Mode
import fr.arichard.whooz.core.Settings
import fr.arichard.whooz.core.Timings
import fr.arichard.whooz.ui.Haptics

/**
 * Group camera mode: the selfie preview with the [FaceOverlayView] on top, every face ringed
 * at once. The host logic (permission, session, banner, screen, sleep) lives in
 * [CameraHostActivity]; this class only wires the overlay, the sub-mode pill, the way to the
 * roll call and the debug toggle.
 *
 * The back key simply finishes: the last mode stays CAMERA so a camera-only group reopens
 * straight into this screen; only the finger and roll-call icons write another mode back.
 */
class CameraActivity : CameraHostActivity(), FaceOverlayView.Host {

    override val layoutRes: Int = R.layout.activity_camera

    private lateinit var overlay: FaceOverlayView
    private lateinit var modeChips: Array<TextView>

    override fun onCreateContent(): List<View> {
        overlay = findViewById(R.id.camera_overlay)
        overlay.host = this

        val rollCallButton = findViewById<View>(R.id.btn_rollcall)
        rollCallButton.setOnClickListener { goTo(RollCallActivity::class.java, Settings.ScreenMode.ROLLCALL) }

        val pill = findViewById<View>(R.id.camera_pill)
        modeChips = arrayOf(findViewById(R.id.chip_pick_1), findViewById(R.id.chip_pick_2))
        val modes = Mode.CAMERA_MODES
        for (i in modeChips.indices) {
            val mode = modes[i]
            modeChips[i].setOnClickListener { selectMode(mode, persist = true) }
        }

        if (BuildConfig.DEBUG) {
            // Long-press shows the mapped analysis bounds and raw boxes; a plain tap still
            // reaches the overlay's re-arm so the hint does not steal "Tap to play again".
            hint.setOnLongClickListener {
                session.faceSource.debugEnabled = overlay.toggleDebug()
                true
            }
            hint.setOnClickListener { overlay.tap() }
        }
        return listOf(rollCallButton, pill, modeChips[0], modeChips[1])
    }

    override fun onResumeContent() {
        overlay.configure(Timings.camera(settings.stabilization), Haptics(this, settings.haptics), settings.cameraMode)
        selectMode(settings.cameraMode, persist = false)
        if (hint.text.isEmpty()) hint.setText(R.string.hint_camera_idle)
    }

    override fun onPauseContent() {
        overlay.resetAll()
    }

    override fun onFrame(frame: FaceFrame) {
        overlay.onFrameArrived(frame, if (overlay.debugEnabled) session.faceSource.uiDebug else null)
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
        sleepCamera()
    }

    override fun onCameraWake() {
        wakeCamera()
    }

    override fun onInteraction() {
        armKeepScreenOn()
    }
}
