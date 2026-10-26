package fr.arichard.whooz.camera

import android.graphics.Matrix
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.annotation.UiThread
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageProxy
import androidx.camera.view.PreviewView
import com.google.android.gms.tasks.OnCompleteListener
import com.google.android.gms.tasks.Task
import com.google.mlkit.common.MlKitException
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetector
import com.google.mlkit.vision.face.FaceDetectorOptions
import fr.arichard.whooz.core.Timings
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * ML Kit face detection turned into [FaceFrame]s in view pixels.
 *
 * Owns the detector and its retry loop (Google Play services may still be downloading the face
 * model on a fresh install), the per-frame coordinate chain, the [FaceTracker] and the frame
 * hand-over to the UI thread. Everything heavy runs on the single analysis executor the
 * [CameraSession] created: [analyze] is entered there, ML Kit's completion listener is delivered
 * there, and the tracker is only ever touched there, so the tracker needs no lock.
 *
 * Coordinate chain per face, all on the analysis thread:
 * 1. ML Kit reports the box in UPRIGHT coordinates (the buffer rotated by `rotationDegrees`);
 * 2. [FaceGeometry.uprightToBuffer] rotates it back to raw buffer coordinates;
 * 3. the four corners go through the analysis→view [Matrix] (inverse of CameraX's
 *    sensor→buffer matrix followed by the PreviewView's sensor→view transform, which already
 *    contains the FILL_CENTER crop, the display rotation and the front-camera mirroring);
 * 4. the mapped corners' bounding box becomes a ring through [FaceGeometry.ringFromRect];
 * 5. a ring whose centre falls outside the view ([FaceClip]) is dropped: the FILL_CENTER
 *    preview crops a band on both sides of the 4:3 sensor image that ML Kit still sees, and a
 *    face nobody can see on screen must not play.
 *
 * The analysis→view matrix is computed on the UI thread (PreviewView demands it) and copied here
 * under a tiny lock; the UI thread refreshes it after every posted frame and on every layout, so
 * a transform that CameraX delivers late is picked up by the next frame instead of never. The
 * view size used by the clip comes the same way, from the layout listener, as two volatile ints.
 *
 * Frames are triple-buffered by copy: the tracker writes its own back frame, copies it into a
 * shared frame under the lock, and the UI runnable copies the shared frame into the one the
 * overlay draws. A plain two-buffer swap would let the writer reuse the frame the overlay is
 * still drawing; three 16-entry copies per frame cost nothing. An empty frame following an
 * empty frame with the same set version is not posted at all: nothing on screen can change, and
 * waking the UI thread 15 times a second to redraw nothing is exactly the kind of idle cost the
 * battery rule forbids.
 *
 * The in-flight [ImageProxy] lives in an [AtomicReference] so that whoever comes last, the
 * completion on the analysis thread or [release] on the UI thread, closes it: after release the
 * executor rejects ML Kit's completion, and an unclosed proxy would keep CameraX's analysis
 * ImageReader and its native buffers alive until the finalizer.
 *
 * Unavoidable per-frame allocations, all outside draw paths: the [InputImage] wrapper and the
 * continuation the Task API builds around the (preallocated) completion listener.
 */
class FaceSource(private val analysisExecutor: Executor, timings: Timings) {

    /** Both callbacks run on the UI thread. */
    interface Listener {
        /** A new frame is ready; [frame] is the same object every time, overwritten in place. */
        @UiThread
        fun onFaceFrame(frame: FaceFrame)

        /** One of [STATE_DOWNLOADING], [STATE_READY], [STATE_UNAVAILABLE]. */
        @UiThread
        fun onDetectorState(state: Int)
    }

    /**
     * What the debug overlay needs beyond the frame: the raw ML Kit boxes mapped to view pixels
     * (four corners per face) and the matrix the frame was mapped with, so the analysis bounds
     * can be drawn too. Filled only while [debugEnabled].
     */
    class DebugBoxes {
        var count: Int = 0
        val quads: FloatArray = FloatArray(FaceObservations.CAPACITY * 8)
        val analysisToView: Matrix = Matrix()
        var transformValid: Boolean = false

        fun copyFrom(other: DebugBoxes) {
            count = other.count
            System.arraycopy(other.quads, 0, quads, 0, count * 8)
            analysisToView.set(other.analysisToView)
            transformValid = other.transformValid
        }
    }

    @Volatile
    var listener: Listener? = null

    /** Written by the overlay toggle on the UI thread, read per frame on the analysis thread. */
    @Volatile
    var debugEnabled: Boolean = false

