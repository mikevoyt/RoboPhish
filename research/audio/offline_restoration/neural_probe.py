#!/usr/bin/env python3
"""Bounded local Apollo experiment; no network, app integration, or model redistribution.

Apollo by Kai Li and Yi Luo: https://github.com/JusperLee/Apollo (CC BY-SA 4.0).
The caller supplies the inspected, pinned official checkout and model checkpoint.
The upstream unsafe pickle loader is deliberately not invoked.
"""
import argparse
import collections
import datetime
import hashlib
import json
import os
from pathlib import Path
import random
import subprocess
import sys
import time
import typing

os.environ["HF_HUB_OFFLINE"] = "1"
os.environ["HF_HUB_DISABLE_TELEMETRY"] = "1"
import numpy as np
import pyloudnorm as pyln
from scipy import signal
import soundfile as sf
import torch

SOURCE_COMMIT = "e84bcacc59d5455f05d86a5c97dd4aeb3c14dbb6"
CHECKPOINT_SHA256 = "99d9af7f1ff20e63c393035513a655392818d66b4d7fc23d658175c1f15e8d76"
DATES = ["1993-08-13", "1995-12-07", "2009-08-07", "2024-08-16"]
SR = 44100
INFERENCE_CONFIG = {"chunk_seconds": 4, "overlap_seconds": 1,
                    "context_seconds_per_side": 1, "chunk_batch_size": 1,
                    "blend": 0.25, "input_peak_cap": 0.6}
METADATA_NAMES = {
    "omegaconf.dictconfig.DictConfig", "omegaconf.listconfig.ListConfig",
    "omegaconf.base.Metadata", "omegaconf.base.ContainerMetadata", "omegaconf.nodes.AnyNode",
}
OTHER_NAMES = {"builtins.int", "builtins.dict", "builtins.list", "collections.defaultdict",
               "typing.Any", "torch.torch_version.TorchVersion"}


class _InertMetadata:
    """Checkpoint config is not used; retain its state without importing OmegaConf."""


def load_weights(checkpoint):
    digest = hashlib.sha256(checkpoint.read_bytes()).hexdigest()
    if digest != CHECKPOINT_SHA256:
        raise ValueError("Checkpoint differs from the inspected official pinned artifact")
    names = set(torch.serialization.get_unsafe_globals_in_checkpoint(checkpoint))
    if not names.issubset(METADATA_NAMES | OTHER_NAMES):
        raise ValueError("Unexpected checkpoint globals: " + repr(sorted(names)))
    allowlist = [(_InertMetadata, name) for name in METADATA_NAMES]
    allowlist += [int, dict, list, collections.defaultdict, typing.Any, torch.torch_version.TorchVersion]
    with torch.serialization.safe_globals(allowlist):
        checkpoint_data = torch.load(checkpoint, map_location="cpu", weights_only=True)
    if checkpoint_data["model_name"] != "Apollo":
        raise ValueError("Unexpected architecture")
    weights = checkpoint_data["state_dict"]
    if not isinstance(weights, (dict, collections.OrderedDict)) or not all(
        isinstance(name, str) and isinstance(value, torch.Tensor) for name, value in weights.items()
    ):
        raise ValueError("Expected a tensor-only state dictionary")
    return weights, sorted(names)


def decode_excerpt(path, start, seconds):
    data = subprocess.check_output(["ffmpeg", "-v", "error", "-ss", str(start), "-i", str(path),
        "-t", str(seconds), "-ar", str(SR), "-ac", "2", "-f", "f32le", "-"])
    return np.frombuffer(data, dtype="<f4").reshape(-1, 2).copy()


