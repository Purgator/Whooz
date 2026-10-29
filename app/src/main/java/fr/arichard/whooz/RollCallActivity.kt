package fr.arichard.whooz

import android.graphics.Bitmap
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import fr.arichard.whooz.camera.FaceFrame
import fr.arichard.whooz.camera.RecentGroupStore
import fr.arichard.whooz.camera.RollCallView
import fr.arichard.whooz.core.Mode
import fr.arichard.whooz.core.Settings
import fr.arichard.whooz.core.Timings
import fr.arichard.whooz.ui.Haptics

/**
 * Roll call mode: players come in front of the selfie camera one at a time, each is locked and
 * photographed, and the draw runs over the portraits. The host logic lives in
 * [CameraHostActivity]; this class wires the [RollCallView], the Go / new-group buttons, the
 * sub-mode pill and the portrait capture.
 *
 * The portrait is cut from [androidx.camera.view.PreviewView.getBitmap], which hands back the
 * displayed preview in view coordinates (cropped, rotated and mirrored exactly like the
 * screen), so the face ring's view coordinates index straight into it. The copy is synchronous
 * on current Android builds; should it ever come back blank or null, the player is enrolled
 * with a plain colored tile, which the draw treats the same way.
 */
class RollCallActivity : CameraHostActivity(), RollCallView.Host {

    override val layoutRes: Int = R.layout.activity_rollcall

    private lateinit var overlay: RollCallView
    private lateinit var goButton: TextView
    private lateinit var newGroupButton: View
    private lateinit var recentGroupsButton: TextView
    private lateinit var removeButton: TextView
    private lateinit var captureButton: TextView
    private lateinit var modeChips: Array<TextView>
    private lateinit var recentGroups: RecentGroupStore
    private var rosterDirty = false
    private var configuring = false

    override fun onCreateContent(): List<View> {
        overlay = findViewById(R.id.rollcall_overlay)
        overlay.host = this
        recentGroups = RecentGroupStore(this)

        val cameraButton = findViewById<View>(R.id.btn_camera)
        cameraButton.setOnClickListener { goTo(CameraActivity::class.java, Settings.ScreenMode.CAMERA) }

        goButton = findViewById(R.id.rollcall_go)
        goButton.setOnClickListener { overlay.startRound() }
        newGroupButton = findViewById(R.id.rollcall_new_group)
        newGroupButton.setOnClickListener {
            saveCurrentGroup()
            overlay.newGroup()
        }
        recentGroupsButton = findViewById(R.id.rollcall_recent)
        recentGroupsButton.setOnClickListener { showRecentGroups() }
        removeButton = findViewById(R.id.rollcall_remove)
        removeButton.setOnClickListener { overlay.removeSelected() }
        captureButton = findViewById(R.id.rollcall_capture)
        captureButton.setOnClickListener { overlay.captureNow() }

        val pill = findViewById<View>(R.id.camera_pill)
        modeChips = arrayOf(findViewById(R.id.chip_pick_1), findViewById(R.id.chip_pick_2))
        val modes = Mode.CAMERA_MODES
        for (i in modeChips.indices) {
            val mode = modes[i]
            modeChips[i].setOnClickListener { selectMode(mode, persist = true) }
        }
        return listOf(
            cameraButton,
            goButton,
            newGroupButton,
            recentGroupsButton,
            removeButton,
            captureButton,
            pill,
            modeChips[0],
            modeChips[1],
        )
    }

    override fun onResumeContent() {
        configuring = true
        overlay.configure(
            Timings.camera(settings.stabilization),
            Haptics(this, settings.haptics),
            settings.rollCallMode,
            settings.autoCapturePortraits,
        )
        selectMode(settings.rollCallMode, persist = false)
        configuring = false
        refreshRecentGroupsButton()
    }

    override fun onPauseContent() {
        saveCurrentGroup()
        overlay.resetAll()
    }

    override fun onFrame(frame: FaceFrame) {
        overlay.onFrameArrived(frame)
    }

    override fun onBottomInset(px: Int) {
        overlay.setBottomInset(px)
    }

    private fun selectMode(mode: Mode, persist: Boolean) {
        overlay.mode = mode
        if (persist) settings.rollCallMode = mode
        val accent = ContextCompat.getColor(this, R.color.accent)
        val muted = ContextCompat.getColor(this, R.color.text_secondary)
        val modes = Mode.CAMERA_MODES
        for (i in modeChips.indices) {
            val on = modes[i] == mode
            modeChips[i].isSelected = on
            modeChips[i].setTextColor(if (on) accent else muted)
        }
    }

    // ---- RollCallView.Host -------------------------------------------------------------

    override fun onHintChanged(hint: Int, count: Int) {
        when (hint) {
            RollCallView.HINT_FIRST -> this.hint.setText(R.string.rollcall_hint_first)
            RollCallView.HINT_WAITING -> this.hint.setText(R.string.rollcall_hint_waiting)
            RollCallView.HINT_HOLD -> this.hint.setText(R.string.hint_hold_still)
            RollCallView.HINT_ONE_AT_A_TIME -> this.hint.setText(R.string.rollcall_hint_one_at_a_time)
            RollCallView.HINT_NEXT -> this.hint.setText(R.string.rollcall_hint_next)
            RollCallView.HINT_READY -> this.hint.text = getString(R.string.rollcall_hint_ready, count)
            RollCallView.HINT_FULL -> this.hint.setText(R.string.rollcall_hint_full)
            RollCallView.HINT_RESULT -> this.hint.setText(R.string.hint_camera_tap_again)
            RollCallView.HINT_SLEEPING -> this.hint.setText(R.string.hint_camera_sleeping)
            else -> this.hint.text = ""
        }
    }

