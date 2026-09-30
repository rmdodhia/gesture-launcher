# Gesture Launcher — Plan

**Status:** v1 (M1–M3, M5) implemented. M4 (Google Home) implemented behind the `homeSdk` Gradle property; compiles against the real SDK 17.1.0 (Kotlin 2.4) and reaches Google's consent screen on the emulator. Still to verify on the S25+: OAuth consent, device list, commands (README → Google Home setup). M0 Spike B (verify book deep links) must be done on the phone using each action's **Test** button.

Draw a shape or perform a touch gesture on a full-screen canvas; the app recognizes it and runs a bound action (open app, open a specific book, control a Google Home device).

**v1 scope (first pass):** capture canvas, record/recognize gestures, app + book + generic URI actions, quick access. **No Google Home / no Google Home Developers registration in v1.** Google Home was added afterwards (M4) as an optional build feature.

## Constraints & decisions

| Item | Decision |
|---|---|
| Device | Samsung S25+, latest Android (One UI). `minSdk 29` (Home APIs need Android 10+), `targetSdk` = latest stable |
| Distribution | Personal only; install via Android Studio / adb |
| Dev setup | Android Studio on Windows. Emulator for UI work; **physical S25+ required** for multi-touch testing and Google Home APIs (emulator unsupported by Home APIs) |
| Language/UI | Kotlin, Jetpack Compose, Material 3 |
| Storage | Single JSON file (kotlinx.serialization), atomic writes, corrupt-file quarantine; also the export/import format |
| Recognition | On-device, user-recorded templates; no ML training infra |
| Overlay / draw-over-apps | **Deferred** (see Future) |
| Google Home | M4: optional. Builds without the SDK (stub gateway); `homeSdk=<version>` switches in `src/home` + SDK deps. No Developer Console registration (testing mode, own account as OAuth test user) |

## Architecture

```
app/src/main/java/io/github/rmdodhia/gesturelauncher/
  core/          Pure Kotlin: model, SampleBuilder, FeatureExtractor, PointCloud ($P), Recognizer, Links
  data/          Store (JSON), ErrorLog (crash + non-fatal log)
  ui/            Compose: CaptureSurface, DrawScreen, GestureListScreen, EditGestureScreen, ActionPicker
  ActionRunner.kt, MainActivity.kt (share intake), DrawTileService.kt
  home/          HomeGateway interface (+ src/home real SDK impl, src/nohome stub)
```

No DI framework, Room or navigation library: state lives in one `Store` (StateFlow) owned by the Application.

### Gesture data model

A captured `GestureSample` is the universal input so new gesture kinds can be added later without changing storage:

- `pointers`: list of pointer tracks, each `[(x, y, t, pressure)]`, normalized to canvas size
- `strokes`: grouping of tracks into strokes (a stroke ends on pointer-up; a gesture ends after an idle timeout, default ~600 ms, configurable)
- derived features: max simultaneous pointers, stroke count, total duration, tap intervals, path length

`Gesture` = name + kind + N recorded `GestureSample`s (3–5 recommended). `Binding` = gesture → `Action`.

### Recognition pipeline

1. **Router** decides the gesture kind from features:
   - Short, near-stationary contacts → **tap-type** (count simultaneous fingers, count taps, intervals)
   - Multi-pointer movement → **multi-finger swipe** (finger count + dominant direction)
   - Otherwise → **shape** (single- or multi-stroke drawing)
2. **Shape**: $P point-cloud recognizer (stroke-order/direction invariant, handles multi-stroke, works with few templates). Resample to 32 points, scale, translate; nearest-template distance → score (`1 - d/6`, calibrated on synthetic data in `PointCloudCalibrationTest`). Finger count and stroke count must match. For strokes drawn together (single strokes, multi-finger swipes) a start→end direction check separates left/right/up/down.
3. **Multi-finger tap/swipe**: exact match on finger count + tap count/direction.
4. **Tap rhythm**: normalize inter-tap intervals, compare with DTW or tolerance band.
5. **Decision**: best score must exceed threshold **and** beat runner-up by a margin; otherwise show "Not recognized" (no action). On success, execute immediately (no confirmation step) and show a brief toast/haptic with the gesture name.
6. **Conflict check at record time**: when saving a new gesture, test it against existing ones and warn if too similar.

