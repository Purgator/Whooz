# Whooz — Final Design Brief

## 1. Purpose and non-negotiables

Whooz is a single-purpose Android app: randomly choose a player among people physically present. Two modes: FINGER (everyone puts one finger on the screen) and CAMERA (everyone in front of the selfie camera). Inspiration: Chwazi.

Hard rules for the implementer:

- Zero friction: the launcher opens straight into a playable screen. No splash, no onboarding, no menu before play.
- Lightweight: plain Android Views + custom Canvas views. No Jetpack Compose, no Material Components, no androidx.preference, no RecyclerView, no coroutines in app code, no sounds, no PNGs (vector drawables only). Target release APK 2–3 MB.
- Battery: no Service, WorkManager, JobScheduler, AlarmManager, BOOT receiver. Nothing runs when the app is not on screen. No frame is drawn while nothing animates.
- Smooth: 60 fps during animations, zero allocation in draw and touch paths.
- Bug-free by construction: every decision lives in pure Kotlin classes with injected clock and Random, covered by JUnit tests. Android glue is thin, portrait-locked, and reviewed by reasoning. Verification gate is `lint + testReleaseUnitTest + assembleRelease` (no device available).
- Dark futuristic look: background #0B0E14, neon player palette, soft glows.
- Keep commits small and focused, with gitmoji-prefixed messages.

## 2. Build configuration and dependencies

- Single Gradle module `app`, namespace and applicationId `fr.arichard.whooz`, Kotlin DSL.
- Gradle 9.5.1 wrapper, AGP 8.13.2, Kotlin 2.2.21, compileSdk 34, minSdk 26, targetSdk 34, build-tools 34.0.0 (or 35.0.0 if AGP insists). JVM target 17 via `kotlin { compilerOptions { jvmTarget } }` (not the deprecated kotlinOptions). If the Kotlin plugin refuses the JDK 25 host, pin a 17 toolchain with the Foojay resolver rather than changing the global JDK.
- Release build type: `isMinifyEnabled = true`, `isShrinkResources = true`, `proguard-android-optimize.txt` + `proguard-rules.pro`; `resourceConfigurations += listOf("en", "fr")`; `vectorDrawables.useSupportLibrary = true`; viewBinding on. Debug build type: `applicationIdSuffix ".debug"` is NOT used (self-update must match the installed package); use a different versionName suffix only.
- Signing: release config read from `keystore.properties` at the repo root, same key layout as AdBlocker4Android, reusing its `release.keystore` (different applicationId, so no conflict). Both files stay untracked (.gitignore). The key used for release 1 must never be rotated.
- versionCode is an integer incremented on every release; versionName is `MAJOR.MINOR` (e.g. `1.0`), the GitHub tag is `v1.0`.
- lint.xml: `DrawAllocation`, `MissingPermission`, `NewApi`, `UnusedResources` at error severity; `abortOnError = true`.

Dependencies (exact):

- plugins: `com.android.application` 8.13.2, `org.jetbrains.kotlin.android` 2.2.21
- `androidx.core:core-ktx:1.13.1`
- `androidx.appcompat:appcompat:1.7.0`
- `androidx.activity:activity-ktx:1.9.3`
- `androidx.camera:camera-core:1.4.2`
- `androidx.camera:camera-camera2:1.4.2`
- `androidx.camera:camera-lifecycle:1.4.2`
- `androidx.camera:camera-view:1.4.2`
- `com.google.android.gms:play-services-mlkit-face-detection:17.1.0`
- `testImplementation junit:junit:4.13.2`

Every AndroidX version above declares minCompileSdk 34 or lower; do not bump any of them without checking AAR metadata (core 1.15+, activity 1.10+, CameraX 1.5+ require compileSdk 35). Run `:app:dependencies` on the first build and pin any transitive that declares minCompileSdk 35. camera-video arrives transitively from camera-view and kotlinx-coroutines from camera-core; both are unused by app code and stripped by R8.

## 3. Manifest

Permissions:

- `android.permission.CAMERA` (runtime, requested only in CameraActivity)
- `android.permission.INTERNET`
- `android.permission.ACCESS_NETWORK_STATE`
- `android.permission.VIBRATE`
- `android.permission.REQUEST_INSTALL_PACKAGES`
- `android.permission.UPDATE_PACKAGES_WITHOUT_USER_ACTION`
- No POST_NOTIFICATIONS (updates are surfaced by an in-app banner).

Features: `android.hardware.camera.any` required=false, `android.hardware.camera.front` required=false, `android.hardware.touchscreen.multitouch.distinct` required=false.

Meta-data: `com.google.mlkit.vision.DEPENDENCIES` = `face`.

Application: `android:allowBackup="false"`, `android:enableOnBackInvokedCallback="true"`, theme `Theme.Whooz`, `android:hardwareAccelerated` default (true).

Components:

- `MainActivity` (launcher, `screenOrientation="portrait"`, `launchMode="singleTask"`, exported=true)
- `CameraActivity` (`screenOrientation="portrait"`, exported=false)
- `SettingsActivity` (exported=false)
- `androidx.core.content.FileProvider` with `xml/file_paths.xml` exposing `cache-path updates/` (authority `${applicationId}.fileprovider`), exported=false, grantUriPermissions=true
- `fr.arichard.whooz.update.ApkInstaller$Receiver`, exported=false

Theme `Theme.Whooz`: parent `Theme.AppCompat.NoActionBar`; `windowBackground` = solid color #0B0E14 (exactly the view background, so the cold-start frame is already correct and there is no decor overdraw); status and navigation bars transparent; `windowLayoutInDisplayCutoutMode = shortEdges`; no `windowIsTranslucent`.

## 4. Screens and navigation

Three activities, no fragments, no navigation library.

### MainActivity (finger mode, launcher)

