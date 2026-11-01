# Audience audio: research and implementation record

Status: experimental, default off. The evidence supports conservative tonal adjustment and preservation checks. It does **not** establish a large perceptual improvement, crowd removal, or restoration of missing musical information. No listening ratings have been collected. This is not a completed PhD-scale audio restoration result.

## What was actually done

Downloaded 12 complete public Phish.in MP3s through the unauthenticated v2 API. The accepted audience recordings span 1993–2023, indoor/outdoor venues, several microphone rigs, a dual-mono source and a previously remastered source. Full source URLs, SHA-256 hashes, durations and taper notes are in `evidence/corpus.json`. Full recordings remain in disposable research-runner storage; the repository contains metadata and measurements only.

Analyzed three 20-second excerpts at 20%, 50% and 80% of each track. The first experiment evaluated 27 EQ combinations on every recording: 324 level-matched comparisons. A second experiment ran the actual Java playback engine, not a Python approximation, in two modes at two strengths: 48 renders. A third tested confidence-gated hum removal and two conservative high-frequency spectral gates: 36 more renders. The scripts and pinned Python dependencies are here; `ffmpeg`, `ffprobe` and Java 17 are required.

| Date | Track | Source feature |
|---|---|---|
| 1993-08-13 | Rift | Nakamichi CM-300 audience DAT |
| 1997-12-06 | Cavern | Microtech Gefell audience source; decoded dual mono |
| 1998-04-03 | Roses Are Free | AKG 461 audience source; long jam |
| 2000-09-14 | Drowned | Neumann u89i; taper notes already specify 80 Hz roll-off |
| 2003-07-30 | Fast Enough for You | Schoeps MK5; quieter musical material |
| 2009-08-07 | The Moma Dance | Outdoor Gorge, Schoeps mk22 |
| 2018-07-17 | Carini | Mixed Schoeps/Neumann audience microphones |
| 2023-08-05 | Tweezer | MSG, Neumann u89i |
| 1995-12-07 | Taste That Surrounds | AT4031, remastered tag; hot peaks |
| 1996-12-06 | Sparkle | Schoeps621; prior L2/BBE processing in lineage |
| 1999-07-10 | Golgi Apparatus | Schoeps mk41; loudest selected excerpt |
| 2015-08-22 | 46 Days | Outdoor festival, Schoeps mk4v |

Selection is reproducible, not statistically representative of the archive. Earlier candidate dates were mostly tagged soundboard and excluded. The conservative source filter also excludes notes mentioning a soundboard position, even if the actual source might be audience. There are no certified clean references or isolated noise samples. Three windows do not cover every defect in a long track. Window concatenation creates artificial boundaries that can influence peak measurements. Development/holdout labels are recorded, but thresholds were inspected across the complete set; this is exploratory analysis, not an untouched confirmatory holdout.

## Findings and decisions

Measurements include BS.1770-style integrated loudness (pyloudnorm), sample peak, a 4× oversampled peak estimate, crest factor, stereo correlation, side/mid ratio and band power. The oversampled estimate is not a certified true-peak meter. Guardrails were absolute crest change ≤1.5 dB, correlation change ≤0.05 and side/mid change ≤0.5 dB. These are provisional preservation bounds, **not quality scores**; spectral weighting alone can change those spatial summaries without changing per-frequency channel relationships.

| Candidate, level matched | Recordings within all preservation bounds | Median 0–30 Hz power change | Decision |
|---|---:|---:|---|
| 20 Hz high-pass only | 12/12 | −0.61 dB | Small effect |
| 30 Hz high-pass only | 12/12 | −1.53 dB | Conservative Rumble mode |
| 30 Hz high-pass, −1.5 dB at 280 Hz | 12/12 | −1.10 dB | Optional subtractive Clarity mode |
| Same with +1 dB at 2.2 kHz | 4/12 | −1.31 dB | Removed presence boost |
| 45 Hz high-pass only | 11/12 | −3.39 dB | More bass alteration; not default |

The unprocessed corpus spans roughly −21.7 to −12.8 LUFS. Two float-decoded sources already exceed 0 dBFS in the oversampled estimate. Global normalization or treble boost would need careful headroom management. Existing taper mastering matters: a fixed correction cannot know whether an artistic bass tone, room coloration, or previous mastering caused a spectral imbalance.

At default 60% blend, the final Java engine with headroom measured:

| Mode | Median loudness change | Largest absolute crest change | Largest absolute correlation change | Median processing/audio time |
|---|---:|---:|---:|---:|
| Rumble | −0.681 LU | 0.564 dB | 0.0246 | 0.194% |
| Clarity | −1.073 LU | 0.706 dB | 0.0386 | 0.193% |

The worst 4096-frame processing call across all 48 renders took 5.93 ms on the Linux runner (the block represents 92.9 ms of 44.1 kHz audio). This includes cold/JIT outliers but excludes decoding, audio-device scheduling and some setup. It is **not an Android battery or startup-latency measurement**. The causal engine emits the first sample immediately and preserves frame count, with no lookahead, file reads, network calls or inference in the playback callback. Actual startup and underruns still require device measurements.

