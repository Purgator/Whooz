package fr.arichard.whooz.ui

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.provider.Settings as SystemSettings
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import fr.arichard.whooz.R
import fr.arichard.whooz.core.Settings
import fr.arichard.whooz.update.ApkInstaller
import fr.arichard.whooz.update.UpdateManager
import fr.arichard.whooz.update.UpdatePolicy

/**
 * The "Whooz vX is ready" strip at the bottom of the arena, with Install and Later.
 *
 * Adds itself to [container] at construction and stays GONE until [offer] is called with
 * an update the user has not dismissed within 24 h. The host hides it while fingers are
 * down through [setAllowed]; the pending offer survives so the strip comes back on idle.
 */
class UpdateBanner(private val activity: Activity, container: FrameLayout) {

    private val view: View = LayoutInflater.from(activity).inflate(R.layout.view_update_banner, container, false)
    private val text: TextView = view.findViewById(R.id.update_text)
    private val settings = Settings(activity)

    private var offer: UpdateManager.ReadyUpdate? = null
    private var allowed = true

    /** Set when Install bounced to the unknown-sources screen; [retryIfPending] consumes it. */
    private var pendingInstall = false

    init {
        view.visibility = View.GONE
        view.findViewById<View>(R.id.update_install).setOnClickListener { install() }
        view.findViewById<View>(R.id.update_later).setOnClickListener { later() }
        container.addView(view)
    }

    /** Shows [update] unless the user tapped Later on this version less than a day ago. */
    fun offer(update: UpdateManager.ReadyUpdate) {
        val dismissed = UpdatePolicy.isDismissed(
            update.version, settings.updateDismissedVersion, settings.updateDismissedAt, System.currentTimeMillis(),
        )
        if (dismissed) {
            hide()
            return
        }
        offer = update
        text.text = activity.getString(R.string.update_ready, update.version)
        apply()
    }

    /** Drops the offer and hides the strip. */
    fun hide() {
        offer = null
        pendingInstall = false
        apply()
    }

    /**
     * Visibility gate from the host's chrome rule: false while any finger is down or a
     * round runs, true back in IDLE. The offer is kept either way.
     */
    fun setAllowed(idle: Boolean) {
        allowed = idle
        apply()
    }

    /**
     * Called by the host's `onResume`: if Install had to send the user to the
     * unknown-sources setting, continue the install now that the permission may be granted.
     * If it was refused, the banner simply stays, ready for another tap.
     */
    fun retryIfPending() {
        if (!pendingInstall) return
        pendingInstall = false
        if (offer != null && activity.packageManager.canRequestPackageInstalls()) startInstall()
    }

    private fun apply() {
        val show = offer != null && allowed
        view.visibility = if (show) View.VISIBLE else View.GONE
        view.isClickable = show
    }

    private fun install() {
        if (offer == null) return
        if (activity.packageManager.canRequestPackageInstalls()) {
            startInstall()
            return
        }
        pendingInstall = true
        try {
            activity.startActivity(
                Intent(SystemSettings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${activity.packageName}")),
            )
        } catch (e: Exception) {
            Log.w(UpdateManager.TAG, "Could not open the unknown-sources setting", e)
            pendingInstall = false
        }
    }

    /** Copies the APK into an installer session off the UI thread; the strip goes away meanwhile. */
    private fun startInstall() {
        val update = offer ?: return
        val app = activity.applicationContext
        hide()
        val thread = Thread({ ApkInstaller.install(app, update.file) }, "whooz-install")
        thread.isDaemon = true
        thread.start()
    }

    private fun later() {
        val update = offer ?: return
        settings.updateDismissedVersion = update.version
        settings.updateDismissedAt = System.currentTimeMillis()
        hide()
    }
}
