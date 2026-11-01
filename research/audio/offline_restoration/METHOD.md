# Saved offline audio: method, evidence, and limits

The saved-version workflow is real, but **soundboard-grade restoration is not established**. The final Android candidate abstained on **all 24 full recordings**, producing no demonstrated enhancement on the real corpus. Its narrower known-defect experiments show some modest correction, but do not meet the requested high-quality restoration goal. No human listening ratings have been collected; the listening package is an evaluation deliverable, not a claim that listeners preferred the output.

The candidate is conservative, track-adaptive mastering. It does not recover a clean source, separate the band from the crowd, repair clipping, or generate missing musical detail. When no clear correction qualifies, the app reports that outcome and retains the original without storing a duplicate WAV. The research harness still renders unchanged copies to validate exact duration and sample preservation.

## Corpus and experimental separation

`manifest.json` locks 24 full audience recordings spanning 1992–2025 (four decades), different venues, microphone lineages, quiet and dense music, prior mastering, dual-mono, and long jams. `acquisition.json` records complete-source hashes and download metadata. The compressed source collection is approximately 396 MB and 3.674 hours. Full source audio and rendered files stay outside the repository in task scratch storage.

Sixteen recordings are development material. Eight entirely new show dates were reserved before waveform analysis. The previous investigation's twelve recordings cannot be called an untouched holdout, so all twelve belong to development. The evaluation harness snapshots the exact Java source it compiles. It requires a frozen implementation and measurement-protocol fingerprint before opening the eight holdout recordings. This is a small convenience sample, not a statistically representative estimate of the Phish archive.

The full-track evaluation includes duration and content consistency, sample peaks, a 4× oversampled peak estimate, FFmpeg's EBU loudness/true-peak meter, loudness range, crest factor, stereo correlation, side/mid energy, frequency-band coherence, envelope correlation, onset-flux correlation, and the quieter fifth of the track. Quiet passages are **not semantic crowd labels**. The original report retains raw measurements, including the poorly conditioned side/mid ratio of essentially dual-mono sources; width-change checks are inapplicable when both versions' side energy is below −60 dB relative to mid.

## Candidate implemented in the app

The renderer makes two complete passes over the decoded source. Pass one derives an immutable profile; pass two writes a separate PCM WAV. Decoder chunks can have arbitrary whole-frame lengths. The processing core uses bounded memory and has no network or model dependencies. Original files are untouched. Decoder/render cancellation and atomic publication are responsibilities of the surrounding background worker.

Analysis uses approximately 46 ms sine windows: 1,024 points at 22.05/24 kHz, 2,048 at 44.1/48 kHz, and 4,096 at 88.2/96 kHz. Scaling the FFT size avoids a different confidence decision caused merely by reduced frequency resolution at higher sample rates. Empty reference bands never count as evidence of a spectral valley. Near-silence does not set the profile, and short or spectrally sparse material causes abstention.

For each active analysis frame, the analyzer computes channel-linked energy density in 26 bands. Between 100 Hz and 1 kHz, a band's log-energy is compared with the log-frequency interpolation of the bands roughly one octave below and above it. Only the lower quartile of this excess is eligible: a proposed broad peak must persist in at least 75% of the analyzed windows. Excess above 3 dB is corrected at half strength, capped at 3 dB, and smoothed between bands. Corrections below 0.25 dB cause bit-exact abstention. These limits were chosen using development experiments; they are engineering guardrails, not validated perceptual thresholds.

Rendering uses a sine-window weighted overlap/add transform with 75% overlap, explicit normalization, leading-delay removal and end flushing. It preserves exact frame count. Static broad attenuation is supplemented by up to 1.5 dB of additional adaptive attenuation only in bands already supported by the persistent-peak profile. This attenuation requires excess energy above the full-track 80th percentile plus 1.5 dB. Positive spectral flux protects abrupt onsets; high spectral flatness protects broadband ambience; quiet frames are protected. Attenuation engages slowly and releases quickly. There is no noise gate, blanket noise-floor subtraction, stereo widening or per-channel automatic gain.

Each time-frequency bin uses the same real-valued gain in both channels. This preserves the interchannel ratio and phase within that transform representation; it does **not** guarantee unchanged broadband correlation or perceived width, which are measured separately. A single fixed gain reserves headroom on hot sources. Any new integer clipping rejects publication instead of silently accepting a distorted result. Existing clipping is not repaired.

This approach can modestly reduce persistent low-mid buildup. It cannot determine whether every broad peak came from the room, an instrument, the taper's mastering, or an artistic choice. The Original/Enhanced choice therefore remains explicit, with original playback immediately available.

## Full-track outcome

The frozen final candidate processed **24 complete recordings totaling 3.67444 hours**: 16 development recordings and eight previously untouched holdouts. **All 24 abstained**. Every rendered PCM stream was verified bit-exact against the decoded original, with identical frame counts. All 24 passed the numerical preservation checks, but unchanged output trivially preserves those properties; these results provide no evidence of an improved recording.

The local evaluation package contains 24 full-length WAVs and 96 blinded comparison pairs (four passages per recording). In this candidate's final package the compared samples are identical. That fact must be disclosed, not hidden behind an “enhanced” label or a successful-test count. Earlier mean-based development renders and separate dereverberation experiments are retained as research evidence of actual changes and their limitations.

The revised confidence rule was frozen before the eight holdouts were analyzed. A later readability-only Java formatting change is linked to the development snapshot by `evidence/formatting-equivalence.json`, which verifies identical JVM instructions and constants for all four relevant classes. No algorithm parameters were retuned using holdout results.

## Development iterations and known-reference tests

