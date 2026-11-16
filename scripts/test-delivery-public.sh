#!/usr/bin/env bash
set -euo pipefail
mkdir -p output/public-evidence
trap 'adb logcat -d -b crash > output/public-crashes.txt || true; adb pull /sdcard/Download/ole-evidence/. output/public-evidence/ || true' EXIT
adb install -r android/app/build/outputs/apk/debug/app-debug.apk
adb install -r android/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell cmd connectivity airplane-mode enable
adb shell svc wifi disable
adb shell svc data disable
adb shell am instrument -w -r -e class org.oregonlandexplorer.ParcelDatabaseTest,org.oregonlandexplorer.LocalTilesTest,org.oregonlandexplorer.RegionImportTest org.oregonlandexplorer.expanded.test/androidx.test.runner.AndroidJUnitRunner | tee output/public-delivery-tests.txt
grep -q 'OK (' output/public-delivery-tests.txt
! grep -q 'FAILURES\|INSTRUMENTATION_FAILED\|Process crashed' output/public-delivery-tests.txt
