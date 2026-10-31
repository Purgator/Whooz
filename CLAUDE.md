# CLAUDE.md — project instructions for AI assistants

Whooz is a single-purpose Android app (Kotlin, one Gradle module, no third-party runtime
dependencies beyond AndroidX, CameraX and the unbundled ML Kit face detector): it randomly
chooses a player among people physically present. Finger mode (everyone puts a finger on
the screen), camera mode (everyone in front of the selfie camera at once) and the roll call
(players photographed one at a time, the draw runs over the portraits). Inspired by Chwazi.
The binding design is `docs/DESIGN.md`; read it before touching the engine or the camera
pipeline.

## The golden rules

1. **Zero friction.** The launcher opens straight into the finger arena. No splash, no
   onboarding, no menu before play. Chrome (camera / settings / sub-mode pill) is only
   visible while the arena is idle with no finger down.
2. **Lightweight.** Plain Views + custom Canvas views. No Compose, no Material
   Components, no androidx.preference, no coroutines, no sounds, no PNGs. The release
   APK should stay around 2–3 MB.
3. **Battery is a feature.** Nothing runs when the app is not on screen: no Service,
   WorkManager, alarms or boot receiver. The frame loop (`ui/FrameClock`) runs only while
   something animates; a resting phone with fingers down costs zero frames. The camera
   analyzer is throttled to ~15 Hz with one frame in flight and is cleared during the
   elimination/result. Known exception, documented in the README: the unbundled ML Kit
   client (camera mode only) may flush a usage-statistics batch through a JobScheduler
   job after use; it is Google-owned, has no opt-out, and must not be stripped from the
   merged manifest with `tools:node="remove"` (transport-runtime would crash on
   `JobScheduler.schedule`).
4. **Zero allocation in hot paths.** `onDraw`, `onTouchEvent` and the per-frame camera
   path use preallocated primitive arrays and indexed loops: no lambdas, collections,
   boxing or `String.format`. Lint treats `DrawAllocation` as an error.
5. **Decisions are pure and tested.** `core/` (except `Settings.kt`) and the face
   tracker/geometry have no Android imports and take an injected clock and `Random`.
   New logic goes there with a JUnit test; Android glue stays thin.
6. **Locale-safe.** Every user-facing string exists in `values/` and `values-fr/`.
7. **Every change ships.** Build + test + lint, commit with gitmoji, bump the version,
   push, publish the GitHub release. Installed apps
   self-update from GitHub releases within a day.

## Environment

- Windows 11, Git Bash. **JDK 25 at `C:/dev/tools/jdk-25`** (`export JAVA_HOME="C:/dev/tools/jdk-25"`
  in a fresh shell; the machine PATH answers with an unsupported JDK 26).
- Android SDK path is configured in the gitignored `local.properties`; point it to the
  installed SDK and escape the Windows drive colon (`C\:`) or lint fails with PropertyEscape.
  Platform 34, build-tools 34.0.0 / 35.0.0.
- Gradle 9.5.1 / AGP 8.13.2 / Kotlin 2.2.21 via the wrapper. compileSdk 34 pins CameraX
  to 1.4.2 and androidx.core to 1.13.x; any dependency bump must pass the AAR
  `minCompileSdk` check.
- **Signing**: `release.keystore` + `keystore.properties` at the repo root, both
  gitignored (alias `adblocker`). Never commit or print them. Self-update requires this
  key forever.
- **No emulator/device on this machine.** Verification = compile + unit tests + lint +
  reasoning. The owner tests on a Pixel 8 Pro and reports back; be explicit about what
  could not be exercised (the camera transform chain in particular).

## Commands

```bash
./gradlew assembleDebug testDebugUnitTest lintDebug   # the standard gate
./gradlew assembleRelease                              # signed release APK
grep -c 'severity="Error"' app/build/reports/lint-results-debug.xml   # must be 0
```

## Release recipe

