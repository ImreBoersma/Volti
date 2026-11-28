# Data and storage

What Volti keeps on the tablet, and how it keeps it through upgrades. Most of it can be downloaded or imported again. My marks can't: they aren't backed up ([quality.md](quality.md), Backup), so the database is the only copy. A change that loses them is the worst bug Volti can have, worse than a crash.

## Where what lives

**Room (`volti.db`)** holds everything I made or decided: ensembles, which files are mine, my names for files, marks (strokes and symbols), setlists, which version I last saw.

**DataStore (`settings`)** holds settings only: switches such as `Crop margins` and `Half-page turns`. Nothing that has to change together with a Room row goes in DataStore. The two can't be written in one transaction.

**The PDF store** (`PdfStore` in `LocalCopy.kt`) holds the PDFs, one file per version, named by its md5. The same PDF in two ensembles is stored once. A PDF is never edited in place: a new version is a new file.

All three are in internal storage and excluded from backup and device transfer (`data_extraction_rules.xml`).

## Marks belong to the file, not the version

**A mark is keyed by `scoreKey`**: `file:<file id>` for an ensemble's file, so a new version in Drive keeps my marks; `local:<md5>` for a local file, so a rename keeps them. Use `fileKey()` and `localKey()`, never build the string by hand.

**Positions are in page units, pages are 0-based in storage.** The log and the UI count from 1 ([messages.md](messages.md)).

**Marks outlive the ensemble.** Removing an ensemble deletes its rows, but its marks and my names for its files stay, so adding the same folder again brings them back. They are small, so nothing cleans them up. The delete dialogue says they are kept. Deleting an imported file is different: there is no folder to add again, so `deleteImported` removes its marks, its name and its places in setlists in one transaction.

**Stored names are frozen.** A symbol is stored by its `Symbol` name, a colour as its ARGB value. Renaming an enum entry loses every stamp of it; add a new entry and keep the old one, or write a migration. A name the app doesn't know is skipped when drawing, never deleted.

**Strokes are androidx.ink's own bytes.** That format belongs to the library, so a bump of `ink` in the version catalogue is a data change, not just a dependency update. Strokes saved by the current version are kept as test fixtures and decoded in an instrumented test; an `ink` bump is merged only when that test passes on the tablet.

## Changing the schema

**Never destructive.** No `fallbackToDestructiveMigration` in any form, also not `OnDowngrade`. A schema Room can't migrate is a crash I can fix in the next build; a wiped database can't be fixed.

**A version is final once a build with it has run anywhere**, a test emulator included: change the schema again and it is a new version, never an edit of the last one. A database made by an earlier form of a version fails Room's check on start ("Room cannot verify the data integrity"), and only clearing the app's data gets past it. (It happened with version 10 on the emulator, 2026-11-27.)

**One version per schema change**, with an `AutoMigration` where Room can work it out, an `AutoMigrationSpec` where data has to move too (`DriveKeysToFileKeys` is the pattern), and a hand-written `Migration` only when neither works.

**New columns have a default** (`@ColumnInfo(defaultValue = …)`) that means "as before": `mine = 0`, `imported = 0`. Existing rows must mean the same after the migration as before it.

**Schemas are in git** (`app/schemas`), every version. A migration is written and tested against the exported schema, never against a guess.

**Every new version gets a migration test** in `EnsembleFileDaoTest` (or the DAO test it concerns): create the database at the previous version with `MigrationTestHelper`, insert the rows that matter (marks, names, `mine`), migrate to the latest, and assert they are still there and mean the same. The test from version 1 to the latest stays and grows.

**Only forward.** Volti never opens a database from a newer version. Installing an older build over a newer one crashes on start; the only way back is to uninstall, which deletes my marks. Don't do it on the tablet I play from.

## Writing

**What belongs together is written together.** A sync's result replaces an ensemble's rows in one transaction (`EnsembleFileDao.replace`), deleting a file removes everything that points to it in one (`deleteImported`). A half-written state is never visible, also not after the process is killed halfway.

**Saved when made** ([quality.md](quality.md), Lifecycle): a stroke goes to Room when the pen lifts, not when the reader closes. Marks are written through `MarkWrites`: one at a time, in the order they were made (a mark and its undo never land the wrong way round), in a scope that outlives the reader, so leaving it right after the pen lifts doesn't cancel the write.

**No disk on the main thread.** Room and DataStore are called from `suspend` functions or `Flow`s; file work runs on `Dispatchers.IO`.

## The PDF store

**What lands in the store passed `copyChecked`**: at most `MAX_PDF_BYTES`, starts like a PDF, and for a download the md5 Drive gave. It is written to a temporary file and renamed into place, so a half-written PDF never has a version's name.

**Downloads and imports are written in `files/drive-incoming`, outside the store (`files/drive`)**, so a clean-up can't remove one mid-copy and nothing holds `storeLock` while another app or Google Drive is read: opening a file never waits for another one's transfer. `keep` then renames the copy into the store under `storeLock`, in the same go as the row that needs it (`downloaded`, and `opened` when it is being opened), so no clean-up comes between. Leftovers older than `INCOMING_KEPT_MS` are deleted at start.

**Clean-up keeps what a row needs.** `keepOnly(versionsInUse())` deletes every version no row points to (`localMd5`, `seenMd5` or `previousMd5`) and every unfinished download. It runs under `storeLock`, after a sync or a removal; downloads in progress are outside its reach. A new column that points to a version must be added to `versionsInUse`, or its PDF is deleted at the next sync.

**Space.** A download needs its size plus `KEEP_FREE_BYTES` free, so Volti never fills the tablet. Not enough space is a `Rejection.NoSpace`, shown to the musician, not a crash.

## Not yet following this

Nothing known.
