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

**Numbers** as digits, thousands in the language's format (`5,000` in English, `5.000` in Dutch). Spell out singular and plural; no `PDF(s)`.

## Punctuation

A sentence ends with a full stop. A label does not: chips, buttons, row status, and the sync status line when it is a fragment (`Up to date`, `Not synced yet`, `Removed from Google Drive`). Work in progress ends with a single `…` character, not three dots: `Checking Google Drive…`.

## Where it appears

In the place the problem is, and it stays until the problem is gone. A page that can't render shows its message where the page would be; a folder that can't be read shows it instead of the list; a sync problem goes in that ensemble's status line, with `Try again` or `Sign in to Google Drive` beside it.

No toasts or snackbars. They disappear while the player's hands are busy, and on stage a moving element draws the eye. A confirmation dialogue only before something that deletes, and it says what is deleted and what is kept: `Its PDFs and which of them are mine are deleted from this tablet. The folder in Google Drive stays as it is.`

In the reader, use `LocalReaderColors.current.message` so the text follows the page colours.

## Where the text is written

The layer that knows what went wrong returns a type, the UI turns it into words. `ReaderState.Problem` and its `when` in `ReaderScreen` is the pattern; so are `DriveProblem` and `syncProblemText` / `fileProblemText` in `SyncStatus.kt`, and `Rejection` for imports. This keeps wording in one layer and lets tests assert on the problem, not on a sentence.

Text is English, written in the Kotlin code, until milestone 10 moves it to `strings.xml` and adds Dutch. These rules then apply per language; how to write translatable text is in [words.md](words.md).

## Logs and exceptions

These are for the developer, not the musician.

- `Log.w("Volti", "Can't <verb> <what>", e)` for any expected failure: untrusted PDFs, a folder that went away, no network. Always pass the exception.
- `Log.e` is for a bug: a state the code believed couldn't happen.
- `Log.i` / `Log.d` for timing and counts; behind `BuildConfig.DEBUG` when it runs per page or per frame in a release build.
- URIs, file ids and page numbers (1-based, as the musician counts) are fine. Account names, email addresses and OAuth tokens never go in a log.
- A thrown exception's message has no full stop and is never shown to the user as is: `IOException("Can't start the PDF renderer")`.

## Not yet following this

Nothing known.
