# Bug triage

Bugs and gaps that are known but not fixed yet, worst first. A bug is reproduced (on the tablet, or in a test that fails) before it is fixed. Once fixed, the entry goes, and so does its line under "Not yet following this" in the guideline it came from.

Severity: **data loss** (something I made is gone), **wrong** (the app does or says the wrong thing), **gap** (a missing test or check, nothing broken yet).

## 1. No ink fixtures or decode test

- **Severity:** gap
- **Status:** confirmed
- **Where:** `app/src/androidTest`
- **What happens:** nothing yet. A bump of `androidx.ink` that can't decode strokes stored by the previous version would lose every mark, and no test would catch it before the tablet does.
- **Fix direction:** store strokes encoded by the current `ink` version as fixtures, and a test that decodes them. Run it before merging any `ink` bump (Renovate included).
- **Found in:** [data.md](../guidelines/data.md)
