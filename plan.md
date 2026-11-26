# Volti: plan

A sheet music reader for Android tablets. Offline, fast, low battery use.

## Priorities

**Security.** PDFs are untrusted files parsed by native code. That is the one real attack surface. Everything else is about keeping the app's reach small:

- No network permission. Changed 2026-11-25 for milestone 3 (scores from Google Drive): network only to Google's Drive endpoints, only to download, only while the app is open. The reader never needs the network: scores are always read from the device.
- No storage permission. Access only to the one folder granted through the system picker.
- The platform `PdfRenderer`, not a bundled PDF parser that needs patching by hand.
- No backup of app data. Annotations will be the exception (milestone 4): they are the one thing on the tablet that can't be downloaded again.

**Speed.** Two targets, measured on a release build on the tablet:

- A page turn shows a bitmap that is already rendered. No blank frame.
- Cold start to the library in under one second.

**Battery.** A page that is not changing costs zero CPU. No animations, no background work. Milestone 3's sync runs only when the library is opened or pulled, never in the background.

## Milestone 1: a reader for one folder

Closed 2026-11-25. Everything below is built and checked on the tablet; the rehearsal moved to milestone 2.

No database, no setlists, no annotations. Done when it survives one rehearsal on a stand.

### 0. Baseline

- [x] `git init` and a first commit.
- [x] Set `android:allowBackup` to false in the manifest.
- [x] Update the dependency versions in the version catalogue (Compose BOM, lifecycle, navigation are from early 2025).
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

A dropped bitmap is only drawn into once the screen is done with it: the reader reports each page it records (`onDrawn`) and each frame the render thread commits (`onPresented`, via `registerFrameCommitCallback`), and a bitmap is reused at least 50 ms after a newer frame replaced it on screen. Counting from the state alone failed on the CI emulator, where a busy main thread kept an old page on screen.

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

Limit, accepted for milestone 1, fixed in milestone 2 step 1: PDFium runs inside the app's process. A native crash or a decompression bomb kills the app, no catch can stop that, and an exploit would run with the app's folder grant. Shown on the tablet on 2026-11-25: `form-bomb.pdf` (6.7 KB, 40 nested forms that each draw the next twice) makes PDFium abort within seconds, a native crash. The app restarts in the library, so there is no crash loop, but on stage the app is gone. A hanging file also holds the renderer's process-wide lock, so other scores stay blank until the app restarts. The fix is the isolated renderer below, first after milestone 1. Acceptable for now: the files are my own, and the app has no network permission.

Done when: each case shows a message and none crashes.

### 8. Release check

- [x] Turn on `isMinifyEnabled` for the release build type. Resource shrinking too: the APK goes from 27.7 MB to 2.2 MB. No keep rules were needed; the full flow works in the minified build.
- [x] Tidy the keep rules: the `proguard-rules.pro` reference is gone. `src/main/keepRules/rules.keep` has `-dontobfuscate`, so a stack trace from the stand is readable without that build's mapping file, and strips `Log.d`/`Log.i`/`Log.v` from release (no file URIs or page turns in logcat; `Log.w` stays).
- [x] No backup at all: `data_extraction_rules.xml` excludes everything from cloud backup and from device-to-device transfer. `allowBackup="false"` alone does not stop the latter on Android 12+.
- [x] Process death in the release build: app killed in the background and reopened comes back in the reader, no crash. It restarts at page 1; the page is not saved.
- [x] Time cold start and page turns on the tablet, release build. 2026-11-25, minified: first frame 189-262 ms, library with its list of 205 files 361-474 ms ("Fully drawn", via `ReportDrawnWhen`). Page turns on the 20-page scan: 2 ms after the tap at reading pace, at most 50 ms in a burst.
- [ ] ~~Use it at one rehearsal.~~ Moved to milestone 2, step 0.

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

## Milestone 2: a better library and reader

Improvements to the two things the app does: finding a score and reading it. Same priorities, same way of working. New kinds of features (setlists, annotations, import) wait for a later milestone.

### 0. Rehearsal

- [ ] Use the milestone 1 release build at one rehearsal. Write down what annoyed you; it may reorder the steps below.

