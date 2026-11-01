# Offline listening

## Experience

Downloads is available from the navigation drawer and the catalog root. Save an entire show from its toolbar, or save individual tracks with their download arrows. Whole-show confirmation explains the approximate size and the current network preference.

The collection uses date tiles, venue names, expandable setlists, explicit progress, and storage totals. “Ready to play” includes shows with at least one saved track. “Play saved” queues only verified local files, in original setlist order, without filling gaps with streams. Removing a show also cancels its pending transfers; confirmation explains the storage and playback impact.

Wi-Fi/unmetered downloads are the default. Roaming is disabled. Changing the network preference restarts active files because Android does not support editing a submitted DownloadManager request. Completed tracks are preserved.

## Reliability

- Android DownloadManager owns transfers, including background scheduling and transient network retries.
- The app persists metadata, source URLs, ordering, destinations and transfer IDs before reporting downloads to the UI.
- Track IDs prevent duplicate queued or completed requests. New attempts use unique destination names.
- Destinations are saved before enqueue, so an interrupted enqueue can recover its system ID by matching the destination.
- Startup and visible-screen reconciliation query system transfer state, recover missed completion broadcasts, and check local file availability and expected byte length.
- Playback resolves local files in MediaSession callbacks, independently of the online catalog. The data source also checks at file-open time so a track downloaded after queuing can play locally.
- Missing files and terminal download failures stay visible and retryable.
- Storage estimates reserve space for other queued files and a 32 MiB margin. Estimates assume up to 320 kbps.
- Completion processing uses BroadcastReceiver.goAsync; disk and download-manager work runs off the UI thread.
- Existing prototype records migrate null fields and show IDs that accidentally contain a track suffix. Records without source URLs can recover metadata when the show is online.
- Downloaded audio and setlist metadata live in app-specific persistent storage. Artwork uses an app-provided fallback offline. Uninstalling the app removes downloads.

## Design references

[Spotify offline listening](https://support.spotify.com/fm/article/listen-offline/) informed Wi-Fi defaults, visible state, and automatically using local tracks.
[Apple Music removal](https://support.apple.com/en-ie/102344) informed clearly separating removal of a device download from the online catalog.
[Android DownloadManager.Request](https://developer.android.com/reference/android/app/DownloadManager.Request) documents persistent app-specific destinations and metered/roaming restrictions.

## Validation

JVM regression tests cover recovery without broadcasts, missing system jobs, missing or truncated files, network wait states, unknown content lengths, completion gating, and setlist ordering. Instrumented tests use real DownloadManager transfers against an emulator-local HTTP server, including deduplication, HTTP failure/retry, and deletion. A second suite disables Wi-Fi and cellular and exercises real ExoPlayer playback through MediaSession, seeking, next track, activity recreation, JSON persistence and collection rendering.

The CI workflow builds a debug APK, runs tests and lint, and captures emulator screenshots. No release bundle is produced. CI uses the upstream API-35 build configuration; the original Mac checkout had separate API-36/version changes that this feature branch does not replace.

Before a release, additionally verify a physical device with a real show: airplane-mode cold launch, several consecutive MP3 tracks and transitions, Bluetooth/headset controls, background completion, reboot recovery, low-storage behavior, and TalkBack/large-text interaction. Synthetic fixtures and emulator checks do not establish gapless behavior or device-specific battery restrictions.