The sample limiter did not eliminate intersample overshoots: the worst estimate remained +0.153 dBFS. The latest engine therefore adds 1 dB of smoothly blended headroom at full strength (about 0.59 dB at 60%). This is a conservative margin, not a universal true-peak guarantee. The exact-engine rerun completed successfully: all 48 rendered excerpts remain below 0 dBFS in the oversampled estimate; the worst is −0.433 dBFS. See `evidence/realtime-headroom.json`. The earlier result is retained explicitly as `evidence/realtime-before-headroom.json`. This sample-limited finite corpus result cannot certify arbitrary audio or the first frames of a crossfade.

The hum detector found no high-confidence stationary two-harmonic hum pattern in the 12 originals, so it correctly abstained rather than applying a universal notch. This does not establish sensitivity to actual hum. The stronger spectral gate changed 6–20 kHz energy by only −0.19 dB median. It can confuse cymbal decay, ambience and tape hiss without a noise-only reference. Neither result supports shipping automatic noise removal or claiming a major improvement. Those candidates remain research-only.

Successful final-headroom research run: https://github.com/mikevoyt/RoboPhish/actions/runs/41338674184 . Its `audio-research-metrics` artifact contains numerical results and 36 blinded FLAC clips; artifact ID 13096641572, expires 2026-12-01. The earlier run before headroom is 41335673798. The first 324-comparison sweep is run 41334959153. New runs regenerate the clips and results.

## Product and playback integration

The top-level drawer item **Audience audio** opens an opt-in switch, Rumble or Rumble + gentle clarity, amount control and a reversible comparison button. All settings are local. No source MP3 or saved download is rewritten. The screen explicitly describes tonal correction and its limits. Comparison changes processing on the existing player; it does not seek, reload, or recreate the player.

The Java core uses a second-order 30 Hz high-pass plus optional −1.5 dB/280 Hz bell. It uses identical filters in each channel, a linked sample limiter, a 50 ms enabled/amount ramp and a separate 50 ms mode crossfade. The control is a wet/dry blend, not a monotonic filter-cutoff control: high-pass phase rotation can make partial blending cut slightly more near the cutoff. The measured 30 Hz Rumble gain is −5.27 dB at 60% blend and −4.01 dB at 100%, including headroom; 55 Hz is −1.55/−1.37 dB respectively. See `evidence/frequency-response.csv` and `AudioResponseProbe.java`. Unsupported PCM formats bypass the custom processor. Toggle-off becomes bit-exact after the ramp. The optional extra headroom is blended with the same ramp.

Use `ExoPlayer.Builder(this, AudienceRenderersFactory(this))` in `MusicService`. Keep the offline branch's `.setMediaSourceFactory(...)` call on that builder; the audio renderer and offline source resolver compose. Exclusive source files are the `audio/` package, `ui/AudienceAudioActivity.kt`, `audience_audio_screen.xml`, `audio_strings.xml`, `audio_card_bg.xml`, `audio_hero_bg.xml`, `ic_audio_controls.xml`, and audio test/research files. Shared edits are the MusicService constructor, activity declaration in AndroidManifest, drawer menu item, and ActionBarCastActivity route/selection.

There is a power tradeoff: the PCM processor stays active even while disabled so toggles are seamless and filters remain warm. This prevents an offload/passthrough path from using the custom stage; float output is disabled and the current MP3 path is PCM16. Battery impact has not been measured. Cast playback occurs remotely and cannot use this local processor; Bluetooth/USB wired output uses the local decoded path where supported. UI should not imply that remote Cast audio is enhanced.

## Validation and listening protocol

The pure Java suite covers bit-exact bypass over every PCM16 value, return to original after the ramp, chunk independence, in-place operation, mono equality, stereo polarity, bass/rumble response at multiple rates, invalid strength, full-scale behavior, incomplete frames and immediate impulse output. A transition-continuity test covers live mode and bypass changes. The final headroom build passed `assembleDebug`, `testDebugUnitTest` and the existing `lintDebug` task in run https://github.com/mikevoyt/RoboPhish/actions/runs/41338674226 (d90d686). The standalone branch retains the repository baseline lint configuration (`abortOnError false`); this is not a claim of zero inherited lint findings. Its earlier test failure was the repository's timezone-dependent DateKtTest, corrected using the parent branch's explicit PST/UTC test. Media3 classes declare the required UnstableApi opt-in. The combined offline/audio branch adds separate real-player Android tests and stricter lint validation.

`evaluate_realtime.py` writes 12-second A/B/C excerpts for each date with randomized labels, equal integrated loudness and a common peak-safe attenuation. One is original; two are processed. Listen on good headphones at comfortable fixed volume; rate bass naturalness, vocal/cymbal artifacts, spatial image, crowd distraction and overall preference, allowing ties. Repeat in a different order and only then open `answer-key.json`. This is a blind preference exercise, not a MUSHRA result: there is no clean reference or calibrated panel. No subjective ratings have been supplied or fabricated. The completed combined-branch pipeline also generates a local `listening/index.html` page to switch blinded versions and save/export ratings without transmitting them.

