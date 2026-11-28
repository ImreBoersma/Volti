# Testing

What gets a test, where it goes, and what is checked on the tablet instead. The commands are in `CLAUDE.md`; the steps per milestone in `plan.md`, Way of working.

## Where a test goes

**Unit tests (`app/src/test`) for pure Kotlin**: wording, geometry, parsing, the copy check, sorting, colour contrast. Nothing from Android in them; what needs a `Context`, a `Cursor` or a `ContentResolver` is instrumented. Pure logic is pulled out of a ViewModel or a screen so it can be tested here (`syncProblemText`, `copyChecked`, `reconcile`).

**Instrumented tests (`app/src/androidTest`) for anything that touches Android**: Room, DataStore, `ContentResolver`, `PdfRenderer` in the `:pdf` process, Compose input, Back, keys, stage mode.

**Instrumented tests run against the minified build**, as on CI, so R8 can't remove something a release needs without a test noticing. A test that starts using new app code fails there with `NoSuchMethodError`: run `scripts/trace-test-references.sh`, don't add a keep rule by hand. `-Pvolti.testBuildType=debug` is for a quicker run while writing a test, not for calling it done.

**Never the system folder picker or Google's sign-in.** Tests start behind them: a granted tree, URIs handed straight to `Syncer.receive` (`ImportTest`), a Drive response as JSON (`DriveFilesTest`).

## What gets a test

**The logic of every step, and its "Done when"** as far as it can be automated. The review checks that the tests cover it.

**Every bug fix, with a test that failed before the fix.** Write the test first, see it fail for the reason in the bug, then fix. Only when it truly can't be automated (pen timing on the real screen, a Bluetooth pedal, how a page looks) is the check on the tablet enough, and the commit or the entry in [triage.md](../bugs/triage.md) says why.

**Every database version**, with a migration test from the exported schema of the version before (see [data.md](data.md)).

**Every kind of hostile input** gets a file in the corpus (`androidTest/assets/hostile`), made by `make_hostile_pdfs.py` and checked in with it, and goes through the real ViewModel and renderer (`HostileInputTest`). The test asserts the `Problem` it turns into.

**Every `Problem`, `DriveProblem` and `Rejection` has its wording tested** in one place (`SyncStatusTest`): a new case without words fails there, not on stage.

## UI tests: behaviour, not looks

**A UI test does what a musician does** and checks what they'd notice: a page turn by tap, swipe, key and pedal; Back at every level; a dialogue confirmed and cancelled; a mark drawn and still there after leaving. Find nodes the way TalkBack does, by text or content description, never by test tag alone: a test that finds a button by its label also checks the label.

**No screenshot tests.** Layout, colours and text that wraps are checked on the tablet, in light and dark and at font scale 2.0 (see [android.md](android.md)). Golden images would need updating with every wording change, and milestone 10 changes most of them.

## Writing a test

**The name is a sentence of what the musician gets**: `offlineItSaysHowOldTheCopyIs`, `aPedalHeldDownTurnsOnce`. Not `testSync2`.

**Wait for a state, not a time.** `waitUntil`, `waitForIdle`, a latch. `Thread.sleep` only when time is what is tested (the watchdog's timeout) or to show that nothing more happens (`aPedalHeldDownTurnsOnce`), with a comment or assertion message saying which.

**Each test makes what it needs and leaves nothing behind**: its own database in memory or a temporary file, its own folder in the cache. Tests run in any order, on CI's emulator and on the tablet.

**Fixtures are made by a script in the repository**, next to the files it made, so a fixture can be remade and its intent read. No PDF, ink or schema file from nowhere.

## On the tablet

**A step is done when its "Done when" is checked on the reference tablet** (Galaxy Tab S7 FE, Android 14), not on the emulator alone. Checked somewhere else first? `plan.md` says where, and "on the tablet still to do" until it is.

**The instrumented tests pass on the tablet too** before a milestone is done, not only on CI's emulator.

## Speed, by hand

The budgets are in `plan.md`, Priorities. They are measured by hand, not in a test: a real device is the only one that counts, and CI has none.

**On the tablet, release (minified) build, installed fresh**, at least five runs, and the range is what is written down.

- Cold start: `adb shell am start -W -S nl.imreboersma.volti/.MainActivity` for the first frame, and the `Fully drawn` line in logcat (`ReportDrawnWhen` in `HomeScreen`) for the library with its list.
- A page turn: from the tap to the frame that shows the page, at reading pace and in a burst. Write down how it was measured next to the number.

**Recorded in `plan.md`**, at the step or milestone it belongs to: date, device, build, the ranges. A milestone that touches startup or the reader measures again; a number that goes over a budget is a bug in [triage.md](../bugs/triage.md).

## Not yet following this

- No ink fixtures or decode test (see [data.md](data.md)).
- Cold start since milestone 3 is measured on the emulator and a Fairphone 6, not on the tablet (`plan.md`, milestone 3).
