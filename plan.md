# Volti: plan

A sheet music reader for Android tablets. Offline, fast, low battery use.

## Priorities

**Security.** PDFs are untrusted files parsed by native code. That is the one real attack surface. Everything else is about keeping the app's reach small:

- No network permission.
- No storage permission. Access only to the one folder granted through the system picker.
- The platform `PdfRenderer`, not a bundled PDF parser that needs patching by hand.
- No backup of app data.

**Speed.** Two targets, measured on a release build on the tablet:

- A page turn shows a bitmap that is already rendered. No blank frame.
- Cold start to the library in under one second.

**Battery.** A page that is not changing costs zero CPU. No animations, no background work, no sync.

## Milestone 1: a reader for one folder

No database, no setlists, no annotations. Done when it survives one rehearsal on a stand.

### 0. Baseline

- [x] `git init` and a first commit.
- [x] Set `android:allowBackup` to false in the manifest.
- [x] Update the dependency versions in the version catalog (Compose BOM, lifecycle, navigation are from early 2025).
- [x] Run the empty app on the tablet over USB.
- [x] Note the tablet's Android version and security patch level here: Galaxy Tab S7 FE (SM-T733), Android 14 / API 34, security patch 2025-06-01, Play system update 2026 (MediaProvider 372042780).

Done when: it launches on the tablet and the Merged Manifest tab shows no permissions.

### 1. Folder grant

- [x] Launch the picker with `ActivityResultContracts.OpenDocumentTree`.
- [x] Persist access with `takePersistableUriPermission`, read-only.
- [x] Pass `EXTRA_LOCAL_ONLY` so cloud-backed folders are excluded.
- [x] Store the tree URI in `DataStore` (Preferences).

Done when: pick the folder once, kill the app, reopen, access is still there.

This stays a manual check on the tablet. A test for it needs a real grant from the system picker; the instrumented tests cover the failure paths only.

### 2. List the PDFs

- [x] Enumerate with one `DocumentsContract` children query, off the main thread. Not `DocumentFile`: its `listFiles()` costs an extra provider query per `getName()` and `getType()`, about 400 for 200 files.
- [x] Filter on MIME type `application/pdf`, not on file extension. Note: the local storage provider derives the MIME type from the extension, so a text file named `.pdf` is listed and a PDF without extension is not. Opening it is where the real check happens (step 7).
- [x] Flat list only. Subfolders come in milestone 2.

Done when: the library shows file names, and a folder with 200 files does not freeze the UI.

### 3. Pass a document to the reader

- [x] Replace the string routes with type-safe routes (`@Serializable` route classes).
- [x] The reader route carries the document URI.

Done when: tapping a title opens the reader with that file's URI available.

### 4. Render page one

- [x] Open the file with `ContentResolver.openFileDescriptor`.
- [x] Hand the `ParcelFileDescriptor` to `PdfRendererPreV` on Android 14 (Play-updated, in the MediaProvider module), `PdfRenderer` on 15+. Falls back to `PdfRenderer` if the SDK extension is missing.
- [x] Render into a bitmap sized to the reader's pixel dimensions.
- [x] Own the renderer in a `ViewModel`. Pages close right after rendering; renderer and descriptor close in `onCleared`, on the render thread.

Decisions:

- Threading: `PdfRenderer` allows one open page at a time and is not thread-safe. Use a single-threaded dispatcher (`limitedParallelism`).
- Bitmap size: must be `ARGB_8888`. A 2560 x 1600 page is about 16 MB. Render at view size, never larger.
- Background: fill the bitmap white first. PDF pages are transparent by default.

Done when: page one is sharp, has the correct aspect ratio, and rotation does not reopen the file.

### 5. Page turns

- [x] Tap zones on the left and right halves (`pointerInput`, `detectTapGestures`).
- [x] Keep three bitmaps: previous, current, next.
- [x] After each turn, render the new neighbour in the background and reuse the dropped bitmap.

Measured 2026-11-25, release build, 20-page 200 dpi scan: a page renders in about 100 ms. Turning at a normal pace, the next page is ready 1 ms after the tap. In a burst of taps about 100 ms apart, every page was still ready; the tap waits up to 43 ms behind the prefetch.

Done when: turning through a 20-page scan never shows a blank frame.

### 6. Stage mode

- [x] Keep the screen on in the reader only (`FLAG_KEEP_SCREEN_ON`, set and cleared in a `DisposableEffect`).
- [x] Hide the system bars with `WindowInsetsControllerCompat`. A swipe from the edge shows them briefly.

Checked 2026-11-25 with a 15 s screen timeout: still awake after 25 s in the reader, dozing 25 s after returning to the library. Stage mode follows the current destination, so reopening a score during the 700 ms crossfade keeps it on. A file that can't be opened does not keep the screen on. In Samsung split screen the system places the window below the status bar, so the page is not covered.

Done when: the screen stays on in the reader and dims normally in the library.

### 7. Hostile and broken input

Test files are in `app/src/androidTest/assets/hostile/`, made by `make_hostile_pdfs.py`. `HostileInputTest` opens each through the real ViewModel and renderer; each was also opened from the library on the tablet on 2026-11-25, without a crash.