A phone-to-Mac-microphone recording can diagnose output glitches, but is not a clean quality reference. Fix device placement, orientation, volume and room conditions; replay the same segment in A/B/A/B order; use finite recordings with no unrelated conversation. Phone-speaker filtering, room reflections, microphone directionality and automatic gain control can dominate the measured difference. Digital loudness-matched headphone comparison remains primary. In this environment CoreAudio/AVFoundation discovery returned zero devices, and native QuickTime app selection stalled. No microphone recording was made. Parent is independently validating the app on a Pixel through Android Studio's normal native workflow.

## Track-aware and AI direction

A safe future architecture starts playback immediately with original audio or the user's conservative preset. A bounded, lower-priority worker may analyze PCM already decoded for playback, or an existing downloaded file, without taking over the media request. An SPSC ring buffer should drop analysis frames when full; playback must never wait. Cache a small profile keyed by content hash/source URL, sample format and algorithm version. Cancel stale track work. Publish only high-confidence, bounded parameter changes through immutable state and a crossfade; abstain on missing evidence. Model loading, HTTP requests and analysis never belong on the renderer thread. No such adaptive profile is currently shipped.

`evaluate_controlled.py` completed 48 tests of known injected 50/60 Hz hum and high-band hiss at 25 dB signal/noise ratio, plus an adversarial sustained-musical-tone check. Reconstruction error is relative to the original audience clip only for the added defect, not a clean concert reference.

| Research candidate | Controlled cases | Median error improvement | Finding |
|---|---:|---:|---|
| Confidence-gated dehum | 24 | 0.00 dB | Detected only 2/24 injected cases; best case 8.80 dB |
| High-band gate, −3 dB floor | 12 | 1.53 dB | Range 1.45–1.63 dB; modest known-noise reduction |
| High-band gate, −6 dB floor | 12 | 1.90 dB | Range 1.78–2.03 dB; modest known-noise reduction |

The dehum detector also falsely identified a deliberately musical 50/100 Hz oscillator pair as noise. Its combination of poor sensitivity and semantic ambiguity is a decisive reason not to ship automatic notching. The spectral gates reduced error for the artificial hiss, but that does not show an audible preference or establish safe cymbal/ambience preservation in an unknown tape. Neither candidate is enabled in the app. The permanent numerical summary is `evidence/controlled-summary.json`; full per-trial data is in the run artifact's `controlled.json`. All 12 source hashes matched the earlier corpus.

Completed combined-branch run: https://github.com/mikevoyt/RoboPhish/actions/runs/41341368218 (head 83322e46). Artifact `audio-research-metrics`, ID 13098005052, contains the full controlled results, current exact-engine metrics, 36 blinded FLAC excerpts and `listening/index.html`. Extract the artifact and open that page beside the audio files. It expires 2026-12-01; scripts, source hashes and summary evidence remain in this repository.

Next expand controlled degradations to unseen noise levels, dropouts and clipping and an expanded hard-case audience set, then use new held-out shows and blinded listening. A detector with good metrics but no audible preference must not be promoted. In particular, a persistent musical bass note can resemble a mains hum harmonic; narrow-band energy alone is insufficient.

SonicMaster is a plausible offline research candidate, not a proven mobile solution. Its official inference code uses 30-second chunks, 10-second overlap, a generative latent model and a separate VAE. That architecture needs model/runtime, licensing and musical-fidelity evaluation before integration. BABE-2 explicitly uses generative restoration on historical material; inferred detail is not recovery of a particular live performance. Demucs separates instrument stems, not a validated crowd/venue-noise channel. None has been benchmarked on this corpus here, and no AI improvement is claimed. A later neural experiment should cache a separate derived version and preserve immediate original playback, with explicit user choice and fallback.

## Reproduce

```sh
pip install -r research/audio/requirements.txt
python research/audio/benchmark.py
python research/audio/evaluate_realtime.py
python research/audio/evaluate_alternatives.py
python research/audio/evaluate_controlled.py
```

For subsequent exact-engine iterations, `benchmark.py --skip-sweep` acquires the same corpus without repeating the 324 exploratory comparisons. GitHub workflows run the pipeline and Android debug checks. This project does not require release signing for research or validation.

## Primary references

- Phish.in source and canonical API schema: https://github.com/jcraigk/phishin ; https://phish.in/api/v2/swagger_doc
- W3C RBJ Audio EQ Cookbook, coefficient equations: https://www.w3.org/TR/audio-eq-cookbook/
- Media3 DefaultAudioSink processing/offload contract: https://developer.android.com/reference/androidx/media3/exoplayer/audio/DefaultAudioSink.Builder
- ITU-R BS.1770 loudness/true peak: https://www.itu.int/rec/R-REC-BS.1770
- ITU-R BS.1534 listening-test methodology: https://www.itu.int/rec/R-REC-BS.1534
- Demucs official model scope: https://github.com/facebookresearch/demucs
- BABE-2 paper: https://arxiv.org/abs/2403.18636
- SonicMaster paper and implementation: https://arxiv.org/abs/2508.03448 ; https://github.com/AMAAI-Lab/SonicMaster/blob/main/infer_single.py