The initial estimator used mean spectral curvature. Its complete numerical evidence is retained in `evidence/controlled-mean-profile.json`. On 36 trials—four development passages, three broad bell frequencies (160/315/630 Hz), and three added gains (3/6/9 dB)—median reference-error improvement was only **0.343 dB**. Twenty-three trials improved by more than 0.1 dB, eight worsened by more than 0.1 dB, and the worst deterioration was **2.247 dB**. It could remove genuine coloration elsewhere while correcting a mild artificial defect. That version was rejected.

The persistent-peak confidence revision produced **zero worsened cases** in the same 36 development trials. Twelve improved by more than 0.1 dB; 21 abstained and returned identical PCM. Median improvement including abstention was **0 dB**, and the best was about **1.215 dB**. This is improved caution, not evidence of a dramatic restoration breakthrough. These trials influenced development and are not a held-out confirmatory test.

In these tests, the reference is the original audience recording *before* a known extra defect was introduced. It is not an independently recorded clean concert. Reconstruction error is evaluated after one global level alignment, so simply making audio quieter cannot count as improvement. `controlled_degradation.py` reproduces the tests through the exact Java renderer used by Android.

## Independent dereverberation and model review

| Candidate | Evidence and decision |
|---|---|
| NARA-WPE | The MIT-licensed NumPy implementation is a feasible local CPU baseline. It is designed for speech dereverberation, so music/crowd preservation requires separate evidence. Four development excerpts were tested with six taps, three-frame prediction delay and two iterations. Full WPE worsened reconstruction error in three of four known added-reverb tests (−3.25, −2.91, +1.04, −0.80 dB), and altered spatial/dynamic measurements. A 25% blend gave only +0.14 to +0.59 dB. A shared attenuation mask bounded to 1.5 dB gave +0.06 to +0.35 dB. None ships. See `evidence/wpe-comparison.json` and `model_comparison.py`. [Official implementation](https://github.com/fgnt/nara_wpe). |
| SonicMaster | A relevant music-specific generative research candidate with Apache-2.0 code and a model card listing 0.9 billion F32 parameters. Official inference uses 30-second chunks and 10-second overlap, a text encoder and a separate Stable Audio Open VAE. It chooses CUDA or CPU and provides no validated Android path. The VAE requires Hugging Face account consent and a distinct community license; no such consent was accepted and no weights were downloaded. Generative fidelity and crowd treatment would need direct evaluation. [Code](https://github.com/AMAAI-Lab/SonicMaster), [model card](https://huggingface.co/amaai-lab/SonicMaster), [inference](https://github.com/AMAAI-Lab/SonicMaster/blob/main/infer_single.py), [VAE access terms](https://huggingface.co/stabilityai/stable-audio-open-1.0). |
| RESTORE | A recent historical-recording model exposes vocals, music, noise, transients, residual and generated high-frequency extension. Its code supports CPU and includes a roughly 168 MB checkpoint, but uses CC BY-NC 4.0. Its default mix drops noise/residual and adds generated extension, contrary to treating crowd and the original performance as authoritative. No weights were downloaded or bundled. Research with explicitly preserved residual and disabled generated extension would still require license suitability and concert-specific validation. [Official repository](https://github.com/MelissaChen15/restore). |
| BABE-2 | MIT research code addresses historical piano/vocal recordings through a generative prior. The authors explicitly describe inferred restored content. It is not evidence of faithful reconstruction of this concert corpus and was not benchmarked here. [Official repository](https://github.com/eloimoliner/BABE2-music-restoration). |

No recording was sent to an external AI service. No neural model is represented as evaluated when it was only reviewed. Generic speech denoisers and instrument separators were not substituted for crowd-preserving restoration.

## Listening and acceptance

The full-track harness extracts four comparison windows per recording, independently randomizes A/B labels, matches integrated loudness, applies common peak-safe attenuation, and writes FLAC files with a local browser player that switches at the same musical position. It records user ratings locally and permits ties. Rate overall preference, instrument/vocal naturalness, crowd presence, stereo image and transient artifacts on headphones. The numerical reports cannot supply these preferences. This is an informal blinded comparison, not a standards-compliant MUSHRA panel or a clean-reference restoration score.

The implementation's automated preservation gates are deliberately separated from listening preference. Passing them means it avoided the measured regressions within this corpus. It does not establish soundboard-like clarity, absence of all artifacts or superiority on arbitrary recordings. Peak estimates and instrumentation are not a certification. [ITU loudness/true-peak recommendation](https://www.itu.int/rec/R-REC-BS.1770), [ITU subjective evaluation recommendation](https://www.itu.int/rec/R-REC-BS.1534).

Before promoting a stronger treatment: collect actual blinded ratings, investigate recordings where this candidate abstains, test new untouched recordings, and obtain an appropriate local model/runtime with verified usage terms. Keep residual ambience and generated content independently auditable, and reject candidates that change musical identity even when a generic quality metric improves.

## Reproduction

Use an isolated Python environment. The full-track harness uses the parent directory's pinned `requirements.txt`, FFmpeg/FFprobe and Java 17 or newer. The optional WPE baseline additionally needs `nara_wpe==0.0.11`; it is not an Android dependency.

```sh
python acquire.py --help
python evaluate.py --help
python controlled_degradation.py --corpus /path/to/source-mp3s --output /path/to/controlled-results
OPENBLAS_NUM_THREADS=1 OMP_NUM_THREADS=1 python model_comparison.py --corpus /path/to/source-mp3s --output /path/to/wpe-results
```

The pinned manifests, compiled-source fingerprints, machine-readable measurements, development failures and final holdout results belong beside this method. Full audio stays in local artifact storage. No release, merge, upload or deployment is implied by this research record.