def stats(x):
    x = x.astype(np.float64)
    mid, side = (x[:, 0] + x[:, 1]) / 2, (x[:, 0] - x[:, 1]) / 2
    rms = np.sqrt(np.mean(x*x))
    _, _, z = signal.stft(x.T, fs=SR, nperseg=2048, noverlap=1536, boundary=None)
    mag = np.sqrt(np.mean(abs(z)**2, axis=0))
    flux = np.maximum(np.diff(mag, axis=1), 0).sum(axis=0)
    envelope = np.sqrt(np.mean(x[:len(x)//512*512].reshape(-1, 512, 2)**2, axis=(1, 2)))
    return {
        "frames": len(x), "peak": float(abs(x).max()),
        "oversampled_peak": float(abs(signal.resample_poly(x, 4, 1, axis=0)).max()),
        "crest_db": float(20*np.log10(max(abs(x).max(), 1e-20)/max(rms, 1e-20))),
        "integrated_lufs": float(pyln.Meter(SR).integrated_loudness(x)),
        "stereo_correlation": float(np.corrcoef(x.T)[0, 1]),
        "side_mid_db": float(10*np.log10(max(np.mean(side*side), 1e-20)/max(np.mean(mid*mid), 1e-20))),
    }, z, flux, envelope


def reference_error(reference, candidate):
    scale = float(np.sum(reference*candidate)/max(np.sum(candidate*candidate), 1e-20))
    return float(10*np.log10(np.sum(reference*reference)/max(np.sum((reference-scale*candidate)**2), 1e-20)))


def compare(original, candidate):
    if original.shape != candidate.shape or not np.isfinite(candidate).all():
        raise ValueError("Candidate changed shape or produced non-finite audio")
    a, za, fa, ea = stats(original)
    b, zb, fb, eb = stats(candidate)
    match = 10**((a["integrated_lufs"]-b["integrated_lufs"])/20)
    quiet = (ea <= np.quantile(ea, .2)) & (ea > 1e-5)
    delta = 20*np.log10(np.maximum(eb*match, 1e-20)/np.maximum(ea, 1e-20))
    cross_a, cross_b = za[0]*za[1].conj(), zb[0]*zb[1].conj()
    phase_delta = np.angle(cross_b*cross_a.conj())
    weight = np.sqrt(abs(cross_a)*abs(cross_b))
    phase_error = float(np.sum(abs(phase_delta)*weight)/max(np.sum(weight), 1e-20)*180/np.pi)
    return {
        "original": a, "processed": b, "loudness_match_gain_db": a["integrated_lufs"]-b["integrated_lufs"],
        "identity_snr_db_after_one_global_gain": reference_error(original, candidate),
        "stereo_correlation_change": b["stereo_correlation"]-a["stereo_correlation"],
        "side_mid_change_db": b["side_mid_db"]-a["side_mid_db"],
        "crest_change_db": b["crest_db"]-a["crest_db"],
        "onset_flux_correlation": float(np.corrcoef(fa, fb)[0, 1]),
        "envelope_correlation": float(np.corrcoef(ea, eb)[0, 1]),
        "weighted_interchannel_phase_error_degrees": phase_error,
        "low_level_gain_p05_db": float(np.quantile(delta[quiet], .05)) if quiet.any() else None,
        "crowd_metric_caveat": "Low-level envelope is only a proxy; no semantic crowd labels or subjective listening ratings.",
    }


def spectral_errors(reference, candidate):
    """Loudness-aligned magnitude errors, complementary to waveform identity."""
    meter = pyln.Meter(SR)
    gain_db = meter.integrated_loudness(reference) - meter.integrated_loudness(candidate)
    aligned = candidate * 10**(gain_db/20)
    resolutions = []
    for size in [512, 2048, 8192]:
        f, _, rz = signal.stft(reference.T, fs=SR, nperseg=size, noverlap=size*3//4, boundary=None)
        _, _, yz = signal.stft(aligned.T, fs=SR, nperseg=size, noverlap=size*3//4, boundary=None)
        r, y = abs(rz), abs(yz)
        floor = max(float(r.max())*1e-4, 1e-12)
        logs = abs(20*np.log10(np.maximum(r, floor)) - 20*np.log10(np.maximum(y, floor)))
        high = (f >= 6000) & (f <= 18000)
        resolutions.append({
            "fft_size": size,
            "spectral_convergence": float(np.linalg.norm(y-r)/max(np.linalg.norm(r), 1e-20)),
            "log_magnitude_mae_db": float(logs.mean()),
            "high_band_6_18khz_convergence": float(np.linalg.norm(y[:, high]-r[:, high])/max(np.linalg.norm(r[:, high]), 1e-20)),
            "high_band_6_18khz_log_magnitude_mae_db": float(logs[:, high].mean()),
        })
    return {"loudness_alignment_gain_db": float(gain_db), "resolutions": resolutions,
        "mean_spectral_convergence": float(np.mean([x["spectral_convergence"] for x in resolutions])),
        "mean_log_magnitude_mae_db": float(np.mean([x["log_magnitude_mae_db"] for x in resolutions])),
        "mean_high_band_convergence": float(np.mean([x["high_band_6_18khz_convergence"] for x in resolutions])),
        "mean_high_band_log_magnitude_mae_db": float(np.mean([x["high_band_6_18khz_log_magnitude_mae_db"] for x in resolutions]))}


def append_spectral_audit(output):
    path = output/"neural-apollo-results.json"
    report = json.loads(path.read_text())
    for row in report["records"]:
        if row["condition"] != "added_64kbps_mp3":
            continue
        date = row["date"]
        original, _ = sf.read(output/f"{date}-context.wav", dtype="float32", always_2d=True)
        compressed = decode_excerpt(output/f"{date}-added-64k.mp3", 0, 14)
        reference = original[2*SR:12*SR]
        baseline = spectral_errors(reference, compressed[2*SR:12*SR])
        row["codec_spectral_baseline"] = baseline
        for name, measurement in row["variants"].items():
            candidate, _ = sf.read(output/f"{date}-added_64kbps_mp3-{name}.wav", dtype="float32", always_2d=True)
            after = spectral_errors(reference, candidate)
            measurement["codec_spectral_after"] = after
            measurement["codec_spectral_improvement_percent"] = {
                key: float((baseline[key]-after[key])/max(baseline[key], 1e-20)*100)
                for key in ["mean_spectral_convergence", "mean_log_magnitude_mae_db", "mean_high_band_convergence", "mean_high_band_log_magnitude_mae_db"]
            }
    report["spectral_metric_method"] = "Three FFT sizes512/2048/8192;75%overlap; one integrated-loudness alignment;80dB log-magnitude floor relative to maximum reference bin;high band6–18kHz. Lower error is better. These metrics do not establish subjective preference."
    path.write_text(json.dumps(report, indent=2)+"\n")


def listening_versions(out, date, versions, answer_key):
    meter = pyln.Meter(SR)
    loudness = [meter.integrated_loudness(x) for x in versions.values()]
    target = min(loudness)
    matched = {name: x*10**((target-lufs)/20) for (name, x), lufs in zip(versions.items(), loudness)}
    maximum = max(float(abs(signal.resample_poly(x, 4, 1, axis=0)).max()) for x in matched.values())
    attenuation = min(1, .85/max(maximum, 1e-20))
    order = list(matched)
    random.Random("apollo-blind-" + date).shuffle(order)
    for label, name in zip("ABC", order):
        sf.write(out/f"{date}-{label}.flac", matched[name]*attenuation, SR, subtype="PCM_24")
        answer_key[f"{date}-{label}"] = name


def write_listening_html(out, dates=DATES, description="Four development excerpts"):
    rows = "\n".join(
        f'<section><h2>{date}</h2><audio id="p{date}" controls></audio> '
        + " ".join(f'<button onclick="play(\'{date}\',\'{letter}\')">{letter}</button>' for letter in "ABC")
        + f'<p><label>Preference / crowd / instruments / artifacts: <input data-date="{date}"></label></p></section>'
        for date in dates)
    document = '''<!doctype html><meta charset="utf-8"><title>Apollo blinded development comparison</title>
<style>body{font:16px system-ui;max-width:900px;margin:40px auto;background:#111;color:#eee}section{padding:20px;border:1px solid #444;margin:20px 0}button,input{font:inherit;padding:10px}input{width:90%}audio{width:70%}</style>
<h1>Local blinded Apollo comparison</h1><p>''' + description + '''; original, Apollo and 25% Apollo blend, randomized A/B/C. Loudness matched with common peak-safe gain. No ratings have been invented. Use headphones; ties are valid. Keep the live atmosphere and musical identity in mind.</p>
''' + rows + '''<button onclick="save()">Export my ratings</button><script>
function play(date,label){const a=document.getElementById('p'+date),t=a.currentTime||0;a.src=date+'-'+label+'.flac';a.onloadedmetadata=()=>{a.currentTime=Math.min(t,a.duration-.01);a.play()};a.load()}
function save(){const data=[...document.querySelectorAll('input')].map(x=>({date:x.dataset.date,rating:x.value}));const a=document.createElement('a');a.href=URL.createObjectURL(new Blob([JSON.stringify(data,null,2)],{type:'application/json'}));a.download='apollo-listening-ratings.json';a.click()}
</script>'''
    (out/"listening.html").write_text(document)


def boundary_audit(original, candidate):
    """Check every overlap start/end; figures are diagnostics, not a click detector."""
    residual = candidate-original
    hop = 3*SR
    positions = sorted(set(range(hop, len(original), hop)) |
                       set(range(4*SR, len(original), hop)))
    records = []
    for position in positions:
        if position < 441 or position+441 >= len(original):
            continue
        sl = slice(position-441, position+441)
        # Compare a single joining step with nearby natural waveform steps.
        original_scale = max(float(np.sqrt(np.mean(np.diff(original[sl], axis=0)**2))), 1e-9)
        records.append({"frame": position,
            "output_step_over_local_original_step_rms": float(np.max(abs(candidate[position]-candidate[position-1]))/original_scale),
            "residual_step_over_local_original_step_rms": float(np.max(abs(residual[position]-residual[position-1]))/original_scale)})
    return {"positions_checked": len(records), "records": records,
            "caveat": "Large ratios may be musical transients; this is not a validated perceptual click metric."}


def full_track_compare(original, candidate):
    """Bound spectral-analysis memory with consecutive, untuned 20-second windows."""
    if original.shape != candidate.shape or not np.isfinite(candidate).all():
        raise ValueError("Full-track output lost frames or contains non-finite values")
    windows = []
    for start in range(0, len(original), 20*SR):
        end = min(start+20*SR, len(original))
        if end-start < SR:
            continue
        x, y = original[start:end], candidate[start:end]
        measurement = compare(x, y)
        # Full cross-correlation would be unnecessary; inspect +/-32-sample lags.
        mono_x, mono_y = x.mean(axis=1), y.mean(axis=1)
        correlation = signal.correlate(mono_y, mono_x, mode="full", method="fft")
        midpoint = len(mono_x)-1
        measurement["estimated_lag_samples_within_32"] = int(np.argmax(correlation[midpoint-32:midpoint+33])-32)
        windows.append({"start_seconds": start/SR, "end_seconds": end/SR,
                        "measurement": measurement})
    meter = pyln.Meter(SR)
    return {"input_frames": len(original), "output_frames": len(candidate),
        "all_finite": True, "duration_seconds": len(original)/SR,
        "original_lufs": float(meter.integrated_loudness(original)),
        "candidate_lufs": float(meter.integrated_loudness(candidate)),
        "identity_snr_db_after_one_global_gain": reference_error(original, candidate),
        "sample_peak": float(abs(candidate).max()),
        "max_window_oversampled_peak": max(w["measurement"]["processed"]["oversampled_peak"] for w in windows),
        "windows": windows, "chunk_boundary_audit": boundary_audit(original, candidate),
        "crowd_caveat": "Windows are time-coded only; low-level envelopes do not semantically identify crowd and cannot establish preservation by listening."}


def validation_stage(args, model, run_model):
    """One full development track and two previously untouched neural holdouts."""
    selections = [("1996-12-06", "development_full_track"),
                  ("1992-12-31", "neural_holdout_midpoint_10s"),
                  ("2025-07-26", "neural_holdout_midpoint_10s")]
    freeze = {"frozen_at_utc": datetime.datetime.now(datetime.timezone.utc).isoformat(),
        "source_commit": SOURCE_COMMIT, "checkpoint_sha256": CHECKPOINT_SHA256,
        "script_sha256": hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),
        "upstream_inference_sha256": hashlib.sha256((args.source/"inference.py").read_bytes()).hexdigest(),
        "configuration": INFERENCE_CONFIG, "selections": selections,
        "policy": "No parameter tuning after freeze or after holdout inspection. Full-track raw model edges retained and reported; heldout centers exclude two context seconds each side."}
    canonical = json.dumps(INFERENCE_CONFIG, sort_keys=True).encode()
    freeze["configuration_sha256"] = hashlib.sha256(canonical).hexdigest()
    freeze_path = args.output/"neural-apollo-freeze.json"
    if freeze_path.exists():
        raise ValueError("Validation freeze already exists; refusing to overwrite prior evidence")
    freeze_path.write_text(json.dumps(freeze, indent=2)+"\n")
    report = {"freeze": freeze, "device": args.device, "records": [],
              "scope": "Natural-source evidence only; no soundboard reference, no subjective ratings, no model tuning."}
    answer_key = {}
    for date, split in selections:
        source = args.corpus/f"{date}.mp3"
        duration = float(subprocess.check_output(["ffprobe", "-v", "error", "-show_entries", "format=duration", "-of", "default=nw=1:nk=1", str(source)]))
        full = split == "development_full_track"
        data = decode_excerpt(source, 0 if full else duration*.5-2, duration+1 if full else 14)
        gain = min(1, INFERENCE_CONFIG["input_peak_cap"]/max(abs(data).max(), 1e-20))
        data *= gain
        sf.write(args.output/f"{date}-context.wav", data, SR, subtype="FLOAT")
        tensor = torch.from_numpy(np.ascontiguousarray(data.T)).unsqueeze(0)
        print("Starting", date, split, len(data)/SR, "seconds", flush=True)
        started = time.monotonic()
        with torch.inference_mode():
            result = run_model(model, tensor, torch.device(args.device), chunk_samples=4*SR,
                overlap_samples=SR, chunk_pad_samples=SR, chunk_batch_size=1)
        elapsed = time.monotonic()-started
        y = result.squeeze(0).numpy().T.copy()
        if y.shape != data.shape or not np.isfinite(y).all():
            raise ValueError("Invalid validation model output")
        x = data if full else data[2*SR:12*SR]
        y = y if full else y[2*SR:12*SR]
        versions = {"original": x, "Apollo": y, "Apollo25percent": .75*x+.25*y}
        row = {"date": date, "split": split, "source_sha256": hashlib.sha256(source.read_bytes()).hexdigest(),
            "input_headroom_gain": float(gain), "input_duration_seconds": len(data)/SR,
            "inference_seconds": elapsed, "variants": {}}
        for name, version in list(versions.items())[1:]:
            sf.write(args.output/f"{date}-{name}.wav", version, SR, subtype="FLOAT")
            row["variants"][name] = full_track_compare(x, version) if full else compare(x, version)
        listening_versions(args.output, date, versions, answer_key)
        report["records"].append(row)
        (args.output/"neural-apollo-validation.json").write_text(json.dumps(report, indent=2)+"\n")
        (args.output/"answer-key.json").write_text(json.dumps(answer_key, indent=2)+"\n")
        write_listening_html(args.output, [r["date"] for r in report["records"]],
                             "One full development track and two fixed neural holdout excerpts")
        print("Completed", date, round(elapsed, 2), "seconds", flush=True)


def main():
    p = argparse.ArgumentParser()
    p.add_argument("--source", type=Path, required=True)
    p.add_argument("--checkpoint", type=Path, required=True)
    p.add_argument("--corpus", type=Path, required=True)
    p.add_argument("--output", type=Path, required=True)
    p.add_argument("--device", choices=["cpu", "mps"], default="mps")
    p.add_argument("--smoke-only", action="store_true")
    p.add_argument("--validation-stage", action="store_true")
    args = p.parse_args()
    actual = subprocess.check_output(["git", "-C", str(args.source), "rev-parse", "HEAD"], text=True).strip()
    if actual != SOURCE_COMMIT:
        raise ValueError("Official source revision differs from inspected pin")
    args.output.mkdir(parents=True, exist_ok=True)
    weights, metadata_names = load_weights(args.checkpoint)
    sys.path.insert(0, str(args.source))
    from look2hear.models.apollo import Apollo
    from inference import run_model
    if args.device == "mps" and not torch.backends.mps.is_available():
        raise RuntimeError("MPS is unavailable in this execution environment")
    torch.set_num_threads(2)
    model = Apollo(sr=SR, win=20, feature_dim=256, layer=6)
    model.load_state_dict(weights, strict=True)
    model = model.to(args.device).eval()
    if args.validation_stage:
        validation_stage(args, model, run_model)
        return
    report = {
        "model": "Apollo", "authors": "Kai Li, Yi Luo", "license": "CC BY-SA 4.0",
        "source": "https://github.com/JusperLee/Apollo", "source_commit": actual,
        "checkpoint": "https://huggingface.co/JusperLee/Apollo/resolve/c68bd80/pytorch_model.bin",
        "checkpoint_sha256": CHECKPOINT_SHA256, "torch_version": str(torch.__version__),
        "checkpoint_loading": "weights_only=True; allowlisted builtins and inert, unused config placeholders; no upstream unsafe loader",
        "checkpoint_metadata_globals": metadata_names, "device": args.device,
        "configuration": {"chunk_seconds": 4, "overlap_seconds": 1, "context_seconds_per_side": 1, "chunk_batch_size": 1},
        "scope": "Four development recordings only. Natural 10-second center excerpts and a controlled additional 64kbps MP3 degradation. No holdout. No human ratings.",
        "records": [],
    }
    answer_key = {}
    for date in DATES[:1] if args.smoke_only else DATES:
        source = args.corpus/f"{date}.mp3"
        duration = float(subprocess.check_output(["ffprobe", "-v", "error", "-show_entries", "format=duration", "-of", "default=nw=1:nk=1", str(source)]))
        original = decode_excerpt(source, duration*.5-2, 14)
        original *= min(1, .6/max(abs(original).max(), 1e-20))
        clean_file = args.output/f"{date}-context.wav"
        mp3_file = args.output/f"{date}-added-64k.mp3"
        sf.write(clean_file, original, SR, subtype="FLOAT")
        subprocess.run(["ffmpeg", "-v", "error", "-y", "-i", str(clean_file), "-c:a", "libmp3lame", "-b:a", "64k", str(mp3_file)], check=True)
        compressed = decode_excerpt(mp3_file, 0, 14)
        if len(compressed) != len(original):
            raise ValueError("Controlled codec round trip changed length")
        for condition, data in [("natural", original), ("added_64kbps_mp3", compressed)]:
            tensor = torch.from_numpy(np.ascontiguousarray(data.T)).unsqueeze(0)
            started = time.monotonic()
            with torch.inference_mode():
                rendered = run_model(model, tensor, torch.device(args.device), chunk_samples=4*SR,
                    overlap_samples=SR, chunk_pad_samples=SR, chunk_batch_size=1)
            elapsed = time.monotonic()-started
            y = rendered.squeeze(0).numpy().T.copy()
            if y.shape != data.shape or not np.isfinite(y).all():
                raise ValueError("Invalid model output")
            # Ignore the model's documented file-edge defects using real context.
            center = slice(2*SR, 12*SR)
            x, y, reference = data[center], y[center], original[center]
            row = {"date": date, "condition": condition, "inference_seconds_for_14s_context": elapsed,
                   "source_sha256": hashlib.sha256(source.read_bytes()).hexdigest(), "variants": {}}
            versions = {"original": x, "Apollo": y, "Apollo25percent": .75*x+.25*y}
            for name, version in list(versions.items())[1:]:
                measurement = compare(x, version)
                if condition == "added_64kbps_mp3":
                    measurement["controlled_codec_reference_snr_before_db"] = reference_error(reference, x)
                    measurement["controlled_codec_reference_snr_after_db"] = reference_error(reference, version)
                    measurement["controlled_codec_error_improvement_db"] = reference_error(reference, version)-reference_error(reference, x)
                row["variants"][name] = measurement
                sf.write(args.output/f"{date}-{condition}-{name}.wav", version, SR, subtype="FLOAT")
            if condition == "natural":
                listening_versions(args.output, date, versions, answer_key)
            report["records"].append(row)
            (args.output/"neural-apollo-results.json").write_text(json.dumps(report, indent=2)+"\n")
            print(date, condition, round(elapsed, 2), "seconds", flush=True)
            if args.smoke_only:
                return
    (args.output/"answer-key.json").write_text(json.dumps(answer_key, indent=2)+"\n")
    write_listening_html(args.output)
    append_spectral_audit(args.output)


if __name__ == "__main__":
    main()
