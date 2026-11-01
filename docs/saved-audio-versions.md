# Saved audio versions

Audience audio → Saved versions can process a completed download in the background and keep a separate lossless copy. Originals remain the default and are never rewritten. Choosing Enhanced changes the preference for future play requests; **Play selected version** explicitly starts that version. Items already queued retain the version selected when that queue was created. Finishing a job never interrupts or switches current playback.

The current algorithm is experimental adaptive mastering. When it cannot identify a sufficiently persistent broad resonance, analysis finishes with **Original retained** and creates no duplicate WAV. The screen shows that outcome. A saved file is not evidence of improved sound, and soundboard-quality restoration has not been established. The [research method and results](../research/audio/offline_restoration/METHOD.md) describe the development failures, corpus separation, measurements and listening protocol.

## Processing and storage

- WorkManager owns durable, cancellable jobs. One render runs at a time at background thread priority; battery and storage constraints can delay it. Queued, analyzing, processing, ready, failed and cancelled states survive recreation. Interrupted workers restart from the original; there is no partial-file playback.
- The local decoder makes a full streaming analysis pass and, only if a correction is proposed, a second render pass. Abstention is recorded without writing a copy. No request, model load, hash or analysis is inserted into the playback callback. Selecting/starting the original never waits for a render.
- Outputs are PCM16 WAV files at the original sample rate and channel count, with exact decoded frame count. Supported inputs are mono/stereo at 22.05–96 kHz. WAV avoids another lossy encode but uses substantially more storage: about 10.6 MB/minute for stereo 44.1 kHz. The UI shows estimates, actual usage and removal controls.
- The writer checks free space before and during rendering, leaves a 32 MiB margin, enforces the RIFF size limit, flushes the completed file and only then publishes it through an atomic rename and durable journal update. A partial file has no usable WAV header.
- Source size, modification time and SHA-256 are checked across both passes. New integer clipping or unequal decode/render frame counts rejects the result. Estimated MP3 container duration is used for progress only; VBR estimates cannot reliably detect truncation. The download subsystem verifies a known source byte count when available.
- Every attempt has a distinct generation and file name. Cancel/remove invalidates it before stopping work, so a late worker cannot resurrect a deleted copy. Removal of an original also cleans up its derived copy. Failed filesystem deletion keeps a removable record instead of silently leaking storage.
- Backups and device transfer exclude generated audio, installation-specific records and WorkManager's job database.

## Playback behavior

The service resolves only complete, explicitly selected copies with matching source identity and algorithm version. Otherwise it uses the original. Existing live tone processing is bypassed for a saved enhanced source, and its live comparison button is disabled to avoid a misleading comparison. Use Saved versions to choose the original recording.

A queued copy can disappear after selection. A playback error for that copy rebuilds an original MediaItem, preserving the queue and requested time position. It resets the extractor and MIME hint; it never applies a WAV byte offset to an MP3. Missing originals still report an error rather than starting a network transfer in an offline request.

Saved-copy processing and playback are local. This does not add remote Cast playback of app-private WAV files. Rendering progress and file creation do not prove subjective improvement, zero battery cost, or unchanged startup latency on every device.

## Verification

The JVM suites cover overlap/add timing, arbitrary decoder chunking, source-rate confidence, stereo/phase invariants, abstention, clipping, WAV integrity, storage exhaustion, RIFF limits and gapless trimming. Android tests cover actual local decode/render/playback, default-original selection, explicit version choice, recreation, original hashes, cancellation, stale publication, missing-copy recovery and a VBR file without a seek header.

Use a disposable emulator for synthetic tests; never run these fixtures against personal phone data. `scripts/test-offline-device.sh` guards that requirement and disconnects the emulator for playback checks. It leaves the emulator offline. Use a fresh emulator or restore its connectivity after testing.

Run the following from the repository root with JDK 17, Android SDK platform 35, Android SDK command-line/platform tools, and an emulator installed. Set `JAVA_HOME` to your JDK 17 directory, configure the SDK through `ANDROID_HOME` or an existing `local.properties`, and put `adb` on `PATH`. The explicit Gradle property below overrides this branch's machine-specific Homebrew JDK path. The device script also reads `JAVA_HOME` and uses GNU `timeout` to collect evidence; on macOS, provide that command on `PATH` (for example, Coreutils' `gnubin` directory). Initial dependency resolution requires network access.

```sh
./gradlew --no-daemon -Dorg.gradle.java.home="$JAVA_HOME" \
  :mobile:assembleDebug :mobile:assembleDebugAndroidTest \
  :mobile:testDebugUnitTest :networking:test :mobile:lintDebug
# Start exactly one disposable emulator and disconnect other devices before this
# script: its adb calls and Gradle's connected tests must select the same device.
adb devices -l
bash scripts/test-offline-device.sh
```

## Install the debug preview

The APK is `mobile/build/outputs/apk/debug/mobile-debug.apk`, labelled **RoboPhish Dev**, application ID `com.bayapps.android.robophish.dev`, version **2.2.3-dev / code 42**. It can coexist with the production app, `com.bayapps.android.robophish`. This branch does not change release signing or the production app's version.

For a fresh Dev installation, or an update from a compatible Dev version no higher than 42, explicitly select the intended device:

```sh
adb -s DEVICE_SERIAL install -r mobile/build/outputs/apk/debug/mobile-debug.apk
```

As of 2026-11-01, main builds Dev **code 46** with the same application ID. A normal update from that version to this preview is rejected as a downgrade. To deliberately install this **debuggable** preview over Dev46 while retaining installed app data, use:

```sh
adb -s DEVICE_SERIAL install -r -d mobile/build/outputs/apk/debug/mobile-debug.apk
```

`-r` replaces the app while retaining data; `-d` allows a version-code downgrade for a debuggable package. Both still require a matching signing certificate. If Android reports a signature mismatch or refuses the downgrade, stop and diagnose it; cancel any uninstall prompt and preserve the existing app and data. Data retention is not proof that every newer feature's stored state is compatible with an older preview. The synthetic test script must still run only on a disposable emulator; use ordinary app UI checks on a personal phone.

The [research reproduction guide](../research/audio/offline_restoration/REPRODUCING.md) separately documents desktop environments, source hashes, frozen scripts and model prerequisites. Its research models are not included in this APK.

This feature is a separate branch and PR stacked on the existing audience-audio PR (#56), which itself depends on offline downloads (#55). The separate artwork fix is not included in this diff.
