package fr.arichard.whooz

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import fr.arichard.whooz.core.Settings
import fr.arichard.whooz.core.StabilizationPreset
import fr.arichard.whooz.update.UpdateManager

/**
 * Hand-built settings screen: a ScrollView of rows wired to [Settings] directly, because the
 * preference library would double the APK for five switches. Every change is written the moment
 * it is made; the chooser screens reload a snapshot in their own `onResume`, so nothing here is
 * ever read by a draw loop.
 *
 * Unlike the arenas this screen keeps the normal system bars: the user came here to read, not
 * to play, and immersive mode would hide the status bar clock for no reason.
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var settings: Settings

    // The three preset chips in StabilizationPreset.entries order, so selection is an index.
    private lateinit var chips: Array<TextView>

    // Guards against stacking network threads when the row is tapped repeatedly.
    @Volatile
    private var checking = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = Settings(this)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_settings)

        applyInsetsAsPadding(findViewById(R.id.settings_content))

        findViewById<View>(R.id.settings_back).setOnClickListener { finish() }

        bindChips()
        bindSwitch(R.id.row_haptics, R.id.switch_haptics, settings.haptics) { settings.haptics = it }
        bindSwitch(R.id.row_keep_screen_on, R.id.switch_keep_screen_on, settings.keepScreenOn) {
            settings.keepScreenOn = it
        }
        bindSwitch(R.id.row_remember_mode, R.id.switch_remember_mode, settings.rememberLastMode) {
            settings.rememberLastMode = it
        }
        bindSwitch(R.id.row_auto_update, R.id.switch_auto_update, settings.autoUpdate) {
            settings.autoUpdate = it
        }

        findViewById<View>(R.id.row_check_now).setOnClickListener { checkForUpdates() }

        findViewById<TextView>(R.id.version_text).text =
            getString(R.string.settings_version, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE)
        findViewById<View>(R.id.row_github).setOnClickListener { openGitHub() }
        findViewById<View>(R.id.row_licenses).setOnClickListener { showLicenses() }
    }

    /**
     * Edge-to-edge: the window draws under the transparent bars and the scrolling column pads
     * itself by the bar insets, so the first row is never under the status bar and the last one
     * clears the navigation bar. The layout's own padding is kept underneath.
     */
    private fun applyInsetsAsPadding(content: View) {
        val baseLeft = content.paddingLeft
        val baseTop = content.paddingTop
        val baseRight = content.paddingRight
        val baseBottom = content.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(content) { v, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout(),
            )
            v.setPadding(
                baseLeft + bars.left,
                baseTop + bars.top,
                baseRight + bars.right,
                baseBottom + bars.bottom,
            )
            insets
        }
    }

    /** A whole row toggles its switch: the hit target is the row, the switch only displays. */
    private fun bindSwitch(rowId: Int, switchId: Int, initial: Boolean, write: (Boolean) -> Unit) {
        val row = findViewById<View>(rowId)
        val switch = findViewById<SwitchCompat>(switchId)
        switch.isChecked = initial
        switch.setOnCheckedChangeListener { _, checked -> write(checked) }
        row.setOnClickListener { switch.toggle() }
    }

    private fun bindChips() {
        chips = arrayOf(
            findViewById(R.id.chip_fast),
            findViewById(R.id.chip_normal),
            findViewById(R.id.chip_slow),
        )
        val presets = StabilizationPreset.entries
        for (i in chips.indices) {
            val preset = presets[i]
            chips[i].setOnClickListener {
                settings.stabilization = preset
                selectChip(i)
            }
        }
        selectChip(presets.indexOf(settings.stabilization))
    }

    /** Selection is both the drawable state and the text color: accent when chosen. */
    private fun selectChip(selected: Int) {
        val accent = ContextCompat.getColor(this, R.color.accent)
        val muted = ContextCompat.getColor(this, R.color.text_secondary)
        for (i in chips.indices) {
            val on = i == selected
            chips[i].isSelected = on
            chips[i].setTextColor(if (on) accent else muted)
        }
    }

    /**
     * Forces a release check regardless of the daily throttle and the metered network, on a
     * plain daemon thread: the user asked for it now. The result is toasted only if the screen
     * is still alive, so a rotated or closed activity never leaks through the callback.
     */
    private fun checkForUpdates() {
        if (checking) return
        checking = true
        val status = findViewById<View>(R.id.check_now_status)
        status.visibility = View.VISIBLE
        val appContext = applicationContext
        val thread = Thread {
            val result = try {
                UpdateManager.checkNow(appContext, force = true)
            } catch (e: Exception) {
                UpdateManager.Result(UpdateManager.Status.ERROR, detail = e.message)
            }
            runOnUiThread {
                checking = false
                if (isFinishing || isDestroyed) return@runOnUiThread
                status.visibility = View.GONE
                Toast.makeText(this, toastFor(result.status), Toast.LENGTH_LONG).show()
            }
        }
        thread.isDaemon = true
        thread.name = "whooz-check-now"
        thread.start()
    }

    private fun toastFor(status: UpdateManager.Status): Int = when (status) {
        UpdateManager.Status.UP_TO_DATE -> R.string.update_up_to_date
        UpdateManager.Status.UPDATE_READY -> R.string.update_downloaded
        UpdateManager.Status.UPDATE_DEFERRED -> R.string.update_wifi_pending
        UpdateManager.Status.DOWNLOAD_FAILED -> R.string.update_download_failed
        UpdateManager.Status.NO_NETWORK -> R.string.update_no_network
        UpdateManager.Status.ERROR -> R.string.update_check_failed
    }

    private fun openGitHub() {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(GITHUB_URL)))
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, R.string.settings_no_browser, Toast.LENGTH_SHORT).show()
        }
    }

    /** The notices are a raw text resource: a dialog is enough, no second screen to maintain. */
    private fun showLicenses() {
        val text = resources.openRawResource(R.raw.licenses).bufferedReader().use { it.readText() }
        AlertDialog.Builder(this)
            .setTitle(R.string.settings_licenses)
            .setMessage(text)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private companion object {
        const val GITHUB_URL = "https://github.com/Purgator/Whooz"
    }
}
