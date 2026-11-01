#!/usr/bin/env python3
"""Posthoc diagnostics of fixed listening artifacts; never tunes the candidate.

Use a separate Welch spectrum and re-read delivered FLACs, so the reported
filter curve is not substituted for a measurement of actual output. Large
single-bin phase differences are retained with their signal-energy support.
"""
import argparse
import hashlib
import json
from pathlib import Path

import numpy as np
from scipy import signal
import soundfile as sf


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def spectrum(audio, rate):
    frequency, power = signal.welch(audio.T, fs=rate, nperseg=8192, noverlap=4096)
    return frequency, power.mean(axis=0)


def transform(audio, rate):
    return signal.stft(audio.T, fs=rate, nperseg=4096, noverlap=3072,
                       boundary="zeros", padded=True)[2]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--listening", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    keys = json.loads((args.listening / "answer-key.json").read_text())
    report = {
        "scope": "Posthoc artifact audit only; no parameter, selection or waveform changes",
        "script_sha256": digest(Path(__file__)),
        "phase_interpretation": "Energy-weighted error and weight of large deviations complement the unweighted maximum; none establishes perceived stereo fidelity",
        "spectral_interpretation": "Delivered files are loudness matched. Band changes can alter instrument tone as well as recording coloration; the audience cohort is not a clean target",
        "records": [],
    }
    for key in keys:
        date = key["id"]
        folder = args.listening
        if not (folder / (date + "_A.flac")).exists():
            folder /= "validation"
        paths = {name: folder / (date + "_" + label + ".flac")
                 for label, name in key.items() if label in "ABC"}
        original, rate = sf.read(paths["original"], always_2d=True)
        za = transform(original, rate)
        cross_a = za[0] * za[1].conj()
        frequency, power_a = spectrum(original, rate)
        for name, path in paths.items():
            if name == "original":
                continue
            audio, actual_rate = sf.read(path, always_2d=True)
            if actual_rate != rate or audio.shape != original.shape:
                raise ValueError("Listening artifact format/frame mismatch")
            zb = transform(audio, rate)
            cross_b = zb[0] * zb[1].conj()
            phase = np.abs(np.angle(cross_b * cross_a.conj())) * 180 / np.pi
            weight = np.sqrt(np.abs(cross_a) * np.abs(cross_b))
            total = max(float(weight.sum()), 1e-30)
            _, power_b = spectrum(audio, rate)
            bands = {}
            for center in [125, 250, 500, 1000, 2000, 4000, 8000, 16000]:
                mask = (frequency >= center / np.sqrt(2)) & (frequency < center * np.sqrt(2))
                bands[str(center)] = float(10 * np.log10(max(power_b[mask].sum(), 1e-30)
                                                       / max(power_a[mask].sum(), 1e-30)))
            block = int(rate * .02)
            count = len(audio) // block * block
            envelope_a = np.sqrt(np.mean(original[:count].reshape(-1, block, 2) ** 2, axis=(1, 2)))
            envelope_b = np.sqrt(np.mean(audio[:count].reshape(-1, block, 2) ** 2, axis=(1, 2)))
            envelope_delta = 20 * np.log10(np.maximum(envelope_b, 1e-20) / np.maximum(envelope_a, 1e-20))
            report["records"].append({
                "date": date, "variant": name,
                "original_flac_sha256": digest(paths["original"]), "candidate_flac_sha256": digest(path),
                "energy_weighted_phase_error_degrees": float(np.sum(phase * weight) / total),
                "maximum_phase_error_degrees": float(phase.max()),
                "phase_weight_fraction_above_30_degrees": float(weight[phase > 30].sum() / total),
                "phase_weight_fraction_above_90_degrees": float(weight[phase > 90].sum() / total),
                "welch_octave_power_change_db_loudness_matched": bands,
                "twenty_ms_envelope_gain_db_quantiles_05_50_95": np.quantile(envelope_delta, [.05, .5, .95]).tolist(),
            })
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, indent=2) + "\n")
    print("Audited", len(report["records"]), "fixed FLAC candidates; no rerender or retuning")


if __name__ == "__main__":
    main()