    override fun onChromeVisible(visible: Boolean) {
        chrome.setVisible(visible)
    }

    override fun onAnalyzerEnabled(enabled: Boolean) {
        session.setAnalyzerEnabled(enabled)
    }

    override fun onRosterChanged(count: Int, canDraw: Boolean) {
        if (!configuring) rosterDirty = count > 0
        // Enabled state is shown by color, not alpha: the chrome fade owns the alpha.
        goButton.isEnabled = canDraw
        goButton.setBackgroundResource(if (canDraw) R.drawable.bg_go else R.drawable.bg_go_disabled)
        goButton.setTextColor(ContextCompat.getColor(this, if (canDraw) R.color.arena else R.color.text_secondary))
        newGroupButton.visibility = if (count > 0) View.VISIBLE else View.INVISIBLE
    }

    override fun onPortraitSelected(index: Int) {
        removeButton.visibility = if (index >= 0) View.VISIBLE else View.GONE
    }

    override fun onCaptureAvailable(available: Boolean) {
        captureButton.isEnabled = available
        captureButton.setBackgroundResource(if (available) R.drawable.bg_go else R.drawable.bg_go_disabled)
        captureButton.setTextColor(
            ContextCompat.getColor(this, if (available) R.color.arena else R.color.text_secondary),
        )
    }

    override fun capturePortrait(cx: Float, cy: Float, radius: Float, sizePx: Int): Bitmap? {
        val source = try {
            previewView.bitmap
        } catch (_: Exception) {
            null
        } ?: return null
        try {
            val viewW = previewView.width
            val viewH = previewView.height
            if (viewW <= 0 || viewH <= 0 || source.width <= 0 || source.height <= 0) return null
            val sx = source.width.toFloat() / viewW
            val sy = source.height.toFloat() / viewH
            // A square around the ring, a little wider than the face, clamped to the picture.
            var side = radius * PORTRAIT_SPAN * 2f
            val left = (cx - side * 0.5f) * sx
            val top = (cy - side * 0.5f) * sy
            var l = left.toInt().coerceIn(0, source.width - 1)
            var t = top.toInt().coerceIn(0, source.height - 1)
            var w = (side * sx).toInt().coerceAtLeast(1)
            var h = (side * sy).toInt().coerceAtLeast(1)
            if (l + w > source.width) w = source.width - l
            if (t + h > source.height) h = source.height - t
            val square = if (w < h) w else h
            l = (l + (w - square) / 2).coerceAtLeast(0)
            t = (t + (h - square) / 2).coerceAtLeast(0)
            side = square.toFloat()
            if (side < 2f) return null
            val crop = Bitmap.createBitmap(source, l, t, square, square)
            val thumb = Bitmap.createScaledBitmap(crop, sizePx, sizePx, true)
            if (crop !== thumb) crop.recycle()
            // CameraX gives up on the pixel copy after 100 ms and hands back a transparent
            // bitmap; a see-through portrait is worse than the colored tile.
            if (isBlank(thumb)) {
                thumb.recycle()
                return null
            }
            return thumb
        } catch (_: Exception) {
            return null
        } finally {
            source.recycle()
        }
    }

    /** True when the centre and the four quarter points are all fully transparent. */
    private fun isBlank(bitmap: Bitmap): Boolean {
        val w = bitmap.width
        val h = bitmap.height
        if (w < 4 || h < 4) return true
        val q = intArrayOf(w / 2, h / 2, w / 4, h / 4, 3 * w / 4, h / 4, w / 4, 3 * h / 4, 3 * w / 4, 3 * h / 4)
        var i = 0
        while (i < q.size) {
            if (bitmap.getPixel(q[i], q[i + 1]) ushr 24 != 0) return false
            i += 2
        }
        return true
    }

    override fun onInteraction() {
        armKeepScreenOn()
    }

    private fun saveCurrentGroup() {
        if (!rosterDirty) return
        val portraits = overlay.currentPortraits()
        if (portraits.isEmpty()) return
        try {
            recentGroups.saveGroup(portraits)
            rosterDirty = false
            refreshRecentGroupsButton()
        } catch (error: java.io.IOException) {
            Toast.makeText(this, error.localizedMessage ?: "Could not save group", Toast.LENGTH_SHORT).show()
        } catch (error: IllegalStateException) {
            Toast.makeText(this, error.localizedMessage ?: "Could not save group", Toast.LENGTH_SHORT).show()
        }
    }

    private fun refreshRecentGroupsButton() {
        if (::recentGroupsButton.isInitialized) {
            recentGroupsButton.visibility = if (recentGroups.summaries().isEmpty()) View.GONE else View.VISIBLE
        }
    }

    private fun showRecentGroups() {
        saveCurrentGroup()
        val summaries = recentGroups.summaries()
        if (summaries.isEmpty()) {
            Toast.makeText(this, R.string.rollcall_no_recent_groups, Toast.LENGTH_SHORT).show()
            return
        }
        val labels = summaries.map { getString(R.string.rollcall_recent_group_item, it.count) }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(R.string.rollcall_recent_group_title)
            .setItems(labels) { _, which ->
                val portraits = recentGroups.load(summaries[which])
                if (portraits.isNotEmpty()) {
                    overlay.restoreGroup(portraits)
                    rosterDirty = false
                }
            }
            .show()
    }

    override fun onCameraSleep() {
        sleepCamera()
    }

    override fun onCameraWake() {
        wakeCamera()
    }

    companion object {
        /** Portrait half-side as a multiple of the ring radius: head plus a bit of shoulders. */
        private const val PORTRAIT_SPAN = 1.25f
    }
}
