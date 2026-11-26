# App quality

The technical and privacy parts of Google's [app quality guidelines](https://developer.android.com/quality) and the [core app quality checklist](https://developer.android.com/docs/quality-guidelines/core-app-quality) that apply to Volti. The design and interaction parts are in [android.md](android.md). Checklist IDs are in brackets.

Left out because Volti has none of it: notifications, audio, video, sharing, WebViews, sign-in with passwords, payments, ads, Play Store listing assets.

## Lifecycle

**The score is where I left it.** Locking the tablet, letting it sleep, switching apps and coming back from Recents all return to the same score, page and half-page position, with annotating still on or off. Nothing reloads that doesn't have to. (`App_Switcher`, `Sleep_Resume`, `Lock_Resume`)

**Nothing the musician made is lost by leaving.** A stroke, a stamp, an ensemble, a setting: saved when it is made, not when the screen closes. Back never discards work, so it never needs to ask. (`State_Preservation`)

## Performance

**The budgets are in `plan.md`** and stricter than Google's: cold start to the library under one second (Google allows two before a progress indicator), and a page turn shows a bitmap that is already rendered. Measure on a release build on the tablet; `ReportDrawnWhen` in `HomeScreen` marks "fully drawn". (`App_Startup_Time`)

**60 fps where anything moves**: scrolling lists and drawing ink. A page turn is not an animation. (`Rendering_Performance`)

**No disk or network on the main thread.** Debug builds run with `StrictMode` thread and VM policies that log violations; a new violation is a bug. (`StrictMode_Compliance`, `Stability_ANR`)

**A crash in the PDF parser is not a crash of the app.** It stays in the isolated `:pdf` process and turns into a `Problem`. (`Stability_ANR`)

## Platform and dependencies

**`targetSdk` and `compileSdk` on the latest stable API**, dependencies kept current by Renovate. No non-SDK interfaces, no reflection into the framework. (`Target_SDK_Version`, `Compile_SDK_Version`, `SDK_Maintenance`, `Non_SDK_Interfaces`)

**Nothing debug-only in a release build.** Test helpers such as `ui-test-manifest` are `debugImplementation` or `minifiedImplementation`, never `implementation`. (`Production_Build_Quality`)

**No background work, so Doze and App Standby need nothing.** Sync runs only while the app is open. (`Power_Management`, `Background_Service_Optimization`)

## Privacy and security

**The permission list stays as it is: `INTERNET`, nothing else.** Files come through the system folder picker, Drive through Google's authorisation flow. A new permission needs a decision in `plan.md` first. (`Minimize_Permissions`, `Runtime_Permissions`)

**Every component sets `android:exported` explicitly**; only `MainActivity` is exported. (`Component_Export`)

**HTTPS only**, declared in a network security configuration that disallows cleartext traffic. (`Network_Security_Traffic`, `Network_Security_Configuration`)

**App data stays in internal storage**, and logs carry no account names, email addresses or tokens (see [messages.md](messages.md)). (`Sensitive_Data_Storage`, `Sensitive_Data_Logging`)

**No code loaded from outside the APK, no custom cryptography.** (`App_Bundles`, `Cryptographic_Algorithms`)

### Backup: a deliberate exception

Google asks apps to keep data across backups and device transfers (`App_Data_Backup`). Volti doesn't: `data_extraction_rules.xml` excludes everything from cloud backup and device-to-device transfer. Scores can be downloaded again and the folder grant can't move between devices anyway. Annotations are the planned exception (milestone 4 in `plan.md`), because they exist nowhere else.

## Not yet following this

- No network security configuration: the manifest has no `android:networkSecurityConfig`. Cleartext is already off by default for this `targetSdk`, so this is a declaration, not a hole.
- `StrictMode` isn't switched on in debug builds.
- Annotations are excluded from backup until milestone 4 decides how they are backed up.
