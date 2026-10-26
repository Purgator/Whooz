package fr.arichard.whooz.update

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import androidx.core.content.FileProvider
import fr.arichard.whooz.R
import java.io.File

/**
 * Installs a downloaded update through the [PackageInstaller] session API.
 *
 * For a self-update (same package, same signing key) on Android 12+ the session asks for
 * USER_ACTION_NOT_REQUIRED, so the update applies silently and the app restarts. When the
 * system still wants a confirmation (older Android, OEM policy, future Android versions)
 * it answers STATUS_PENDING_USER_ACTION and [Receiver] launches the one dialog it hands
 * back. If the session path fails outright, the classic ACTION_VIEW installer intent is the
 * fallback so updating still works somehow.
 */
object ApkInstaller {

    private const val TAG = UpdateManager.TAG

    /** Must match the receiver's intent filter in the manifest. */
    const val ACTION_INSTALL_STATUS = "fr.arichard.whooz.action.INSTALL_STATUS"

    /** Carries the APK path to [Receiver] so a failed session can fall back to ACTION_VIEW. */
    private const val EXTRA_APK_PATH = "apk_path"

    /**
     * Abandons sessions left behind by a previous process (a kill mid-install, a reboot):
     * each one holds staging space and the system caps how many an app may own. Called
     * once per launch from the updater's background thread; never throws.
     */
    fun abandonStaleSessions(context: Context) {
        try {
            val installer = context.applicationContext.packageManager.packageInstaller
            for (session in installer.mySessions) {
                try {
                    installer.abandonSession(session.sessionId)
                    Log.i(TAG, "Abandoned stale install session ${session.sessionId}")
                } catch (e: Exception) {
                    Log.w(TAG, "Could not abandon session ${session.sessionId}: $e")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not list install sessions: $e")
        }
    }

    /**
     * Streams [apk] into a new session and commits it. Blocking (the whole file is copied),
     * so call from a background thread, with an application context since the result comes
     * back through a broadcast long after any activity may be gone.
     */
    fun install(context: Context, apk: File) {
        val app = context.applicationContext
        if (!apk.isFile || apk.length() <= 0L) {
            Log.w(TAG, "APK missing or empty: $apk")
            toast(app, app.getString(R.string.update_failed, "file missing"))
            return
        }
        try {
            installViaSession(app, apk)
        } catch (e: Exception) {
            Log.w(TAG, "Session install failed, falling back to the installer intent", e)
            fallbackInstall(app, apk, announce = true)
        }
    }

    private fun installViaSession(app: Context, apk: File) {
        val installer = app.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        params.setAppPackageName(app.packageName)
        params.setSize(apk.length())
        if (Build.VERSION.SDK_INT >= 31) {
            params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val sessionId = installer.createSession(params)
        installer.openSession(sessionId).use { session ->
            apk.inputStream().use { input ->
                session.openWrite("whooz.apk", 0, apk.length()).use { out ->
                    input.copyTo(out, DEFAULT_BUFFER_SIZE)
                    session.fsync(out)
                }
            }
            // Explicit component: the receiver is not exported, so an implicit action-only
            // broadcast would never be delivered and the status would silently vanish.
            val callback = Intent(app, Receiver::class.java)
                .setAction(ACTION_INSTALL_STATUS)
                .putExtra(EXTRA_APK_PATH, apk.path)
            // Mutable: the installer fills in the status extras of this very intent.
            val flags = if (Build.VERSION.SDK_INT >= 31) {
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            }
            val pending = PendingIntent.getBroadcast(app, sessionId, callback, flags)
            session.commit(pending.intentSender)
        }
        Log.i(TAG, "Committed install session $sessionId for ${apk.name}")
    }

    /** Hands the APK to the system installer UI through our FileProvider. */
    private fun fallbackInstall(app: Context, apk: File, announce: Boolean) {
        try {
            val uri = FileProvider.getUriForFile(app, "${app.packageName}.fileprovider", apk)
            val intent = Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            app.startActivity(intent)
            if (announce) toast(app, app.getString(R.string.update_open_file))
        } catch (e: Exception) {
            Log.w(TAG, "Fallback install failed", e)
            toast(app, app.getString(R.string.update_failed, e.message ?: e.javaClass.simpleName))
        }
    }

    private fun toast(app: Context, message: String) {
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(app, message, Toast.LENGTH_LONG).show()
        }
    }

    /** Receives the PackageInstaller status callbacks of committed sessions. */
    class Receiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val app = context.applicationContext
            when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -1)) {
                PackageInstaller.STATUS_PENDING_USER_ACTION -> launchConfirmation(app, intent)
                PackageInstaller.STATUS_SUCCESS -> {
                    Log.i(TAG, "Update installed")
                    toast(app, app.getString(R.string.update_installed))
                }
                PackageInstaller.STATUS_FAILURE_ABORTED -> Log.i(TAG, "Install cancelled by the user")
                else -> {
                    val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "status $status"
                    Log.w(TAG, "Install failed: $message")
                    toast(app, app.getString(R.string.update_failed, message))
                    val path = intent.getStringExtra(EXTRA_APK_PATH)
                    if (path != null && isOurCachedApk(app, File(path))) fallbackInstall(app, File(path), announce = false)
                }
            }
        }

        private fun launchConfirmation(app: Context, intent: Intent) {
            val confirm = if (Build.VERSION.SDK_INT >= 33) {
                intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(Intent.EXTRA_INTENT)
            }
            if (confirm == null) {
                Log.w(TAG, "PENDING_USER_ACTION without a confirmation intent")
                return
            }
            // The app is in the foreground (the banner was just tapped), so launching from
            // a receiver is allowed; NEW_TASK is mandatory outside an activity context.
            confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try {
                app.startActivity(confirm)
            } catch (e: Exception) {
                Log.w(TAG, "Could not launch the install confirmation", e)
            }
        }

        /** Only files inside our own update cache are ever handed to the system installer. */
        private fun isOurCachedApk(app: Context, file: File): Boolean = try {
            file.isFile && file.canonicalPath.startsWith(File(app.cacheDir, "updates").canonicalPath + File.separator)
        } catch (e: Exception) {
            false
        }
    }
}