### 1. Isolated renderer

PDFium in the app's process is the one accepted security limit of milestone 1: `form-bomb.pdf` (6.7 KB) crashes the app.

- [x] Render in a service with `android:isolatedProcess="true"`: no permissions, no folder grant, only the file descriptor it is handed. On the tablet it runs as an isolated user (`u0_i…`), the app as `u0_a…`.
- [x] Bitmaps back over `SharedMemory`; the service reports page count and errors over AIDL (`IPdfRenderer.aidl`). Binder only carries a few exception types, so a password comes back as `SecurityException` and anything else as `IllegalStateException`.
- [x] A native crash or hang kills only the service: show the message, restart it for the next score. A watchdog in the service kills its own process after 10 s in one call (`Watchdog`, unit-tested). After a crash the app unbinds and binds again: the system's own restart was delayed 19 s by the memory pressure the crash left. The decision to rebind is taken on the dead binder, not on `onServiceDisconnected`, which arrives later.
- [x] An open score survives its renderer dying: if the renderer is already dead before a call (an earlier page crashed it, or Android reclaimed the idle process), the document is reopened in a fresh one first. The page that kills the renderer during its own call is marked broken and not tried again, so a bad page costs one crash, not two.
- [x] The app does not trust the renderer's answers: a size that doesn't fit the view or the shared memory is rejected before anything is allocated (`checkRenderedSize`, unit-tested). A compromised renderer can't make the app allocate itself to death.

Done when: a PDF that crashes or hangs PDFium leaves the app running and other scores readable. Test case: `form-bomb.pdf` from `make_hostile_pdfs.py`.

Checked 2026-11-25: `form-bomb.pdf` shows "Can't show page 1." (it opens; rendering aborts PDFium), the app keeps its process, and the next score opens at once, also twice in a row and in the release build. `bomb-on-page-2.pdf`: page 2 shows "Can't show page 2.", pages 1 and 3 render. `HostileInputTest` covers both. A turn at reading pace still takes 2-3 ms; in a burst up to 82 ms (was 50), from the extra copy through shared memory. The watchdog itself is unit-tested, but not on a real hang: no PDF that hangs PDFium could be made.