- [x] Password-protected PDF: "This PDF is password-protected." The renderer's `SecurityException` becomes `PasswordProtectedException`, so a lost folder grant (also a `SecurityException`) is not mistaken for a password.
- [x] Corrupt, truncated or non-PDF file with a `.pdf` name: "Can't open this file."
- [x] Absurd page dimensions: the bitmap never exceeds the view. A 1,000,000,000 x 1 page renders as a 1600 x 1 strip: no crash, but effectively blank.
- [x] A PDF with zero pages: "Can't open this file." A 0 x 0 page: PDFium substitutes a default page size and renders it.
- [x] One broken page in a good score: shows "Can't show page N.", the other pages stay readable. Proven only with a fake document: with the real renderer a broken content stream does not throw, PDFium draws what it can, so the message is never reached.
- [x] A provider that hands out a non-seekable descriptor (a pipe): any exception from opening gives "Can't open this file." Tested with a fake; no such provider on the tablet.
- [x] Folder deleted: "Can't read this folder." (step 2). Permission revoked: the stored folder is ignored and the library asks for a folder again (step 1, `FolderStoreTest`).
- [x] File deleted after the library listed it: "Can't open this file."
- [x] Listing the folder throws anything (any provider, not just a lost grant): "Can't read this folder." Before this, an unexpected exception crashed on every start, because the folder is stored.
- [x] A file that hangs the renderer: after 10 s without a first page, "This file takes too long to open." and the screen may sleep again. Tested with a fake. Tried on the tablet: a self-drawing form renders in 48 ms (PDFium detects it), a 20000 x 20000 compressed image in 1.2 s. No real PDF hung it.

Limit, accepted for milestone 1: PDFium runs inside the app's process. A native crash or a decompression bomb kills the app, no catch can stop that, and an exploit would run with the app's folder grant. Shown on the tablet on 2026-11-25: `form-bomb.pdf` (6.7 KB, 40 nested forms that each draw the next twice) makes PDFium abort within seconds, a native crash. The app restarts in the library, so there is no crash loop, but on stage the app is gone. A hanging file also holds the renderer's process-wide lock, so other scores stay blank until the app restarts. The fix is the isolated renderer below, first after milestone 1. Acceptable for now: the files are my own, and the app has no network permission.

Done when: each case shows a message and none crashes.

### 8. Release check

- [x] Turn on `isMinifyEnabled` for the release build type. Resource shrinking too: the APK goes from 27.7 MB to 2.2 MB. No keep rules were needed; the full flow works in the minified build.
- [x] Tidy the keep rules: the `proguard-rules.pro` reference is gone. `src/main/keepRules/rules.keep` has `-dontobfuscate`, so a stack trace from the stand is readable without that build's mapping file, and strips `Log.d`/`Log.i`/`Log.v` from release (no file URIs or page turns in logcat; `Log.w` stays).
- [x] No backup at all: `data_extraction_rules.xml` excludes everything from cloud backup and from device-to-device transfer. `allowBackup="false"` alone does not stop the latter on Android 12+.
- [x] Process death in the release build: app killed in the background and reopened comes back in the reader, no crash. It restarts at page 1; the page is not saved.
- [x] Time cold start and page turns on the tablet, release build. 2026-11-25, minified: first frame 189-262 ms, library with its list of 205 files 361-474 ms ("Fully drawn", via `ReportDrawnWhen`). Page turns on the 20-page scan: 2 ms after the tap at reading pace, at most 50 ms in a burst.
- [ ] Use it at one rehearsal. Write down what annoyed you.

The tablet's release build is signed with the debug key (`-Pandroid.injected.signing.*`, see below). Good enough for one tablet; a real release key is needed before anyone else installs it. Changing the key later means uninstalling, which drops the folder grant.

Install the rehearsal build:

```
./gradlew :app:installRelease \
  -Pandroid.injected.signing.store.file=$HOME/.android/debug.keystore \
  -Pandroid.injected.signing.store.password=android \
  -Pandroid.injected.signing.key.alias=androiddebugkey \
  -Pandroid.injected.signing.key.password=android
```

## To verify

- ~~On Android 15 and up, `PdfRenderer` is believed to be updated through Google Play system updates.~~ Confirmed 2026-11-25: the tablet's firmware patch is 2025-06-01 (no longer patched), but the MediaProvider module with `PdfRendererPreV` and its own PDFium is on the 2026 Play train. Hence `PdfRendererPreV` on Android 14.
- ~~The Jetpack `androidx.pdf` library.~~ Decided: not used. It is a viewer on top of the same module, so no security gain, and its UI does not fit three-bitmap page turns.

## Milestone 2, first step: isolated renderer

- [ ] Render in a service with `android:isolatedProcess="true"`: no permissions, no folder grant, only the file descriptor it is handed.
- [ ] Bitmaps back over `SharedMemory`; the service reports page count and errors over AIDL.
- [ ] A native crash or hang kills only the service: show the message, restart it for the next score.

Done when: a PDF that crashes or hangs PDFium leaves the app running and other scores readable. Test case: `form-bomb.pdf` from `make_hostile_pdfs.py`.

## Later milestones (not planned yet)

- Run the instrumented tests against a minified build type, so R8 problems show up in tests instead of on the stand.
- Remember the current page across process death and reopening a score.
- Library: one folder per piece, subfolders, Room index, search, refresh after files change.
- Setlists as references to pieces.
- Play order and jump markers, stored alongside the PDF.
- Import: share and open-with target for PDFs.
- Annotations as a separate layer.
- Margin cropping, half-page turns, system-by-system view.
- MusicXML as a second document type.

## Out of scope

- IMSLP catalogue browsing.
- On-device optical music recognition.

## Way of working

Per step:

1. Claude writes the code and checks the step's "Done when" on the tablet.
2. Claude writes tests for the step's logic and runs them on the tablet. Unit tests for pure logic, instrumented tests for anything touching Android (DataStore, ContentResolver, `PdfRenderer`). No tests that drive the system folder picker.
3. A separate agent reviews the change and names the three most serious issues, without rewrites. It also checks that the tests cover the step's "Done when".
4. The issues go to me. I decide which get fixed.
5. Claude fixes the chosen issues.
6. Commit and push to `main`.
