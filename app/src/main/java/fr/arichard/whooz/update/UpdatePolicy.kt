package fr.arichard.whooz.update

import java.io.EOFException
import java.io.InterruptedIOException
import java.net.SocketException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/**
 * Every timing decision of the self-updater, with the clock injected so it is testable
 * without Android. [UpdateManager] and [fr.arichard.whooz.ui.UpdateBanner] only translate
 * these answers into I/O.
 */
object UpdatePolicy {

    /** GitHub is asked at most this often; it is unauthenticated and rate limited. */
    const val CHECK_INTERVAL_MS: Long = 24L * 60 * 60 * 1000

    /** "Later" silences the banner for this long, then the same version is offered again. */
    const val DISMISS_INTERVAL_MS: Long = 24L * 60 * 60 * 1000

    /**
     * Whether the daily check should contact GitHub now.
     *
     * Due when it never ran, when the interval elapsed, or when the clock went backwards
     * (a stale future timestamp would otherwise block checks for days). A deferred update
     * (found earlier on metered data, or whose download died) retries as soon as an
     * unmetered network is available, but not before: on metered data the answer would be
     * "deferred" again.
     */
    fun isCheckDue(nowMs: Long, lastCheckMs: Long, deferred: Boolean, unmetered: Boolean): Boolean {
        if (lastCheckMs <= 0L) return true
        if (lastCheckMs > nowMs) return true
        if (nowMs - lastCheckMs >= CHECK_INTERVAL_MS) return true
        return deferred && unmetered
    }

    /** Downloads cost data: only on unmetered networks, unless the user explicitly asked. */
    fun shouldDownload(unmetered: Boolean, force: Boolean): Boolean = force || unmetered

    /**
     * True while the banner must stay hidden for [version] because the user tapped "Later"
     * on that same version less than [DISMISS_INTERVAL_MS] ago. A different version, an
     * elapsed interval, or a dismissal timestamp in the future (clock moved back) all show
     * the banner again.
     */
    fun isDismissed(version: String, dismissedVersion: String, dismissedAtMs: Long, nowMs: Long): Boolean {
        if (version.isEmpty() || version != dismissedVersion) return false
        val elapsed = nowMs - dismissedAtMs
        return elapsed >= 0L && elapsed < DISMISS_INTERVAL_MS
    }

    /**
     * Whether a finished check updates the throttle timestamp: a check that never reached
     * the network is not a check, so the next launch retries right away.
     */
    fun recordsCheck(reachedServer: Boolean): Boolean = reachedServer

    /**
     * Failures that mean "no usable connection right now" rather than a broken release, so
     * the check is not recorded and the next launch retries at once. Besides DNS, connect
     * and TLS failures this covers a connection that dies mid-transfer (reset, abort, EOF)
     * and every interrupted-I/O timeout: that is what a Wi-Fi drop looks like from inside a
     * read, and it must not pass for a server-side error.
     */
    fun isNetworkFailure(e: Throwable): Boolean =
        e is UnknownHostException || e is SocketException || e is InterruptedIOException ||
            e is EOFException || e is SSLException

    /**
     * Whether a release must not be downloaded because an earlier download of that exact
     * [version] was rejected after the fact (wrong signer, lower versionCode). Fetching it
     * again would cost a few MB per day and be rejected again, so only a different tag is
     * tried; a forced check (the Settings row) retries once in case the asset was replaced.
     */
    fun skipsRejected(version: String, rejectedVersion: String, force: Boolean): Boolean =
        !force && version.isNotEmpty() && version == rejectedVersion

    /**
     * PackageInstaller refuses a lower versionCode (INSTALL_FAILED_VERSION_DOWNGRADE) whatever
     * the tag says, so an archive is only worth offering when its code is at least the
     * installed one; equal is a legal reinstall.
     */
    fun acceptsVersionCode(archiveCode: Long, installedCode: Long): Boolean = archiveCode >= installedCode
}
