#!/usr/bin/env bash
# Run in ONE API-35 Pixel-2 Mesa emulator, offline. Keep installed old data during APK update.
set -euo pipefail
mkdir -p output/emulator-screenshots
trap 'adb pull /sdcard/Download/ole-evidence/. output/emulator-screenshots/ || true' EXIT
adb shell mkdir -p /sdcard/Download/ole-evidence
adb shell cmd connectivity airplane-mode enable
adb shell svc wifi disable
adb shell svc data disable

# Fail fast on the device's actual SQLite build before the long comparison.
# This sandbox is the disposable CI emulator; no physical-phone data is cleared.
adb install -r android/app/build/outputs/apk/debug/app-debug.apk
adb install -r android/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument -w -r -e class org.oregonlandexplorer.ParcelDatabaseTest org.oregonlandexplorer.expanded.test/androidx.test.runner.AndroidJUnitRunner | tee output/database-instrumentation.txt
grep -q 'OK (4 tests)' output/database-instrumentation.txt
adb uninstall org.oregonlandexplorer.expanded.test
adb uninstall org.oregonlandexplorer.expanded

cold_launches() {
  local variant="$1"
  for attempt in 1 2 3; do
    adb shell am force-stop org.oregonlandexplorer.expanded
    adb shell am start -W -n org.oregonlandexplorer.expanded/org.oregonlandexplorer.MainActivity > "output/$variant-cold-$attempt.txt"
    sleep 8
    adb shell dumpsys meminfo org.oregonlandexplorer.expanded > "output/$variant-cold-$attempt-memory.txt"
  done
}

adb install -r work/expanded/oregon-land-explorer-expanded.apk
cold_launches baseline
adb install -r work/baseline-source/android/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument -w -r -e class org.oregonlandexplorer.ParcelPerformanceTest -e variant baseline org.oregonlandexplorer.expanded.test/androidx.test.runner.AndroidJUnitRunner | tee output/baseline-instrumentation.txt
grep -q 'OK (1 test)' output/baseline-instrumentation.txt
adb pull /sdcard/Download/ole-evidence/. output/emulator-screenshots/
adb shell am force-stop org.oregonlandexplorer.expanded
adb uninstall org.oregonlandexplorer.expanded.test
# This MUST be an in-place update of the same signing identity, with old installed packs retained.
adb install -r android/app/build/outputs/apk/debug/app-debug.apk
cold_launches upgraded-legacy
adb install -r android/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument -w -r -e class org.oregonlandexplorer.ParcelPerformanceTest -e variant optimized org.oregonlandexplorer.expanded.test/androidx.test.runner.AndroidJUnitRunner | tee output/optimized-instrumentation.txt
grep -q 'OK (1 test)' output/optimized-instrumentation.txt
cold_launches optimized
adb shell dumpsys gfxinfo org.oregonlandexplorer.expanded framestats > output/optimized-gfx.txt
adb pull /sdcard/Download/ole-evidence/. output/emulator-screenshots/
