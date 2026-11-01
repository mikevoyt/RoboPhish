#!/usr/bin/env bash
set -euo pipefail
capture_evidence() {
  timeout 20s adb logcat -d -s AndroidRuntime > android-crashes.txt || true
  timeout 20s adb pull /sdcard/Download/robophish-offline offline-screenshots || true
}
trap capture_evidence EXIT
adb shell mkdir -p /sdcard/Download/robophish-offline

./gradlew --no-daemon -Dorg.gradle.java.home="$JAVA_HOME" :mobile:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.bayapps.android.robophish.downloads.DownloadTransferTest
adb shell svc wifi disable
adb shell svc data disable
./gradlew --no-daemon -Dorg.gradle.java.home="$JAVA_HOME" :mobile:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.bayapps.android.robophish.downloads.OfflinePlaybackTest
adb shell test -f /sdcard/Download/robophish-offline/offline-ready.png
