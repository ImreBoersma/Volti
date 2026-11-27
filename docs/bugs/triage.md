# Bug triage

Bugs and gaps that are known but not fixed yet, worst first. A bug is reproduced (on the tablet, or in a test that fails) before it is fixed. Once fixed, the entry goes, and so does its line under "Not yet following this" in the guideline it came from.

Severity: **data loss** (something I made is gone), **wrong** (the app does or says the wrong thing), **gap** (a missing test or check, nothing broken yet).

## 1. A stroke may be lost when I leave right after the pen lifts

- **Severity:** data loss
- **Status:** not reproduced
- **Where:** `AnnotationViewModel.add` (`app/src/main/java/nl/imreboersma/volti/annotate/AnnotationViewModel.kt`)
- **What happens:** the stroke shows at once, then is written to Room in a coroutine on `viewModelScope`. Pressing Back right after lifting the pen clears the ViewModel, which cancels that scope and possibly the write. The next time I open the score the stroke isn't there.
- **To reproduce:** draw a stroke and press Back (or the pedal's back action) within a few milliseconds of lifting the pen. Reopen the score. A test can do it more reliably: add a stroke, clear the ViewModel straight after, and read the table.
- **Fix direction:** write marks in a scope that outlives the screen (an application-level scope, as `Syncer` has), not in `viewModelScope`.
- **Found in:** [data.md](../guidelines/data.md)

## 2. Long or multi-line names stretch list rows

- **Severity:** wrong
- **Status:** not reproduced
- **Where:** names that come from outside the app (Drive file and folder names, a shared file's display name, a picked folder's name) are shown without `maxLines` in `HomeScreen.kt`, `EnsemblesScreen.kt`, `ImportScreen.kt`, `FileMenu.kt` and the delete and remove dialogues. Only the setlist screens cap them.
- **What happens:** a file named with 2,000 characters or with line breaks pushes one row to many lines. On a stand that scrolls the scores I need off the screen.
- **To reproduce:** share a PDF whose display name is very long or contains newlines (a test `ContentProvider`, or a Drive file renamed through the Drive API), then open the ensemble.
- **Fix direction:** one or two lines with an ellipsis in rows, the full name in the file menu, in the rule from [quality.md](../guidelines/quality.md) (Input from other apps and Drive).
- **Found in:** [quality.md](../guidelines/quality.md)

## 3. The ensemble delete dialogue doesn't say my marks are kept

- **Severity:** wrong
- **Status:** confirmed by reading the code
- **Where:** `SettingsScreen.kt`, the "Remove …?" dialogue (around line 193)
- **What happens:** removing an ensemble keeps my marks and my file names on purpose, so adding the folder again brings them back. The dialogue doesn't say so, so I may think they are gone, or never learn they can come back.
- **Fix direction:** one sentence in the dialogue, following [messages.md](../guidelines/messages.md).
- **Found in:** [data.md](../guidelines/data.md)

## 4. No ink fixtures or decode test

- **Severity:** gap
- **Status:** confirmed
- **Where:** `app/src/androidTest`
- **What happens:** nothing yet. A bump of `androidx.ink` that can't decode strokes stored by the previous version would lose every mark, and no test would catch it before the tablet does.
- **Fix direction:** store strokes encoded by the current `ink` version as fixtures, and a test that decodes them. Run it before merging any `ink` bump (Renovate included).
- **Found in:** [data.md](../guidelines/data.md)
