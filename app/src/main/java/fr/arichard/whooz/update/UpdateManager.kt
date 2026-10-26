package fr.arichard.whooz.update

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.util.Log
import androidx.core.content.pm.PackageInfoCompat
import fr.arichard.whooz.core.ReleaseInfo
import fr.arichard.whooz.core.ReleaseParser
import fr.arichard.whooz.core.Settings
import fr.arichard.whooz.core.VersionCompare
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Self-updater backed by GitHub Releases, driven only while the app is on screen.
 *
 * Once a day the main screen asks GitHub for the latest release; a newer APK is downloaded
 * on unmetered networks into the cache and offered by an in-app banner. Before an APK is
 * ever reported as ready its package name, versionCode and signing certificates are
 * compared with the running app's, so a wrong, tampered or downgrading file is deleted
 * instead of being handed to the installer (which would reject it anyway, but with a
 * confusing system error), and its version is remembered so it is not fetched again.
 *
 * Every function here does blocking I/O and must run on a background thread, except
 * [readyUpdate], which is local and cheap enough for `onResume`.
 */
object UpdateManager {

    const val TAG = "Whooz.Update"

    private const val API_URL = "https://api.github.com/repos/Purgator/Whooz/releases/latest"
    private const val USER_AGENT = "Whooz-updater"
    private const val CACHE_DIR = "updates"
    private const val FILE_PREFIX = "Whooz-v"
    private const val FILE_SUFFIX = ".apk"
    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val READ_TIMEOUT_MS = 20_000
    private const val DOWNLOAD_READ_TIMEOUT_MS = 60_000

    /**
     * [UPDATE_DEFERRED] and [DOWNLOAD_FAILED] both mean "a newer release is known but not on
     * disk": the first by choice (metered network), the second because the transfer died.
     * Both retry on the next unmetered network instead of waiting out the daily throttle.
     */
    enum class Status { UP_TO_DATE, UPDATE_READY, UPDATE_DEFERRED, DOWNLOAD_FAILED, NO_NETWORK, ERROR }

    /** Outcome of one check, for the Settings "Check now" toast. */
    class Result(val status: Status, val version: String? = null, val detail: String? = null)

    /** A verified, fully downloaded newer APK waiting for the user's tap. */
    class ReadyUpdate(val file: File, val version: String)

    /** Path + size + mtime of the last APK whose checks passed, so onResume never re-parses it. */
    @Volatile private var verifiedPath: String? = null
    @Volatile private var verifiedLength = 0L
    @Volatile private var verifiedModified = 0L
    @Volatile private var staleSessionsAbandoned = false

    /** Installed versionName, "0" when the package manager fails (never happens for ourselves). */
    fun currentVersion(context: Context): String = try {
        VersionCompare.normalize(packageInfo(context.packageManager, context.packageName, 0)?.versionName).ifEmpty { "0" }
    } catch (e: Exception) {
        "0"
    }

    /**
     * The daily check kicked off by the main screen after its first frame. Returns the
     * update to offer, or null. Cheap when throttled: an already downloaded APK is found
     * locally. Also abandons leftover installer sessions once per process, since this is
     * the one background thread that runs at every launch.
     *
     * The network check runs whenever it is due even when a newer APK already sits in the
     * cache: nothing else ever asks GitHub again, so a release the user keeps postponing
     * would otherwise hide every later one for good. A cached file of the same version is
     * reused by [check] without a download, so a due check with nothing new costs one API
     * request, and [readyUpdate] then offers the newest file on disk.
     */
    fun maybeDailyCheck(context: Context): ReadyUpdate? {
        val app = context.applicationContext
        if (!staleSessionsAbandoned) {
            staleSessionsAbandoned = true
            ApkInstaller.abandonStaleSessions(app)
        }
        val settings = Settings(app)
        if (settings.autoUpdate) {
            val due = UpdatePolicy.isCheckDue(
                System.currentTimeMillis(), settings.lastUpdateCheck, settings.updateDeferred, isUnmetered(app),
            )
            if (due) checkNow(app, force = false)
        }
        return readyUpdate(app)
    }

