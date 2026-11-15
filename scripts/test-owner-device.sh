#!/usr/bin/env bash
set -euo pipefail
mkdir -p output/emulator-screenshots
adb shell mkdir -p /sdcard/Download/ole-evidence
trap 'adb logcat -d > output/owner-logcat.txt || true; adb logcat -d -b crash > output/owner-crashes.txt || true; adb pull /sdcard/Download/ole-evidence/. output/emulator-screenshots/ || true' EXIT
adb shell cmd connectivity airplane-mode enable
adb shell svc wifi disable
adb shell svc data disable
runner=org.oregonlandexplorer.expanded.test/androidx.test.runner.AndroidJUnitRunner
instrument() {
  adb shell am instrument -w -r -e class "$1" "$runner" | tee "$2"
  grep -q 'OK (' "$2"
  ! grep -q 'FAILURES\|INSTRUMENTATION_FAILED\|Process crashed' "$2"
}
# The same signing key must permit an upgrade without clearing installed maps.
adb install -r work/v04/oregon-land-explorer-v0.4.0.apk
adb install -r android/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
instrument 'org.oregonlandexplorer.OwnerWorkflowTest#prepareV04InstalledData' output/owner-before-upgrade.txt
adb shell am force-stop org.oregonlandexplorer.expanded
adb install -r android/app/build/outputs/apk/debug/app-debug.apk
instrument 'org.oregonlandexplorer.OwnerWorkflowTest#countyOwnershipOfflineUpdateRollbackAndNavigation' output/owner-instrumentation.txt
# Reuse the original real owner records and GPS/aerial/browser-picker regressions.
instrument 'org.oregonlandexplorer.ParcelDatabaseTest,org.oregonlandexplorer.LocalTilesTest' output/owner-existing-database-tests.txt
# County packages remain installed; disable them to run the original corridor UI oracle.
instrument 'org.oregonlandexplorer.OwnerRegressionSetupTest' output/owner-regression-setup.txt
instrument 'org.oregonlandexplorer.RegionImportTest' output/owner-existing-ui-tests.txt
for attempt in 1 2 3; do
  adb shell am force-stop org.oregonlandexplorer.expanded
  adb shell am start -W -n org.oregonlandexplorer.expanded/org.oregonlandexplorer.MainActivity > "output/owner-cold-$attempt.txt"
  sleep 3
done
