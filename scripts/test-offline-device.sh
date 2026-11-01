#!/usr/bin/env bash
set -euo pipefail
if [[ "$(adb shell getprop ro.kernel.qemu | tr -d '\r')" != "1" ]]; then
  echo "Synthetic download tests require an emulator; use manual checks on a personal phone." >&2
  exit 1
fi
capture_evidence() {
  timeout 20s adb logcat -d -s AndroidRuntime > android-crashes.txt || true
  timeout 20s adb pull /sdcard/Download/robophish-offline offline-screenshots || true
}
trap capture_evidence EXIT
# API 36 can report boot completion before emulated external storage is mounted.
for attempt in $(seq 1 60); do
  if adb shell test -d /sdcard; then break; fi
  sleep 1
done
adb shell mkdir -p /sdcard/Download/robophish-offline

./gradlew --no-daemon -Dorg.gradle.java.home="$JAVA_HOME" :mobile:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.bayapps.android.robophish.downloads.DownloadTransferTest
adb shell svc wifi disable || true
adb shell svc data disable || true
api=$(adb shell getprop ro.build.version.sdk | tr -d '\r')
if (( api >= 30 )); then
  adb shell cmd connectivity airplane-mode enable
else
  # This branch runs only on a disposable emulator (guarded above). Older
  # images expose an Ethernet transport and restrict airplane-mode broadcasts
  # to system/root, so the ordinary shell radio toggles do not disconnect it.
  adb root
  adb wait-for-device
  adb shell svc wifi disable
  adb shell svc data disable
  adb shell settings put global airplane_mode_on 1
  adb shell am broadcast -a android.intent.action.AIRPLANE_MODE --ez state true
  adb emu gsm data off
  if adb shell ip link show eth0 >/dev/null 2>&1; then
    adb shell ip link set eth0 down
  fi
fi
adb shell dumpsys connectivity > offline-network-state.txt
grep -E 'NetworkAgentInfo|Active default network|VALIDATED' offline-network-state.txt || true
offline_test_classes=com.bayapps.android.robophish.downloads.OfflinePlaybackTest,com.bayapps.android.robophish.audio.AudiencePlaybackTest
./gradlew --no-daemon -Dorg.gradle.java.home="$JAVA_HOME" :mobile:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class="$offline_test_classes"
adb shell test -f /sdcard/Download/robophish-offline/offline-ready.png
