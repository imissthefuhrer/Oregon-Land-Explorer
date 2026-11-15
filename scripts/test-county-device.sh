#!/usr/bin/env bash
set -euo pipefail
mkdir -p output/emulator-screenshots
adb shell mkdir -p /sdcard/Download/ole-evidence
trap 'adb logcat -d -b crash > output/county-emulator-crashes.txt || true; adb pull /sdcard/Download/ole-evidence/. output/emulator-screenshots/ || true' EXIT
adb shell cmd connectivity airplane-mode enable
adb shell svc wifi disable
adb shell svc data disable
test_app=android/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
runner=org.oregonlandexplorer.expanded.test/androidx.test.runner.AndroidJUnitRunner
instrument() {
  local test_class="$1" result_file="$2" variant="${3:-county}"
  adb shell am instrument -w -r -e class "$test_class" -e variant "$variant" "$runner" | tee "$result_file"
  grep -q 'OK (' "$result_file"
  ! grep -q 'FAILURES\|INSTRUMENTATION_FAILED\|Process crashed' "$result_file"
}
# Run the unchanged v0.3.2 app on the same device with the same original-region benchmark.
adb install -r work/v032/app-debug.apk
adb install -r "$test_app"
instrument org.oregonlandexplorer.ParcelPerformanceTest output/v032-instrumentation.txt v032
adb shell am force-stop org.oregonlandexplorer.expanded
# Install over existing data with the same signing identity; never clear app storage here.
adb install -r android/app/build/outputs/apk/debug/app-debug.apk
for attempt in 1 2 3; do
  adb shell am force-stop org.oregonlandexplorer.expanded
  adb shell am start -W -n org.oregonlandexplorer.expanded/org.oregonlandexplorer.MainActivity > "output/county-before-import-cold-$attempt.txt"
  sleep 3
done
instrument org.oregonlandexplorer.ParcelDatabaseTest,org.oregonlandexplorer.LocalTilesTest output/county-database-instrumentation.txt
instrument org.oregonlandexplorer.CountyPerformanceTest output/county-instrumentation.txt
for attempt in 1 2 3; do
  adb shell am force-stop org.oregonlandexplorer.expanded
  adb shell am start -W -n org.oregonlandexplorer.expanded/org.oregonlandexplorer.MainActivity > "output/county-installed-cold-$attempt.txt"
  sleep 5
  adb shell dumpsys meminfo org.oregonlandexplorer.expanded > "output/county-installed-cold-$attempt-memory.txt"
done
adb pull /sdcard/Download/ole-evidence/. output/emulator-screenshots/
python scripts/summarize-county-performance.py
