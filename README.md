# Whooz

**Who plays first?** Whooz is a tiny Android app that answers that one question. Everyone
puts a finger on the screen, or everyone looks at the selfie camera, and after a short
suspense only one player's color remains. No accounts, no ads, and no network except for its
own updates; camera data stays on the phone. Inspired by [Chwazi](https://chwazi.com/).

## Two ways to decide

- ☝️ **Finger mode** — the app opens straight into the arena. Every finger that lands gets
  its own neon circle. Once everybody holds still, a ring fills around each finger, the
  circles pulse faster and faster, the losers vanish one by one and the winner's color
  floods the whole screen. Lift every finger to play again.
- 🤳 **Camera mode** — tap the camera icon. The selfie camera finds every face and draws a
  colored ring around it. Hold still, and the same countdown eliminates the rings until one
  player is left. Nothing leaves the phone: frames are analyzed on the device and dropped.
  (Needs Google Play services for the face model.)
- 🪪 **Roll call** — for big groups or a small room: players come in front of the camera
  one at a time. Each face is locked after a second and photographed into a portrait tile
  at the bottom of the screen; step aside, next player. When everyone is in, tap **Go** and
  the draw runs over the portraits: they pulse, the losers vanish one by one, and the
  winner's portrait fills the screen in their color. Tap a portrait to select it, then use
  **Remove selected** for an intentional deletion, or **New group** to start over. Up to five
  recent groups stay in memory for the current session, with real portrait thumbnails so a group
  can be recognized and restored later. Closing the app clears them.

Both modes offer more than "pick one" from the pill at the bottom of the screen:

| Sub-mode | What it does |
|---|---|
| **Pick 1 / 2 / 3** | Keeps one, two or three players and eliminates the others. |
| **Order** | Gives every player a number: the order of play. (finger mode) |
| **2 teams / 3 teams** | Splits the players into teams of equal size, by color. (finger mode) |

The **Settings** gear lets you choose how long everyone has to hold still (fast / normal /
slow), turn haptics or keep-screen-on off, choose automatic or manual portrait capture,
decide whether the app remembers the last mode, and control automatic updates.

Designed to be invisible: a single ~3 MB APK, no background services, no wakelocks, no
trackers, zero frames drawn while nothing moves, and the camera is released the moment the
screen goes away.

## 📥 Download & install (2 minutes, no technical skills needed)

1. **On your Android phone**, open this link:
   **[⬇ Download the latest Whooz APK](https://github.com/Purgator/Whooz/releases/latest/download/Whooz.apk)**
2. When the download finishes, tap the file (or open it from your notifications).
3. Android may ask you to *allow installing apps from this source* — allow it, then come
   back and tap the file again.
4. If Google Play Protect shows a warning, tap **More details → Install anyway** (the
   warning appears for every app installed outside the Play Store).
5. Open **Whooz**. Put fingers down. That is the whole tutorial.

Requires Android 8.0 (2017) or newer. Camera mode asks for the camera permission the first
time you open it; deny it and finger mode keeps working.

**Updates arrive by themselves**: the app checks GitHub once a day while it is open,
downloads the new version over Wi-Fi, and shows a small *Install* banner in the arena. All
versions with release notes: [Releases page](https://github.com/Purgator/Whooz/releases).

### Good to know

- Most phones track **10 simultaneous fingers**; extra fingers simply get no circle.
- Keep fingers away from the **very bottom edge** of the screen: Android reserves it for the
  home gesture and may cancel the round (it never picks a wrong winner).
- The status bar is hidden while you play; swipe from the top to peek at it.
- Camera mode needs **Google Play services** (it downloads the face model the first time,
  which can take a few seconds). Phones without it only have finger mode.
- After a camera round, Google's ML Kit client may flush a small usage-statistics batch to
  Google through a scheduled job once the app is closed. That is the only thing that can
  ever run while Whooz is off screen; it comes from the face detector itself and has no
  switch in its public API. Finger mode never triggers it.

## Development setup

A standard Gradle/Kotlin Android project: Gradle 9.5.1 (wrapper included), AGP 8.13.2,
Kotlin 2.2.21, compile/target SDK 34, min SDK 26. Dependencies: AndroidX core/appcompat/
activity, CameraX 1.4.2 and the unbundled ML Kit face detector. No Compose, no Material
Components, no coroutines.

### 1. Prerequisites

- **JDK 17 or newer** (JDK 25 is what the project is built with; JDK 26 is not yet
  supported by the Android toolchain).
- **Android SDK** platform 34 and build-tools 34.0.0, either through Android Studio or the
  [command-line tools](https://developer.android.com/studio#command-line-tools-only):
  ```
  sdkmanager --sdk_root=<sdk-dir> "platform-tools" "platforms;android-34" "build-tools;34.0.0"
  ```

### 2. Clone and point Gradle at the SDK

```bash
git clone https://github.com/Purgator/Whooz.git
cd Whooz
echo "sdk.dir=/path/to/android-sdk" > local.properties
```

On Windows, escape the drive colon (`sdk.dir=C\:/path/to/android-sdk`) or lint fails with
PropertyEscape.

### 3. Build and test

```bash
./gradlew assembleDebug          # debug APK -> app/build/outputs/apk/debug/
./gradlew testDebugUnitTest      # unit tests (engine, tracker, geometry, updater)
./gradlew lintDebug              # lint: draw-path allocation is an error here
./gradlew assembleRelease        # signed release APK (needs the keystore, see below)
```

The unit tests cover every decision the app makes: pointer bookkeeping, the hold-still
gate, the chooser state machine (including a 10 000-round fairness check), the face tracker
(id rescue, confirmation, grace period), the rotation math of the camera pipeline, version
comparison and the update policy. Run them after touching anything in `core/` or
`camera/`.

### 4. Release signing (optional, needed for `assembleRelease`)

Release builds are signed only when `keystore.properties` exists at the repo root. Both it
and `release.keystore` are **gitignored — never commit them**:

```properties
storeFile=release.keystore
storePassword=...
keyAlias=...
keyPassword=...
```

Keep signing with the same key forever: Android only installs updates whose signature
matches the installed app, and the self-updater relies on that. Debug builds use the debug
key and cannot update a release install (uninstall first).

### 5. Deploy to a phone

```bash
adb install -r app/build/outputs/apk/release/app-release.apk
adb logcat -s Whooz.Update Whooz.Camera
```

There is no emulator in the project's own setup: the camera coordinate chain (sensor →
analysis buffer → preview view, including the front-camera mirroring) is verified by unit
tests on the pure rotation math and by a debug overlay (long-press the hint in a debug
build) that draws the raw face boxes over the preview.

### Code map

| Path | Role |
|---|---|
| `MainActivity.kt` | Finger arena host: immersive window, chrome, hints, mode memory, update banner |
| `finger/FingerChooserView.kt` | The arena: multi-touch → tracker/gate/engine, allocation-free drawing, winner flood |
| `core/TouchTracker.kt` | Pointer bookkeeping by id; slot = color; absorbed fingers |
| `core/StabilityGate.kt` | "Hold still" progress with a collapse animation on every change |
| `core/ChooserEngine.kt` | IDLE → COUNTDOWN → ELIMINATING → RESULT → FADING; draws the outcome once |
| `core/Mode.kt`, `Timings.kt`, `Palette.kt`, `Easing.kt` | Sub-modes, durations, colors, animation math |
| `CameraHostActivity.kt` | Shared camera host: permission flow, session, ML Kit banner, immersive window, sleep |
| `CameraActivity.kt` | Group camera mode: the face overlay, the Pick 1/2 pill |
| `RollCallActivity.kt`, `camera/RollCallView.kt`, `camera/RollCall.kt` | Roll call: one-at-a-time lock (pure, tested), portrait capture from the preview, the draw over the portraits |
| `camera/CameraSession.kt` | CameraX: front camera, Preview + ImageAnalysis, 15 Hz throttle, view matrix |
| `camera/FaceSource.kt` | ML Kit detector lifecycle/retry; faces → view coordinates → tracker |
| `camera/FaceTracker.kt`, `FaceGeometry.kt` | Pure tracking (id rescue, 3-hit confirm, grace) and rotation math |
| `camera/FaceOverlayView.kt` | Rings, countdown, elimination and winner tint over the preview |
| `update/` | GitHub-releases self-update: daily check, Wi-Fi download, signature check, PackageInstaller |
| `SettingsActivity.kt`, `ui/` | Hand-built settings; frame clock, haptics, chrome fade, update banner |

### Releasing a new version

1. Bump `versionCode` and `versionName` in `app/build.gradle.kts`.
2. `./gradlew assembleRelease`
3. Publish the APK as a release asset named exactly **`Whooz.apk`** with tag `vX.Y.Z`:
   ```bash
   cp app/build/outputs/apk/release/app-release.apk Whooz.apk
   gh release create vX.Y.Z Whooz.apk --title "..." --notes-file notes.md
   ```
   Installed apps pick it up within a day (daily check, Wi-Fi download, one tap to install).

## How it works

1. **Finger mode.** `TouchTracker` follows each pointer by id and gives it the lowest free
   color. `StabilityGate` fills a ring around every finger while the set of fingers is
   unchanged for the chosen delay; any finger landing or lifting collapses the rings and
   restarts. When armed, `ChooserEngine` freezes the participants, draws the outcome once
   with a secure random source, and pre-shuffles the elimination order. The countdown
   pulses faster and faster, the losers dim in order, vanish one every 350 ms, and the
   winner's color floods the screen from under their finger. Everything is a pure function
   of the frame time, so the view allocates nothing while drawing and stops asking for
   frames as soon as nothing moves.
2. **Camera mode.** CameraX feeds ~15 frames per second of the selfie camera to the ML Kit
   face detector (fast mode, tracking on). `FaceTracker` turns the detections into stable
   players: a face must be seen three frames in a row to count, keeps its ring for 700 ms
   when it blinks out, and survives ML Kit changing its id if it reappears close by. The
   same gate and engine then run on the confirmed faces; during the elimination the analyzer
   is switched off so rings stay where they were and nobody can dodge.
   The roll call reuses the same session and tracker with a one-face rule: exactly one
   confirmed face held still locks, the preview is copied once around the ring into a
   portrait, and the frame must be empty for a moment before the next player can be timed,
   so nobody is enrolled twice by standing still. The draw then runs on portraits instead
   of live faces, which also makes it immune to detection range.
3. **Updates.** Once a day while the app is open, it reads the latest GitHub release,
   downloads `Whooz.apk` over Wi-Fi, checks that the APK is signed with the same key, and
   offers it in a banner. The install goes through a `PackageInstaller` session, so on recent
   Android versions it needs no confirmation at all.

## License

Whooz is free software licensed under the **GNU General Public License v3.0** — see
[LICENSE](LICENSE). You may use, study, share, and modify it; if you distribute a modified
version, you must release your changes under the same license.

Copyright © 2026 Aymeric Richard and contributors.
