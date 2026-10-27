package fr.arichard.whooz.camera

import android.content.Context
import android.graphics.Matrix
import android.util.Log
import android.util.Size
import androidx.annotation.UiThread
import androidx.camera.core.CameraInfoUnavailableException
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.UseCaseGroup
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.google.common.util.concurrent.ListenableFuture
import fr.arichard.whooz.core.Timings
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException

/**
 * CameraX glue for the camera screen: provider, selector, Preview + ImageAnalysis bound as one
 * [UseCaseGroup] with the PreviewView's viewport, and the analyzer gates.
 *
 * The analyzer itself only decides whether a frame is worth analysing: one frame in flight
 * ([FaceSource.isBusy]) and at most one every 66 ms ([FrameThrottle]); everything else is
 * closed on the spot so CameraX never runs out of buffers. Every method is UI-thread only
 * except the analyzer, which CameraX calls on the single "whooz-face" executor that also runs
 * ML Kit and the tracker (see [FaceSource]).
 *
 * The lambdas here capture only this session and its [FaceSource], never the activity, so a
 * provider future resolving after the screen is gone cannot leak it. The executor rejects
 * nothing after [release]: ML Kit's completion is posted through [safeExecutor], which swallows
 * the rejection instead of crashing the Task thread; the proxy that completion would have
 * closed is closed by [FaceSource.release] instead.
 *
 * [bind] is idempotent for the same owner: the use cases are bound to the activity lifecycle,
 * so CameraX already closes the camera on `onStop` and reopens it on `onStart`, and a resume
 * must not unbind and rebind on top of that (every open costs hundreds of milliseconds of HAL
 * work and a black preview). The full unbind-and-bind path runs only for the first bind, after
 * [unbindAll] (sleep) or for a different owner; [setAnalyzerEnabled] gives the resume path the
 * tracker clear and throttle reset it needs.
 */
class CameraSession(context: Context, private val previewView: PreviewView, timings: Timings) {

    /** UI thread. */
    interface Listener {
        /** The camera was bound; [front] is false when only a back camera exists. */
        fun onCameraBound(front: Boolean)

        /** Binding failed for good (no usable camera): the screen should suggest finger mode. */
        fun onCameraUnavailable()
    }

    var listener: Listener? = null

    private val appContext: Context = context.applicationContext
    private val executor: ExecutorService = Executors.newSingleThreadExecutor { r -> Thread(r, "whooz-face") }
    private val safeExecutor = Executor { r ->
        try {
            executor.execute(r)
        } catch (_: RejectedExecutionException) {
            // Released: a late ML Kit completion has nothing left to do.
        }
    }
    private val mainExecutor: Executor = ContextCompat.getMainExecutor(appContext)

    val faceSource = FaceSource(safeExecutor, timings)
    private val throttle = FrameThrottle(FrameThrottle.DEFAULT_INTERVAL_NS)

    private var providerFuture: ListenableFuture<ProcessCameraProvider>? = null
    private var provider: ProcessCameraProvider? = null
    private var owner: LifecycleOwner? = null
    private var bindRequested = false
    private var bound = false
    private var front = true
    private var analyzerEnabled = true
    private var released = false

    private val preview: Preview = Preview.Builder().build()

    private val analysis: ImageAnalysis = ImageAnalysis.Builder()
        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
        .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
        .setResolutionSelector(
            ResolutionSelector.Builder()
                .setResolutionStrategy(
                    ResolutionStrategy(
                        Size(ANALYSIS_WIDTH, ANALYSIS_HEIGHT),
                        ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER,
                    ),
                )
                .build(),
        )
        .build()

    private val analyzer = ImageAnalysis.Analyzer { proxy ->
        if (faceSource.isBusy() || !throttle.accept(System.nanoTime())) {
            proxy.close()
        } else {
            faceSource.analyze(proxy)
        }
    }

    private val onProviderReady = Runnable {
        val future = providerFuture ?: return@Runnable
        val p = try {
            future.get()
        } catch (e: Exception) {
            Log.w(TAG, "Camera provider unavailable: $e")
            if (!released) listener?.onCameraUnavailable()
            return@Runnable
        }
        provider = p
        if (bindRequested && !released) bindNow(p)
    }

