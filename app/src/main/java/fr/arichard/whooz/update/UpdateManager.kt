package fr.arichard.whooz.update

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.util.Log
import fr.arichard.whooz.core.ReleaseInfo
import fr.arichard.whooz.core.ReleaseParser
import fr.arichard.whooz.core.Settings
import fr.arichard.whooz.core.VersionCompare
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/**
 * Self-updater backed by GitHub Releases, driven only while the app is on screen.
 *
 * Once a day the main screen asks GitHub for the latest release; a newer APK is downloaded
 * on unmetered networks into the cache and offered by an in-app banner. Before an APK is
 * ever reported as ready its signing certificates are compared with the running app's, so
 * a wrong or tampered file is deleted instead of being handed to the installer (which
 * would reject it anyway, but with a confusing system error).
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

    enum class Status { UP_TO_DATE, UPDATE_READY, UPDATE_DEFERRED, NO_NETWORK, ERROR }

    /** Outcome of one check, for the Settings "Check now" toast. */
    class Result(val status: Status, val version: String? = null, val detail: String? = null)

    /** A verified, fully downloaded newer APK waiting for the user's tap. */
    class ReadyUpdate(val file: File, val version: String)

    /** Path + size + mtime of the last APK whose signature passed, so onResume never re-parses it. */
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
     */
    fun maybeDailyCheck(context: Context): ReadyUpdate? {
        val app = context.applicationContext
        if (!staleSessionsAbandoned) {
            staleSessionsAbandoned = true
            ApkInstaller.abandonStaleSessions(app)
        }
        readyUpdate(app)?.let { return it }
        val settings = Settings(app)
        if (!settings.autoUpdate) return null
        val due = UpdatePolicy.isCheckDue(
            System.currentTimeMillis(), settings.lastUpdateCheck, settings.updateDeferred, isUnmetered(app),
        )
        if (!due) return null
        val result = checkNow(app, force = false)
        return if (result.status == Status.UPDATE_READY) readyUpdate(app) else null
    }

    /**
     * Full check against GitHub. With [force] (the Settings row) the APK is downloaded on
     * any network; otherwise only on unmetered ones. Records the throttle bookkeeping in
     * [Settings] whenever the server was actually reached. Synchronized so the daily check
     * and a "Check now" tap never download the same file twice.
     */
    @Synchronized
    fun checkNow(context: Context, force: Boolean): Result {
        val app = context.applicationContext
        if (!isOnline(app)) return Result(Status.NO_NETWORK)
        val result = try {
            check(app, UpdatePolicy.shouldDownload(isUnmetered(app), force))
        } catch (e: Exception) {
            Log.w(TAG, "Update check failed: $e")
            Result(if (isNetworkFailure(e)) Status.NO_NETWORK else Status.ERROR, null, e.message ?: e.javaClass.simpleName)
        }
        if (UpdatePolicy.recordsCheck(result.status != Status.NO_NETWORK)) {
            val settings = Settings(app)
            settings.lastUpdateCheck = System.currentTimeMillis()
            settings.updateDeferred = result.status == Status.UPDATE_DEFERRED
        }
        Log.i(TAG, "Check: ${result.status} ${result.version ?: ""} ${result.detail ?: ""}")
        return result
    }

    private fun check(app: Context, allowDownload: Boolean): Result {
        val release: ReleaseInfo = ReleaseParser.parse(httpGet(API_URL))
            ?: return Result(Status.ERROR, null, "no usable release")
        val current = currentVersion(app)
        cleanupOldApks(app)
        if (!VersionCompare.isNewer(release.version, current)) return Result(Status.UP_TO_DATE, current)

        val apk = apkFile(app, release.version)
        if (apk.isFile && apk.length() == release.assetSize && isVerified(app, apk)) {
            return Result(Status.UPDATE_READY, release.version)
        }
        if (!allowDownload) return Result(Status.UPDATE_DEFERRED, release.version)

        download(release.assetUrl, apk, release.assetSize)
        if (!isVerified(app, apk)) {
            apk.delete()
            return Result(Status.ERROR, release.version, "signature mismatch")
        }
        Log.i(TAG, "Downloaded update ${release.version} (${apk.length()} bytes)")
        return Result(Status.UPDATE_READY, release.version)
    }

    /**
     * The newest downloaded APK that is newer than the installed app and carries our
     * signature, or null. Local only: no network, no preference write. Safe on the main
     * thread once the file has been verified (the result is remembered per file).
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

    // ---- Signature verification ---------------------------------------------------------

    /**
     * True when [apk] is a package named like us and signed with exactly our certificates.
     * A failed check is logged and the file is deleted, so it is never offered again.
     */
    private fun isVerified(app: Context, apk: File): Boolean {
        if (apk.path == verifiedPath && apk.length() == verifiedLength && apk.lastModified() == verifiedModified) {
            return true
        }
        val ok = try {
            sameSigner(app, apk)
        } catch (e: Exception) {
            Log.w(TAG, "Signature check failed: $e")
            false
        }
        if (ok) {
            verifiedPath = apk.path
            verifiedLength = apk.length()
            verifiedModified = apk.lastModified()
        } else {
            Log.w(TAG, "Rejecting ${apk.name}: not signed like the installed app")
            apk.delete()
        }
        return ok
    }

    private fun sameSigner(app: Context, apk: File): Boolean {
        val pm = app.packageManager
        val archive = archiveInfo(pm, apk.path, signingFlags()) ?: return false
        if (archive.packageName != app.packageName) return false
        val ours = signatures(packageInfo(pm, app.packageName, signingFlags()))
        var theirs = signatures(archive)
        if (theirs.isEmpty() && Build.VERSION.SDK_INT >= 28) {
            // Some Android 9 builds leave signingInfo empty for archives; the legacy flag still works.
            @Suppress("DEPRECATION")
            theirs = signatures(archiveInfo(pm, apk.path, PackageManager.GET_SIGNATURES))
        }
        if (ours.isEmpty() || theirs.size != ours.size) return false
        for (sig in theirs) {
            var found = false
            for (mine in ours) if (mine.contentEquals(sig)) { found = true; break }
            if (!found) return false
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

    /** Failures that mean "no usable connection right now" rather than a broken release. */
    private fun isNetworkFailure(e: Exception): Boolean =
        e is UnknownHostException || e is ConnectException || e is SocketTimeoutException || e is SSLException

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
