# Volti

A sheet music reader for Android tablets, used on a music stand during rehearsals and concerts. Offline, fast, low battery use. Kotlin, Jetpack Compose, Room, DataStore. `minSdk` 34, reference device Galaxy Tab S7 FE (Android 14).

`plan.md` is the source of truth: priorities (security, speed, battery), milestones with their "Done when" checks, decisions, and the way of working. Read the Priorities section and the current milestone before changing anything.

## Guidelines

Follow these for every change:

- [docs/guidelines/messages.md](docs/guidelines/messages.md): error and warning messages, and logging.
- [docs/guidelines/android.md](docs/guidelines/android.md): window sizes, system bars, Back, keyboard and pedal input, accessibility, settings.
- [docs/guidelines/quality.md](docs/guidelines/quality.md): lifecycle, performance, dependencies, permissions, privacy and backup.
- [docs/guidelines/data.md](docs/guidelines/data.md): what is kept where, marks, schema changes and migrations, the PDF store.
- [docs/guidelines/ux-laws.md](docs/guidelines/ux-laws.md): the Laws of UX that apply to a reader on a music stand, as rules.

Each guideline ends with "Not yet following this". When a change fixes one of those, remove it from the list. When a change knowingly adds one, add it.

## Code

- `nl.imreboersma.volti`: `ui/` screens and ViewModels, `render/` PDF rendering in an isolated process, `drive/` Google Drive sync, `db/` Room, `data/` DataStore and folder access, `annotate/` annotations.
- PDFs are untrusted input. Parsing stays in `PdfRenderService` (`:pdf`, `isolatedProcess`). Catch whatever it throws and turn it into a `Problem`, never a crash.
- Comments say why, from the musician's side ("I" is the musician): `/** The suggested parts, all ticked: I untick what isn't mine. */`.
- No new permissions, no background work, no decorative animation, without a decision in `plan.md` first.

## Build and test

```sh
./gradlew test lintDebug                    # unit tests and lint, as on CI
./gradlew connectedMinifiedAndroidTest      # instrumented tests on a device, against the minified build
./gradlew connectedDebugAndroidTest -Pvolti.testBuildType=debug   # quicker local run
scripts/trace-test-references.sh            # after a test starts using new app code (NoSuchMethodError in the minified run)
scripts/ci-local.sh                         # what CI does, on this Mac: unit tests, lint, instrumented tests on an API 34 tablet emulator
```

Every push runs `scripts/ci-local.sh` first through the pre-push hook (`scripts/install-hooks.sh`, once per clone); a push of only Markdown skips it. When `.github/workflows/ci.yml` changes, change the script with it.

A step is done when its "Done when" in `plan.md` is checked on the tablet, not only in tests.

## Commits

Conventional prefix, lower case, one line saying what changed for the musician: `feat: annotate scores with a pen or a finger`, `fix: a shorter sync status: Synced 4 min ago`. No `Co-Authored-By` or other Claude attribution.