    /**
     * Binds preview and analysis to [lifecycleOwner]. Safe to call on every `onResume`: a
     * session already bound to the same owner is left alone (see the class doc), a different
     * owner or a session released by [unbindAll] gets a fresh binding. Nothing happens before
     * the provider future resolves, and nothing happens after [release].
     */
    @UiThread
    fun bind(lifecycleOwner: LifecycleOwner) {
        if (released) return
        if (bound && owner === lifecycleOwner) return
        owner = lifecycleOwner
        bindRequested = true
        val p = provider
        if (p != null) {
            bindNow(p)
            return
        }
        if (providerFuture == null) {
            val future = ProcessCameraProvider.getInstance(appContext)
            providerFuture = future
            future.addListener(onProviderReady, mainExecutor)
        }
    }

    private fun bindNow(p: ProcessCameraProvider) {
        val lifecycleOwner = owner ?: return
        p.unbindAll()
        bound = false
        front = try {
            p.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA)
        } catch (e: CameraInfoUnavailableException) {
            false
        }
        val selector = if (front) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
        preview.setSurfaceProvider(previewView.surfaceProvider)
        throttle.reset()
        applyAnalyzer()
        try {
            // The viewport is null until the PreviewView is laid out; the mapping stays right
            // without it (both CameraX matrices describe the same uncropped stream).
            val viewPort = previewView.viewPort
            if (viewPort != null) {
                val group = UseCaseGroup.Builder()
                    .setViewPort(viewPort)
                    .addUseCase(preview)
                    .addUseCase(analysis)
                    .build()
                p.bindToLifecycle(lifecycleOwner, selector, group)
            } else {
                p.bindToLifecycle(lifecycleOwner, selector, preview, analysis)
            }
            bound = true
            listener?.onCameraBound(front)
        } catch (e: Exception) {
            Log.w(TAG, "bindToLifecycle failed: $e")
            listener?.onCameraUnavailable()
        }
    }

    /**
     * Starts or stops face detection without touching the preview: off while a round is being
     * decided (rings freeze) and while the screen is paused (a Recents thumbnail must not start a
     * round), on again when the screen re-arms or resumes. Enabling also forgets the tracker's
     * old tracks, since faces may have moved while nobody watched.
     */
    @UiThread
    fun setAnalyzerEnabled(enabled: Boolean) {
        analyzerEnabled = enabled
        if (bound) applyAnalyzer()
    }

    /**
     * Updates the physical device orientation (0, 90, 180, 270 degrees CCW) so the detector
     * can find faces even when the phone is held in landscape.
     */
    @UiThread
    fun setDeviceRotation(degrees: Int) {
        faceSource.deviceRotation = degrees
    }

    private fun applyAnalyzer() {
        if (analyzerEnabled) {
            faceSource.requestClear()
            throttle.reset()
            analysis.setAnalyzer(safeExecutor, analyzer)
        } else {
            analysis.clearAnalyzer()
        }
    }

    /** Releases the camera (60 s idle result, or explicit stop); [bind] brings it back. */
    @UiThread
    fun unbindAll() {
        bindRequested = false
        bound = false
        provider?.unbindAll()
    }

    /** The analysis→view matrix is recomputed; see [FaceSource.refreshTransform]. */
    @UiThread
    fun refreshTransform(): Boolean = faceSource.refreshTransform(previewView)

    /** `onDestroy`: unbinds, closes the detector, stops the analysis thread. Irreversible. */
    @UiThread
    fun release() {
        if (released) return
        released = true
        unbindAll()
        faceSource.release()
        executor.shutdown()
    }

    companion object {
        private const val TAG = "Whooz.Camera"

        /** The one knob of detection distance: FAST mode needs faces of ~100 px at this size. */
        const val ANALYSIS_WIDTH = 640
        const val ANALYSIS_HEIGHT = 480

        /**
         * The only Android-specific piece of the coordinate chain: analysis buffer → view.
         * `sensorToBufferTransformMatrix` maps sensor to analysis buffer, `sensorToViewTransform`
         * maps sensor to PreviewView pixels (crop, rotation, mirroring included), so
         * buffer → sensor → view is inverse(sensorToBuffer) followed by sensorToView.
         * UI thread only (PreviewView's rule). Returns false, leaving [out] untouched, while
         * the preview has no transform yet or the buffer matrix is singular.
         */
        @UiThread
        fun currentAnalysisToView(previewView: PreviewView, sensorToBuffer: Matrix, out: Matrix): Boolean {
            val sensorToView = previewView.sensorToViewTransform ?: return false
            if (!sensorToBuffer.invert(out)) return false
            out.postConcat(sensorToView)
            return true
        }
    }
}
