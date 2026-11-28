# Volti: plan

A sheet music reader for Android tablets. Offline, fast, low battery use.

The finished milestones below are short lists. Their full write-ups (measurements, review fixes, what was checked where) are in the plan as of commit `953ac46`: `git show 953ac46:plan.md`.

## Priorities

**Security.** PDFs are untrusted files parsed by native code. That is the one real attack surface. Everything else is about keeping the app's reach small:

- No network permission. Changed 2026-11-25 for milestone 3 (scores from Google Drive): network only to Google's Drive endpoints, only to download, only while the app is open. The reader never needs the network: scores are always read from the device.
- No storage permission. Access only to the one folder granted through the system picker.
- The platform `PdfRenderer`, not a bundled PDF parser that needs patching by hand.
- No backup of app data. Annotations, the one thing on the tablet that can't be downloaded again, aren't backed up either for now (decided in milestone 4).

**Speed.** Two targets, measured on a release build on the tablet:

- A page turn shows a bitmap that is already rendered. No blank frame.
- Cold start to the library in under one second.

**Battery.** A page that is not changing costs zero CPU. No animations, no background work. Milestone 3's sync runs only when the library is opened or pulled, never in the background.

## Done

### Milestone 1: a reader for one folder (2026-11-25)

- One folder granted through the system picker (read-only, persisted), its PDFs listed with one `DocumentsContract` query, filtered on MIME type.
- Type-safe routes; the reader renders with `PdfRendererPreV` on Android 14 (Play-updated MediaProvider), `PdfRenderer` on 15+, at view size, on white, on a single-threaded dispatcher.
- Page turns by tapping the halves, with three bitmaps (previous, current, next); a dropped bitmap is reused only 50 ms after the screen stopped showing it (`onDrawn`, `onPresented`).
- Stage mode in the reader: screen kept on, system bars hidden.
- Hostile and broken input shows a message, never a crash (`HostileInputTest`, files from `make_hostile_pdfs.py`).
- Release build minified and resource-shrunk (27.7 MB to 2.2 MB), `-dontobfuscate`, debug logs stripped, no backup or device transfer at all.

### Milestone 2: a better library and reader (2026-11-25)

- PDFium runs in an isolated service (`isolatedProcess`, no permissions): bitmaps over `SharedMemory`, a watchdog for hangs and runaway memory, the app checks every answer. A crashing PDF leaves the app and other scores working.
- Library: subfolders, real folder names, natural sort; pull to refresh; search across the folder and its subfolders (up to 5,000 entries).
- Reader: white margins cropped (one crop per score), half-page turns, a back button that hides after 3 s, the score's own paper colour around the page.
- Instrumented tests run against a `minified` build type, so R8 problems show up in tests (`scripts/trace-test-references.sh` keeps what tests use).

### Milestone 3: ensembles from Google Drive (2026-11-26)

Decisions:

