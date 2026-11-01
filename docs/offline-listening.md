# Offline listening

## Experience

Downloads is available from the navigation drawer and the catalog root. Save an entire show from its toolbar, or save individual tracks with their download arrows. Whole-show confirmation explains the approximate size and the current network preference.

The collection uses date tiles, venue names, expandable setlists, explicit progress, and storage totals. “Ready to play” includes shows with at least one saved track. “Play saved” queues only verified local files, in original setlist order, without filling gaps with streams. Removing a show also cancels its pending transfers; confirmation explains the storage and playback impact.

Unmetered downloads are the default, usually using Wi-Fi. This follows Android's metering classification rather than promising a strict network transport. Roaming is disabled. Changing the network preference restarts active files because Android does not support editing a submitted DownloadManager request. Completed tracks are preserved.

## Reliability

- Android DownloadManager owns transfers, including background scheduling and transient network retries.
- The app persists metadata, source URLs, ordering, destinations and transfer IDs before reporting downloads to the UI.
- Track IDs prevent duplicate queued or completed requests. New attempts use unique destination names.
- Destinations are saved before enqueue, so an interrupted enqueue can recover its system ID by matching the destination.
- Completed files are journaled and atomically moved into a separate app-owned `Music/saved` directory before removing their system transfer record. This prevents Android's seven-day cleanup of non-visible managed downloads from deleting the saved collection. Reconciliation recovers interruptions before or after the move.
- Startup and visible-screen reconciliation query system transfer state, recover missed completion broadcasts, and check local file availability and expected byte length.
- Playback resolves local files in MediaSession callbacks, independently of the online catalog. The data source also checks at file-open time so a track downloaded after queuing can play locally.
- Missing files and terminal download failures stay visible and retryable.
- Storage estimates reserve space for other queued files and a 32 MiB margin. Estimates assume up to 320 kbps.
- Completion processing uses BroadcastReceiver.goAsync; disk and download-manager work runs off the UI thread.
- Existing prototype records migrate null fields and show IDs that accidentally contain a track suffix. Records without source URLs can recover metadata when the show is online.
- A late metadata response cannot resurrect a removed legacy download: retry checks the original record/attempt under the repository lock.
- Downloaded audio and setlist metadata live in app-specific persistent storage. Artwork uses an app-provided fallback offline. Uninstalling the app removes downloads.
- Backup and device transfer exclude downloaded audio and installation-specific transfer records, preventing stale file paths and system job IDs from appearing on a different device.

## Design references

[Spotify offline listening](https://support.spotify.com/fm/article/listen-offline/) informed Wi-Fi defaults, visible state, and automatically using local tracks.
[Apple Music removal](https://support.apple.com/en-ie/102344) informed clearly separating removal of a device download from the online catalog.
[Android DownloadManager.Request](https://developer.android.com/reference/android/app/DownloadManager.Request) documents persistent app-specific destinations and metered/roaming restrictions.

## Validation

JVM regression tests cover recovery without broadcasts, missing system jobs, missing or truncated files, network wait states, unknown content lengths, completion gating, and setlist ordering. Instrumented tests use real DownloadManager transfers against an emulator-local HTTP server, including deduplication, HTTP failure/retry, and deletion. A second suite disables Wi-Fi and cellular and exercises real ExoPlayer playback through MediaSession, seeking, next track, activity recreation, JSON persistence and collection rendering. The current fixtures use an original generated stereo VBR MP3, testing the decoder used by the catalog rather than only uncompressed PCM. These synthetic tests do not measure the quality or seamless transitions of concert recordings.

The CI workflow builds a debug APK, runs tests and lint, and captures emulator screenshots. No release bundle is produced. CI uses the upstream API-35 build configuration; the original Mac checkout had separate API-36/version changes that this feature branch does not replace.

At commit `2cda126`, [run 41337447120](https://github.com/mikevoyt/RoboPhish/actions/runs/41337447120) passed all 38 mobile and 5 networking JVM tests, with zero lint errors (120 existing warnings). Android 15/API 35 and Android 16/API 36 each passed the real transfer test and nine offline instrumentation tests, including adoption-journal recovery and 200% text sizing. Android 8/API 26 passed the transfer test but rejected the harness's protected airplane-mode broadcast; a harness correction and additional credential-redirect tests are undergoing validation.

Android Studio also successfully synced and built the Mac checkout with API 36, and installed the debug app on the attached Pixel 9 Pro (API 37.1). Debug builds use a separate `.dev` application ID and **RoboPhish Dev** name, preserving the user's existing signed app and downloads. Physical playback is not yet verified: the screen mirror connected, but the phone was locked. See `AGENTS.md` for the working native Studio workflow when shell ADB cannot start its daemon.

The SDK audit found an unrelated credential-routing issue that needed containment before live network testing. Artwork now uses a client without API credentials. Provider credentials are attached only to each provider's exact HTTPS origin and API path on every network hop; HTTPS-to-HTTP redirects are disabled. Phish.net uses its supported `X-API-KEY` header rather than query parameters, and request logging excludes queries and response bodies. These changes do not remove embedded app credentials; migration to the keyless Phish.in API and a server-side Phish.net integration remain separate recommendations.

Before a release, additionally verify a physical device with a real show: airplane-mode cold launch, several consecutive MP3 tracks and transitions, Bluetooth/headset controls, background completion, reboot recovery, low-storage behavior, and TalkBack/large-text interaction. Synthetic fixtures and emulator checks do not establish gapless behavior or device-specific battery restrictions.