- Full-screen `FingerChooserView`. On top, in a FrameLayout: a 40 dp ghost camera icon (top-right), a 40 dp ghost settings gear (top-left), a sub-mode pill (bottom center), a hint TextView (center), a first-launch caption (bottom), and the `UpdateBanner` (bottom, above the pill).
- Chrome visibility rule: all chrome views are visible only in IDLE with zero fingers. Whenever any finger is down or the engine is not IDLE, chrome gets `alpha = 0` and `isClickable = false` (and `isFocusable = false`), so the first finger cannot trigger a button and a stray finger cannot flip a sub-mode mid-countdown. Chrome fades back in over 300 ms when the view returns to IDLE with zero fingers. Fading uses `animate().alpha()` on plain Views (not the custom view's loop).
- Hint: "Everyone, put one finger on the screen" in IDLE; "Need one more finger" when exactly 1 finger is down; "Hold still…" during ARMING; nothing during countdown/result; "Lift to play again" during RESULT once the hold time elapsed; "Too many fingers" when more than MAX_POINTERS pointers are present. After the first completed round the idle hint shrinks to a smaller, lower-alpha text.
- First-launch caption (shown once, 2.5 s fade): "Keep fingers away from the very bottom edge".
- Window: sticky immersive (`WindowInsetsControllerCompat.hide(systemBars())`, `systemBarsBehavior = BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE`), re-applied in `onResume` and `onWindowFocusChanged(true)`. `FLAG_KEEP_SCREEN_ON` added in `onResume` when the setting is on, cleared in `onPause`; additionally cleared after 3 minutes without any touch (one `Handler.postDelayed` token, reset on every ACTION_DOWN) and re-added on the next ACTION_DOWN.
- Back: `OnBackPressedCallback` enabled only while ≥1 finger is down or the engine is not IDLE; when enabled it cancels the round (clear tracker, abort engine). Otherwise back finishes the app.
- Mode memory: if the "Remember last mode" setting is on and the last mode was CAMERA and `savedInstanceState == null`, `onCreate` starts `CameraActivity` and finishes itself before `setContentView`. Back from CameraActivity writes lastMode = FINGER only if the user explicitly tapped the finger icon; the system back keeps lastMode = CAMERA so a camera-only group keeps zero friction.
- Update check: after the first frame (`view.post`), once per process, start a daemon Thread running `UpdateManager.maybeDailyCheck` if auto-update is on. `onResume` asks `UpdateManager` whether a downloaded APK is ready and shows the banner.
- `onPause`: `tracker.clear()`, `engine.abort()`, stop the frame loop.

### CameraActivity (camera mode)

- Layout: `PreviewView` (ImplementationMode PERFORMANCE, scaleType FILL_CENTER) + `FaceOverlayView` with identical bounds + ghost finger icon (top-left, goes back to MainActivity and writes lastMode = FINGER) + gear (top-right) + sub-mode pill limited to Pick 1 / Pick 2 + hint band + permission card + status banner. Same chrome rule as finger mode (hidden while the engine is not IDLE).
- Permission flow: the `ActivityResultLauncher` is registered in `onCreate`; `onStart` requests CAMERA if not granted. While waiting, the preview area is the dark background with the hint. Denied once: card with "Allow camera" (re-request) and "Use finger mode". Permanently denied (rationale false after a denial): card with "Open settings" (ACTION_APPLICATION_DETAILS_SETTINGS) and "Use finger mode". `onResume` re-checks and binds automatically once granted.
- Hints: "Everyone, look at the camera" until ≥2 faces are confirmed; "Hold still…" during ARMING; "Tap to play again" in RESULT; "Come closer" if faces are confirmed but all rings are smaller than 60 dp for 3 s.
- ML Kit unavailable handling: catch exceptions from `FaceDetection.getClient` and `MlKitException` with code UNAVAILABLE on `process`: show the banner "Face model is being downloaded by Google Play services…" and retry detector creation every 5 s up to 12 times while the preview keeps running; after 12 failures the banner becomes "Camera mode needs Google Play services — use Finger mode" with a button. Never degrade to another engine.
- No front camera: fall back to `DEFAULT_BACK_CAMERA` with the hint "Using back camera". No camera at all (`FEATURE_CAMERA_ANY` false, checked once in MainActivity): the camera icon is hidden on the finger screen.
- Back: finishes to MainActivity. Immersive, keep-screen-on, and chrome rules as in MainActivity. `onPause` resets the engine to IDLE and stops the loop; CameraX unbinds on `onStop` via the lifecycle; `onDestroy` calls `CameraSession.release()`.
- Debug toggle: in `BuildConfig.DEBUG` builds, a long-press on the hint toggles drawing the mapped analysis-frame bounds as a rectangle and the raw ML Kit boxes, so the first real-device run confirms the transform chain at a glance.

### SettingsActivity

- Hand-built: a `ScrollView` with a `LinearLayout` of rows (TextView title/subtitle + `SwitchCompat`, or a chip-like row of `TextView`s toggling selection). Normal system bars (not immersive), edge-to-edge insets applied as padding. Uses a Toolbar-less layout with a back arrow ImageButton and the title "Settings".
- Rows (defaults in parentheses):
  - Stabilization delay: chips Fast 1.0 s / Normal 1.5 s / Slow 2.5 s (Normal). Camera mode adds +300 ms on top.
  - Haptics: switch (on).
  - Keep screen on: switch (on).
  - Remember last mode: switch (on).
  - Auto-update over Wi-Fi: switch (on).
  - Check for updates now: tappable row; runs the check on a background thread and shows a Toast result ("You are up to date", "Update downloaded — go back to install", "No connection", "Update will download on Wi-Fi").
  - Version row: "Whooz x.y (code N)".
  - Open on GitHub: opens the repository URL.
  - Open-source licences: opens a plain text screen (same activity, a second layout or an AlertDialog) listing AndroidX, CameraX, ML Kit Apache-2.0 notices.
- Settings are written to SharedPreferences immediately and read by `Settings.load(context)` once per `onResume` of the chooser activities into an immutable snapshot; the draw loop never reads preferences.

## 5. Class map

Package root `fr.arichard.whooz`:

- `MainActivity.kt` — finger screen host; immersive, keep-screen-on, chrome visibility, hints, back callback, mode forwarding, update banner, daily check kickoff.
- `CameraActivity.kt` — camera screen host; permission flow, `CameraSession` ownership, ML Kit retry banner, chrome, hints, debug toggle.
- `SettingsActivity.kt` — hand-built settings and licences.

`core` (pure Kotlin, no Android imports):

- `Mode.kt` — sealed class `Mode { Winner(n: Int), Order, Teams(k: Int) }`, `Assignment` (winners bitmask, order IntArray, team IntArray), parse/serialize to a preference string, with fallback `Winner(1)` on unknown input; clamping rules.
- `Timings.kt` — immutable timing config: `stableMs`, `countdownMs = 1000`, `elimStepMs = 350`, `elimAnimMs = 250`, `floodMs = 500`, `resultHoldMs` (finger 2500, camera 4000), `rearmFadeMs = 400`, `collapseMs = 150`, `graceMs = 700`, `rescueMs = 500`, `resultIdleTimeoutMs = 60000`; `finger(preset)` and `camera(preset)` factories mapping the stabilization preset.
- `TouchTracker.kt` — pointer bookkeeping in parallel preallocated arrays (`ids IntArray(16)`, `x`, `y FloatArray(16)`, `slot IntArray(16)`, `count`, `setVersion`); `down(id, x, y, toolType)`, `move(id, x, y)`, `up(id, cancelled)`, `clear()`; slot = lowest free color index; MAX_POINTERS = 16; ignores tool types other than FINGER and UNKNOWN; also used for the "absorbed" fingers during RESULT (they are tracked but not participants).
- `StabilityGate.kt` — given `count`, `setVersion` and `nowMs`, reports `progress` 0..1, `armed` when `count >= 2` and the version has been unchanged for `stableMs`; `reset()`; tracks a `collapseStartMs` so the view can animate the arc back to 0 over 150 ms after a set change.
- `ChooserEngine.kt` — state machine IDLE → COUNTDOWN → ELIMINATING → RESULT → (fade) → IDLE, time-based with injected clock and `kotlin.random.Random`; `start(participants: IntArray, mode, nowMs)` freezes the set, draws the `Assignment` once, pre-shuffles the elimination order; `tick(nowMs)` advances phases; `abort()`; `participantLifted(slot)` (aborts during COUNTDOWN in finger mode only — a flag `abortOnLift`); per-slot queries: `isParticipant`, `isAlive`, `eliminatedAtMs`, `isWinner`, `teamOf`, `orderOf`; `canRearm(allLifted, nowMs)`; `needsFrames()`.
- `Easing.kt` — `easeOutCubic`, `easeInQuad`, `lerp`, `clamp01`, `pulse(t, periodMs)` in [0,1], `floodRadius(x, y, w, h, t)`.
- `Palette.kt` — `IntArray` of 16 opaque neon colors (first 12: #FF3B6B, #3BE8FF, #B6FF3B, #FFB13B, #C13BFF, #3BFFA8, #FF6B3B, #3B8CFF, #FFE63B, #FF3BE1, #7CFF3B, #FF8C3B; then #FFFFFF, #19E3D1, #8C6BFF, #FF5CE1), BACKGROUND #0B0E14, team colors = first k entries, `withAlpha(color, alpha)`.
- `Settings.kt` — SharedPreferences wrapper producing an immutable snapshot (stabilizationPreset, haptics, keepScreenOn, rememberLastMode, lastMode, autoUpdate, lastUpdateCheckMs, updateDeferredVersion, fingerMode string, cameraMode string, firstLaunchHintShown, completedRounds). This file is the only one in `core` that imports Android.
- `VersionCompare.kt` — pure dotted-version comparison (`compareVersions`, `isNewer`), 'v' prefix stripped, non-numeric parts = 0, never throws.
- `ReleaseParser.kt` — pure parsing of the GitHub `releases/latest` JSON string (via `org.json`, available on Android; in tests via the json jar or a minimal hand parser) into `ReleaseInfo(tagName, assetName, assetUrl, assetSize)`; selects the asset named `Whooz.apk`; rejects non-https URLs and sizes outside 1..MAX_APK_BYTES (30 MB).

`ui`:

- `FrameClock.kt` — one reusable `Choreographer.FrameCallback`; `start()`, `stop()`, `isRunning`; invokes a listener with `frameTimeMs = frameTimeNanos / 1_000_000` and calls `invalidate()` on the owning view once per vsync while running.
- `Haptics.kt` — `tick()`, `confirm()`, `heavy()`: `performHapticFeedback(CLOCK_TICK / CONFIRM)` first (CONFIRM on API 30+, else LONG_PRESS), `Vibrator.vibrate(createPredefined(EFFECT_HEAVY_CLICK))` on API 29+ for heavy, `createOneShot(60, DEFAULT_AMPLITUDE)` on 26–28; `VibratorManager` on 31+; no-op when the setting is off. Never called from `onDraw`; only from state transitions.
- `Chrome.kt` — helper that shows/hides the chrome views with the alpha + clickable rule.
- `UpdateBanner.kt` — a small bottom banner (LinearLayout inflated from `view_update_banner.xml`): "Whooz vX ready" with Install / Later. Install: if `canRequestPackageInstalls()` is false, open `ACTION_MANAGE_UNKNOWN_APP_SOURCES` for the package and set a flag so `onResume` retries; else call `ApkInstaller.install` on a background thread. Later: store `updateDeferredVersion` so the banner stays hidden for that version for 24 h.

`finger`:

- `FingerChooserView.kt` — custom View: `onTouchEvent` → `TouchTracker`; drives `StabilityGate` and `ChooserEngine`; owns `FrameClock`; `onLayout` sets gesture exclusion rects; allocation-free `onDraw`; callbacks to the activity for hints/chrome/haptics via a small listener interface.

`camera`:

- `CameraSession.kt` — CameraX glue: provider future, front/back selector, Preview + ImageAnalysis in one `UseCaseGroup` with the PreviewView viewport, single-thread executor "whooz-face", busy flag, 15 Hz throttle, `setAnalyzer`/`clearAnalyzer`, `bind()`, `unbindPreviewOnly()`, `release()`; the 10-line `currentAnalysisToView()` matrix builder.
- `FaceSource.kt` — ML Kit detector lifecycle and retry, `process(InputImage)`, converts `Face` list into `FaceObservation`s (upright coordinates), runs `FaceGeometry` + matrix mapping and `FaceTracker.update` on the analysis thread, swaps the double-buffered `FaceFrame` and posts it to the UI thread.
- `FaceGeometry.kt` — pure: `uprightToBuffer(left, top, right, bottom, rotation, bufferW, bufferH, out FloatArray)` for 0/90/180/270; `ringFromRect` (cx, cy, radius = 0.62 × max(w, h)); `clampRadius`.
- `FaceTracker.kt` — pure tracker (section 8).
- `FaceFrame.kt` — preallocated snapshot: `count`, `slot IntArray(16)`, `cx/cy/radius FloatArray(16)`, `state IntArray(16)`, `alpha FloatArray(16)`, `setVersion`, `confirmedCount`.
- `FaceOverlayView.kt` — transparent custom View over the preview: draws from the latest `FaceFrame` + `ChooserEngine`; tap-to-rearm in RESULT; debug rectangles.

`update`:

- `UpdateManager.kt` — port of AdBlocker4Android `core/UpdateManager.kt`: `maybeDailyCheck`, `checkNow`, GitHub `releases/latest` via HttpURLConnection (15 s connect / 20 s read, User-Agent "Whooz"), `ReleaseParser`, `VersionCompare`, unmetered-only download (`ConnectivityManager.isActiveNetworkMetered` false, or when the user tapped "Check now" regardless) into `cacheDir/updates/Whooz-v<ver>.apk` with a size check, temp file renamed on completion, `cleanupOldApks`, 24 h throttle, deferred retry, `readyUpdate()` returning the downloaded file + version or null. Before offering an APK it verifies the archive's signing certificate (`getPackageArchiveInfo` with `GET_SIGNING_CERTIFICATES`, API 28+; on 26–27 skip the check) equals the running app's.
- `ApkInstaller.kt` — port of AdBlocker4Android `core/ApkInstaller.kt`: abandons stale `getMySessions()` at launch; `PackageInstaller` session `MODE_FULL_INSTALL`, `setRequireUserAction(USER_ACTION_NOT_REQUIRED)` on API 31+, stream + fsync, `commit` with a `PendingIntent.getBroadcast` to the explicit `Receiver` using `FLAG_UPDATE_CURRENT or FLAG_MUTABLE`; `Receiver` handles STATUS_PENDING_USER_ACTION by launching `EXTRA_INTENT` with NEW_TASK (the app is in foreground since the banner was tapped), STATUS_SUCCESS (no-op, the app restarts), failures → Toast with the message and a FileProvider `ACTION_VIEW` fallback offer.

Resources: `layout/activity_main.xml`, `activity_camera.xml`, `activity_settings.xml`, `view_update_banner.xml`; `drawable/ic_camera.xml`, `ic_hand.xml`, `ic_settings.xml`, `ic_close.xml`, adaptive launcher icon (vector foreground: a ring with a dot, background #0B0E14); `values/strings.xml`, `values-fr/strings.xml`, `values/themes.xml`, `values/colors.xml`, `values/dimens.xml`; `xml/file_paths.xml`; `lint.xml`; `proguard-rules.pro`.

Repo files: `README.md` (general description, screenshots placeholders, "Install for everyone": download `Whooz.apk` from Releases, allow installs from your browser/Files app, the app updates itself over Wi-Fi; "For developers": JDK 17+, Android SDK 34, `gradlew assembleRelease`, `keystore.properties` layout, how releases are made; known limitations: 10-finger digitizers, bottom edge, Google Play services for camera mode, status bar hidden while playing), `LICENSE` (MIT), `.gitignore`. No `.github/workflows` directory.

## 6. Finger mode: touch handling

`FingerChooserView.onTouchEvent` always switches on `actionMasked` and returns true:

- ACTION_DOWN: `parent.requestDisallowInterceptTouchEvent(true)`; reset the 3-minute keep-screen-on timer; if the engine is in RESULT and re-arm is not yet allowed, the finger is "absorbed": tracked by `TouchTracker` but not a participant (drawn as a small 8 dp dim dot). Otherwise `tracker.down(id, x, y, toolType)`; haptic tick on success.
- ACTION_POINTER_DOWN: same with `event.getPointerId(event.actionIndex)`.
- ACTION_MOVE: for each index in `0 until pointerCount`, `tracker.move(getPointerId(i), getX(i), getY(i))` with the latest sample only. If the frame loop is not running (IDLE/TOUCHING with no arming), `invalidate()` only if some tracked finger moved ≥ 1 px.
- ACTION_POINTER_UP / ACTION_UP: `cancelled = API ≥ 33 && (event.flags and FLAG_CANCELED) != 0`; `tracker.up(id, cancelled)`; no haptic when cancelled. If that pointer was a frozen participant during COUNTDOWN, `engine.abort()` → back to gathering with the collapse animation. ACTION_UP additionally calls `tracker.clear()` (ids are only unique within a gesture) and, if the engine is in RESULT and `resultHoldMs` elapsed, triggers the fade back to IDLE.
- ACTION_CANCEL: `tracker.clear()`, `engine.abort()`, gate reset, no winner ever.
- Pointers beyond MAX_POINTERS (16) are ignored and the activity shows "Too many fingers". Pixel digitizers typically track 10; documented in README.
- Edge protection: `onLayout` calls `ViewCompat.setSystemGestureExclusionRects` with two full-height strips (left and right, width = `systemGestures` inset or 32 dp fallback). With sticky immersive the 200 dp cap is lifted. The bottom home zone cannot be excluded; a faint 24 dp bottom gradient marks it.

## 7. Finger mode: state machine and timings

Phases observed by the view (combination of `TouchTracker`, `StabilityGate`, `ChooserEngine`):

1. IDLE — 0 fingers, engine IDLE. Chrome visible, hint shown, no frame loop. Draw once.
2. TOUCHING — ≥1 finger, engine IDLE, gate not progressing (count < 2). No frame loop; redraw only on touch change. Circle "appear" pop (0 → 1.08 → 1.0 over 220 ms) is driven by a short loop that stops after the pop.
3. ARMING — ≥2 fingers with an unchanged set; `StabilityGate.progress` rises 0 → 1 over `stableMs` (1000/1500/2500 by preset). Frame loop runs. Each ring shows a 3 dp arc sweeping 0 → 360° with the progress. Any set change (down/up/cancel) resets the gate; the arcs animate back to 0 over 150 ms (collapse) and the gate restarts after the collapse. Moving fingers do not reset the gate.
4. COUNTDOWN — 1000 ms. The engine freezes the participant set (slots snapshot), draws the `Assignment` once with the injected Random (`java.security.SecureRandom`-seeded `kotlin.random.Random` in production), and pre-shuffles the elimination order (winner never included, losers each exactly once). All circles pulse together (scale 1.0 → 1.15 sine; period accelerates from 400 ms to 200 ms across the countdown); haptic tick at 0, 250, 500, 750 ms; during the last 400 ms the losers are dimmed one by one in elimination order (alpha → 0.55, scale 0.92) so the reveal reads as a sequence. New fingers landing now are absorbed (dim dot). A frozen participant lifting aborts to gathering (Chwazi behaviour, prevents "lifted early" disputes).
5. ELIMINATING — for `Winner(n)`: losers vanish one every 350 ms, each shrinking to 0 and fading over 250 ms with a haptic tick; total (participants − n) × 350 ms. For `Order` and `Teams(k)` this phase is a reveal instead: numbers pop in one every 150 ms in order 1..N, or rings crossfade to their team color over 300 ms each, staggered 100 ms.
6. RESULT — for `Winner(1)`: at ELIMINATING end, haptic heavy, a flood circle grows from the winner's finger with `radius = easeOutCubic(t / 500 ms) × distance to the farthest screen corner`, filling the screen with the winner's color; a 56 dp white ring with the winner color inside stays at the finger and follows it on MOVE. For `Winner(n>1)`: the screen splits into n vertical bands of the winners' colors (grown from the left over 500 ms) and each winner ring stays. For `Order`/`Teams`: no flood, rings stay with their numbers/team colors, background stays dark. Frame loop stops when the flood/band animation is complete; afterwards redraw only on MOVE.
   Re-arm: the engine returns to IDLE when all fingers are lifted AND `resultHoldMs` (2500 ms) have elapsed since RESULT start; the flood fades out over 400 ms (frame loop for that duration only). A finger that lands during RESULT after the hold is also absorbed until the full lift so a bystander cannot erase the winner. `onPause` resets everything.
7. Abort from any phase (ACTION_CANCEL, back, participant lifted during COUNTDOWN): circles collapse 150 ms, back to TOUCHING/ARMING according to the remaining count, no winner.

Sub-modes (`Mode`), chosen from the pill, remembered per screen in preferences: `Winner(1)` default, `Winner(2)`, `Winner(3)`, `Order`, `Teams(2)`, `Teams(3)`. `Winner(n)` with participants ≤ n: all present win (documented edge case). `Teams(k)` with participants < k: k is clamped to participants. Team sizes differ by at most 1 (shuffle then round-robin). Camera mode offers only `Winner(1)` and `Winner(2)`.

## 8. Camera mode pipeline

Binding (`CameraSession`):

- `ProcessCameraProvider.getInstance(ctx)` listener on the main executor; `unbindAll()` then `bindToLifecycle(activity, selector, UseCaseGroup.Builder().setViewPort(previewView.viewPort).addUseCase(preview).addUseCase(analysis).build())`. Selector = `DEFAULT_FRONT_CAMERA` if `hasCamera` else `DEFAULT_BACK_CAMERA`. `previewView.surfaceProvider` set before binding. Nothing is bound before CAMERA is granted.
- Preview: `PreviewView` in `ImplementationMode.PERFORMANCE` (SurfaceView, cheapest), `FILL_CENTER`. `getSensorToViewTransform()` works in this mode; the winner tint is drawn on the overlay, never by animating the preview.
- ImageAnalysis: `STRATEGY_KEEP_ONLY_LATEST`, `OUTPUT_IMAGE_FORMAT_YUV_420_888`, `ResolutionSelector` with `ResolutionStrategy(Size(640, 480), FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER)`. Analyzer on `Executors.newSingleThreadExecutor` named "whooz-face". Two gates: an `AtomicBoolean busy` (one frame in flight; while busy, `imageProxy.close()` immediately) and a throttle (if fewer than 66 ms have passed since the last accepted frame, close immediately) → at most ~15 detections per second.
- Analyzer is set only while the engine is IDLE/gathering/COUNTDOWN; `clearAnalyzer()` is called on entering ELIMINATING and `setAnalyzer` again on re-arm. Rings freeze at their last position during elimination/result. After 60 s in RESULT with no interaction, the preview is unbound (`unbindAll`) and the overlay shows "Tap to scan again"; a tap rebinds.
- `release()` in `onDestroy`: `unbindAll()`, `detector.close()`, `executor.shutdown()`. The analyzer lambda captures only `CameraSession`/`FaceSource`, never the Activity.

Detection (`FaceSource`):

- `FaceDetectorOptions`: `PERFORMANCE_MODE_FAST`, `enableTracking()`, `setMinFaceSize(0.15f)`, landmark/contour/classification modes NONE (contours would collapse detection to one face), `setExecutor(analysisExecutor)`.
- Per frame: `InputImage.fromMediaImage(imageProxy.image, imageProxy.imageInfo.rotationDegrees)` (annotated `@ExperimentalGetImage`), `detector.process(image).addOnCompleteListener(analysisExecutor)`: on completion `imageProxy.close()`, `busy = false`, then convert faces. Never close the `Media.Image` directly. Capture `rotationDegrees`, buffer width/height and a copy of `sensorToBufferTransformMatrix` before processing.
- Each `Face` → `FaceObservation(mlId = trackingId ?: -1, box in upright coordinates)`. Mapping to view coordinates happens on the analysis thread: `FaceGeometry.uprightToBuffer` (pure, rotates the box back to buffer coordinates for 0/90/180/270), then `analysisToView.mapPoints` on a reused `FloatArray` (center and a radius vector), then `ringFromRect`.
- The `analysisToView` matrix = inverse(`sensorToBufferTransformMatrix`) post-concatenated with `previewView.sensorToViewTransform`. `sensorToViewTransform` must be read on the UI thread: `CameraActivity` keeps a `@Volatile` Matrix copy refreshed (a) from an `OnLayoutChangeListener` on the PreviewView and (b) in the UI-thread runnable that receives every posted `FaceFrame` (this covers the case where CameraX delivers the transformation info after layout, otherwise frames would be dropped forever). While it is null, frames are dropped and the hint stays. The matrix already includes FILL_CENTER crop/scale, display rotation and front-camera mirroring; no manual flip anywhere. The whole Android-only part is one ~10-line function `currentAnalysisToView()`.
- The mapped observations feed `FaceTracker.update` on the same thread (single-threaded tracker, no locks). The result is copied into the inactive `FaceFrame` buffer, the buffers are swapped under a tiny lock, and one preallocated Runnable is posted to the UI thread which stores the frame reference, refreshes the view transform, updates `StabilityGate`/`ChooserEngine` with the frame's `setVersion`/`confirmedCount`, and calls `invalidate()` (no Choreographer loop needed while only tracking).

Tracking (`FaceTracker`, pure, capacity 16, fields per track: playerId slot, mlId, cx, cy, radius smoothed, lastSeenMs, firstSeenMs, hitCount, missSinceMs, state):

- Matching order per frame: (1) identical `mlId` (≥0); (2) for unmatched observations, nearest unmatched track whose center distance ≤ 0.6 × max(track.radius, obs.radius) and whose radius ratio is within [0.6, 1.6] — this re-attaches a face whose ML Kit id changed (id-swap rescue, also valid when the old track has been missing < 500 ms); (3) remaining observations spawn TENTATIVE tracks with the lowest free playerId (color persists while the slot lives).
- States: TENTATIVE → CONFIRMED after 3 consecutive hits; CONFIRMED → missing: kept with alpha fading 1.0 → 0.4 for 700 ms (`graceMs`) at the last position; after 700 ms → GONE, slot freed, `setVersion` bumped. TENTATIVE tracks missing one frame are dropped immediately.
- Smoothing: exponential on cx, cy, radius with alpha 0.35, skipped on the first hit.
- `setVersion` bumps only when the set of CONFIRMED playerIds changes (confirm or GONE), never on rescues or moves. Output sorted by playerId. `confirmedCount` exposed.

Stabilization and rounds (camera): `StabilityGate` with `stableMs = preset + 300` (1300/1800/2800) and `confirmedCount ≥ 2`; any `setVersion` change collapses the arcs and restarts. `ChooserEngine` with `abortOnLift = false`: the participant set is frozen at COUNTDOWN start; a face that disappears during COUNTDOWN/ELIMINATING stays eligible and its ring is drawn at its last position (a player cannot cheat by ducking). Late faces are drawn as dim thin rings and ignored. COUNTDOWN 1000 ms, elimination 350 ms per loser, RESULT: winner ring thickens to 10 dp with a white inner ring and pulses 3 times (600 ms period), the overlay floods the winner color at 35 % alpha with a full-width colored band at the bottom; losers' rings collapse. If the winner's face is lost in RESULT the ring freezes at its last position and never jumps to another face. Re-arm on tap on the overlay, or when `resultHoldMs` (4000 ms) elapsed and `setVersion` changed, or on the 60 s idle timeout path above.

## 9. Animation plan and allocation rules

What `FingerChooserView.onDraw` draws, in order: `drawColor(BACKGROUND)`; in IDLE/TOUCHING a faint grid (two cached stroke Paints, lines every 48 dp, drawn from precomputed float arrays with `drawLines`); the bottom gradient (cached `LinearGradient` Paint); for each tracked slot: glow (three concentric stroked rings at 12/8/4 % alpha, widths 18/12/6 dp — no shadow layers), filled disc (radius 34 dp, 18 % alpha), main ring (radius 44 dp, stroke 5 dp, thickening to 8 dp during COUNTDOWN), arming arc (3 dp, sweep = progress × 360 with the collapse easing), pulse scale applied as a radius multiplier, elimination scale/alpha from `eliminatedAtMs`, order number (`drawText` from a preallocated `Array<String>(17)`), team color; absorbed fingers as 8 dp dots; the flood circle or winner bands last, then the winner ring on top.

`FaceOverlayView.onDraw`: for each `FaceFrame` slot: ring (stroke 6 dp, glow 18 dp at 25 % alpha) with the tracker alpha, a 18 dp color badge above the ring, arming arc, countdown pulse, elimination collapse, winner emphasis, the 35 % tint and the band in RESULT; debug rectangles when enabled.

Rules:

- All Paint, Path, RectF, Matrix, FloatArray, String and shader objects are fields created in the constructor or `onSizeChanged`. `onDraw` and `onTouchEvent` allocate nothing: indexed `for` loops over primitive arrays, no Kotlin collections, no lambdas, no boxing, no `String.format`, no `Canvas.save/restore` beyond one per frame, no `drawBitmap`, no hardware layers, no `setLayerType`.
- All animation values are functions of `frameTimeMs − phaseStartMs` through `Easing`; no ValueAnimator/ObjectAnimator ever created at runtime (chrome fades use `View.animate()` outside the custom views).
- Frame scheduling: `FrameClock` runs only while `engine.needsFrames()` or the view has a transient animation (appear pop, collapse, arming arc, countdown, elimination, flood, re-arm fade). It is stopped in IDLE, TOUCHING with no arming, steady RESULT, `onDetachedFromWindow` and `onPause`. In those states the view redraws only when a touch changed a position or a new `FaceFrame` arrived. A resting phone with fingers down and fewer than 2 fingers costs zero frames; with ≥2 fingers it costs frames only for `stableMs` plus the round.
- Haptics and hint/chrome callbacks are emitted on state transitions detected in the tick, never inside `onDraw`.
- `needsFrames()` is pure on `ChooserEngine` and unit-tested (AnimationBudgetTest).

## 10. Settings (summary with defaults)

- stabilizationPreset: FAST (1.0 s) / NORMAL (1.5 s) / SLOW (2.5 s) — default NORMAL; camera adds 300 ms.
- haptics: true.
- keepScreenOn: true.
- rememberLastMode: true; lastMode: FINGER.
- autoUpdate: true; lastUpdateCheckMs: 0; updateDeferredVersion: "".
- fingerMode: "winner:1"; cameraMode: "winner:1".
- firstLaunchHintShown: false; completedRounds: 0.

## 11. Self-update

- Source: `https://api.github.com/repos/Purgator/Whooz/releases/latest` (owner to be confirmed from the AdBlocker4Android constant), asset `Whooz.apk`.
- Check at most once per 24 h, only when `MainActivity` is on screen, on a daemon thread started after the first frame; no background scheduling.
- Download only on unmetered networks (or when the user taps "Check now"), to `cacheDir/updates/`, with a size cap and atomic rename; old APKs cleaned.
- Offer: in-app banner in `MainActivity` (shown only in IDLE with no fingers). Install path: `canRequestPackageInstalls()` false → `ACTION_MANAGE_UNKNOWN_APP_SOURCES`, retry on `onResume`; signing certificate of the archive must equal the running app's; `PackageInstaller` session with `USER_ACTION_NOT_REQUIRED` (API 31+), mutable broadcast PendingIntent, `STATUS_PENDING_USER_ACTION` launches the confirmation intent; FileProvider `ACTION_VIEW` fallback on failure. Stale sessions abandoned at launch.
- Zero-tap conditions: targetSdk 34 satisfies Android 14 (needs 31+), 15 (33+) and 16 (34+); only Android 17 (API 37) will need the confirmation dialog, which the pending-user-action path handles.

## 12. Strings (English; every key also in values-fr)

app_name "Whooz"; hint_idle "Everyone, put one finger on the screen"; hint_idle_short "Put your fingers down"; hint_one_more "Need one more finger"; hint_hold_still "Hold still…"; hint_lift_to_replay "Lift to play again"; hint_too_many "Too many fingers"; caption_bottom_edge "Keep fingers away from the very bottom edge"; hint_camera_idle "Everyone, look at the camera"; hint_camera_tap_again "Tap to play again"; hint_come_closer "Come closer"; hint_back_camera "Using back camera"; perm_camera_title "Camera access needed"; perm_camera_body "Whooz uses the selfie camera to find the players. Nothing is recorded."; perm_allow "Allow camera"; perm_open_settings "Open settings"; perm_use_finger "Use finger mode"; mlkit_downloading "Face model is being downloaded by Google Play services…"; mlkit_unavailable "Camera mode needs Google Play services — use Finger mode"; mode_winner_1 "Pick 1"; mode_winner_2 "Pick 2"; mode_winner_3 "Pick 3"; mode_order "Order"; mode_teams_2 "2 teams"; mode_teams_3 "3 teams"; cd_camera "Camera mode"; cd_finger "Finger mode"; cd_settings "Settings"; cd_close "Close"; settings_title "Settings"; settings_delay "Stabilization delay"; settings_delay_fast "Fast"; settings_delay_normal "Normal"; settings_delay_slow "Slow"; settings_haptics "Haptics"; settings_keep_screen_on "Keep screen on"; settings_remember_mode "Remember last mode"; settings_auto_update "Auto-update over Wi-Fi"; settings_check_now "Check for updates now"; settings_version "Version %1$s (%2$d)"; settings_github "Open on GitHub"; settings_licenses "Open-source licences"; update_ready "Whooz v%1$s is ready"; update_install "Install"; update_later "Later"; update_up_to_date "You are up to date"; update_downloaded "Update downloaded — go back to install"; update_no_network "No connection"; update_wifi_pending "Update will download on Wi-Fi"; update_failed "Install failed: %1$s"; update_open_file "Open the APK instead".

## 13. Unit tests (JUnit 4, `app/src/test/java/fr/arichard/whooz/`)

- TouchTrackerTest: down/move/up by id keeps a finger's slot when another lifts; ids reused after `clear()`; 17th pointer ignored; non-finger tool types ignored, UNKNOWN accepted; `setVersion` bumps only on set changes; `up` of an unknown id is a no-op; cancelled flag removes without a haptic event.
- StabilityGateTest: not armed below 2; armed exactly at `stableMs`; any version change resets to 0 and reports collapse; progress monotonic; re-arm after abort.
- ChooserEngineTest (injected clock + seeded Random): phase boundaries at COUNTDOWN/ELIM_STEP; participant set frozen (late joiners ignored); `abort()` during COUNTDOWN → IDLE, no winner; elimination order is a permutation of losers, winner last/never eliminated; `Winner(n)` leaves exactly n alive and "all win" when participants ≤ n; `Order` yields a permutation 1..N; `Teams(k)` sizes differ by ≤ 1, every slot assigned, k clamped; 2 participants → one elimination step; `canRearm` requires all lifted AND hold elapsed; `participantLifted` aborts only with `abortOnLift`; outcome drawn once per round.
- UniformityTest: 10 000 seeded rounds with 4 participants, each wins 25 % ± 2 %.
- AnimationBudgetTest: `needsFrames()` false in IDLE and steady RESULT, true in COUNTDOWN/ELIMINATING and during the flood/fade windows.
- FaceTrackerTest: same mlId keeps playerId; id change with a nearby position and valid radius ratio re-attaches (also within the 500 ms rescue window); far new face gets a new slot; 3 hits to CONFIRMED; 500 ms gap keeps the track with decreasing alpha; GONE after 700 ms and slot freed; lowest free slot reused; smoothing converges and is skipped on first hit; swapping faces keep ids via mlId priority; capacity 16; output sorted; `setVersion` only on confirm/GONE.
- FaceGeometryTest: `uprightToBuffer` round-trips known rects for 0/90/180/270 (640×480 buffer, 480×640 upright); `ringFromRect`; radius clamping.
- ModeTest: serialize/deserialize every Mode; unknown string → `Winner(1)`; clamping.
- TimingsTest: preset mapping 1000/1500/2500, camera +300, hold values.
- EasingTest: boundaries, monotonicity, clamp, pulse within [0,1] and periodic, `floodRadius` covers the farthest corner at t = 1 for the four corners and the centre.
- PaletteTest: 16 distinct opaque colors, team colors are the first k, `withAlpha`.
- VersionCompareTest: 1.10 > 1.9, 2.0 > 1.99, 'v' prefix, garbage never newer, equal not newer, never throws.
- ReleaseParserTest: picks `Whooz.apk`, ignores other assets, rejects http URLs and sizes outside bounds, tolerates missing fields; daily-throttle and deferred-retry decision function with injected timestamps.

## 14. Known risks and mitigations

- No device: the sensor-to-view chain (mirroring, crop) cannot be seen. Mitigation: one isolated function, FaceGeometry tests for all rotations, the debug overlay toggle, and a README note that release 1.0 is a first real-device candidate.
- Transform null forever: covered by refreshing the volatile transform on every posted frame, not only on layout.
- Unbundled ML Kit needs Google Play services and a one-time model download; a sideloaded first run can take seconds. Mitigation: retry banner (5 s × 12), finger mode always available, never a silent degradation. GMS-free phones: camera mode disabled with a clear message.
- ML Kit id churn: rescue window + radius-ratio check + 700 ms grace; thresholds (3 hits, 700 ms, 0.6 radius, alpha 0.35) are reasoned constants in one place, to tune after the first real session.
- Distance: FAST mode at 640×480 needs faces ≥ ~100 px; "Come closer" hint; the resolution constant is the single knob.
- Digitizer limits (~10 touches on most phones): extra fingers get no circle; "Too many fingers" only when the framework reports them; documented.
- Bottom home-gesture zone cannot be excluded: a finger there can cancel the round (never a wrong winner); first-launch caption and bottom gradient.
- Palm rejection on Android ≤ 12 looks like a lift and restarts the gate; harmless.
- Toolchain (AGP 8.13.2 / Kotlin 2.2.21 / Gradle 9.5.1 / JDK 25): expect one iteration on jvmTarget/toolchain; never change the global JDK.
- compileSdk 34 pins CameraX 1.4.2 and AndroidX versions; any bump must pass the AAR minCompileSdk check.
- R8 and ML Kit/CameraX: consumer rules ship in the AARs; keep `ApkInstaller$Receiver` explicitly; inspect `mapping.txt` for stripped camera/mlkit classes before release.
- Keystore reuse ties both apps to one key; never lose or rotate it; keystore files untracked.
- Status bar hidden while playing (no clock/battery visible): accepted, in README.
- Zero-tap update on Android 17+ will require the confirmation dialog: the STATUS_PENDING_USER_ACTION path is mandatory and tested by reasoning.

## 15. Delivery process

- Treat the repository root as the project root and commit step by step with gitmoji messages, following the repository's commit conventions. Suggested commit sequence: project skeleton and build config; core pure classes + tests; finger view and MainActivity; camera session, tracker, overlay and CameraActivity; settings; update manager and installer port; strings FR; README/LICENSE; release version bump.
- Gate before release: `gradlew lint testReleaseUnitTest assembleRelease`, then check APK size (expect 2–3 MB) and `mapping.txt`.
- Publish `Whooz.apk` as a GitHub release (`v1.0`); fill the GitHub "About" with a one-line description, topics (android, kotlin, player-picker, chwazi, board-games) and the website left empty.