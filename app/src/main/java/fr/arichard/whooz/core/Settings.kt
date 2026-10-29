package fr.arichard.whooz.core

import android.content.Context
import android.content.SharedPreferences

/** The only `core` class that touches Android: typed access to the app's preferences. */
class Settings(context: Context) {
    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    enum class ScreenMode { FINGER, CAMERA, ROLLCALL }

    var stabilization: StabilizationPreset
        get() = StabilizationPreset.fromName(prefs.getString(KEY_STABILIZATION, null))
        set(value) = prefs.edit().putString(KEY_STABILIZATION, value.name).apply()

    var haptics: Boolean
        get() = prefs.getBoolean(KEY_HAPTICS, true)
        set(value) = prefs.edit().putBoolean(KEY_HAPTICS, value).apply()

    var autoCapturePortraits: Boolean
        get() = prefs.getBoolean(KEY_AUTO_CAPTURE_PORTRAITS, true)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_CAPTURE_PORTRAITS, value).apply()

    var keepScreenOn: Boolean
        get() = prefs.getBoolean(KEY_KEEP_SCREEN_ON, true)
        set(value) = prefs.edit().putBoolean(KEY_KEEP_SCREEN_ON, value).apply()

    var rememberLastMode: Boolean
        get() = prefs.getBoolean(KEY_REMEMBER_MODE, true)
        set(value) = prefs.edit().putBoolean(KEY_REMEMBER_MODE, value).apply()

    var lastMode: ScreenMode
        get() = when (prefs.getString(KEY_LAST_MODE, null)) {
            ScreenMode.CAMERA.name -> ScreenMode.CAMERA
            ScreenMode.ROLLCALL.name -> ScreenMode.ROLLCALL
            else -> ScreenMode.FINGER
        }
        set(value) = prefs.edit().putString(KEY_LAST_MODE, value.name).apply()

    var fingerMode: Mode
        get() = Mode.parse(prefs.getString(KEY_FINGER_MODE, null))
        set(value) = prefs.edit().putString(KEY_FINGER_MODE, value.serialize()).apply()

    var cameraMode: Mode
        get() = Mode.parse(prefs.getString(KEY_CAMERA_MODE, null)).let { if (it in Mode.CAMERA_MODES) it else Mode.DEFAULT }
        set(value) = prefs.edit().putString(KEY_CAMERA_MODE, value.serialize()).apply()

    var rollCallMode: Mode
        get() = Mode.parse(prefs.getString(KEY_ROLLCALL_MODE, null)).let { if (it in Mode.CAMERA_MODES) it else Mode.DEFAULT }
        set(value) = prefs.edit().putString(KEY_ROLLCALL_MODE, value.serialize()).apply()

    var firstLaunchHintShown: Boolean
        get() = prefs.getBoolean(KEY_FIRST_HINT, false)
        set(value) = prefs.edit().putBoolean(KEY_FIRST_HINT, value).apply()

    var completedRounds: Int
        get() = prefs.getInt(KEY_ROUNDS, 0)
        set(value) = prefs.edit().putInt(KEY_ROUNDS, value).apply()

    // Self-update bookkeeping.
    var autoUpdate: Boolean
        get() = prefs.getBoolean(KEY_AUTO_UPDATE, true)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_UPDATE, value).apply()

    var lastUpdateCheck: Long
        get() = prefs.getLong(KEY_LAST_UPDATE_CHECK, 0L)
        set(value) = prefs.edit().putLong(KEY_LAST_UPDATE_CHECK, value).apply()

    /** A newer release was found but not downloaded (metered network): retry sooner. */
    var updateDeferred: Boolean
        get() = prefs.getBoolean(KEY_UPDATE_DEFERRED, false)
        set(value) = prefs.edit().putBoolean(KEY_UPDATE_DEFERRED, value).apply()

    /** Version the user tapped "Later" on, with the time of that tap. */
    var updateDismissedVersion: String
        get() = prefs.getString(KEY_UPDATE_DISMISSED, "") ?: ""
        set(value) = prefs.edit().putString(KEY_UPDATE_DISMISSED, value).apply()

    var updateDismissedAt: Long
        get() = prefs.getLong(KEY_UPDATE_DISMISSED_AT, 0L)
        set(value) = prefs.edit().putLong(KEY_UPDATE_DISMISSED_AT, value).apply()

    /**
     * Version whose downloaded APK failed the archive checks (signer, versionCode): never
     * downloaded again by the daily check, so a bad asset costs one transfer, not one a day.
     */
    var updateRejectedVersion: String
        get() = prefs.getString(KEY_UPDATE_REJECTED, "") ?: ""
        set(value) = prefs.edit().putString(KEY_UPDATE_REJECTED, value).apply()

    companion object {
        private const val FILE = "whooz"
        private const val KEY_STABILIZATION = "stabilization"
        private const val KEY_HAPTICS = "haptics"
        private const val KEY_AUTO_CAPTURE_PORTRAITS = "auto_capture_portraits"
        private const val KEY_KEEP_SCREEN_ON = "keep_screen_on"
        private const val KEY_REMEMBER_MODE = "remember_mode"
        private const val KEY_LAST_MODE = "last_mode"
        private const val KEY_FINGER_MODE = "finger_mode"
        private const val KEY_CAMERA_MODE = "camera_mode"
        private const val KEY_ROLLCALL_MODE = "rollcall_mode"
        private const val KEY_FIRST_HINT = "first_hint_shown"
        private const val KEY_ROUNDS = "completed_rounds"
        private const val KEY_AUTO_UPDATE = "auto_update"
        private const val KEY_LAST_UPDATE_CHECK = "last_update_check"
        private const val KEY_UPDATE_DEFERRED = "update_deferred"
        private const val KEY_UPDATE_DISMISSED = "update_dismissed_version"
        private const val KEY_UPDATE_DISMISSED_AT = "update_dismissed_at"
        private const val KEY_UPDATE_REJECTED = "update_rejected_version"
    }
}