- Volti pulls Drive folders itself, read-only (`drive.readonly` through Play services' Authorization API; Volti stores no token). It never writes to a folder.
- The library's top level is ensembles: a shared folder plus what I play in it. My parts are marked by me; matching only suggests.
- Everything Volti adds (my parts, names, marks, setlists) hangs off the Drive file id.
- Network rules: only Google's Drive API over HTTPS; downloaded PDFs are as untrusted as local ones; sync only while the library or an ensemble shows; scores live in the app's storage, so everything works offline.
- Published "In production" without verification, so sign-ins don't run out after 7 days.

Built:

- Add an ensemble by its folder link; files in Room, stored by md5 (`files/drive/<md5>.pdf`), a changed file keeps the version I last saw until I open the new one.
- Sync on opening and pulling, with "New" and "Changed", and status lines; limits on downloads (200 MB, starts like a PDF, Drive's md5, 500 MB kept free).
- My parts: marked per file, suggested by instrument names (Dutch, English, abbreviations, part numbers); only my parts and opened files are kept on the device, the rest downloads when opened.
- A settings screen: crop margins, half-page turns, per ensemble its name, instrument and removal.

### Milestone 4: annotations (2026-11-26)

Decisions:

- No backup for now. A pen writes and a finger turns pages; on a device without a pen, an Annotate button makes a finger draw. Jetpack `androidx.ink`.
- Structure: everything in `annotate/`, with its own ViewModel and tables; the reader only says where the page is (one overlay per page). Page to screen is one pure function; marks are stored in whole-page coordinates, so a crop, a half turn or a zoom never moves them. A score's identity is decided once when it opens. A review counts annotation logic outside `annotate/` as a finding.

Built:

- Pen and eraser (by stroke), undo, strokes in page units.
- Music symbols as stamps: breath mark, fermata, glasses, dynamics, accidentals; the toolbar at the bottom, movable to the top.
- A changed score: my marks on the new version, a notice, and the previous version to compare against.

### Milestone 5: setlists (2026-11-27)

Decisions: per ensemble, made by hand, dragged into order; past the last page straight into the next piece; no extras (breaks, notes, repeats). Setlists live in `setlist/`; the reader only hears that a turn hit an end.

Built:

- Setlists per ensemble: pieces from my parts, dragged into order, renamed, deleted.
- Playing a setlist straight through, with a line naming the piece; a piece not on the device is downloaded or skipped with why.
- The setlists in the ensemble's screen: the newest three above the parts, beside them on a wide window.

### Milestone 6: one library (2026-11-27)

Decisions: an imported PDF is a copy in the app's storage and an ensemble file like any other; an ensemble may have no Drive folder; three ways in (picker, Share and Open with, a whole folder); the old folder library goes.

Built:

- Import into an ensemble with the file picker; the same name asks to replace (a new version) or keep both.
- Share and Open with: copied in at once, then I pick the ensemble and folder; one Volti (`singleTask`).
- Import a whole folder; the old granted folder is offered once, marks included.

### Milestone 7: display names (2026-11-27)

Decisions: one text field in my own convention, pre-filled with the cleaned file name; renamed from the ensemble and from a setlist; every file can have one, kept per file id. The Drive name stays.

Built: rename from a file's ⋮ menu; the name shows, sorts and is found everywhere, and stays with the file (its own table, database version 10).

### Milestone 8: annotating, closer (2026-11-27)

Decisions: zoom only while annotating; six named colours, shown only while choosing; the last colour is the default, no setting. The zoom is the annotation layer's; the reader knows "shown enlarged" as a plain value, because rendering is its job.

Built:

- Two fingers zoom up to four times while annotating; not with a pen on the glass.
- Zoomed in, the part on screen is rendered sharp once the fingers rest.
- The pen's colour button opens six named colours; the last one is kept.
- Marks are saved through one ordered queue that outlives the reader; a mark that can't be saved says so.

### Milestone 9: downloads in the background (2026-11-27)

Decisions: the spinner means "checking Google Drive" only; sheets show their download; downloads still pause while reading.

Built:

- A download is copied next to the store and taken in only when complete: opening a file never waits for another's download.
- Every ensemble is checked first, then downloaded; the sheet downloading now turns, the ones to come show an arrow.

### Milestone 10: Volti in Dutch (2026-11-28)

Decisions: one word per thing, the musician's word (see [words.md](docs/guidelines/words.md)); Android's per-app language, also set from Volti's Settings; Dutch terms partij, stuk, setlist, aantekeningen, with "je".

Built:

- Every text from `strings.xml`: whole sentences, plurals, one phrase per device, numbers in the language's format.
- A Dutch translation, and Language in Settings (System, English, Nederlands); tests always run in English.

## To verify

- ~~On Android 15 and up, `PdfRenderer` is believed to be updated through Google Play system updates.~~ Confirmed 2026-11-25: the tablet's firmware patch is 2025-06-01 (no longer patched), but the MediaProvider module with `PdfRendererPreV` and its own PDFium is on the 2026 Play train. Hence `PdfRendererPreV` on Android 14.
- ~~The Jetpack `androidx.pdf` library.~~ Decided: not used. It is a viewer on top of the same module, so no security gain, and its UI does not fit three-bitmap page turns.
- Use Volti at a rehearsal, and write down what annoyed me (was milestone 2, step 0).
- On the tablet, when I get to it (each milestone's "Done when", in the full write-up):
  - Milestone 3: cold start of the home screen; a Drive score added, marked mine and opened offline.
  - Milestone 4: marks with the S Pen kept across a restart, a crop change, half-page turns and a new version.
  - Milestone 5: a rehearsal's setlist played through; setlists beside the parts in landscape, above them in portrait.
  - Milestone 6: a PDF from Downloads and one shared from Gmail imported; my old folder imported with its marks.
  - Milestone 7: a renamed part shown under its name in a setlist, also after a sync.
  - Milestone 8: a fingering drawn at four times in place at the whole page; sharp at four times; green kept for the next part.
  - Milestone 9: a first sync of a big folder: files open meanwhile, sheets come in one by one.
  - Milestone 10: every screen in Dutch at font scale 2.0. Choices to review: Delete = Verwijderen, Remove = Weghalen, Clear = Wissen; Rename = Hernoemen; "1 partij is waarschijnlijk van jou"; Up to date = Bijgewerkt; Play = Spelen; Undo = Ongedaan maken (long); "Gecontroleerd" for Marks checked; the symbol names (Ademteken, Herstellingsteken, Let op de dirigent, Gum, Kruis, Mol).

## Later milestones (not planned yet)

- Learn my parts from my corrections. Every suggestion I accept or turn down, and every file I mark by hand, is stored per file already; a later version can learn from those which names mean my part (a new abbreviation, an orchestra's own naming) instead of relying on the fixed word list.

- Search across ensembles, and a list of all my files, local and from every ensemble, in one place.
- Remember the page per score, also across process death. (Was milestone 2, step 2; not wanted now.)
- Page-turner pedal: Bluetooth page turners send key presses. (Was milestone 2, step 3; not wanted now.)
- Play order and jump markers, stored alongside the PDF.
- System-by-system view.
- MusicXML as a second document type.

## Out of scope

- IMSLP catalogue browsing.
- On-device optical music recognition.

## Reference

The release build is signed with the debug key: good enough for my devices; a real release key is needed before anyone else installs it, and changing the key means uninstalling, which deletes my marks.

```
./gradlew :app:installRelease \
  -Pandroid.injected.signing.store.file=$HOME/.android/debug.keystore \
  -Pandroid.injected.signing.store.password=android \
  -Pandroid.injected.signing.key.alias=androiddebugkey \
  -Pandroid.injected.signing.key.password=android
```

Google Cloud, set up once (project `volti-515911`): the Google Drive API enabled; the OAuth consent screen External and published (no 7-day sign-ins), scope `.../auth/drive.readonly`; two Android OAuth clients, `nl.imreboersma.volti` and `nl.imreboersma.volti.debug`, both with the debug key's SHA-1 `78:BA:25:D0:59:1A:A0:DB:42:A3:E7:2E:C8:80:55:F1:CC:30:EC:19`. A release key needs a third client. No client secret or API key goes into the app.

The tablet: Galaxy Tab S7 FE (SM-T733), Android 14 / API 34, security patch 2025-06-01, Play system update 2026.

## Way of working

Per step:

1. Claude writes the code, and checks it on the emulator (since 2026-11-27; the tablet when asked).
2. Claude writes tests for the step's logic and runs them on the emulator. Unit tests for pure logic, instrumented tests for anything touching Android (DataStore, ContentResolver, `PdfRenderer`). No tests that drive the system folder picker. A bug fix comes with a test that failed before it. See [testing.md](docs/guidelines/testing.md).
3. A separate agent reviews the change and names the three most serious issues, without rewrites. It also checks that the tests cover the step's "Done when".
4. Claude fixes every issue the review finds, without asking first (since 2026-11-25), and reports what was fixed and how.
5. Commit and push to `main`; the pre-push hook runs CI on the Mac first.
6. Wait for GitHub Actions on that commit. The step is done when it passes; when it fails, find out why, fix it and push again (since 2026-11-28). The "Done when" is checked on the tablet when the musician gets to it.