    /**
     * Full check against GitHub. With [force] (the Settings row) the APK is downloaded on
     * any network and a version rejected earlier is tried once more; otherwise only on
     * unmetered ones. Records the throttle bookkeeping in [Settings] whenever the server
     * was actually reached. Synchronized so the daily check and a "Check now" tap never
     * download the same file twice.
     */
    @Synchronized
    fun checkNow(context: Context, force: Boolean): Result {
        val app = context.applicationContext
        if (!isOnline(app)) return Result(Status.NO_NETWORK)
        val settings = Settings(app)
        val result = try {
            check(app, settings, UpdatePolicy.shouldDownload(isUnmetered(app), force), force)
        } catch (e: Exception) {
            Log.w(TAG, "Update check failed: $e")
            val status = if (UpdatePolicy.isNetworkFailure(e)) Status.NO_NETWORK else Status.ERROR
            Result(status, null, e.message ?: e.javaClass.simpleName)
        }
        if (UpdatePolicy.recordsCheck(result.status != Status.NO_NETWORK)) {
            settings.lastUpdateCheck = System.currentTimeMillis()
            settings.updateDeferred =
                result.status == Status.UPDATE_DEFERRED || result.status == Status.DOWNLOAD_FAILED
        }
        if (force && result.status == Status.UPDATE_READY) {
            // The user asked for this update explicitly and is told to "go back to install":
            // an earlier "Later" on the same version must not keep the banner hidden until
            // its day is up, or there would be no way to install what was just fetched.
            settings.updateDismissedVersion = ""
            settings.updateDismissedAt = 0L
        }
        Log.i(TAG, "Check: ${result.status} ${result.version ?: ""} ${result.detail ?: ""}")
        return result
    }

    private fun check(app: Context, settings: Settings, allowDownload: Boolean, force: Boolean): Result {
        val release: ReleaseInfo = ReleaseParser.parse(httpGet(API_URL))
            ?: return Result(Status.ERROR, null, "no usable release")
        val current = currentVersion(app)
        cleanupOldApks(app)
        if (!VersionCompare.isNewer(release.version, current)) return Result(Status.UP_TO_DATE, current)

        val apk = apkFile(app, release.version)
        if (apk.isFile && apk.length() == release.assetSize && isVerified(app, apk)) {
            return Result(Status.UPDATE_READY, release.version)
        }
        if (UpdatePolicy.skipsRejected(release.version, settings.updateRejectedVersion, force)) {
            return Result(Status.ERROR, release.version, "rejected earlier, not downloaded again")
        }
        if (!allowDownload) return Result(Status.UPDATE_DEFERRED, release.version)

        // From here on the release is known, so a transfer that dies (reset, EOF, truncated
        // body) is "not downloaded yet", retried on the next unmetered network, and never a
        // full day of silence; the same exception before this point is a failed check.
        try {
            download(release.assetUrl, apk, release.assetSize)
        } catch (e: Exception) {
            Log.w(TAG, "Download of ${release.version} failed: $e")
            return Result(Status.DOWNLOAD_FAILED, release.version, e.message ?: e.javaClass.simpleName)
        }
        if (!isVerified(app, apk)) {
            // Remembered so the same asset is not fetched and thrown away again every day;
            // a new tag, or a forced check, tries again.
            settings.updateRejectedVersion = release.version
            return Result(Status.ERROR, release.version, "rejected")
        }
        if (settings.updateRejectedVersion == release.version) settings.updateRejectedVersion = ""
        Log.i(TAG, "Downloaded update ${release.version} (${apk.length()} bytes)")
        return Result(Status.UPDATE_READY, release.version)
    }

