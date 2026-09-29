# Gesture Launcher

Draw a shape, tap with several fingers, swipe, or tap a rhythm on a full-screen canvas, and the phone opens an app or a specific book. You record your own gestures.

See [PLAN.md](PLAN.md) for the design and roadmap. Google Home control is deferred to v2.

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
  ui/     Compose screens: draw canvas, gesture list, editor, action picker
  ActionRunner.kt, MainActivity.kt, GestureApp.kt, DrawTileService.kt
app/src/test/  JVM unit tests + Robolectric end-to-end UI tests (record → save → draw → action)
```
