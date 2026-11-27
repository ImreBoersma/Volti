# Words

Which word means what, and how buttons, empty lists and other text that isn't a message are written. Messages are in [messages.md](messages.md); settings labels in [android.md](android.md). Both follow the terms here.

Text speaks to the musician as "you" (see [android.md](android.md), Settings).

## Terms

One thing, one word, on every screen. The musician's word, not the system's: a musician doesn't think in files.

| Word | Means | Not | Dutch |
|---|---|---|---|
| **ensemble** | A band or orchestra I play in: its shared Google Drive folder, or only what I imported into it. | band, group, library | ensemble |
| **part** | One PDF in an ensemble: what I open and play from. `All parts`, `Your parts`, `This part changed`. | score, file, sheet | partij |
| **piece** | A part's place in a setlist. `Add pieces`, `Opening the next piece…` | item, entry, song | stuk |
| **setlist** | The pieces of one concert or rehearsal, in playing order. | programme, playlist | setlist |
| **marks** | What I draw or stamp on a part: strokes and symbols. | annotations (the code's word), notes, ink | aantekeningen |
| **PDF** | A file before it is in an ensemble (picked, shared, in a folder being imported), or when the format is the point (`This PDF is password-protected.`). | document | pdf |
| **file** | Only for the file itself: its file name, a link that is to a file instead of a folder. | | bestand |
| **score** | Not used. Volti can't tell a conductor's score from a part, so everything in an ensemble is a part. | | partituur |
| **Google Drive**, **device** | See [messages.md](messages.md), Wording. | | Google Drive, apparaat |

**Code may keep its own names** (`EnsembleFile`, `scoreKey`, annotations): renaming a stored name is a migration (see [data.md](data.md)). The rule is for what the musician reads.

## Delete and remove

**Delete**: gone from this device, and it doesn't come back by itself. An imported part, a setlist.

**Remove**: out of a list, or out of Volti, but it still exists somewhere. A piece from a setlist (the part stays in the ensemble), an ensemble (its folder stays in Google Drive, my marks stay on the device), the old folder after the move to ensembles.

The confirmation dialogue says what goes and what stays (see [messages.md](messages.md), Where it appears). No third verb.

## Buttons and menu items

**A verb that says what happens**, in sentence case: `Delete`, `Add pieces`, `Import here`, `Try again`. Never `OK`, `Yes`, `Submit` or `Confirm`.

**A dialogue's confirm button repeats the verb of its title**: `Delete Concert 14 December?` → `Delete`. The way out is `Cancel` when it undoes the step, and `Not now` when the question can come back later.

**Short enough for the tablet at font scale 2.0** without wrapping, and short in Dutch too: Dutch runs about a third longer.

## Empty lists and states

**What will be here, then the one way to fill it**, in one or two sentences: `No setlists yet. Make one for a concert or a rehearsal, and put its pieces in the order you play them.`, `No pieces yet. Add them from your parts.` No illustrations, no "Get started".

**Search with no results repeats what was searched for**: `Nothing found for "farandole"`.

## Text that can be translated

From milestone 10 Volti is in English and Dutch, in Android's per-app language: the system's language unless I pick another, in Android's settings or in Volti's. These rules hold from now on, and for all text once it has moved.

**Text lives in `strings.xml`**, never in Kotlin. Logs and code comments stay English in the code.

**Whole sentences, never stitched together.** One string per sentence with numbered placeholders (`%1$s`, `%1$d`), and `<plurals>` for counts. Not `"1 file looks" + " like my part"`, not `"this ${deviceName()}"`: Dutch says "deze tablet", "deze telefoon", but "dit apparaat", so each device gets its own phrase.

**Numbers and dates in the language's format**, through `NumberFormat` and `DateTimeFormatter` for the current locale: `5,000` in English, `5.000` in Dutch.

**Names stay as they are.** Instrument names, file names and ensemble names are never translated; the word lists that match parts (klarinet, cl.) already cover both languages.

## Not yet following this

- All text is in Kotlin, not `strings.xml` (milestone 10).
- Terms: `All files` (`EnsemblesScreen.kt`, `SetlistScreens.kt`), "1 file looks like my part" (`EnsemblesScreen.kt`), "No PDFs here yet." (`EnsemblesScreen.kt`), "Imported files only" (`HomeScreen.kt`), "This score changed since you made your marks" (`AnnotationOverlay.kt`), "If other scores stay blank too" (`ReaderScreen.kt`), "every page of a score" (`SettingsScreen.kt`), "1 PDF couldn't be downloaded" (`SyncStatus.kt`): parts, once they are in an ensemble.
- `Let it go` (`HomeScreen.kt`) is a third verb: `Remove`.
- `Later` (`AnnotationOverlay.kt`) for a question that comes back: `Not now`.
- Sentences stitched together: "this ${deviceName()}" (8 places), the count in "files look like my part" (`EnsemblesScreen.kt`), `5,000` written into `SyncStatus.kt`.