    /**
     * The newest downloaded APK that is newer than the installed app and passes the archive
     * checks, or null. Local only: no network, no preference write. Safe on the main thread
     * once the file has been verified (the result is remembered per file).
     */
    fun readyUpdate(context: Context): ReadyUpdate? {
        val app = context.applicationContext
        val current = currentVersion(app)
        var best: File? = null
        var bestVersion = ""
        val files = File(app.cacheDir, CACHE_DIR).listFiles() ?: return null
        for (file in files) {
            val version = versionOf(file) ?: continue
            if (file.length() <= 0L || !VersionCompare.isNewer(version, current)) continue
            if (best == null || VersionCompare.isNewer(version, bestVersion)) {
                best = file
                bestVersion = version
            }
        }
        val file = best ?: return null
        return if (isVerified(app, file)) ReadyUpdate(file, bestVersion) else null
    }

    /** Deletes cached APKs that are not newer than the installed app, plus leftovers. */
    fun cleanupOldApks(context: Context) {
        val app = context.applicationContext
        val current = currentVersion(app)
        val files = File(app.cacheDir, CACHE_DIR).listFiles() ?: return
        for (file in files) {
            val version = versionOf(file)
            if (version == null || !VersionCompare.isNewer(version, current)) {
                if (file.delete()) Log.i(TAG, "Removed ${file.name}")
            }
        }
    }

    /** "Whooz-v1.2.apk" → "1.2"; anything else (tmp files, foreign files) → null. */
    private fun versionOf(file: File): String? {
        val name = file.name
        if (!file.isFile || !name.startsWith(FILE_PREFIX) || !name.endsWith(FILE_SUFFIX)) return null
        val version = name.substring(FILE_PREFIX.length, name.length - FILE_SUFFIX.length)
        return if (version.isEmpty()) null else version
    }

    private fun apkFile(app: Context, version: String): File =
        File(app.cacheDir, "$CACHE_DIR/$FILE_PREFIX$version$FILE_SUFFIX").apply { parentFile?.mkdirs() }

    // ---- Archive verification -----------------------------------------------------------

    /**
     * True when [apk] is a package named like us, not a versionCode downgrade, and signed
     * with exactly our certificates. A failed check is logged and the file is deleted, so it
     * is never offered again.
     */
    private fun isVerified(app: Context, apk: File): Boolean {
        if (apk.path == verifiedPath && apk.length() == verifiedLength && apk.lastModified() == verifiedModified) {
            return true
        }
        val ok = try {
            isInstallable(app, apk)
        } catch (e: Exception) {
            Log.w(TAG, "Archive check failed: $e")
            false
        }
        if (ok) {
            verifiedPath = apk.path
            verifiedLength = apk.length()
            verifiedModified = apk.lastModified()
        } else {
            Log.w(TAG, "Rejecting ${apk.name}: not installable over the running app")
            apk.delete()
        }
        return ok
    }

    /**
     * Package name, versionCode and signers of the archive against the installed app's. The
     * versionCode matters because newer-ness is decided from the tag (a versionName) while
     * PackageInstaller only looks at the code: a release whose code was not bumped would
     * otherwise be offered every day and fail at every Install tap with a system error.
     */
    private fun isInstallable(app: Context, apk: File): Boolean {
        val pm = app.packageManager
        val archive = archiveInfo(pm, apk.path, signingFlags()) ?: return false
        if (archive.packageName != app.packageName) {
            Log.w(TAG, "${apk.name} is ${archive.packageName}, not ${app.packageName}")
            return false
        }
        val installed = packageInfo(pm, app.packageName, signingFlags()) ?: return false
        val archiveCode = PackageInfoCompat.getLongVersionCode(archive)
        val installedCode = PackageInfoCompat.getLongVersionCode(installed)
        if (!UpdatePolicy.acceptsVersionCode(archiveCode, installedCode)) {
            Log.w(TAG, "${apk.name} has versionCode $archiveCode, below the installed $installedCode")
            return false
        }
        val ours = signatures(installed)
        var theirs = signatures(archive)
        if (theirs.isEmpty() && Build.VERSION.SDK_INT >= 28) {
            // Some Android 9 builds leave signingInfo empty for archives; the legacy flag still works.
            @Suppress("DEPRECATION")
            theirs = signatures(archiveInfo(pm, apk.path, PackageManager.GET_SIGNATURES))
        }
        if (ours.isEmpty() || theirs.size != ours.size) {
            Log.w(TAG, "${apk.name} is not signed like the installed app")
            return false
        }
        for (sig in theirs) {
            var found = false
            for (mine in ours) if (mine.contentEquals(sig)) { found = true; break }
            if (!found) {
                Log.w(TAG, "${apk.name} is not signed like the installed app")
                return false
            }
        }
        return true
    }

