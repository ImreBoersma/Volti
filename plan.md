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

- [ ] Keep the screen on in the reader only (`FLAG_KEEP_SCREEN_ON`, set and cleared in a `DisposableEffect`).
- [ ] Hide the system bars with `WindowInsetsControllerCompat`.

Done when: the screen stays on in the reader and dims normally in the library.

### 7. Hostile and broken input

- [ ] Password-protected PDF (`SecurityException`).
- [ ] Corrupt or non-PDF file with a `.pdf` name (`IOException`).
- [ ] Absurd page dimensions: cap the bitmap size. (Done in step 4: the bitmap never exceeds the view.)
- [ ] A PDF with zero pages, or a page of 0 x 0.
- [ ] One broken page in a good score: shows "Can't show page N.", the other pages stay readable. (Built in step 5, tested with a fake; not yet seen with a real corrupt page.)
- [ ] A provider that hands out a non-seekable descriptor (a pipe): `IllegalArgumentException`.
- [ ] Folder deleted or permission revoked: check `persistedUriPermissions` at start, fall back to the picker.

Done when: each case shows a message and none crashes.

### 8. Release check

- [ ] Turn on `isMinifyEnabled` for the release build type.
- [ ] Tidy the keep rules: the build file references a `proguard-rules.pro` that does not exist, the rules live in `keepRules`.
- [ ] Time cold start and page turns on the tablet, release build.
- [ ] Use it at one rehearsal. Write down what annoyed you.

## To verify

- ~~On Android 15 and up, `PdfRenderer` is believed to be updated through Google Play system updates.~~ Confirmed 2026-11-25: the tablet's firmware patch is 2025-06-01 (no longer patched), but the MediaProvider module with `PdfRendererPreV` and its own PDFium is on the 2026 Play train. Hence `PdfRendererPreV` on Android 14.
- ~~The Jetpack `androidx.pdf` library.~~ Decided: not used. It is a viewer on top of the same module, so no security gain, and its UI does not fit three-bitmap page turns.

## Later milestones (not planned yet)

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
