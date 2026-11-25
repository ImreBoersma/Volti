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

- [ ] Launch the picker with `ActivityResultContracts.OpenDocumentTree`.
- [ ] Persist access with `takePersistableUriPermission`, read-only.
- [ ] Pass `EXTRA_LOCAL_ONLY` so cloud-backed folders are excluded.
- [ ] Store the tree URI in `DataStore` (Preferences).

Done when: pick the folder once, kill the app, reopen, access is still there.

### 2. List the PDFs

- [ ] Enumerate with `DocumentFile.fromTreeUri`, off the main thread.
- [ ] Filter on MIME type `application/pdf`, not on file extension.
- [ ] Flat list only. Subfolders come in milestone 2.

Done when: the library shows file names, and a folder with 200 files does not freeze the UI.

### 3. Pass a document to the reader

- [ ] Replace the string routes with type-safe routes (`@Serializable` route classes).
- [ ] The reader route carries the document URI.

Done when: tapping a title opens the reader with that file's URI available.

### 4. Render page one

- [ ] Open the file with `ContentResolver.openFileDescriptor`.
- [ ] Hand the `ParcelFileDescriptor` to `PdfRenderer`.
- [ ] Render into a bitmap sized to the reader's pixel dimensions.
- [ ] Own the renderer in a `ViewModel`. Close page, renderer and descriptor in `onCleared`.

Decisions:

- Threading: `PdfRenderer` allows one open page at a time and is not thread-safe. Use a single-threaded dispatcher (`limitedParallelism`).
- Bitmap size: must be `ARGB_8888`. A 2560 x 1600 page is about 16 MB. Render at view size, never larger.
- Background: fill the bitmap white first. PDF pages are transparent by default.

Done when: page one is sharp, has the correct aspect ratio, and rotation does not reopen the file.

### 5. Page turns

- [ ] Tap zones on the left and right halves (`pointerInput`, `detectTapGestures`).
- [ ] Keep three bitmaps: previous, current, next.
- [ ] After each turn, render the new neighbour in the background and reuse the dropped bitmap.

Done when: turning through a 20-page scan never shows a blank frame.

### 6. Stage mode

- [ ] Keep the screen on in the reader only (`FLAG_KEEP_SCREEN_ON`, set and cleared in a `DisposableEffect`).
- [ ] Hide the system bars with `WindowInsetsControllerCompat`.

Done when: the screen stays on in the reader and dims normally in the library.

### 7. Hostile and broken input

- [ ] Password-protected PDF (`SecurityException`).
- [ ] Corrupt or non-PDF file with a `.pdf` name (`IOException`).
- [ ] Absurd page dimensions: cap the bitmap size.
- [ ] Folder deleted or permission revoked: check `persistedUriPermissions` at start, fall back to the picker.

Done when: each case shows a message and none crashes.

### 8. Release check

- [ ] Turn on `isMinifyEnabled` for the release build type.
- [ ] Tidy the keep rules: the build file references a `proguard-rules.pro` that does not exist, the rules live in `keepRules`.
- [ ] Time cold start and page turns on the tablet, release build.
- [ ] Use it at one rehearsal. Write down what annoyed you.

## To verify

- On Android 15 and up, `PdfRenderer` is believed to be updated through Google Play system updates. Confirm, and check whether the tablet still receives security patches.
- The Jetpack `androidx.pdf` library: read its current state before step 4 and decide whether it replaces `PdfRenderer`.

## Later milestones (not planned yet)

- Library: one folder per piece, subfolders, Room index, search.
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

I write every line. After each step: commit, then a review that names the three most serious issues, without rewrites.
