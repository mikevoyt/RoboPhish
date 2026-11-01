# RoboPhish platform and architecture review

Audit date: November 1, 2026. This is a recommendation-only review; it changes no production code or dependencies.

RoboPhish already has the right foundation: Kotlin, Media3 ExoPlayer, a `MediaLibraryService`, OkHttp/Retrofit, and Moshi. Keep those foundations. The highest-value work is to close an authorization-header leak, make the release toolchain supported, and make playback and library state survive connectivity and process changes. A wholesale Compose, dependency-injection, or native-audio rewrite would consume time without addressing those problems.

## Scope and evidence

The production baseline is `main` commit `520bf958533e052bc0c362478c1fd4851af6714c`. Source was reviewed in an isolated checkout at `/tmp/robophish-platform-review`, exported from that commit because the original checkout's Git metadata is read-only. The audit branch is `codex/platform-review`. No files in the main feature checkout or the audio research checkout were changed by this audit.

The local developer checkout contains a separate, uncommitted SDK 36/toolchain change. Offline work in [PR #55](https://github.com/mikevoyt/RoboPhish/pull/55) and audio work in [PR #56](https://github.com/mikevoyt/RoboPhish/pull/56) are concurrent. Findings below describe the baseline unless explicitly marked otherwise. Those branches must be rechecked at their final heads; this document is not a certification of either feature.

Evidence is static source inspection plus primary documentation and upstream source. This audit did not run an emulator, a release build, device playback, vulnerability exploitation, or a dependency vulnerability scan. Effort estimates are engineering days for someone familiar with Android, including targeted tests, and are estimates rather than commitments.

## SDK and dependency inventory

| Component | Upstream main | Local developer change | Recommendation |
|---|---|---|---|
| Android SDK | compile 35 / target 35 / min 26 | compile 36 / target 36 / min 26 | Promote and validate the API 36 change for the next phone release. Retain min 26 unless device data justifies changing it. |
| Android Gradle plugin | 8.2.1 | 8.9.1 | The local version is the documented minimum for API 36. Upstream 8.2.1 is below the documented API 35 minimum, 8.6.0. |
| Gradle wrapper / Java | 8.5 / Java 17 | 8.11.1 / Java 17 | 8.11.1 matches AGP 8.9. Keep a reproducible Java toolchain rather than a machine-specific path. |
| Kotlin plugin | 1.9.22 | unchanged | Stage a Kotlin 2.x migration alongside the next Media3 update; do not change all libraries in the release-compliance patch. |
| Media3 exoplayer/session/ui | 1.3.1 | unchanged | Upgrade together in a separate tested change. Official stable is 1.11.1; 1.12 is currently alpha. |
| Coroutines | 1.8.1 | unchanged | Update with Kotlin and lifecycle libraries; preserve cancellation throughout repositories. |
| Android Views | Material 1.12.0, AppCompat 1.7.0, Core 1.13.1, ViewPager2 1.1.0 | feature-specific lifecycle dependency in PR #55 | Views remain suitable. Use ViewModel and lifecycle-aware state collection before considering UI toolkit migration. |
| Networking | OkHttp 4.12.0, Retrofit 2.11.0, Okio 3.9.0 | unchanged | Keep the stack. Fix client/auth boundaries first. Retrofit 3.0.0 is a documented upgrade candidate; assess OkHttp and its test server together. |
| Serialization | Moshi 1.15.1; kapt code generation | unchanged | Keep typed DTOs; migrate code generation to KSP when the Kotlin upgrade is tested. Avoid a simultaneous serialization rewrite. |
| Images | Coil 2.6.0 | unchanged | Fix credential isolation immediately. Coil 3.6.3 is available, but its package/network-module migration is a separate maintenance change. |
| Tests | Mobile: JUnit 4, Mockito 3.2.0, old mockito-kotlin group. Networking: JUnit Platform/Jupiter with `kotlin-test-junit` | PR #55 fixes networking to `kotlin-test-junit5` and makes the date test independent of the CI host timezone | Verify actual executed test counts. Modernize Mockito when touched; mixed JUnit versions across independent modules need not block shipping. |

Build sources: `mobile/mobile.gradle`, `networking/networking.gradle.kts`, `buildSrc/buildSrc.gradle.kts`, and `gradle/wrapper/gradle-wrapper.properties`.

Google Play requires API 36 for new phone apps and updates from August 31, 2026, with extensions only through November 1. API 35 remains sufficient for the existing-app availability rule; these are different requirements. Confirm the app's actual Play Console status before release. [Play policy](https://developer.android.com/google/play/requirements/target-sdk)

The documented API/AGP/Gradle compatibility tables support the local API 36 / AGP 8.9.1 / Gradle 8.11.1 combination. A reasonable subsequent **candidate**, not a configuration validated by this audit, is AGP 8.13.2 + Gradle 8.13 + Kotlin 2.3.21 + JDK 17. It avoids simultaneously adopting AGP 9's build changes while updating Media3. Evaluate a current stable AGP 9.x cohort separately after that baseline is green. [AGP compatibility](https://developer.android.com/build/releases/about-agp), [AGP 8.13](https://developer.android.com/build/releases/agp-8-13-0-release-notes), [Kotlin compatibility](https://kotlinlang.org/docs/gradle-configure-project.html)

Media3's recent releases include MP3 timing/gapless and offload fixes relevant to continuous concerts. Its 1.11 line also updates its Kotlin baseline to 2.2.0. Test the complete player and any custom audio processor against the chosen version; do not assume the large jump from 1.3.1 is drop-in. [Media3 releases](https://developer.android.com/jetpack/androidx/releases/media3)

## P0: stop sending the Phish.in bearer to artwork hosts

This was the most concrete urgent baseline finding. Containment is implemented in [PR #55 at `e6ebc37`](https://github.com/mikevoyt/RoboPhish/commit/e6ebc37): Coil uses an unauthenticated client; network credentials are restricted to HTTPS, exact host/port, and API path; Phish.net uses `X-API-KEY`; and fetch diagnostics are sanitized. The expanded networking suite, including redirect/authentication checks, passes in the later [offline run at `2f693153`](https://github.com/mikevoyt/RoboPhish/actions/runs/41341203053). The baseline evidence and acceptance criteria below remain useful for review; this containment does not remove secrets embedded in the APK.

- [`ServiceLocator.kt:48–53`](https://github.com/mikevoyt/RoboPhish/blob/520bf958533e052bc0c362478c1fd4851af6714c/mobile/src/main/java/com/bayapps/android/robophish/ServiceLocator.kt#L48) adds `PhishinAuthInterceptor` as a **network interceptor**.
- [`ServiceLocator.kt:79–82`](https://github.com/mikevoyt/RoboPhish/blob/520bf958533e052bc0c362478c1fd4851af6714c/mobile/src/main/java/com/bayapps/android/robophish/ServiceLocator.kt#L79) gives that same authenticated client to Coil.
- [`PhishinAuthInterceptor.kt`](https://github.com/mikevoyt/RoboPhish/blob/520bf958533e052bc0c362478c1fd4851af6714c/networking/src/main/kotlin/robophish/phishin/PhishinAuthInterceptor.kt) unconditionally attaches `Authorization` to each request. It checks neither host nor scheme nor path.
- `model/PhishProviderSource.kt` prefetches third-party Imgur/Flickr artwork through that loader.

The resulting request construction sends the bearer to those third parties. Because it is a network interceptor, it can also reattach the header on a redirected request after OkHttp has stripped a cross-origin authorization header. This is a source-confirmed exposure path, not a claim that a third party has abused the credential.

**Containment criteria:** use `okHttpNoAuthClient` for Coil; restrict the auth interceptor to exact HTTPS origin `phish.in:443` and the required `/api/v1/` path boundary; fail closed on cleartext/downgrade credential forwarding. Keep downloads, MP3/CDN requests, and arbitrary artwork on explicitly unauthenticated clients. Avoid suffix-host checks and broad `/api` substring matching. Do not forward a caller-provided bearer to an unrelated host.

**Tests:** use MockWebServer with fake credentials and test TLS/DNS routing to verify authorized API requests, unrelated hosts, subdomain lookalikes, non-API paths, port/scheme changes, redirects from API to artwork/CDN, and same-origin redirects. Assert the destination receives no credential. A loader wiring test should prove artwork uses the no-auth client. Review provider-side key replacement with the owner after the exposure is contained; do not print or rotate credentials as part of this audit. Estimated effort: 1–2 days, low implementation risk.

## API migration can remove one key entirely

The current `PhishinService` is tied to v1 `years`, `years/{year}`, and `shows/{id}`. Public v2 can serve RoboPhish's catalog/playback needs without a shared bearer:

- Phish.in's current upstream [`ApiV2::Base`](https://github.com/jcraigk/phishin/blob/53b48b232e2f85f2b4d2c5f03f30807c7f77fbbe/app/api/api_v2/base.rb) has API-key enforcement disabled.
- Its [`years`](https://github.com/jcraigk/phishin/blob/53b48b232e2f85f2b4d2c5f03f30807c7f77fbbe/app/api/api_v2/years.rb), [`shows`](https://github.com/jcraigk/phishin/blob/53b48b232e2f85f2b4d2c5f03f30807c7f77fbbe/app/api/api_v2/shows.rb), and [`tracks`](https://github.com/jcraigk/phishin/blob/53b48b232e2f85f2b4d2c5f03f30807c7f77fbbe/app/api/api_v2/tracks.rb) read endpoints have no per-request login requirement. Account mutation/admin endpoints are a different scope.
- The concurrent audio study successfully fetched show metadata and twelve source tracks without keys in a [GitHub Actions run](https://github.com/mikevoyt/RoboPhish/actions/runs/41334959153). That is corroborating runtime evidence from the other workstream, not a request performed by this audit.

Build a v2 adapter behind the existing repository contract. It must handle paginated `shows?year=...`, date-based show detail, `mp3_url`, missing audio, tags, real artwork, and explicit duration units. Preserve stable track/show identifiers already stored by downloads and queues. Test old cached data migration, era ranges, all pages, 404/429/5xx, nullable artwork/audio, and partial shows. Once all v1 consumers are gone, remove the Phish.in key build plugin and interceptor. Do not simply delete auth while still calling v1. [Provider API entry point](https://phish.in/api-docs)

Phish.net is a separate provider. The baseline `MediaBrowserFragment.fetchJson` puts `apikey` in a query string and logs the full URL in debug; PR #55 at `e6ebc37` replaces that with `X-API-KEY` and sanitized fetch diagnostics. That reduces accidental exposure but does **not** make a key embedded in an APK secret. The provider's current security addendum, effective November 1, 2026 with an existing-holder compliance date of May 1, 2027, says keys must not live in client-side code. A narrow caching backend or another provider-approved access model remains a priority after containment, alongside the keyless Phish.in v2 migration. Do not introduce a generic open proxy: limit routes/parameters, rate-limit it, and keep provider credentials server-side. [Phish.net authentication](https://docs.phish.net/about-the-api), [security addendum](https://docs.phish.net/security-addendum)

## Playback reliability: one owner, durable state

`MusicService` correctly uses `MediaLibraryService`, requests audio focus through ExoPlayer's audio attributes, and enables becoming-noisy handling. Preserve those choices.

The service also creates `PlayerNotificationManager` and manually manages foreground status. Media3's service already owns those responsibilities. Consolidate notification and foreground lifecycle around the service, customizing metadata/buttons or a `MediaNotification.Provider` only where necessary. Verify paused/failed playback can leave the foreground correctly and that there is exactly one media notification. [Background playback](https://developer.android.com/media/media3/session/background-playback)

`onPlaybackResumption` currently reconstructs only the live player's in-memory queue. After process death that queue is empty. Persist stable media IDs, current index/position, repeat/shuffle state, and minimal display metadata at transitions and bounded intervals. Resolve each item through one service-owned repository: verified local download first, network second when allowed. Persist identities rather than transient cache paths or expiring URLs. Keep restore failures understandable when downloaded files were removed.

`onGetChildren` ignores pagination; `onGetItem` does not correctly resolve all browsable IDs; search reports success but returns no results. Implement the browse contract deliberately, including missing/malformed IDs and cold-start item resolution. `MusicProvider` indexes ID hierarchy segments without validation, and upstream nullable track lookup can reach a `ConcurrentHashMap` null-key failure. PR #55 addresses several lookup/offline paths; recheck the final service contract after integration.

The exported service has no explicit controller policy and accepts media items with supplied URIs. Review available commands by controller and resolve untrusted media IDs to trusted catalog entries instead of accepting arbitrary `file:`, `content:`, or network URIs. Preserve valid system, Bluetooth, and Android Auto connections; do not equate every external controller with an attacker or block all of them.

The manifest has legacy Auto-related metadata but only the Media3 library-service action. Add the platform `android.media.browse.MediaBrowserService` action and current Auto discovery metadata if Auto support is intended, then test with Desktop Head Unit and real hardware. Verify browse pagination, queue order, voice search, unavailable downloads, and reconnect after process death. [Media library interoperability](https://developer.android.com/media/media3/session/serve-content), [Android Auto media support](https://developer.android.com/training/cars/media)

For concert segues, prioritize continuous queue transitions and accurate MP3 delay/padding over crossfade. Measure actual boundaries with adjacent tracks from the same recording. Evaluate bounded next-item preloading after measuring rebuffering. Separately test focus loss, calls/navigation prompts, wired disconnect, Bluetooth route changes, noisy handling, screen-off/Doze, and DSP enabled/disabled. The existing WAKE_LOCK permission alone does not establish a correct long-playback wake policy. Choose and verify ExoPlayer wake mode for the actual streaming/local data source.

## Offline architecture and data ownership

The offline branch introduces the missing user experience and persistence; it should own the download implementation after merge. Remove obsolete `utils/Downloader.kt` and any remaining duplicate receiver/state paths once call sites are gone.

Android's platform `android.app.DownloadManager` is a defensible first implementation for complete progressive MP3 files: the OS owns transfer execution across app lifecycle changes. It is distinct from Media3's `androidx.media3.exoplayer.offline.DownloadManager`. Do not switch merely because both classes have the same name.

Media3's download service/index/cache becomes attractive for adaptive streams, unified streamed/downloaded byte caching, and playback from partially cached media. It also brings service scheduling, non-evicting download-cache ownership, and migration obligations. Decide through a small spike if those needs actually arise; preserve completed files and avoid two independent download engines. [Media3 downloads](https://developer.android.com/media/media3/exoplayer/downloading-media)

For the current feature, the hard requirements are durable state reconciliation, idempotent enqueue/remove, file existence/readability checks, low-storage behavior, reboot recovery, and accurate partial-show status. A completed system transfer is not sufficient evidence that a future playback file still exists. Avoid large progress writes on every tick and all disk work on the main thread. Separate user-owned downloads from disposable stream/image caches.

A versioned atomic file can be sufficient at today's scale if its crash/recovery behavior is tested. Move catalog/download/queue metadata to Room when queries, migrations, and concurrent updates outgrow it; use DataStore for small preferences such as Wi-Fi-only downloads or enhancement mode. Keep the UI observing repository state rather than combining several independent download booleans. Cache browsing metadata and artwork so the library remains useful without network access. [Offline data-layer guidance](https://developer.android.com/topic/architecture/data-layer/offline-first)

Define backup rules explicitly: do not back up large audio caches or machine-specific download-manager IDs and file paths. Restore metadata into a reconciled missing-file state, not an apparently completed download. Test removing the currently playing track, restored backups, storage unmount, and cancellation during enqueue.

## UI, lifecycle, and maintainability

`MediaBrowserFragment` combines approximately a thousand lines of API calls, JSON parsing, HTML generation, tabs, list rendering, and navigation. Extract typed show-details repositories and a ViewModel first. Keep Views and the existing layouts while making state and errors testable. The small `ServiceLocator` is adequate if its dependencies can be injected in tests; Hilt is optional, not an immediate requirement.

The fragment launches some work in fragment `lifecycleScope`, holds WebViews, and has no explicit `onDestroyView` cleanup in the baseline. Use view-lifecycle collection for view work and cancel or detach requests when that view is destroyed. `FullScreenPlayerActivity` releases the resolved controller on stop but does not release/guard a still-pending connection future. Apply consistent ownership/generation checks to pending connections and verify rotate/back/stop-before-connect races. PR #55 already improves related base-activity behavior; avoid fixing only one of several controller owners.

`PhishinRepository` and fragment networking catch broad `Exception`, including coroutine cancellation. Rethrow `CancellationException`, use cancellable Retrofit suspend calls, and classify retryable failures. The current retry helper retries immediately without status-aware backoff; use bounded jittered backoff for transient failures, honor Retry-After, and do not retry authentication/validation errors blindly. Blocking OkHttp `execute()` inside `Dispatchers.IO` still needs cancellation tied to the call.

The WebViews enable JavaScript for static setlists/reviews/notes, and some remote notes are inserted as HTML. Disable JavaScript unless a documented feature requires it, sanitize allowed markup/link schemes, disable unnecessary file/content access, and send approved external HTTPS links to the browser. There is no demonstrated native JavaScript bridge exploit here; the concrete problem is unnecessary active-content capability over externally supplied text.

The application permits cleartext globally. Remove that blanket exception after confirming every active source uses HTTPS; the legacy `model/Track.kt` explicitly downgrades HTTPS to HTTP and should be removed if unused or corrected if reachable. Do not silently break an active source without a regression test.

Replace random per-track stock imagery with stable show artwork and a local fallback. `PhishProviderSource` eagerly preloads the stock collection; make requests sized and lazy, with only a small measured prefetch window. This reduces startup traffic, improves identity, and keeps an offline screen coherent.

Verify the new and existing screens with TalkBack, 200% font scale, 48dp touch targets, contrast, dark theme, rotation, tablets, and API 35/36 edge-to-edge insets. Download progress must have meaningful status text without announcing every percentage tick. Keep play and download actions independently discoverable. Compose can be introduced screen by screen if it improves iteration or state handling; it is not a prerequisite for a polished app.

## What top audio apps actually teach us

Public engineering posts disclose selected decisions at specific dates, not the complete current stacks of Spotify, Apple Music, or SoundCloud. This review does not claim to know their private 2026 SDK versions.

| Published evidence | Useful lesson for RoboPhish | What not to infer |
|---|---|---|
| Spotify, 2020: moved large library metadata/sort views to disk and loaded/synchronized incrementally. [Engineering post](https://engineering.atspotify.com/2020/5/spotify-modernizes-client-side-architecture-to-accelerate-service-on-all-devices) | Persist the catalog needed for browsing; do bounded startup work; index large queries instead of rebuilding everything in memory. | RoboPhish needs Spotify's full shared client architecture. |
| SoundCloud, 2022: measured artwork regressions, fixed cache keys and bounded list preloading; discarded complex custom drawing without a performance benefit. [Engineering post](https://developers.soundcloud.com/blog/android-images-at-soundcloud/) | Profile the designed experience; use stable image keys, sized requests, small prefetch windows, and simple rendering. | Switch Coil to their then-used Picasso because a famous app used it. |
| SoundCloud, 2019: described a cross-platform native player and Oboe sink. [Engineering post](https://developers.soundcloud.com/blog/soundcloud-is-playing-the-oboe/) | Native audio is a response to specific measured pipeline/platform needs. | Replace Media3 with C++/Oboe for ordinary MP3 playback or adopt their historical stack uncritically. |

The transferable product standard is fast playback, preserved queues, dependable background controls, clear offline state, stable artwork, and measured audio transitions. Good library names alone cannot provide those guarantees.

The separate enhancement project should keep expensive analysis outside playback startup, cache versioned track analysis, and make original/enhanced comparison immediate. Measure added CPU/battery use and offload interaction. Audible improvement requires representative recordings and listening evidence; architecture review cannot certify it. Avoid shipping an alpha ML dependency solely because it sounds relevant.

## Prioritized implementation roadmap

| Priority / order | Deliverable | Effort / risk | Acceptance evidence |
|---|---|---|---|
| P0 / containment implemented and tested | Isolate artwork auth; exact-origin API auth; redact query credentials; Phish.net header migration | Implemented in PR #55 / low | Expanded networking suite passes in run `41341203053`. APK-secret architecture work remains. |
| P0 / release gate | Promote supported API 36 toolchain; reconcile release metadata; enable actionable lint gates | 1–3 days / medium | Debug and release-like minified checks; API 26/35/36 smoke tests; correct generated manifest. Release signing follows AGENTS.md separately. |
| P1 / next | Complete offline merge, one download owner, crash/reboot/storage reconciliation | 3–6 days / medium | Kill/relaunch offline, partial download/retry, low storage, deletion during playback, upgrade/restore tests. Count only work remaining after PR #55. |
| P1 / next | Service-owned notification, durable queue/resumption, controller and browse contract | 3–5 days / medium | Single notification; cold resume; malformed IDs; notification-denied state; Bluetooth and Auto/voice tests. |
| P1 / next | Keyless Phish.in v2 adapter and metadata migration | 3–5 days / medium | Contract fixtures and pagination; no v1 consumers/key dependency; old downloads keep working. |
| P1 / staged | Kotlin/toolchain/Media3 upgrade; targeted runtime and audio tests | 3–6 days / medium-high | R8 build, actual test counts, full concert segues, process death, DSP compatibility, no startup/battery regression. |
| P2 / scheduled before provider deadline | Provider-approved Phish.net access architecture | 3–7 days plus provider/hosting decisions / medium | No embedded provider secret; bounded cache, rate limits, outages handled; provider terms checked. |
| P2 / incremental | Repository/ViewModel extraction, WebView hardening, artwork, a11y, bounded caches | 4–8 days / medium | Lifecycle races, malicious HTML fixtures, TalkBack/font scale, startup/memory comparison. |
| P2 / continuous | Version catalog, update automation, lint baseline reduction, observability and release hygiene | 2–4 days initial / low | CI executes tests/lint/minified checks; sanitized diagnostics; small reviewed dependency updates. |

Do not sum all rows into the current offline delivery. The first two are release gates; the rest should be reviewable, reversible changes with clear ownership. The SDK and API migrations should not be bundled with a visual rewrite or a new audio engine.

## Tests, observability, and release hygiene

The baseline has a few pure utility/JSON/auth tests and no tracked GitHub CI workflow. PR #55 adds CI and offline tests and fixes the networking JUnit dependency mismatch with `kotlin-test-junit5`. Its date test now explicitly checks Los Angeles and UTC and restores the original timezone, removing the CI host dependency. The final follow-up below records passing API 26/35/36 checks after the retention fixes. These baseline test fixes are complete in PR #55, not additional roadmap work.

PR #55 now runs strict lint with zero errors and 120 warnings; keep that error gate and reduce the remaining warnings incrementally. The original main configuration disables release lint/error enforcement, so retain explicit release-like validation while integrating the branch. Avoid relying on `unitTests.returnDefaultValues = true` to validate Android behavior: use pure fakes for domain logic and instrumentation/Robolectric where real lifecycle/platform semantics matter. Run a minified test build for reflection/JSON regressions, without requiring release signing secrets in ordinary PR jobs.

Pin a small device matrix: API 26 minimum, API 33 media notification behavior, API 35, and target API 36. Include at least one physical Samsung and Pixel for long screen-off/Bluetooth playback when available. Use synthetic tone/impulse fixtures for automated queue and DSP timing, plus authorized representative concert samples for listening and real MP3 metadata coverage. Track failures separately from server/network outages.

Record sanitized playback-start latency (tap to first audio), rebuffer count/time, local-versus-network source, decoder/player error codes, download success/failure categories, queue transition discontinuities, and ANRs/crashes. Establish baselines before setting numerical release thresholds. Do not log provider keys, full URLs with queries, personal identifiers, or private listening histories by default. Start with Play Android vitals and local diagnostic counters; add a telemetry SDK only with a concrete need and appropriate disclosure.

Use Macrobenchmark/Perfetto for startup, browse scrolling, and download-library rendering before adopting Baseline Profiles. Profiles can improve critical paths, but measure a production-like minified build on real hardware. [Baseline Profiles](https://developer.android.com/topic/performance/baselineprofiles/overview)

Centralize dependency versions in a version catalog, align related artifacts, verify the Gradle wrapper checksum, and use reviewable update PRs. Remove stale dependencies/resources only after checking call sites. Update the README's obsolete SDK/setup instructions. Wall-clock build fields complicate reproducible builds; pass release provenance explicitly if byte-for-byte reproducibility becomes a requirement.

`android.jks` is tracked in the baseline; `.gitignore` has commented key exclusions. This audit inspected tracked filenames only, not key contents or credentials. Preserve the required local release keystore and signing identity, prevent additional key material from being committed, and have the owner review repository access/history and whether upload-key remediation is needed. Do not delete or substitute the signing key or rewrite history during feature work.

Release version code/name also differ between upstream and the local SDK changes. Check the live Play Console, choose a strictly higher version code, and follow the repository's signing checklist using the normal Gradle user home. Never infer that a local version is unused or that a successful build used the correct upload certificate. No release artifact was built or approved by this audit.

Additional release references: [Retrofit changelog](https://github.com/square/retrofit/blob/trunk/CHANGELOG.md), [Coil changelog](https://coil-kt.github.io/coil/changelog/).

## Offline implementation follow-up

A targeted static review of the concurrent offline feature found three concrete issues. Fixes are committed in [PR #55 at `42186a9`](https://github.com/mikevoyt/RoboPhish/commit/42186a9ddfdb0b9ce3a13a0f40a69f46d5ae652e):

| Finding | Implemented fix |
|---|---|
| Android can delete invisible DownloadManager transfers and their files after seven days; retaining the managed destination does not provide durable offline storage. | A persisted `transferUri` journal precedes the atomic move into `Music/saved`. Recovery accepts either the original or final file, persists the completed final path before removing the system transfer, then clears its job ID/journal. Removal cleans both locations. |
| Legacy metadata recovery could finish after the user removed the show and enqueue it again. | Recovery now goes through repository retry, which checks that the original record and attempt still exist under the repository lock. It no longer uses an unconditional enqueue after the fetch. |
| The UI promised Wi-Fi-only transfers while the platform request actually restricted metered networks. | Copy now describes unmetered connections accurately. `NETWORK_WIFI` alone would not have changed that policy on supported Android versions. |

Adoption also exposed a legacy identity edge: records without a track ID previously derived their key from paths/job IDs that change during adoption. PR #55 now persists a stable `recordId`, supplies a deterministic migration default, and uses fallback keys for playback lookup and removal. Interruption tests cover both sides of the rename, including a null-track-ID record retaining one identity; the transfer test checks that system-job cleanup cannot delete the saved final file, and a retry test covers a late response after removal.

The earlier [pre-retention API 35 run](https://github.com/mikevoyt/RoboPhish/actions/runs/41335894203) and [run #13](https://github.com/mikevoyt/RoboPhish/actions/runs/41337447120) are superseded by the following evidence. The API 26 protected-broadcast test-harness issue has been resolved.

| Environment | Verified result and limit |
|---|---|
| Offline `2f693153` | [Run `41341203053`](https://github.com/mikevoyt/RoboPhish/actions/runs/41341203053) passed on API 26, 35, and 36: one real-transfer test plus nine offline MP3 instrumentation tests on each. The run also passed 38 mobile and ten networking JVM tests; strict lint reported zero errors and 120 warnings. |
| Show-date fix `97a79524118f36eebf31ebf784758e63e2c81855` | [Run `41342079714`](https://github.com/mikevoyt/RoboPhish/actions/runs/41342079714) is green on API 26, 35, and 36. The device-discovered raw `2026.10.12` display is now formatted as `Oct 12, 2026`. |
| Combined audio `160eb85d84fc93084da42da65359a41af465917a` | [Run `41342096141`](https://github.com/mikevoyt/RoboPhish/actions/runs/41342096141) is green on API 26, 35, and 36. An earlier combined build (`83322` prefix) also passed API 35 with 49 mobile and ten networking JVM tests, one transfer plus ten offline/audio instrumentation tests, and strict lint; those counts are attributed to that earlier build. |
| Native Android Studio and physical device | The root API 36 project synced and built successfully. RoboPhish Dev (`.dev`) was installed alongside the release app on the actual Pixel 9 Pro running API 37.1, and the offline checks below were exercised on that device. |

The physical-device test downloaded two real tracks from Orion, October 12, 2026: **Shade (4:15)** and **Weekapaug (6:16)**, totaling about **21 MB**. With airplane mode enabled and Wi-Fi off, playback, seeking, next-track control, background playback, a fresh-process relaunch, and automatic queue advancement were verified. Live EQ mode, blend, and original controls functioned during local playback without a visible position reset. Enhancement was restored to off, playback was paused, and Wi-Fi/cellular connectivity was restored afterward. The working native workflow is documented in the local `AGENTS.md`.

This evidence establishes functional playback and controls, not audible quality improvement, microphone-captured output, underrun-free audio, sample-accurate gapless boundaries, or battery performance. Those claims remain unverified. Runtime/device evidence comes from the implementation workstream; this reviewer checked journal ordering and the retry guard statically and checked GitHub job status, but did not personally perform the device interaction. The audit remains recommendation-only, with implementation in the feature branches. Successful debug builds and installation do not establish release-signing readiness.

Retention/policy evidence: [DownloadManager request contract](https://developer.android.com/reference/android/app/DownloadManager.Request), [AOSP idle cleanup](https://android.googlesource.com/platform/packages/providers/DownloadProvider/+/master/src/com/android/providers/downloads/DownloadIdleService.java), and [AOSP destination classification/file deletion](https://android.googlesource.com/platform/packages/providers/DownloadProvider/+/refs/heads/main/src/com/android/providers/downloads/DownloadProvider.java).
