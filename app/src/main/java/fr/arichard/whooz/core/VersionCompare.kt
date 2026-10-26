package fr.arichard.whooz.core

/**
 * Dotted-version arithmetic for the self-updater. Pure and total: any string, including
 * null or garbage, compares without throwing, because the inputs come from a GitHub tag
 * and from cached file names that the app does not control.
 *
 * Rules: an optional leading `v`/`V` and surrounding whitespace are ignored; each dotted
 * part counts by its leading digits ("3-rc1" is 3), a part without digits counts as 0, and
 * missing trailing parts count as 0 (so "2.0" equals "2").
 */
object VersionCompare {

    /** "v1.2 " → "1.2"; null → "". */
    fun normalize(version: String?): String {
        if (version == null) return ""
        val trimmed = version.trim()
        return if (trimmed.startsWith("v") || trimmed.startsWith("V")) trimmed.substring(1).trim() else trimmed
    }

    /** Negative when [a] is older than [b], zero when equal, positive when newer. */
    fun compareVersions(a: String?, b: String?): Int {
        val x = normalize(a).split('.')
        val y = normalize(b).split('.')
        val n = if (x.size > y.size) x.size else y.size
        for (i in 0 until n) {
            val px = if (i < x.size) partValue(x[i]) else 0L
            val py = if (i < y.size) partValue(y[i]) else 0L
            if (px != py) return if (px < py) -1 else 1
        }
        return 0
    }

    /** True only when [remote] is strictly newer than [local]: equal or garbage never updates. */
    fun isNewer(remote: String?, local: String?): Boolean = compareVersions(remote, local) > 0

    /** Leading digits of a part, capped so an absurd number cannot overflow into a negative. */
    private fun partValue(part: String): Long {
        var value = 0L
        var i = 0
        val s = part.trim()
        while (i < s.length) {
            val c = s[i]
            if (c < '0' || c > '9') break
            value = value * 10 + (c - '0')
            if (value > MAX_PART) return MAX_PART
            i++
        }
        return value
    }

    private const val MAX_PART = 1_000_000_000L
}