    @Suppress("DEPRECATION")
    private fun signingFlags(): Int =
        if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES

    /** Certificates of a package as raw DER bytes, empty when unknown. */
    @Suppress("DEPRECATION")
    private fun signatures(info: PackageInfo?): List<ByteArray> {
        if (info == null) return emptyList()
        val raw: Array<Signature>? = if (Build.VERSION.SDK_INT >= 28) {
            info.signingInfo?.apkContentsSigners ?: info.signatures
        } else {
            info.signatures
        }
        return raw?.map { it.toByteArray() } ?: emptyList()
    }

    @Suppress("DEPRECATION")
    private fun packageInfo(pm: PackageManager, packageName: String, flags: Int): PackageInfo? =
        if (Build.VERSION.SDK_INT >= 33) {
            pm.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(flags.toLong()))
        } else {
            pm.getPackageInfo(packageName, flags)
        }

    @Suppress("DEPRECATION")
    private fun archiveInfo(pm: PackageManager, path: String, flags: Int): PackageInfo? =
        if (Build.VERSION.SDK_INT >= 33) {
            pm.getPackageArchiveInfo(path, PackageManager.PackageInfoFlags.of(flags.toLong()))
        } else {
            pm.getPackageArchiveInfo(path, flags)
        }

    // ---- Network ------------------------------------------------------------------------

    private fun capabilities(app: Context): NetworkCapabilities? {
        val cm = app.getSystemService(ConnectivityManager::class.java) ?: return null
        return cm.getNetworkCapabilities(cm.activeNetwork)
    }

    private fun isOnline(app: Context): Boolean =
        capabilities(app)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true

    private fun isUnmetered(app: Context): Boolean =
        capabilities(app)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) == true

    private fun httpGet(url: String): String {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.setRequestProperty("Accept", "application/vnd.github+json")
            connection.setRequestProperty("User-Agent", USER_AGENT)
            val code = connection.responseCode
            if (code != HttpURLConnection.HTTP_OK) throw IOException("HTTP $code")
            return connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    /**
     * Streams [url] into a temp file next to [target], refuses to read past [expectedSize]
     * (the cap protects the cache from a runaway body), then renames atomically so a
     * half-written file can never be mistaken for a ready update.
     */
    private fun download(url: String, target: File, expectedSize: Long) {
        val tmp = File(target.path + ".tmp")
        try {
            val connection = URL(url).openConnection() as HttpURLConnection
            try {
                connection.connectTimeout = CONNECT_TIMEOUT_MS
                connection.readTimeout = DOWNLOAD_READ_TIMEOUT_MS
                connection.instanceFollowRedirects = true
                connection.setRequestProperty("User-Agent", USER_AGENT)
                val code = connection.responseCode
                if (code != HttpURLConnection.HTTP_OK) throw IOException("HTTP $code")
                connection.inputStream.use { input ->
                    FileOutputStream(tmp).use { output ->
                        val buffer = ByteArray(64 * 1024)
                        var total = 0L
                        while (true) {
                            val n = input.read(buffer)
                            if (n < 0) break
                            total += n
                            if (total > expectedSize) throw IOException("body larger than $expectedSize")
                            output.write(buffer, 0, n)
                        }
                        output.fd.sync()
                    }
                }
            } finally {
                connection.disconnect()
            }
            if (tmp.length() != expectedSize) {
                throw IOException("size mismatch: got ${tmp.length()}, expected $expectedSize")
            }
            target.delete()
            if (!tmp.renameTo(target)) throw IOException("could not move downloaded file")
        } finally {
            tmp.delete()
        }
    }
}
