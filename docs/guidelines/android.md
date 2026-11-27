# Android design

The parts of Google's [Android design guidance](https://developer.android.com/design) and [adaptive app quality tiers](https://developer.android.com/docs/quality-guidelines/adaptive-app-quality) that apply to Volti, and how. Left out on purpose: widgets, notifications, camera, media playback, picture-in-picture, Wear, TV, XR. Volti has none of those.

The reference device is the Galaxy Tab S7 FE (Android 14, 1280×800 dp). The app must also work on a phone and in split screen, because a musician will open it there sooner or later.

## Windows and layout

**Any size, any orientation.** No `android:screenOrientation`, no `resizeableActivity="false"`. The app fills its window in portrait, landscape, split screen and freeform; it is never letterboxed. (Adaptive Tier 3, `Config_Changes`.)

**One pane, with one exception.** The reader needs the whole window, and other screens show one list, so there is no list-and-detail layout. Wide windows are handled by the maximum width below. The exception is an ensemble: at the expanded window width class (840 dp and wider, the tablet in landscape) its setlists get a narrow pane beside its scores; narrower, they are the top of the scores' list. A second pane is chosen by the window's width class (`LocalWindowInfo`'s size against 840 dp, the expanded breakpoint; or `currentWindowAdaptiveInfo().windowSizeClass` if that library comes in), never by the device: `deviceName()` is for wording only, and a tablet in split screen has a compact window.

**Nothing stretches to full width on a wide window.** Text fields, buttons, dialogues and settings rows sit in a column of at most 600 dp: `Modifier.widthIn(max = MaxContentWidth)` before `fillMaxSize()`. At body size that also keeps a line of text under about 75 characters, Google's limit for reading (`Line_Length`, 45 to 75). Two exceptions: the score uses all the space there is, and a search field spans the full-width list it filters. (Tier 2, `UI_Secondary_Elements`.)

**State survives every configuration change**: rotation, folding, resizing, split screen, and combinations of those. In the reader: the score, the page, the half-page position and the annotation tool. Elsewhere: typed text, the open folder, the search query and the scroll position. Use `rememberSaveable` or the ViewModel, never plain `remember`, for anything the musician did. (Tier 3, `Config_Changes`, `Config_Combinations`.)

**Same features in every orientation and window size.** Portrait and landscape, folded and unfolded, show the same actions; only the layout changes. (Core quality, `Display_State_Parity`.)

**Keep working when not focused.** In split screen, a sync keeps showing its progress while the other app has focus. (Tier 3, `Multi-Resume`.)

## System bars

**Edge to edge everywhere**, set once in `MainActivity` with `enableEdgeToEdge`. Backgrounds and the page surround draw under the bars; text and controls are inset with the `Scaffold` padding or `WindowInsets.safeDrawing`. Critical UI is never inside a display cutout.

**Hide the bars only in the reader.** Immersive mode is for when the benefit goes beyond screen space: on a stand, a stray tap near the edge must not leave the score. `StageMode` hides the bars and keeps the screen on, and a swipe from the edge shows them for a moment. The library, home and settings always show the bars and let the screen sleep.

**No tap or drag targets inside the system gesture insets** (`WindowInsets.systemGestures`). The system wins there: a control at the very edge of the screen becomes a Back gesture. In the reader the first edge swipe only shows the bars; a second one goes Back.

The one exception is annotating: a stroke that starts at the edge must draw, not leave the score. While annotating, the drawing area is excluded with `Modifier.systemGestureExclusion()`; Done and Back leave annotating instead.

## Back

**Back goes one level, always.** A `BackHandler` only handles state inside a screen: up one folder, clear the search, leave annotating. It never asks "are you sure?" and never blocks Back.

**Predictive back stays on.** The target SDK enables it by default. Use the system animations; no custom back transitions (they would cost battery for nothing).

## Touch, keyboard, pedal and stylus

**Every touch target is at least 48 × 48 dp**, also when the visible shape is smaller. Material components do this themselves; a custom `clickable` needs `Modifier.minimumInteractiveComponentSize()` or an explicit size. (Tier 2, `Touch_Targets`.)

**A page turn works without touch.** Bluetooth page-turn pedals are keyboards: they send `PageDown` / `PageUp` or the arrow keys. The reader turns forward on `PageDown`, `→`, `↓` and `Space`, back on `PageUp`, `←` and `↑`. One press is one turn: a key held down doesn't skip pages. This is the one keyboard flow that matters on a stand. (Tier 2, `Keyboard_Navigation`.)

**`Esc` does what Back does**: closes a dialogue, clears the search, leaves annotating. `MainActivity.onKeyDown` sends any Esc no one else took to the back dispatcher, so every `BackHandler` applies to Esc too. (Tier 2, `Keyboard_Exit`.)

**Text fields work with an external keyboard** and switch between it and the on-screen keyboard without a restart. `Enter` submits a single-field form or moves to the next field. (Tier 3, `Keyboard_Input`.)

**Stylus input is touch input**, and works by default. In annotation mode the stylus and the finger both draw (see milestone 4 in `plan.md`). Handwriting into text fields works on Android 14 without code; don't disable it.

**Mouse and trackpad**: click, scroll and select must work. Material components give hover states; a custom clickable gets one through `clickable`'s indication. No right-click menus: Volti has no context menus.

## Accessibility

**Text in `sp`**, never `dp` or pixels, so the system font size applies. No body text under 12 sp. Test with the largest font size: nothing is cut off, labels wrap.

**Contrast at least 4.5:1** for text and **3:1** for large text (18 sp, or 14 sp bold), icons, outlines and controls against their background. `readerColorsOn` already picks ink or cream by contrast; new colours in `ui/theme` are checked against both schemes.

**Every control has a label for TalkBack.** Icons and custom drawings get a `contentDescription` or an `onClickLabel`; decorative images get `contentDescription = null`. Selection is a semantic (`selected`, `Role.RadioButton`), not only a thicker border.

**No action only by gesture.** Every gesture has another route: the reader exposes next and previous page as accessibility actions (`customActions`) as well as the tap halves; anything removed by swipe has a button too.

**Colour is never the only signal.** A pen colour has a name; "new" and "changed" are words, not only a colour.

Check new screens with TalkBack and the Accessibility Scanner on the tablet.

## Settings

**Only what isn't decided better elsewhere.** No theme or font size setting: the app follows the system's dark theme and font scale. Frequent choices live next to what they affect (the annotation pens in the reader, "Your parts" in the ensemble), not in Settings.

**Switch for on/off**, radio buttons in a dialogue or subscreen for one-of-several, never a radio button for a yes/no.

**Labels are short and start with the important word**: `Crop margins`, `Half-page turns`. No generic verbs (`Set`, `Change`, `Manage`, `Use`), no negatives (`Don't…`, `Never…`). Supporting text only when the label alone doesn't explain it, and then it says what happens, not what the setting is.

**Defaults are the ones most musicians want and the cheapest on battery.** The screen is called `Settings`, never `Options` or `Preferences`.

**Text speaks to the musician as "you"**, as Google's guide asks: `Your parts`, `What you play`. Never "I", "me" or "my" in the interface, and don't mix the two on one screen.

## Colour and motion

**Volti's own colour scheme**, from the app icon, in a light and a dark version that follow the system. Every screen works in both. (Core quality, `Theme_Support`.) No dynamic colour: the reader's colours are tied to paper and ink, not to the wallpaper.

**No decorative motion.** A page turn is instant, a page that isn't changing costs no CPU (see Priorities in `plan.md`). The system's own transitions (predictive back, dialogues) are fine.

## Not yet following this

- Text in the first person, from before the "you" rule: `My parts` (`EnsemblesScreen.kt`, `SetlistScreens.kt`), `Mine`, `What I play` (`EnsemblesScreen.kt`, `SettingsScreen.kt`), "looks like my part", "my marks" and "Are these my parts?" (`EnsemblesScreen.kt`).
- Zooming in to annotate is only by gesture (two fingers). It is a help for the pen, which TalkBack users don't write with; a button would still be the rule.

Verified on the emulator only; check pedal, TalkBack and the edge stroke on the tablet.
