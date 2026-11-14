#!/usr/bin/env bash
# Collect screenshots while the emulator is alive, even when instrumentation fails.
set -uo pipefail
status=0
(cd android && gradle --no-daemon :app:connectedDebugAndroidTest) || status=$?
mkdir -p output/emulator-screenshots
adb pull /sdcard/Download/ole-evidence/. output/emulator-screenshots/ || true
adb shell dumpsys meminfo org.oregonlandexplorer.expanded > output/memory.txt || true
exit "$status"
