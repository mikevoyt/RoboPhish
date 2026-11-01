# RoboPhish release notes for agents

## Android release signing

- Release bundles for `com.bayapps.android.robophish` must be signed with the repository-root `android.jks` keystore and alias `myandroidkey`.
- The expected upload-certificate SHA-1 fingerprint is `89:98:BE:7F:8E:4E:97:D1:F1:27:53:26:82:4E:0F:55:67:62:B9:E0`.
- Release credentials already exist in the developer's normal `~/.gradle/gradle.properties`. Never print, copy, overwrite, commit, or ask the user to recreate those secret values.
- Always use the normal Gradle user home for release work. Do **not** pass `-g`, set `GRADLE_USER_HOME`, or use a temporary Gradle home when running `signingReport`, `bundleRelease`, or another release task. Doing so bypasses the stored release credentials, and this project otherwise falls back to `keystore/debug.keystore`.
- The debug/fallback certificate SHA-1 is `0E:46:4C:B7:69:89:89:5E:75:62:EC:EC:F9:05:6F:99:22:36:92:B0`. Never upload an AAB with this fingerprint.
- Do not substitute `robophish.keystore`, `rotate_robophish.keystore`, or another similarly named key without explicit user direction and fingerprint verification.

## Release checklist

1. Confirm the latest version code in Play Console, then set a strictly higher `versionCode` in `mobile/mobile.gradle`. Do not infer that the last local value is unused.
2. Run `./gradlew --no-daemon :mobile:signingReport` and confirm the **release** variant resolves to `android.jks`, alias `myandroidkey`, and the expected SHA-1 above.
3. Run `./gradlew --no-daemon :mobile:bundleRelease` without overriding the Gradle user home.
4. Verify the certificate embedded in the finished artifact with `keytool -printcert -jarfile mobile/build/outputs/bundle/release/mobile-release.aab`. Its SHA-1 must exactly match the expected upload certificate.
5. Verify the generated release manifest has the intended version code and target SDK. The current API-36 configuration is in `mobile/mobile.gradle`.
6. Report that the AAB includes every current worktree change, including unrelated uncommitted edits, so it should be uploaded only after those changes are reviewed.

If any signing check resolves to the debug key or a different fingerprint, stop and diagnose the Gradle credential resolution. Do not hand off or upload that bundle.

## Attached-phone testing from Work Mode

- If shell ADB cannot bind its local daemon socket, do not conclude that phone testing is unavailable. Android Studio's native device tools can still reach the attached phone. This workflow successfully installed RoboPhish Dev on the Google Pixel 9 Pro on 2026-11-01.
- Use the native computer-use tool (`mcp__cua_repl`), selecting `cua.getApp("com.google.android.studio")`. After context compaction, restore its documentation with `cua.rewriteDocumentation()`. Inspect fresh accessibility state after each action; use screenshots for the Running Devices mirror.
- Open this repository in Android Studio, allow Gradle sync, select the `mobile` run configuration, confirm the **debug** build variant, select the attached Pixel, and use the normal Run action. Use Running Devices for interaction and Logcat for debugging. Do not use the IDE terminal to circumvent shell restrictions or change security settings.
- Debug builds use application ID `com.bayapps.android.robophish.dev` and label **RoboPhish Dev**, allowing installation alongside the signed production app. If an installation reports a signature mismatch, cancel the uninstall prompt; preserve the user's existing app and data.
- If the normal Run action remains disabled after project or configuration changes, use **Sync Project with Gradle Files**, wait for sync to finish, then Run again. This restored native build/install on the attached Pixel in this session.
- Synthetic offline instrumentation fixtures clear app state and are emulator-only. On a personal phone, test through the app UI with disposable downloads. Restore connectivity and stop test playback when finished.
- The user explicitly authorized installing/debugging on their attached phone and using the MacBook Pro microphone for this audio-enhancement investigation. Coordinate recording start/stop with test playback; do not record unrelated conversations. This authorization is specific to this task.
- Report separately what compiled, what automated tests passed, and what was actually verified on the physical phone. A failed shell ADB probe does not negate a successful native Android Studio installation.