Canvas must consume system-conflicting input carefully: use edge-to-edge with `systemGestureExclusion` on side edges so back-swipe doesn't fire mid-draw.

### Actions

Sealed `Action` with an executor per type; each can be tested from the binding screen ("Run now").

| Action | Implementation | Risk |
|---|---|---|
| Open app | `PackageManager.getLaunchIntentForPackage`; picker lists launchable apps. Requires `<queries>` with `MAIN/LAUNCHER` intent in manifest (Android 11+ package visibility) | Low |
| Open Kindle book | Unofficial deep link `kindle://book?action=open&asin=<ASIN>`, tried in `com.amazon.kindle` then `com.amazon.kindlefs` (Galaxy Store build); fallback: open Kindle app | Medium — undocumented, verify on device |
| Open Play Books book | `https://play.google.com/books/reader?id=<volumeId>` targeted at `com.google.android.apps.books` (resolves to its ReadingActivity; old `/store/books/details` links are rewritten at run time) | Low |
| Open Libby book | `libbyapp.com/library/<lib>/everything/page-1/<titleId>` (rewritten from `share.libbyapp.com/title/<id>#library-<lib>`) targeted at `com.overdrive.mobile.android.libby`; fallback: open Libby | Medium — opens the title page, not the reader |
| Generic intent/URL | User pastes any URI (catch-all for book links that work) | Low |
| Google Home device (M4) | Home APIs: on/off, brightness (Dimmable/On-Off/Color Temp lights, plugs) | High — setup overhead; see below |

Book picker UX (v0.2): one **Book** tab lists `AppData.books` from all three apps (Reading now / Library, search), so the user never picks the app. Play Books syncs automatically through the official Books API (`mylibrary/bookshelves/3` Reading now + `/7` My eBooks, scope `auth/books`, via `Identity.getAuthorizationClient`). Kindle and Libby books are added by sharing into the app (`ACTION_SEND`) or pasting a link. Automatic Kindle/Libby listing was rejected: there's no public API, Amazon's terms forbid scraping, and Libby's private API explicitly bans third-party clients and threatens suspension.

### Google Home integration (M4)

As built: `HomeControl(deviceId, deviceName, command = ON|OFF|TOGGLE|BRIGHTNESS, percent)` action (JSON type `"home"`); `HomeGateway` interface with `status` (Unavailable / Checking / NeedsAccess / Ready), `requestAccess`, `devices`, `run`. `src/home/.../HomeGatewayFactory.kt` wraps the SDK (15 s timeout, never throws, errors logged); `src/nohome` returns a stub. Brightness % → Matter level `round(p·254/100)` clamped to 1..254. Toggle falls back to reading on/off state if the device lacks the Toggle command.

Original plan:

