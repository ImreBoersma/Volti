# Error and warning messages

The reader is a musician at a stand, often mid-rehearsal, with an instrument in their hands. A message gets one glance. It has to say what is wrong with *their music*, and whether they need to do anything.

## The shape of a message

**What happened, in their terms.** Scores, PDFs, pages, folders, ensembles, Google Drive. Never URIs, ids, exceptions, "API", "token" or "sync job".

**Then, only if it helps: what still works, or the one thing to do.** Leave out advice they can't act on.

| Good                                                                                        | Why                                                                                |
|---------------------------------------------------------------------------------------------|------------------------------------------------------------------------------------|
| `This PDF is password-protected.`                                                           | The cause is the file; nothing to do in Volti.                                     |
| `This Google account can't see that folder. Is it shared with you?`                         | Names the likely cause as a question, because we can't be sure.                    |
| `This file takes too long to open. If other scores stay blank too, close and reopen Volti.` | The one action that helps, and when it applies.                                    |
| `Couldn't reach Google Drive. Synced 4 min ago.`                                            | What failed, then how fresh the copy on the device is: the scores here still work. |
| `Up to date. 1 PDF couldn't be downloaded; it is tried again next time`                     | A warning leads with what worked, then what didn't, then what happens next.        |

## Wording

**Can't vs couldn't.** `Can't` for a state that holds now (`Can't open this file.`, `Can't read this folder.`). `Couldn't` for one attempt that failed and may work next time (`Couldn't reach Google Drive.`).

**Point at the thing on screen.** `This file`, `this folder`, `that folder` (the one just typed in). Not the file name: it is already visible.

**Say the device with `deviceName()`**, never a literal "tablet" or "phone": `Not on this ${deviceName()}`.

**Google Drive** in full when it stands alone. `Drive` only as a modifier: `a Drive folder`, `the shared Drive folder`.

**Validation tells them what to do**, one problem at a time, imperative: `Give the ensemble a name.`, `Say what you play in it.` Not `Name is required`.

**Never:** `Error`, `Failed`, `Invalid`, `Oops`, `Sorry`, `Something went wrong`, `please`, exclamation marks, blame (`You entered…`). An HTTP status only as a last resort, in brackets at the end, when nothing else is known: `Google Drive answered with an error (503). Try again later.`

**Numbers** as digits, thousands with a comma (`5,000`). Spell out singular and plural; no `PDF(s)`.

## Punctuation

A sentence ends with a full stop. A label does not: chips, buttons, row status, and the sync status line when it is a fragment (`Up to date`, `Not synced yet`, `Removed in Drive`). Work in progress ends with a single `…` character, not three dots: `Checking Google Drive…`.

## Where it appears

In the place the problem is, and it stays until the problem is gone. A page that can't render shows its message where the page would be; a folder that can't be read shows it instead of the list; a sync problem goes in that ensemble's status line, with `Try again` or `Sign in to Google Drive` beside it.

No toasts or snackbars. They disappear while the player's hands are busy, and on stage a moving element draws the eye. A confirmation dialogue only before something that deletes, and it says what is deleted and what is kept: `Its PDFs and which of them are mine are deleted from this tablet. The folder in Google Drive stays as it is.`

In the reader, use `LocalReaderColors.current.message` so the text follows the page colours.

## Where the text is written

The layer that knows what went wrong returns a type, the UI turns it into words. `ReaderState.Problem` and its `when` in `ReaderScreen` is the pattern. This keeps wording in one layer and lets tests assert on the problem, not on a sentence.

Text is English, written in the Kotlin code. There is no `strings.xml` translation yet; when there is, these rules apply per language.

## Logs and exceptions

These are for the developer, not the musician.

- `Log.w("Volti", "Can't <verb> <what>", e)` for any expected failure: untrusted PDFs, a folder that went away, no network. Always pass the exception.
- `Log.e` is for a bug: a state the code believed couldn't happen.
- `Log.i` / `Log.d` for timing and counts; behind `BuildConfig.DEBUG` when it runs per page or per frame in a release build.
- URIs, file ids and page numbers (1-based, as the musician counts) are fine. Account names, email addresses and OAuth tokens never go in a log.
- A thrown exception's message has no full stop and is never shown to the user as is: `IOException("Can't start the PDF renderer")`.

## Not yet following this

- A long import (a folder of hundreds of PDFs) says only `Importing…` or `Copying…`, not how far it is (`12 of 300`).
- `Syncer` returns finished sentences in `SyncState.Failed(message)` instead of a problem type, and builds user text outside `ui/`.
- The same situation has two wordings: `Couldn't reach Google Drive.` (`Syncer.kt:149`, `:207`) and `No connection to Google Drive.` (`Syncer.kt:279`).
- Google Drive access has four wordings: `Volti needs read-only access to Google Drive to sync.` (`HomeScreen.kt:159`), `Volti needs access to Google Drive to sync.` (`Syncer.kt:111`), `Sign in to Google Drive first.` (`Syncer.kt:161`), `Signed out of Google Drive.` (`EnsemblesScreen.kt:182`).
- A missing file is both `This file is gone.` (`Syncer.kt:165`) and `This file isn't in Google Drive any more.` (`Syncer.kt:142`).
- `Google Drive answered with an error (…)` has `Try again later.` in one place (`Syncer.kt:275`) and not the other (`Syncer.kt:142`).
- `Removed in Drive` (`EnsemblesScreen.kt:370`) uses `Drive` on its own; `Removed from Google Drive` if it fits the row.
- `Log.w("Volti", "Closing $uri failed", e)` (`ReaderViewModel.kt:327`) breaks the `Can't …` pattern.