Memory limit, added after CI: `form-bomb.pdf` grows the renderer by about 500 MB a second, past 1 GB of the tablet's 3.4 GB before PDFium aborts; on the CI emulator that took the whole system down ("INSTRUMENTATION_ABORTED: System has crashed"). The watchdog now also reads the renderer's resident memory (`/proc/self/statm`) every 50 ms during a call and stops it when one call grows it by more than 300 MB. Growth, not the total: the long-lived renderer creeps up between calls, and a normal page on top of that must not trip it. Measured peaks on the tablet (debug builds log them; release doesn't read them at all): a normal score about 120 MB, the 20000 x 20000 image 137 MB; `form-bomb.pdf` is stopped after growing 324 MB and shows "Can't show page 1.", without a native crash. A fast runaway overshoots by what it touches in 50 ms. CI's emulator now gets 4 GB of RAM (`ram-size`); the default left too little headroom.

### 4. Library: subfolders and names

- [x] One folder per piece: subfolders show as entries (folders first, in bold with a trailing slash), tapping one opens it, back goes up. Still one query per folder. The path shows under the title.
- [x] Show the folder's real name, not `primary:Documents/...`: one extra query for the granted folder's display name; subfolder names come with their parent's listing.
- [x] Natural sort: "Mars 2" before "Mars 10". Digit runs compare by value without parsing (no overflow), text by the locale's collator.

Checked 2026-11-25 on the tablet with a tree of pieces and parts. `GrantedTreeTest` lists a subfolder through the real provider and checks that the parent, the storage root, `Download`, a sibling whose name starts with the granted folder's (`Documents/VoltiTest2`, which exists on the tablet) and `..` paths are all refused ("is not a descendant"). It uses the debug build's own grant; on CI it skips, on the tablet it runs with `-Pandroid.testInstrumentationRunnerArguments.requireGrant=true` and fails without a grant.

The open subfolder is kept in `SavedStateHandle`, tied to the granted folder it belongs to, so it survives process death and can't be applied to a different folder after "Change folder". Back only goes up a folder while the library is the resumed screen. Natural order treats only ASCII digits as numbers, so names with fullwidth or Arabic-Indic digits can't make the order inconsistent; a fuzz test checks every triple with Dutch collation, on the JVM and on the device.

Done when: a folder tree of pieces with parts inside is browsable, and nothing outside the granted folder is reachable.

### 5. Library: refresh and search

- [x] Pull to refresh, and a refresh when the library comes back to the foreground (from another app or from the reader; not on first start, which lists anyway). The library flow no longer restarts after 5 s in the background: the explicit refresh replaces that.
- [x] Search by name within the current folder and its subfolders. The folder is walked once (one query per folder, at most 5,000 entries) when the search field gets focus; each letter then filters in memory. Case and accents don't matter, and every word must appear in the name or a folder on the way ("Bach viool"). Results show their path; a found folder opens in place. Back clears the search first.

Checked 2026-11-25 on the tablet: a file copied in while the app is open shows up after going home and back, another after a pull, and one copied in while a score is open shows up after back (the "was stopped" flag lives in the ViewModel: navigation drops the screen's own state behind the reader). Indexing 230 entries takes about 320 ms (debug build), started on focus, so typing doesn't wait. Only a pull walks the index again; coming back keeps it. A newer folder, query or pull cancels a walk in progress. Each name is matched on its own, so nothing matches the separator. Past 5,000 entries the results say the deepest files are left out. Results stay above the keyboard.

Done when: a file copied into the folder shows up without restarting, and a search across 200 files answers while typing.

### 6. Reader: fit more music on the stand

- [x] Crop white margins. Detected on a 400 px render of a page: content where pixels are at least 30 levels darker than the paper (the page's 90th percentile brightness, so thin staff lines shrunk to light grey still count and off-white scan paper doesn't), rows and columns with too few dark pixels ignored as dust, at most 15% cut from a side. One crop for the whole score, the union of up to six pages spread over it, so the music keeps its size and place from page to page and pages keep one size (their bitmaps can be reused). Each sampled page is detected in its own renderer call: a page that crashes the renderer is skipped and marked broken, not the score. Areas crossing the process boundary are checked (`checkArea`). Costs about 330 ms when opening a 20-page scan, once. On by default; "Crop margins" in the library.
- [x] Half-page turns: a forward tap first shows the next page's top half over the top of the screen, with a line under it, while the bottom half still shows the end of this page; the next tap shows the next page whole. Both halves come from pages already rendered; with half-page turns the window holds four pages. Off by default; "Half-page turns" in the library.

Checked 2026-11-25 on the tablet: an A4 scan with wide margins went from 32% to 40% of the screen width and 67% to 75% of the height (staff-line span), with all staves visible; half and whole turns through the 20-page scan all came from memory in 1-4 ms, forwards and back.

Fixed after review: per-page crops made the music jump in size between pages and stopped bitmap reuse; detecting the score's sample inside one render call let `bomb-on-page-2.pdf` take page 1 down with it; a broken page's message in a half turn was cut by the line (now centred in its own half). Tests cover cropping through the isolated renderer and on a page with /Rotate 90.

Found on the way: the first detection used a fixed darkness threshold and missed 1-px staff lines shrunk for detection, so the page showed uncropped; and a frame without a page (loading, an error) reported as presented late released the pages drawn after it, which `aBusyRenderThreadNeverGetsItsPageDrawnOver` caught in about 3 of 10 runs. Both fixed, each with a test that fails without the fix.

Done when: an A4 scan with wide margins fills the screen noticeably more, and a half-page turn never shows a blank frame.

### 7. Tests against the release build

- [x] Run the instrumented tests against a minified build type, so R8 problems show up in tests instead of on the stand. The `minified` build type is debug with R8 as in release (`testBuildType`); run `./gradlew connectedMinifiedAndroidTest`, on the tablet with `-Pandroid.testInstrumentationRunnerArguments.requireGrant=true`. CI runs it too.

How close it is to release: `minified` is not debuggable, because R8 only optimises (merges, inlines, strips `Log.d`) when a build isn't; R8's metadata reads `isOptimizationsEnabled: true` as in release. 128 of our classes are left after R8, 112 in release (227 while it was still debuggable). The difference: the test APK calls the app's copies of shared libraries and some code only tests use, so the minified build keeps exactly those classes and members, nothing more: `src/minified/keepRules/test-references.keep`, generated from the test APK by `scripts/trace-test-references.sh` (rerun it when a test starts using something new; the minified run then fails with `NoSuchMethodError`). Supertypes the test code implements are kept whole. Library internals the app relies on (reflective ViewModel creation, DataStore's protobuf, navigation's serializer lookup) are not kept by these rules unless a test names them, so their own keep rules are tested too. For a quick local run against debug: `-Pvolti.testBuildType=debug`.

### 8. Reader: a back button

- [x] A small button in the top-left corner of the reader that goes back to the library: 48dp, an arrow in the reader's text colour on a disc of the paper, with a ring. It shows for 3 s when a score opens and then hides, because on a cropped score the clef and key signature sit in that corner; a tap in the corner brings it back. The corner takes its own taps, so it never turns the page back. Back only while the reader is the resumed screen: a second tap during the crossfade would also have popped the library and left a blank screen.
- [x] Back by gesture keeps working. `ReaderBackButtonTest` covers the button, a double tap, a tap on the left beside it still turning back, and the hiding; the reader tells accessibility services which page shows ("Page 2 of 3").

Done when: a score can be left without the back gesture, and a tap anywhere else on the left still turns back.

### 9. Reader: the page's own background colour

- [x] Around the page, the colour of the score's paper instead of white or black: measured from the same small render that finds the margins (the average of the pixels at its 90th percentile brightness), the median of the sampled pages, per score. The sample is now looked at with crop margins off too.
- [x] In both themes: a cream or yellowed scan then fills the screen without a frame. Text, the half-turn line and the back button take ink or cream, whichever contrasts more with that paper: at least 4.5:1 on real paper colours, at least 3:1 even on the mid-grey where neither does well. With crop margins off only the first page is looked at (about 55 ms); with it on, the six-page sample the crop needs (about 330 ms).

Checked 2026-11-25 on the tablet: a yellowed scan shows no edge between page and surround, in the light and the dark theme.

Done when: a scan on off-white paper shows no visible edge between page and surround.

## Milestone 3: ensembles from Google Drive

Decided 2026-11-25: instead of a separate sync app mirroring Drive into the local folder, Volti itself pulls a Drive folder. This changes the priorities above: the app gets network access, so the reach is kept as small as it can be.

Decided 2026-11-26, after going through how I work now (download from Drive, import into the IMSLP app, rename, put in a setlist): Volti should replace the IMSLP app, which I use for its annotations, setlists and simple interface. The Drive folders are shared with me by the orchestras, one per orchestra, each with a different instrument of mine. That makes an ensemble the top level of the library:

- An ensemble is a shared Drive folder plus what I play in it ("Harmonie, Bb clarinet 2").
- The folders are not mine: Volti never renames, moves or writes there. Read-only, also because others keep changing them.
- A folder usually holds every part, but not in one layout: sometimes all files side by side, sometimes a subfolder per instrument. Names follow whoever uploaded them. So my parts are marked by me, not detected; matching my instrument against names and folders only pre-selects.
- Everything Volti adds (my parts, names, annotations, setlists) hangs off the Drive file id, which survives renames and new versions.

Rules for the network:

- Network only to Google's Drive API, only HTTPS. Until milestone 4, only downloads.
- Downloaded PDFs are as untrusted as local ones: they render in the isolated renderer, with the same limits.
- Sync only when the library opens or is pulled. No background work, no sync during reading.
- Scores live in the app's own storage, so the library and reader work offline on stage. What was synced last is always there.
- A new version of a PDF never replaces one silently: not while it is open, and never without saying so (see milestone 4 for annotated scores).

### 0. Decisions before building

- [x] Private or public folder: private (2026-11-25). Google sign-in with the `drive.readonly` scope, through Google Play services' Authorization API, which hands the app an access token; Volti stores no password or token itself.
- [x] Drive only, or Drive next to the local folder: next to it (2026-11-25). If the sync lets you down, a PDF can still go on the tablet directly.
- [x] Library top level: ensembles (2026-11-26).
- [x] No weekly sign-in (2026-11-26): in Testing, Google ends a sign-in after 7 days. Instead the app is published ("In production") without verification, which Google allows for personal use (under 100 users), restricted scope included. The cost: a one-time "Google hasn't verified this app" screen at the first sign-in.

Setup in Google Cloud, once, by me (Volti only needs the result):

1. console.cloud.google.com: a new project, e.g. "Volti".
2. APIs & Services, Library: enable the Google Drive API.
3. Google Auth Platform (OAuth consent screen): External, app name Volti, my e-mail. Under Audience: Publish app, so sign-ins don't run out after 7 days (see above). Done 2026-11-26, project `volti-515911`.
4. Data access: add the scope `.../auth/drive.readonly`.
5. Clients: create an OAuth client of type Android, twice: package `nl.imreboersma.volti` and `nl.imreboersma.volti.debug`, both with the debug key's SHA-1 `78:BA:25:D0:59:1A:A0:DB:42:A3:E7:2E:C8:80:55:F1:CC:30:EC:19`. A release key later needs a third client.

No client secret or API key goes into the app: an Android OAuth client is matched by package name and signing key.

### 1. Sign-in and listing

- [x] Add an ensemble: paste a shared folder's link, give it a name and my instrument. List its PDFs and subfolders, recursively, with their checksums.

Done 2026-11-26. The ensembles are in Room already (pulled forward from step 2, so nothing added now is lost on restart); an Ensembles button in the library leads to them until step 3. Two read-only HTTPS calls, no Google client library; Play services holds the grant, Volti only a short-lived token in memory. Ids from Drive's answers are checked before they go into the next query. Checked on the emulator with Harmonieorkest Brummen's horn folder: 32 PDFs listed. Open: with several Google accounts on the tablet, does Volti ask which one?

The names in that folder show why my parts can't be detected: "F-hoorn 1", "French Horn 1 in F", "hoorn13" (horns 1 and 3), "F-hoorn 1-2", "Hoorn in F", and a euphonium part in the horn folder.

### 2. The local copy

- [x] Files in the database, keyed by Drive file id (name, folder, checksum, version, local file). Milestones 4 and 5 build on it.
- [x] Download new and changed PDFs into the app's private storage. A changed PDF keeps its previous version until I have seen the new one. Removed in Drive: marked as removed, kept while anything of mine refers to it.
- [x] Size limits and checks on what comes in.

Done 2026-11-26. PDFs are stored by checksum (`files/drive/<md5>.pdf`), so a version is its md5, an old and a new version are two files, and one PDF in two ensembles is stored once. Per file the database keeps the version in Drive, the newest on the tablet and the one I last opened; opening a score records it, and it stays until I open a newer one ("Changed" until then), also while a sync brings that newer one in. Gone from Drive: dropped, unless I opened it, then kept as "Removed in Drive". A listing stopped at 5,000 entries drops nothing it didn't reach. A download must be at most 200 MB, start like a PDF and match Drive's md5, and leaves 500 MB free; one that fails is tried again next sync. The sync runs only while the ensemble shows: it stops for the reader or another app and picks up on return.

Checked on the emulator: 32 PDFs (16 MB) downloaded, a score opened with airplane mode on. After clearing the app's data, Play services asked which Google account to use: so with several accounts it asks, it doesn't pick one.

### 3. The library from the copy

- [x] The library opens on the ensembles, next to the local folder. Inside an ensemble: the folders as in Drive, with the same names, sort and search. The reader opens the local file.

Done 2026-11-26. The app opens on a home screen: the ensembles, "Add an ensemble", and "On this tablet" for the local folder; the reader settings moved there, since they apply to every score. An ensemble browses like the library (folders first, natural order) and searches all of itself the library's way. Back and the back button: clear the search, up a folder, home. A folder a sync removes falls back to the nearest one still there. Release cold start on the emulator: first frame 450-530 ms, home fully drawn 505-585 ms; on the tablet still to do.

Search found "hoorn 1" in "hoorn13" and "hoorn12" (horns 1 and 3, 1 and 2), but not "French Horn 1 in F": for step 5's suggestions, instrument names need their Dutch, English and abbreviated forms.

### 4. Sync

- [x] Sync when the library opens and on pull-to-refresh; show progress and when the last sync was. Offline or signed out: the last copy, and say so.
- [x] New and changed files since I last looked are marked as such.

Done 2026-11-26. One sync service for the app: the home screen syncs every ensemble while it shows, an ensemble's screen its own; both pull to refresh. Opening a screen skips an ensemble synced in the last minute, pulling never does. It stops for the reader and when leaving the app, and a download in progress stops within 64 KB; a screen only stops its own sync, since in a crossfade the next one starts first. Each ensemble says "Up to date", "Downloading 3 of 10…" or "Synced 5 min ago"; offline or signed out the same, after the reason ("No connection to Google Drive. Synced 5 min ago."). "New": added since I last left the ensemble, marked for the whole visit (reader included), counted on the home screen; an ensemble's first listing marks nothing. "Changed" stays a newer version of a score I opened. Checked on the emulator with a file made to look new by deleting its row: "1 new" on home, "New" in the ensemble, gone after leaving.

### 5. My parts

- [x] Per ensemble, mark the files I play. The ensemble opens on my parts, with everything else one tap away.
- [x] Pre-select candidates by matching my instrument against file and folder names, including Dutch and abbreviated names (klarinet, cl.); I confirm. New files that match are suggested, never added on their own.

Done 2026-11-26. Every file has a "Mine" toggle; once there are any, the ensemble opens on "My parts", with "All files" next to it. Files that look like my part and that I haven't decided on are offered in a list, all ticked; what I untick is not suggested again, and neither is anything I toggled myself. A file of mine is kept when it leaves Drive. Changing what I play (in settings) suggests again among the files I haven't decided on.

The matching is deliberately simple, a fixed check rather than anything clever: a word list per instrument (Dutch, English, abbreviations: hoorn, horn, hn, cor), whole words only (a flugelhorn is not a horn), part numbers read right after the instrument ("hoorn13" is parts 1 and 3, "1-2" and "1_2" are 1 and 2, "1-3" is 1 to 3), Roman numerals ("Corno II") and ordinals in front ("2e hoorn", "1st horn") count too; no number means a part for everyone. Next to the instrument, a key other than mine (Es hoorn for an F horn) or a voice I didn't name (bas klarinet for a clarinet) means another instrument; a title's "Alto" doesn't count. An instrument not in the list matches by its own words. On the Brummen horn folder it suggested exactly the 14 horn 1 parts.

Then, 2026-11-26: only my parts are downloaded. A sync downloads my parts and new versions of files I have opened, and removes the rest from the device; any other file is downloaded when I open it (a second on the phone), and then stays. Until I mark parts in an ensemble, nothing already on the device is removed, so the first sync after this change didn't empty an offline library. Offline, a file that isn't here says so. No "download everything" switch: I have a connection nearly everywhere. Also, the text names the device: "this phone" or "this tablet" (600 dp on the narrow side), "this device" otherwise.

Checked on a Fairphone 6 (Android 16, release build) with my Koperensemble: a score added in Drive showed up as new after a pull, I marked it mine, and scores opened in airplane mode; non-mine parts were removed and came back on a tap. Cold start: first frame 195-255 ms, fully drawn 215-285 ms. The 74 instrumented tests pass on the phone too. On the tablet still to do.

Also a settings screen, from the library: crop margins and half-page turns (moved off the library), and per ensemble its name, what I play, and removing it (with a confirmation; moved off the ensemble screen).

Done when: a score added to a shared folder in Drive shows up on the tablet after pulling the library, I can mark it as mine, and every score opens with the network off.

## Milestone 4: annotations

The reason the IMSLP app is still needed. No import of my existing IMSLP annotations: not worth it.

### 0. Decisions before building

- [x] Backup: none for now (2026-11-26). Annotations live only on the device: a lost or reset device loses them. Known and accepted; a backup can come later (a file in my own Drive with the `drive.file` scope, or an export).
- [x] Input (2026-11-26): a pen writes, a finger turns pages, with no mode on the tablet. On a device without a pen (my phone), an Annotate button in the reader makes a finger draw until it is switched off.
- [x] Tools in the first version (2026-11-26): pen and eraser, and music symbols. Highlighter and text later, if I miss them.
- [x] Which scores (2026-11-26): ensemble scores and local ones. An ensemble score's annotations belong to its Drive file id; a local score's to its content (md5), so a rename keeps them and an edited PDF starts clean.
- [x] Ink: Jetpack `androidx.ink` 1.0.0 (stable since December 2025), for low-latency strokes, their geometry (erasing) and their storage format.

Structure, because this could easily spread through the reader (decided 2026-11-26):

- Everything for annotations lives in its own package, `annotate/`, with its own ViewModel and its own tables. `ReaderViewModel` and the rendering don't learn about annotations; the reader only says where the page is on screen.
- The reader shows one overlay per page: given the score, the page and where that page sits on screen, the overlay does input, drawing and saving itself.
- Page to screen and back (with the margin crop and half-page turns) is one pure function with unit tests: annotations are stored in coordinates of the whole page, so a crop change or a half turn never moves them.
- A score's identity for annotations is decided once, when it opens (Drive file id, or local content md5), and passed in; nothing downstream asks where a score came from.
- The review of each step also looks at this: annotation logic outside `annotate/`, or the reader growing, counts as a finding.

### 1. Pen and eraser

- [ ] Strokes over the page: pen with a few colours, eraser by stroke, undo. A pen draws at once; without a pen, the Annotate button. Stored per score and page, in whole-page coordinates.

### 2. Music symbols

- [ ] Stamps for the marks I write most: breath mark, fermata, glasses (watch the conductor), dynamics, accidentals. Placed with a tap, erased like strokes.

### 3. A changed score

- [ ] A new version of an annotated PDF: show it with my annotations on top and say the score changed, with the previous version still available. I check; Volti doesn't try to move marks to where it thinks they belong. Happens now and then, mostly in smaller ensembles.

Done when: a part annotated with the S Pen on the tablet keeps its marks across a restart, a crop change, half-page turns and a new version from Drive.

## Milestone 5: setlists

- [ ] Setlists per ensemble: an ordered list of my parts.
- [ ] In a setlist, turning past the last page opens the next piece.

## Later milestones (not planned yet)

- Learn my parts from my corrections. Every suggestion I accept or turn down, and every file I mark by hand, is stored per file already; a later version can learn from those which names mean my part (a new abbreviation, an orchestra's own naming) instead of relying on the fixed word list.

- Display names: my own name per file (my convention is `composer - title - tuning instrument part`), pre-filled from the file name, kept per Drive file id. The Drive name stays as it is.
- Search across ensembles.
- Remember the page per score, also across process death. (Was milestone 2, step 2; not wanted now.)
- Page-turner pedal: Bluetooth page turners send key presses. (Was milestone 2, step 3; not wanted now.)
- Play order and jump markers, stored alongside the PDF.
- Import: share and open-with target for PDFs.
- System-by-system view.
- MusicXML as a second document type.

## Out of scope

- IMSLP catalogue browsing.
- On-device optical music recognition.

## Way of working

Per step:

1. Claude writes the code and checks the step's "Done when" on the tablet.
2. Claude writes tests for the step's logic and runs them on the tablet. Unit tests for pure logic, instrumented tests for anything touching Android (DataStore, ContentResolver, `PdfRenderer`). No tests that drive the system folder picker.
3. A separate agent reviews the change and names the three most serious issues, without rewrites. It also checks that the tests cover the step's "Done when".
4. Claude fixes every issue the review finds, without asking first (since 2026-11-25), and reports what was fixed and how.
5. Commit and push to `main`.
