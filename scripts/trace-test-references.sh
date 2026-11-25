#!/bin/sh
# Writes app/src/minified/keepRules/test-references.keep: what the instrumented tests use from the app and its
# libraries (see trace-test-references.py). The minified build the tests run against keeps exactly that, so R8
# still shrinks, merges and inlines the rest as in release.
#
# Run it again when a test starts using something new: the minified test run then fails with NoSuchMethodError
# or NoClassDefFoundError.
set -e
cd "$(dirname "$0")/.."
SDK="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
DEXDUMP="$(ls "$SDK"/build-tools/*/dexdump | sort -V | tail -1)"
./gradlew -q :app:assembleMinifiedAndroidTest
python3 scripts/trace-test-references.py "$DEXDUMP" \
  app/build/outputs/apk/androidTest/minified/app-minified-androidTest.apk \
  app/src/minified/keepRules/test-references.keep