    /**
     * Current detector state, written on the UI thread. Starts READY (nothing to tell the
     * user) and only becomes READY again after a successful detection, never on mere detector
     * creation: creation succeeds while the model is still downloading, and reporting READY
     * then would make the banner blink on every 5 s retry.
     */
    @Volatile
    var state: Int = STATE_READY
        private set

    private val mainHandler = Handler(Looper.getMainLooper())
    private val tracker = FaceTracker(timings)
    private val observations = FaceObservations()
    private val busy = AtomicBoolean(false)

    private val options: FaceDetectorOptions = FaceDetectorOptions.Builder()
        .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
        .enableTracking()
        .setMinFaceSize(MIN_FACE_SIZE)
        // Contours would collapse detection to a single face; none of the three is needed.
        .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_NONE)
        .setContourMode(FaceDetectorOptions.CONTOUR_MODE_NONE)
        .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_NONE)
        .setExecutor(analysisExecutor)
        .build()

    @Volatile
    private var detector: FaceDetector? = null
    private var attempts = 0
    private var paused = true
    @Volatile
    private var released = false

    // ---- Transform bridge (guarded by lock) --------------------------------------------
    private val lock = Any()
    private val sensorToBuffer = Matrix()
    private var sensorToBufferKnown = false
    private val analysisToView = Matrix()
    private var analysisToViewKnown = false

    // UI-thread scratch for refreshTransform.
    private val uiSensorToBuffer = Matrix()
    private val uiAnalysisToView = Matrix()

    // Analysis-thread copy used for the current frame.
    private val localAnalysisToView = Matrix()

    // ---- Frames -------------------------------------------------------------------------
    private val backFrame = FaceFrame()
    private val sharedFrame = FaceFrame()
    /** The overlay's frame: owned by the UI thread, overwritten before each [Listener.onFaceFrame]. */
    val uiFrame = FaceFrame()

    private val backDebug = DebugBoxes()
    private val sharedDebug = DebugBoxes()
    /** Debug data matching [uiFrame]; UI thread. */
    val uiDebug = DebugBoxes()

    @Volatile
    private var clearRequested = false
    private val postPending = AtomicBoolean(false)

    /**
     * Overlay size in pixels for the visibility clip; written by the layout listener on the UI
     * thread, read once per frame on the analysis thread. Zero until the first layout.
     */
    @Volatile
    private var viewWidth = 0
    @Volatile
    private var viewHeight = 0

    /** False forces the next frame to be posted, whatever it contains (see class doc). */
    @Volatile
    private var lastPostedEmpty = false
    private var lastPostedSetVersion = 0

    // ---- Per-frame scratch (analysis thread) --------------------------------------------
    private val rect = FloatArray(4)
    private val points = FloatArray(8)
    private val ring = FloatArray(3)
    private val pendingProxy = AtomicReference<ImageProxy?>(null)
    private var rotation = 0
    private var bufferW = 0
    private var bufferH = 0

    private val completeListener = OnCompleteListener<List<Face>> { task -> onComplete(task) }

    private val postFrame = Runnable {
        postPending.set(false)
        if (released) return@Runnable
        synchronized(lock) {
            uiFrame.copyFrom(sharedFrame)
            uiDebug.copyFrom(sharedDebug)
        }
        listener?.onFaceFrame(uiFrame)
    }

    private val retryCreate = Runnable {
        if (!released && !paused && detector == null) tryCreateDetector()
    }

    /** Posted from the analysis thread when `process` said the model is not there yet. */
    private val reportUnavailable = Runnable {
        // A detector already dropped by an earlier report must not count twice.
        if (!released && detector != null) onDetectorFailure()
    }

    /** Posted from the analysis thread after the first successful detection of a detector. */
    private val reportReady = Runnable {
        if (released) return@Runnable
        attempts = 0
        setState(STATE_READY)
    }

    // ---- Lifecycle (UI thread) ----------------------------------------------------------

    /** Creates the detector if needed and resumes the retry loop; call from `onResume`/bind. */
    @UiThread
    fun start() {
        if (released) return
        paused = false
        if (detector == null) {
            mainHandler.removeCallbacks(retryCreate)
            tryCreateDetector()
        }
    }

    /** Stops the retry timer: nothing may run while the screen is not shown. */
    @UiThread
    fun pause() {
        paused = true
        mainHandler.removeCallbacks(retryCreate)
    }

    /** Closes the detector and drops every pending callback; the source is dead afterwards. */
    @UiThread
    fun release() {
        released = true
        paused = true
        mainHandler.removeCallbacks(retryCreate)
        mainHandler.removeCallbacks(reportUnavailable)
        mainHandler.removeCallbacks(reportReady)
        mainHandler.removeCallbacks(postFrame)
        listener = null
        val d = detector
        detector = null
        if (d != null) {
            try {
                d.close()
            } catch (e: Exception) {
                Log.w(TAG, "Detector close failed: $e")
            }
        }
        // The frame inside ML Kit, if any: its completion may never reach the analysis thread
        // now that the executor is going away, and CameraX needs every proxy back.
        closePending()
    }

    /**
     * Asks the analysis thread to forget every track before its next update (re-arm, re-bind,
     * pause). The shared frame is emptied at once so a runnable already in flight cannot hand
     * the overlay a stale set, and the next frame is posted whatever it holds so the overlay
     * sees the cleared state.
     */
    fun requestClear() {
        clearRequested = true
        lastPostedEmpty = false
        synchronized(lock) {
            sharedFrame.clear()
            sharedDebug.count = 0
        }
    }

    /** The overlay's size, from the PreviewView layout listener; faces outside it are dropped. */
    @UiThread
    fun setViewSize(width: Int, height: Int) {
        viewWidth = width
        viewHeight = height
    }

    /** True while a frame is inside ML Kit; the analyzer drops frames meanwhile. */
    fun isBusy(): Boolean = busy.get()

    /**
     * Recomputes the analysis→view matrix from the PreviewView; UI thread only. Returns false
     * while either side is unknown (no frame analysed yet, or no preview transform yet), in which
     * case the previous matrix, if any, is kept.
     */
    @UiThread
    fun refreshTransform(previewView: PreviewView): Boolean {
        synchronized(lock) {
            if (!sensorToBufferKnown) return false
            uiSensorToBuffer.set(sensorToBuffer)
        }
        val ok = CameraSession.currentAnalysisToView(previewView, uiSensorToBuffer, uiAnalysisToView)
        if (ok) {
            synchronized(lock) {
                analysisToView.set(uiAnalysisToView)
                analysisToViewKnown = true
            }
        }
        return ok
    }

    // ---- Analysis thread ----------------------------------------------------------------

    /**
     * Takes ownership of [proxy] (it is closed on every path) and runs detection on it. The
     * caller already checked [isBusy] and the throttle. Frames arriving before the view
     * transform is known are dropped but still wake the UI thread, which then computes it.
     */
    @androidx.annotation.OptIn(ExperimentalGetImage::class)
    fun analyze(proxy: ImageProxy) {
        val det = detector
        if (det == null || released) {
            proxy.close()
            return
        }
        val info = proxy.imageInfo
        val haveTransform: Boolean
        synchronized(lock) {
            sensorToBuffer.set(info.sensorToBufferTransformMatrix)
            sensorToBufferKnown = true
            haveTransform = analysisToViewKnown
            if (haveTransform) localAnalysisToView.set(analysisToView)
        }
        if (!haveTransform) {
            proxy.close()
            requestPost()
            return
        }
        val image = proxy.image
        if (image == null) {
            proxy.close()
            return
        }
        rotation = info.rotationDegrees
        bufferW = proxy.width
        bufferH = proxy.height
        busy.set(true)
        pendingProxy.set(proxy)
        try {
            val input = InputImage.fromMediaImage(image, rotation)
            det.process(input).addOnCompleteListener(analysisExecutor, completeListener)
        } catch (e: Exception) {
            Log.w(TAG, "process() threw: $e")
            closePending()
            busy.set(false)
            if (isModelUnavailable(e)) mainHandler.post(reportUnavailable)
        }
    }

    /** Closes the in-flight proxy, once, whichever thread gets here first. */
    private fun closePending() {
        // Closing the proxy (never the MediaImage) hands the buffer back to CameraX.
        pendingProxy.getAndSet(null)?.close()
    }

    private fun onComplete(task: Task<List<Face>>) {
        closePending()
        if (released) {
            // Nothing to track for any more; the proxy above was the only thing owed.
            busy.set(false)
            return
        }
        try {
            if (task.isSuccessful) {
                val faces = task.result
                if (faces != null) handleFaces(faces, SystemClock.uptimeMillis())
                if (state != STATE_READY) mainHandler.post(reportReady)
            } else {
                val e = task.exception
                if (e != null && isModelUnavailable(e)) {
                    mainHandler.post(reportUnavailable)
                } else {
                    Log.w(TAG, "Detection failed: $e")
                }
            }
        } finally {
            busy.set(false)
        }
    }

    private fun handleFaces(faces: List<Face>, nowMs: Long) {
        if (clearRequested) {
            clearRequested = false
            tracker.clear()
        }
        val debug = debugEnabled
        val viewW = viewWidth
        val viewH = viewHeight
        observations.clear()
        backDebug.count = 0
        val n = faces.size
        for (i in 0 until n) {
            val face = faces[i]
            val box = face.boundingBox
            FaceGeometry.uprightToBuffer(
                box.left.toFloat(), box.top.toFloat(), box.right.toFloat(), box.bottom.toFloat(),
                rotation, bufferW, bufferH, rect,
            )
            // Four corners rather than centre + one radius vector: the bounding box of the
            // mapped corners is right whatever the rotation or mirroring the matrix carries.
            points[0] = rect[0]; points[1] = rect[1]
            points[2] = rect[2]; points[3] = rect[1]
            points[4] = rect[2]; points[5] = rect[3]
            points[6] = rect[0]; points[7] = rect[3]
            localAnalysisToView.mapPoints(points)
            var minX = points[0]
            var maxX = points[0]
            var minY = points[1]
            var maxY = points[1]
            for (k in 1 until 4) {
                val x = points[k * 2]
                val y = points[k * 2 + 1]
                if (x < minX) minX = x
                if (x > maxX) maxX = x
                if (y < minY) minY = y
                if (y > maxY) maxY = y
            }
            FaceGeometry.ringFromRect(minX, minY, maxX, maxY, ring)
            // The raw box is kept for the debug overlay even when clipped: seeing what the
            // crop drops is the point of that overlay.
            if (debug && backDebug.count < FaceObservations.CAPACITY) {
                System.arraycopy(points, 0, backDebug.quads, backDebug.count * 8, 8)
                backDebug.count++
            }
            if (!FaceClip.isVisible(ring[0], ring[1], ring[2], viewW, viewH)) continue
            val id = face.trackingId ?: -1
            if (!observations.add(id, ring[0], ring[1], ring[2])) break
        }
        tracker.update(observations, nowMs)
        tracker.writeTo(backFrame)
        backFrame.bufferW = bufferW
        backFrame.bufferH = bufferH
        backFrame.rotation = rotation
        if (debug) {
            backDebug.analysisToView.set(localAnalysisToView)
            backDebug.transformValid = true
        } else {
            backDebug.transformValid = false
        }
        // Empty after empty with the same set: the overlay already shows nothing, so neither
        // the copy nor the UI wake-up buys anything (debug keeps posting: the raw boxes move).
        val empty = backFrame.count == 0
        if (empty && lastPostedEmpty && backFrame.setVersion == lastPostedSetVersion && !debug) return
        lastPostedEmpty = empty
        lastPostedSetVersion = backFrame.setVersion
        synchronized(lock) {
            sharedFrame.copyFrom(backFrame)
            sharedDebug.copyFrom(backDebug)
        }
        requestPost()
    }

    /** One pending runnable at most: a slow UI thread gets the latest frame, not a backlog. */
    private fun requestPost() {
        if (postPending.compareAndSet(false, true)) mainHandler.post(postFrame)
    }

    private fun isModelUnavailable(e: Throwable): Boolean =
        e is MlKitException && e.errorCode == MlKitException.UNAVAILABLE

    // ---- Detector creation and retry (UI thread) ----------------------------------------

    private fun tryCreateDetector() {
        try {
            detector = FaceDetection.getClient(options)
        } catch (e: Exception) {
            Log.w(TAG, "FaceDetection.getClient failed: $e")
            onDetectorFailure()
        }
    }

    /**
     * Counts one failure (creation threw, or `process` said the model is missing), drops the
     * detector and schedules a fresh creation in [RETRY_MS], up to [MAX_ATTEMPTS] times.
     */
    private fun onDetectorFailure() {
        attempts++
        val d = detector
        detector = null
        if (d != null) {
            try {
                d.close()
            } catch (e: Exception) {
                Log.w(TAG, "Detector close failed: $e")
            }
        }
        if (attempts >= MAX_ATTEMPTS) {
            setState(STATE_UNAVAILABLE)
            return
        }
        setState(STATE_DOWNLOADING)
        mainHandler.removeCallbacks(retryCreate)
        if (!paused) mainHandler.postDelayed(retryCreate, RETRY_MS)
    }

    private fun setState(newState: Int) {
        if (state == newState) return
        state = newState
        listener?.onDetectorState(newState)
    }

    companion object {
        private const val TAG = "Whooz.Face"

        const val STATE_DOWNLOADING = 0
        const val STATE_READY = 1
        const val STATE_UNAVAILABLE = 2

        /** Creation retry cadence while Play services fetches the model. */
        const val RETRY_MS = 5_000L

        /** Twelve tries = one minute of patience before suggesting finger mode. */
        const val MAX_ATTEMPTS = 12

        /** Faces smaller than 15 % of the frame are ignored: too far to be a player anyway. */
        const val MIN_FACE_SIZE = 0.15f
    }
}
