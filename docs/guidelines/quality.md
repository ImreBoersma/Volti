# App quality

The technical and privacy parts of Google's [app quality guidelines](https://developer.android.com/quality) and the [core app quality checklist](https://developer.android.com/docs/quality-guidelines/core-app-quality) that apply to Volti. The design and interaction parts are in [android.md](android.md). Checklist IDs are in brackets.

Left out because Volti has none of it: notifications, audio, video, sharing, WebViews, sign-in with passwords, payments, ads, Play Store listing assets.

## Lifecycle

**The score is where I left it.** Locking the tablet, letting it sleep, switching apps and coming back from Recents all return to the same score, page and half-page position, with annotating still on or off. Nothing reloads that doesn't have to. (`App_Switcher`, `Sleep_Resume`, `Lock_Resume`)

**Nothing the musician made is lost by leaving.** A stroke, a stamp, an ensemble, a setting: saved when it is made, not when the screen closes. Back never discards work, so it never needs to ask. (`State_Preservation`)

## Performance

**The budgets are in `plan.md`** and stricter than Google's: cold start to the library under one second (Google allows two before a progress indicator), and a page turn shows a bitmap that is already rendered. Measure on a release build on the tablet; `ReportDrawnWhen` in `HomeScreen` marks "fully drawn". (`App_Startup_Time`)

**60 fps where anything moves**: scrolling lists and drawing ink. A page turn is not an animation. (`Rendering_Performance`)

**No disk or network on the main thread.** Debug builds run with `StrictMode` thread and VM policies that log violations (`MainActivity.onCreate`); a new violation is a bug. Known and outside Volti's code: androidx.ink loads its native library on the main thread the first time the annotation layer shows (about 13 ms). (`StrictMode_Compliance`, `Stability_ANR`)

**A crash in the PDF parser is not a crash of the app.** It stays in the isolated `:pdf` process and turns into a `Problem`. (`Stability_ANR`)

## Platform and dependencies

**`targetSdk` and `compileSdk` on the latest stable API**, dependencies kept current by Renovate. No non-SDK interfaces, no reflection into the framework. (`Target_SDK_Version`, `Compile_SDK_Version`, `SDK_Maintenance`, `Non_SDK_Interfaces`)

**Nothing debug-only in a release build.** Test helpers such as `ui-test-manifest` are `debugImplementation` or `minifiedImplementation`, never `implementation`. (`Production_Build_Quality`)

**No background work, so Doze and App Standby need nothing.** Sync runs only while the app is open. (`Power_Management`, `Background_Service_Optimization`)

## Privacy and security

**The permission list stays as it is: `INTERNET`, nothing else.** Files come through the system folder picker, Drive through Google's authorisation flow. A new permission needs a decision in `plan.md` first. (`Minimize_Permissions`, `Runtime_Permissions`)

**Every component sets `android:exported` explicitly**; only `MainActivity` is exported. (`Component_Export`)

**HTTPS only**, declared in a network security configuration that disallows cleartext traffic and trusts only the system's certificate authorities (`res/xml/network_security_config.xml`). (`Network_Security_Traffic`, `Network_Security_Configuration`)

**App data stays in internal storage**, and logs carry no account names, email addresses or tokens (see [messages.md](messages.md)). (`Sensitive_Data_Storage`, `Sensitive_Data_Logging`)

**No code loaded from outside the APK, no custom cryptography.** (`App_Bundles`, `Cryptographic_Algorithms`)

### Backup: a deliberate exception

Google asks apps to keep data across backups and device transfers (`App_Data_Backup`). Volti doesn't: `data_extraction_rules.xml` excludes everything from cloud backup and device-to-device transfer. Scores can be downloaded again, or imported again. Annotations exist nowhere else, and milestone 4 decided (2026-11-26) not to back them up for now: a lost or reset device loses them. A backup can come later (a file in my own Google Drive, or an export).

## Not yet following this

Nothing known.
