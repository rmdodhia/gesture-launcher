# Gesture Launcher

Draw a shape, tap with several fingers, swipe, or tap a rhythm on a full-screen canvas, and the phone opens an app or a specific book, or switches/dims a Google Home device. You record your own gestures.

See [PLAN.md](PLAN.md) for the design and roadmap. Google Home control needs a one-time setup: see [Google Home setup](#google-home-setup).

## Build & install (Android Studio on Windows → Galaxy S25+)

1. **Get the project onto Windows.** Gradle is slow and unreliable on `\\wsl$` paths, so copy or clone it to a Windows folder:
   ```powershell
   git clone \\wsl.localhost\Ubuntu\home\you\gesture-launcher C:\dev\android-interface
   ```
   (Adjust the distro name if needed, or copy the folder over.)
2. **Open it in Android Studio** with File → Open → `C:\dev\android-interface`, then let Gradle sync. The bundled JDK is fine.
3. **Turn on wireless debugging on the S25+:**
   - Settings → About phone → Software information → tap *Build number* 7 times.
   - Settings → Developer options → *Wireless debugging* → On.
   - In Android Studio: Device Manager → *Pair devices using Wi-Fi* → scan the QR code from the phone.
4. **Run** with the green ▶ button and the `app` configuration on the S25+.

Use the emulator only for single-finger UI checks; the emulator can't simulate multi-finger gestures.

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
   - **Google Home** switches a light/plug on, off, toggles it, or sets brightness (needs [Google Home setup](#google-home-setup)).
   - **Kindle book** takes an ASIN (the `B0…` ID in the book's Amazon URL) or a pasted Amazon link, and opens it with `kindle://book?action=open&asin=…`.
   - **Play Books** takes a volume ID or a Play Store book link.
   - **Libby / link** takes any link, optionally forced to open in Libby, Kindle or Play Books.
   - Use **Test** to check that it opens the right thing, then tap **Use this action** and **Save**.
4. Shortcut: in Libby, Kindle, Play Books or a browser, use **Share → Gesture Launcher** (labelled *Create gesture*). A new gesture opens with the link already filled in.
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

## Google Home setup

Controls any light or plug that works in the Google Home app, including ones linked from other brands' apps ("cloud-to-cloud"); no Nest hub needed for those. It uses Google's **Home APIs**, which have two hurdles: the SDK isn't on a public Maven repo (you download it after signing in), and Google only lets the app talk to your home after an OAuth setup in Google Cloud. Without the SDK the app builds and works normally, and the Google Home tab just explains what's missing.

Do this once, on the machine you build on:

1. **Download the SDK.** Sign in at <https://developers.home.google.com/apis/android/sdk> and download the Android SDK. Unzip it so you have a Maven folder tree `com/google/android/gms/play-services-home/<version>/…` and put that tree either in the project as `home-sdk/` (git-ignored) or in `~/.m2/repository/` (on Windows `%USERPROFILE%\.m2\repository\`).
2. **Turn it on.** In `gradle.properties` uncomment `homeSdk=` and set it to the `<version>` folder name (e.g. `homeSdk=17.1.0`). Sync/rebuild. If the build then fails with a Kotlin "incompatible metadata" error, raise `kotlin` in `gradle/libs.versions.toml` to the version Google's sample app uses.
3. **Google Cloud project + OAuth consent screen.** In <https://console.cloud.google.com>, create a project → *APIs & Services* → *OAuth consent screen*: user type **External**, leave it in **Testing**, no scopes needed, and add your own Google account under **Test users**.
4. **Android OAuth client(s).** *APIs & Services* → *Credentials* → *Create credentials* → *OAuth client ID* → **Android**, package name `io.github.rmdodhia.gesturelauncher`, and the SHA-1 of the key that signs the APK. Each machine has its own debug key, so create one client per machine you install from:
   - WSL build (this repo's `~/.android/debug.keystore`): `AA:BB:CC:DD:EE:FF:00:11:22:33:44:55:66:77:88:99:AA:BB:CC:DD`
   - Android Studio on Windows: run `gradlew signingReport` (or `keytool -list -v -keystore %USERPROFILE%\.android\debug.keystore -alias androiddebugkey -storepass android`) and use its SHA1.
   
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

- The Kindle `kindle://` link is undocumented. If it stops working, the app falls back to opening Kindle. Libby links may only open your shelf, not the specific book. Test each with **Test**.
- Samsung/One UI system gestures (edge swipes, Edge panel handle, navigation-bar swipes) can take touches that start at the very edge of the screen. The drawing area is inset from those edges for that reason.
- On synthetic test data, about 13% of random scribbles are accepted as some gesture at the default strictness. Wrong-gesture matches were 0 of 640. Raise strictness if that bothers you.

## Project layout

```
app/src/main/java/io/github/rmdodhia/gesturelauncher/
  core/   Pure Kotlin: model, feature extraction, $P recognizer, link parsing (unit-tested on the JVM)
  data/   JSON store (atomic writes), error/crash log
  home/   HomeGateway interface (the app's only view of Google Home)
  ui/     Compose screens: draw canvas, gesture list, editor, action picker
  ActionRunner.kt, MainActivity.kt, GestureApp.kt, DrawTileService.kt
app/src/home/    Real Google Home gateway (compiled only when homeSdk is set)
app/src/nohome/  Stub used when the SDK isn't installed
app/src/test/  JVM unit tests + Robolectric end-to-end UI tests (record → save → draw → action)
```
