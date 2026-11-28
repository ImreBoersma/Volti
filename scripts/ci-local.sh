#!/bin/sh
# What CI does (.github/workflows/ci.yml), on this Mac: unit tests and lint, then the instrumented tests against the
# minified build on an API 34 tablet emulator, as CI's. Run by the pre-push hook (scripts/install-hooks.sh); keep the
# two in step when ci.yml changes.
#
# The emulator is the host's architecture (arm64 on Apple silicon), CI's is x86_64: the same Android, not the same
# binary. It is started without a window and stopped again, unless one called volti-ci was running already.
set -e
cd "$(dirname "$0")/.."
SDK="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
ADB="$SDK/platform-tools/adb"
AVD=volti-ci
PORT=5580
SERIAL="emulator-$PORT"
ABI=$([ "$(uname -m)" = arm64 ] && echo arm64-v8a || echo x86_64)
IMAGE="system-images;android-34;google_apis;$ABI"

echo "== Unit tests and lint"
./gradlew -q test lintDebug

if ! "$SDK/emulator/emulator" -list-avds | grep -qx "$AVD"; then
    echo "== Creating the $AVD emulator (once)"
    yes | "$SDK/cmdline-tools/latest/bin/sdkmanager" "$IMAGE" >/dev/null
    # As CI: a tablet with a navigation bar, 2560x1600 like the reference tablet.
    echo no | "$SDK/cmdline-tools/latest/bin/avdmanager" create avd -n "$AVD" -d pixel_tablet -k "$IMAGE" >/dev/null
fi

started=""
if ! "$ADB" -s "$SERIAL" get-state >/dev/null 2>&1; then
    echo "== Starting the $AVD emulator"
    "$SDK/emulator/emulator" -avd "$AVD" -port "$PORT" -no-window -no-snapshot -no-boot-anim -noaudio -memory 4096 \
        -gpu swiftshader_indirect >/dev/null 2>&1 &
    started=$!
    trap '"$ADB" -s "$SERIAL" emu kill >/dev/null 2>&1 || kill $started 2>/dev/null' EXIT
fi
"$ADB" -s "$SERIAL" wait-for-device
until [ "$("$ADB" -s "$SERIAL" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = 1 ]; do sleep 2; done

# As CI: no animations, and the full-screen notice seen already (it takes the focus from key tests).
for scale in window_animation_scale transition_animation_scale animator_duration_scale; do
    "$ADB" -s "$SERIAL" shell settings put global "$scale" 0
done
"$ADB" -s "$SERIAL" shell settings put secure immersive_mode_confirmations confirmed
# As CI: no error dialogs, so a launcher that stops responding doesn't take the focus.
"$ADB" -s "$SERIAL" shell settings put global hide_error_dialogs 1

echo "== Instrumented tests"
if ! ANDROID_SERIAL="$SERIAL" ./gradlew -q connectedMinifiedAndroidTest; then
    # As CI: what had the focus, what the screen showed, and the log.
    d=app/build/reports/androidTests/connected
    mkdir -p "$d"
    "$ADB" -s "$SERIAL" shell dumpsys window | grep -E 'mCurrentFocus|mFocusedApp' > "$d/focus.txt"
    "$ADB" -s "$SERIAL" exec-out screencap -p > "$d/screen.png"
    "$ADB" -s "$SERIAL" logcat -d > "$d/logcat.txt"
    echo "Instrumented tests failed: $d/minified/index.html (and focus.txt, screen.png, logcat.txt)"
    exit 1
fi
echo "== All passed"
