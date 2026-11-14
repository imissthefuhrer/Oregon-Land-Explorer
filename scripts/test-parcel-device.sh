#!/usr/bin/env bash
# Keep dependent shell operations in one process (the emulator action splits script lines).
set -euo pipefail
adb shell cmd connectivity airplane-mode enable
adb shell svc wifi disable
adb shell svc data disable
if [[ "${OLE_REUSE_BENCHMARK:-false}" != true ]]; then
  bash scripts/benchmark-parcels.sh
fi
adb shell mkdir -p /sdcard/Download/ole-evidence
trap 'adb logcat -d -b crash > output/emulator-crashes.txt || true; adb pull /sdcard/Download/ole-evidence/. output/emulator-screenshots/ || true' EXIT
gradle -p android --no-daemon :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.notClass=org.oregonlandexplorer.ParcelPerformanceTest,org.oregonlandexplorer.ParcelDatabaseTest,org.oregonlandexplorer.LocalTilesTest
adb pull /sdcard/Download/ole-evidence/. output/emulator-screenshots/
python scripts/summarize-parcel-performance.py
