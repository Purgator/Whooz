package fr.arichard.whooz.camera

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File
import java.io.IOException

/** Small on-device history of the last few roll-call rosters. */
class RecentGroupStore(context: Context) {

    data class Summary(val id: String, val count: Int)

    private val root = File(context.applicationContext.filesDir, DIRECTORY)

    fun summaries(): List<Summary> = groupDirectories().map { directory ->
        Summary(directory.name, portraitFiles(directory).size)
    }

    fun saveGroup(portraits: List<Bitmap>) {
        if (portraits.isEmpty()) return
        if (!root.exists() && !root.mkdirs()) return
        val directory = File(root, "${System.currentTimeMillis()}_${System.nanoTime()}")
        if (!directory.mkdirs()) return
        try {
            portraits.forEachIndexed { index, bitmap ->
                File(directory, "$index.jpg").outputStream().use { output ->
                    check(bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, output)) {
                        "Could not save portrait $index"
                    }
                }
            }
            prune()
        } catch (error: IOException) {
            directory.deleteRecursively()
            throw error
        } catch (error: IllegalStateException) {
            directory.deleteRecursively()
            throw error
        }
    }

    fun load(summary: Summary): List<Bitmap> {
        val directory = File(root, summary.id)
        return portraitFiles(directory).mapNotNull { BitmapFactory.decodeFile(it.path) }
    }

    private fun groupDirectories(): List<File> = root.listFiles()
        ?.filter { it.isDirectory }
        ?.sortedByDescending { it.name }
        ?: emptyList()

    private fun portraitFiles(directory: File): List<File> = directory.listFiles()
        ?.filter { it.isFile && it.extension == "jpg" }
        ?.sortedBy { it.nameWithoutExtension.toIntOrNull() ?: Int.MAX_VALUE }
        ?: emptyList()

    private fun prune() {
        groupDirectories().drop(MAX_GROUPS).forEach(File::deleteRecursively)
    }

    companion object {
        private const val DIRECTORY = "recent_groups"
        private const val MAX_GROUPS = 5
        private const val JPEG_QUALITY = 90
    }
}