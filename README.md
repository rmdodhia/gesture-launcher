# Gesture Launcher

[![CI](https://github.com/rmdodhia/gesture-launcher/actions/workflows/ci.yml/badge.svg)](https://github.com/rmdodhia/gesture-launcher/actions/workflows/ci.yml)

An Android app that turns gestures into actions. Draw a shape, tap with several fingers, swipe, or tap a rhythm on a full-screen canvas, and the phone opens an app, opens a specific book in Kindle, Libby or Google Play Books, or switches/dims a Google Home light or plug. You record your own gestures; recognition runs on the device.

- **Gestures:** single- and multi-stroke shapes, multi-finger taps and swipes, tap rhythms. Recognition uses a [$P point-cloud recognizer](https://depts.washington.edu/acelab/proj/dollar/pdollar.html) plus finger/stroke/tap-count checks, and refuses ambiguous matches.
- **Actions:** open any app; open a book from one combined list (Play Books synced automatically, Kindle/Libby added via the share sheet); control Google Home devices, including ones linked from other brands' apps; open any link.
- **Quick access:** a Quick Settings tile opens the drawing canvas.
- **Private:** gestures and your book list are stored only on the phone. The app talks to Google's APIs only if you connect Play Books or Google Home.

It's a personal project, developed and tested on a Samsung Galaxy S25+. It needs Android 10 or later. Design notes and roadmap: [PLAN.md](PLAN.md).

## Build & install

Requirements: [Android Studio](https://developer.android.com/studio) (or JDK 17+ and the Android SDK for command-line builds) and an Android phone with [developer options and USB or wireless debugging](https://developer.android.com/studio/debug/dev-options) turned on.

1. Clone the repo and open it in Android Studio (File → Open), then let Gradle sync. The bundled JDK is fine.
   - On Windows with WSL, keep the project on the Windows filesystem (e.g. `C:\dev\gesture-launcher`); Gradle is slow and unreliable on `\\wsl$` paths.
2. Connect your phone (USB, or Device Manager → *Pair devices using Wi-Fi*).
3. Run with the green ▶ button and the `app` configuration.

That's all you need for gestures, apps, links and hand-added books. Two features need a one-time Google Cloud setup of your own, because Google only issues access to apps registered to you: [Play Books sync](#books) and [Google Home control](#google-home-setup). Without them the app still works; those screens just explain what's missing.

Use the emulator only for single-finger UI checks; it can't simulate multi-finger gestures, and Google Home control doesn't work on it.

Command line equivalents (from the project root):

| Task | Command |
|---|---|
| Unit + UI tests (JVM, no device) | `gradlew testDebugUnitTest` |
| Lint | `gradlew lintDebug` |
| Build APK | `gradlew assembleDebug` (output: `app/build/outputs/apk/debug/app-debug.apk`) |
| Install on connected phone | `gradlew installDebug` |

## Using it

1. Open the app. The home screen is the drawing canvas. Tap **Gestures** → **New gesture**.
2. Name it, then draw it **3–5 times** in the box, the way you'll normally do it. Each drawing shows up as a thumbnail with a summary (e.g. "3-finger tap" or "1 finger · 2 strokes"). Tap ✕ to drop a bad one. All samples must be the same kind of gesture (e.g. all 3-finger taps). If one isn't, it's rejected with an explanation.
3. Tap **Choose** to pick an action:
   - **App** opens any installed app.
   - **Book** shows one list of your books from Kindle, Libby and Play Books, split into *Reading now* and *Library*, with search. Pick a book; the gesture opens it in whichever app it belongs to. See [Books](#books) for how books get onto the list.
   - **Google Home** switches a light/plug on, off, toggles it, or sets brightness (needs [Google Home setup](#google-home-setup)).
   - **Link** takes any link, optionally forced to open in Libby, Kindle or Play Books.
   - Use **Test** to check that it opens the right thing, then tap **Use this action** and **Save**.
4. Shortcut: in any app, **Share → Gesture Launcher** (labelled *Create gesture*). Books (Kindle, Libby, Play Books links) are added to your book list, and a **Make gesture** button appears. Any other link opens a new gesture with the link already filled in.
5. Quick access: add the **Draw gesture** tile to Quick Settings (pull down twice → ✎ edit → drag the tile in).

### What counts as the same gesture

- **Shapes** can be one stroke or several (lift your finger between strokes, e.g. an X). For multi-stroke shapes, stroke order and direction don't matter. A single stroke is direction-sensitive, so a left swipe and a right swipe are different gestures.
- **Finger count** and **stroke count** must match. A 2-finger swipe is never confused with a 1-finger swipe.
- **Taps** match on the number of fingers in each tap and the sequence (e.g. double tap, 3-finger tap). With 3 or more taps the rhythm also counts, independent of tempo.
- A gesture ends once no finger has been down for the **end-of-gesture pause** (default 600 ms). Increase it in Settings if slow multi-stroke shapes get split.
- If two gestures match almost equally well, nothing runs. That's deliberate, to avoid wrong actions. The editor warns you when a new gesture is too similar to an existing one.

### Settings (Gestures → ⋮ → Settings)

- **Strictness** (default 80%): raise it if random scribbles trigger actions; lower it if your gestures often come back "Not recognized".
- **End-of-gesture pause**: see above.
- **Show match scores**: shows the score and the closest gesture after each attempt. Useful for tuning.

## Books

The Book list combines three sources:

- **Play Books: automatic.** The first time you open **Book**, tap **Connect** next to *Play Books* and allow access. Your *Reading now* shelf and *My books* are then listed and refreshed whenever you open the list (at most every 10 min; **Refresh** forces it). This uses Google's official Books API, so it needs your own Google Cloud project and OAuth client: do steps 3–4 of the [Google Home setup](#google-home-setup) (the Home SDK itself isn't needed). Then, in that project, open <https://console.cloud.google.com/apis/library/books.googleapis.com> and click **Enable**. If you skip this, the app says so. It uses the same OAuth clients (package name + SHA-1) as Google Home.
- **Kindle and Libby: share them in.** In Kindle or Libby, open a book's page and tap **Share → Gesture Launcher**. You can also tap **Add by link** in the list and paste an Amazon/Kindle link, an ASIN, or a Libby link. New books go under *Reading now*; use ⋮ on a book to move it or remove it.
  - Kindle opens the book with `kindle://book?action=open&asin=…`. The Galaxy Store build of Kindle (`com.amazon.kindlefs`) and the Play Store build both work.
  - Libby share links (`share.libbyapp.com/title/…#library-…`) are rewritten to the title page inside Libby (`libbyapp.com/library/<library>/everything/page-1/<id>`), where you tap *Open book*.

Why Kindle and Libby aren't listed automatically: neither has a public API. Amazon's terms forbid automated access to your Kindle library, and Libby's private API explicitly forbids other clients and threatens account suspension. Sharing a book in takes a few seconds and keeps your accounts safe.

## Google Home setup

Controls any light or plug that works in the Google Home app, including ones linked from other brands' apps ("cloud-to-cloud"); no Nest hub needed for those. It uses Google's **Home APIs**, which have two hurdles: the SDK isn't on a public Maven repo (you download it after signing in, and it can't be redistributed here), and Google only lets the app talk to your home after an OAuth setup in your own Google Cloud project. Without the SDK the app builds and works normally, and the Google Home tab just explains what's missing.

Do this once, on the machine you build on:

1. **Download the SDK.** Sign in at <https://developers.home.google.com/apis/android/sdk> and download the Android SDK zip (e.g. `home.android.sdk_1_11_0.zip`, which contains Maven artifacts version 17.1.0). Unzip it into the project as `home-sdk/` (git-ignored) so you have `home-sdk/com/google/android/gms/play-services-home/17.1.0/…`, or into `~/.m2/repository/` (on Windows `%USERPROFILE%\.m2\repository\`).
2. **Check the version.** `gradle.properties` has `homeSdk=17.1.0`; if your download has a different version folder, change it. The build uses the SDK automatically when that version is present in `home-sdk/` or `~/.m2`, and prints a note and builds without it otherwise. Pass `-PhomeSdk=` to force a build without it. The SDK is compiled with Kotlin 2.4, so the project must stay on Kotlin ≥ 2.4 (`gradle/libs.versions.toml`).
3. **Google Cloud project + OAuth consent screen.** In <https://console.cloud.google.com>, create a project → *APIs & Services* → *OAuth consent screen*: user type **External**, leave it in **Testing**, no scopes needed, and add your own Google account under **Test users**.
4. **Android OAuth client(s).** *APIs & Services* → *Credentials* → *Create credentials* → *OAuth client ID* → **Android**, package name `io.github.rmdodhia.gesturelauncher`, and the SHA-1 of the key that signs the APK. Each machine has its own debug key (`~/.android/debug.keystore`), so create one client per machine you install from. To get the SHA-1:
   - Run `./gradlew signingReport` (Windows: `gradlew signingReport`) and copy the `SHA1` line of the `debug` variant, e.g. `SHA1: AB:CD:…:12` (20 pairs).
   - Or run `keytool -list -v -keystore ~/.android/debug.keystore -alias androiddebugkey -storepass android`. On Windows, `keytool` is in Android Studio's `jbr\bin` folder: `& "C:\Program Files\Android\Android Studio\jbr\bin\keytool.exe" -list -v -keystore "$env:USERPROFILE\.android\debug.keystore" -alias androiddebugkey -storepass android`.
   
   No Google Home Developer Console registration is needed for personal/testing use.
5. **On the phone** (the emulator lacks the needed Play services): install, then edit a gesture → **Choose** → **Google Home** → **Connect Google Home**. Pick your account and home and allow access. Your devices appear with their room; pick one, choose On / Off / Toggle / Brightness, **Test**, then **Use this action**.

Notes:
- Commands go via Google's cloud, so expect ~0.5–2 s and they need internet. The canvas shows "*gesture* → *action*…" while a command runs, then the result; failures (offline device, timeout after 15 s, access revoked) are shown and logged in the Error log.
- Only devices with an on/off control are listed; Brightness is offered only for dimmable ones. Some brand integrations expose fewer controls than the Google Home app shows.
- "Access blocked … has not completed the Google verification process" means your account isn't a test user on the consent screen, or the SHA-1/package doesn't match the OAuth client.

## When something goes wrong

- Errors are caught and shown on screen, and also written to **Gestures → ⋮ → Error log**. You can share that log.
- If the app ever crashes, the crash report is shown the next time you open it, with a **Share** button.
- If the saved gestures file is ever unreadable, it's moved aside (`gestures.json.corrupt-…`), not deleted, and you're told.
- Back up with **⋮ → Export backup**; restore with **Import backup**.

## Known limitations

- The Kindle `kindle://` link is undocumented. If it stops working, the app falls back to opening Kindle. Libby opens the book's page rather than straight into the reader. A Libby link without the library part opens the Libby app. Test each with **Test**.
- Samsung/One UI system gestures (edge swipes, Edge panel handle, navigation-bar swipes) can take touches that start at the very edge of the screen. The drawing area is inset from those edges for that reason.
- On synthetic test data, about 13% of random scribbles are accepted as some gesture at the default strictness. Wrong-gesture matches were 0 of 640. Raise strictness if that bothers you.

## Project layout

```
app/src/main/java/io/github/rmdodhia/gesturelauncher/
  core/   Pure Kotlin: model, feature extraction, $P recognizer, link parsing (unit-tested on the JVM)
  data/   JSON store (atomic writes), error/crash log
  books/  Book sources (Play Books via the Google Books API) and the unified library
  home/   HomeGateway interface (the app's only view of Google Home)
  ui/     Compose screens: draw canvas, gesture list, editor, action picker
  ActionRunner.kt, MainActivity.kt, GestureApp.kt, DrawTileService.kt
app/src/home/    Real Google Home gateway (compiled only when the Home APIs SDK is present)
app/src/nohome/  Stub used when the SDK isn't installed
app/src/test/  JVM unit tests + Robolectric end-to-end UI tests (record → save → draw → action)
```

## Contributing

Issues and pull requests are welcome. Please run `./gradlew testDebugUnitTest lintDebug assembleDebug` before sending a change; CI runs the same (without the Google Home SDK). Gesture recognition and link parsing live in `core/` as plain Kotlin with JVM tests, which is the easiest place to start.

## License and trademarks

[MIT](LICENSE). Not affiliated with or endorsed by Google, Amazon or OverDrive. Kindle, Libby, Google Play Books and Google Home are trademarks of their respective owners. The app opens their apps through public or de-facto links and uses only Google's official APIs; it does not access Kindle or Libby accounts.