1. Bump `versionCode` (+1) and `versionName` in `app/build.gradle.kts`; commit as
   `🔖 Bump version to X.Y.Z`.
2. `./gradlew assembleRelease && git push`
3. `cp app/build/outputs/apk/release/app-release.apk Whooz.apk` — the asset **must** be
   named `Whooz.apk` and the tag `vX.Y.Z` (the updater matches both; tag = versionName).
4. `gh release create vX.Y.Z Whooz.apk --title "..." --notes-file <file>` (notes in a
   temp file: inline backticks get eaten by the shell). Then `rm Whooz.apk`.
5. `gh release view vX.Y.Z --json tagName,assets` to verify.

## Architecture map (package `fr.arichard.whooz`)

| Where | What |
|---|---|
| `MainActivity.kt` | Finger arena host: immersive window, keep-screen-on, chrome visibility, hints, back handling, mode memory, update banner, daily update check. |
| `CameraHostActivity.kt` | Shared base of both camera screens: permission flow, `CameraSession`, ML Kit banner, immersive window, keep-screen-on, sleep/wake. |
| `CameraActivity.kt` | Group camera mode: `FaceOverlayView`, Pick 1/2 pill, debug toggle. |
| `RollCallActivity.kt` | Roll call: `RollCallView`, Go / New group buttons, portrait capture from `PreviewView.bitmap`. |
| `camera/RollCall.kt` | Pure one-at-a-time enrolment: lone confirmed face held still → lock; frame must empty before the next; roster of slots. |
| `camera/RollCallView.kt` | Live ring + lock arc, portrait strip, the draw animated over the portraits (allocation-free). |
| `SettingsActivity.kt` | Hand-built settings (switch rows, preset chips, check-for-updates, version, licences). |
| `core/TouchTracker.kt` | Pointer bookkeeping by id in parallel arrays; slot = color index; absorbed fingers. |
| `core/StabilityGate.kt` | "Hold still" progress; resets with a collapse animation on any set change. |
| `core/ChooserEngine.kt` | IDLE → COUNTDOWN → ELIMINATING → RESULT → FADING; freezes the set, draws the `Assignment` once, pre-shuffles eliminations. |
| `core/Mode.kt`, `Timings.kt`, `Palette.kt`, `Easing.kt` | Sub-modes, durations, colors, allocation-free animation math. |
| `core/Settings.kt` | Typed SharedPreferences (the only Android import in `core`). |
| `finger/FingerChooserView.kt` | The arena: touch → tracker/gate/engine, allocation-free drawing, flood reveal. |
| `camera/CameraSession.kt` | CameraX glue: front camera, Preview + ImageAnalysis, 15 Hz throttle, analysis→view matrix. |
| `camera/FaceSource.kt` | ML Kit detector lifecycle/retry; maps faces to view coordinates and feeds the tracker. |
| `camera/FaceTracker.kt`, `FaceGeometry.kt`, `FaceFrame.kt` | Pure tracking (id rescue, 3-hit confirm, grace period) and rotation math. |
| `camera/FaceClip.kt`, `IdleSleep.kt`, `FrameThrottle.kt`, `ComeCloser.kt` | Pure helpers: drop faces outside the FILL_CENTER crop, 3-minute idle sleep, 15 Hz gate, "come closer" hint. |
| `camera/FaceOverlayView.kt` | Rings over the preview, countdown, elimination, winner tint. |
| `update/UpdateManager.kt`, `ApkInstaller.kt`, `UpdatePolicy.kt` | GitHub-releases self-update: daily check, Wi-Fi download, signature check, PackageInstaller session. |
| `ui/FrameClock.kt`, `Haptics.kt`, `Chrome.kt`, `UpdateBanner.kt` | Frame loop, haptics, chrome fade, in-app update banner. |

Commit style: gitmoji first (`✨ 🐛 💄 📝 🔖 ✅ 🚨 🌐 ♻️ ⚡`), imperative subject, body
explains the why.