- Sign in to Google Home Developers, download the Home APIs Android SDK, host it as a local Maven repo in the project.
- Register the app (package name + debug SHA-1) in the Google Home Developer Console / Cloud project; add your account as a test user.
- In-app: `Home` client init → Permissions API consent flow → list structures/rooms/devices → device picker in ActionPicker.
- Commands: `OnOff.on/off/toggle`, `LevelControl` move-to-level (brightness %). Store device ID + trait + params in the Action.
- Your setup: lights/devices are **cloud-to-cloud** (linked via other brands' apps), **no Nest hub**. Home APIs support cloud-to-cloud devices without a hub (hub is only needed for local Matter control), so this path is viable. Commands go through Google's cloud, so expect ~0.5–2 s latency and a network dependency.
- Still verify in Spike A that each of your devices appears and exposes the traits we need (some brand integrations expose fewer traits, e.g. no brightness).
- **Fallback** if Home APIs don't cover your devices: webhook action (IFTTT / Home Assistant REST / SmartThings API). The `Action` abstraction makes this a drop-in.

## Milestones

Each milestone ends with a verification on the S25+.

**M0 — Project & spikes (de-risk first)**
- Create Compose project (Kotlin DSL, version catalog), Room, Hilt (or manual DI).
- Spike B: from a throwaway button, fire Kindle / Play Books / Libby deep links with a real book each; record which work.
- ✅ Done when: we know which book links are viable.

**M1 — Capture canvas**
- Full-screen Compose canvas, multi-pointer tracking via `pointerInput`, live stroke rendering, idle-timeout end-of-gesture, debug overlay (pointer count, strokes, duration).
- ✅ 3-finger tap, 2-finger swipe, multi-stroke "X" captured correctly (inspect debug overlay).

**M2 — Record & recognize**
- Record screen (draw 3–5 samples, preview), gesture list (rename/delete/add samples).
- Router + $Q + multi-touch + tap-rhythm classifiers; threshold/margin; similarity warning on save.
- Unit tests for recognizers with synthetic and recorded fixtures (JVM tests, no device).
- ✅ ≥10 recorded gestures, each recognized ≥9/10 attempts, <1/20 false triggers on random scribbles.

**M3 — Actions: apps & books**
- Action model, ActionPicker, app picker, book/URI actions, share-target intake, "Run now" test.
- ✅ Gestures open chosen apps and at least the book links proven in Spike B.

**M4 — Google Home** — implemented and built against SDK 17.1.0; pending on-phone verification (consent, devices, commands)
- Spike A first: build & run Google's Home APIs Sample App on the S25+; confirm cloud-linked lights/devices are listed and on/off + brightness work.
- SDK integration, permissions flow, device picker, on/off/brightness actions, error handling (offline, permission revoked).
- ✅ Gesture dims a real light to a chosen %.

**M5 — Polish & quick access**
- Quick Settings tile and home-screen shortcut that open the canvas directly; haptics; settings (timeout, sensitivity); export/import gestures (JSON backup).
- ✅ From home screen to action in ≤2 s.

## Testing strategy

- JVM unit tests: resampling/normalization, $Q scoring, router, tap-rhythm matching, Action serialization.
- Recorded gesture fixtures (export from device as JSON) used as regression tests.
- Instrumented/manual on S25+: multi-touch, Home APIs, deep links. Emulator only for single-pointer UI flows.

## Risks

1. **Home APIs** — open beta, SDK not on Maven Central, per-brand trait coverage for cloud-to-cloud devices, cloud latency. Mitigated by Spike A + webhook fallback.
2. **Book deep links** — undocumented; may break with app updates. Mitigated by generic-URI action and "open app" fallback.
3. **Emulator** — poor multi-touch; use the S25+ with wireless debugging from Windows.
4. **Samsung system gestures** — edge swipes/Edge Panel may steal touches; use gesture exclusion rects and avoid edge-starting shapes.
5. **Recognition confusability** — as gesture count grows; mitigated by similarity warning and margin threshold.

## Future (deferred)

- Draw over other apps: foreground service + `SYSTEM_ALERT_WINDOW` edge handle that expands into a transparent capture canvas (avoid a persistent full-screen overlay that blocks touches); Samsung battery-optimization exemption.
- Lock-screen access, context-aware bindings (time/location), more action types (system toggles, Tasker, webhooks).

## Resolved decisions

- v1 = M0 (Spike B only), M1, M2, M3, M5. M4 added afterwards as an optional build feature.
- Devices: cloud-to-cloud via other brands' apps; no Nest hub → Home APIs, no local control.
- Actions execute immediately, no confirmation.
