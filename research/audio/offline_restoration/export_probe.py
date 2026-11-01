#!/usr/bin/env python3
"""Bounded CPU export feasibility check; not an Android deployment validation.

Imports only the separately supplied, inspected Apollo checkout and safe loader.
No weights or exported model are redistributed by this script.
"""
import argparse
import json
from pathlib import Path
import signal
import subprocess
import sys
import time

import torch
from neural_probe import SOURCE_COMMIT, CHECKPOINT_SHA256, load_weights


def timeout(signum, frame):
    raise TimeoutError("Bounded export probe exceeded 120 seconds")


def attempt(module, shape, check_parity):
    started = time.monotonic()
    result = {"exported": False, "shape": shape, "phase": "capture"}
    try:
        # Nonzero deterministic values exercise the graph rather than obtaining
        # uninformative parity from two silent outputs. This tests one fixed
        # input shape only; it is not a replacement for held-out music tests.
        generator = torch.Generator().manual_seed(48731)
        example = torch.randn(shape, generator=generator) * 0.05
        exported = torch.export.export(module, (example,), strict=False)
        operators = sorted({str(node.target) for node in exported.graph.nodes if node.op == "call_function"})
        result.update(exported=True, export_seconds=time.monotonic() - started,
                      operators=operators, graph_nodes=len(list(exported.graph.nodes)))
        if check_parity:
            result["phase"] = "cpu_parity"
            with torch.inference_mode():
                reference = module(example)
                actual = exported.module()(example)
            error = actual - reference
            result["cpu_graph_parity"] = {
                    "stimulus": "seed48731 Gaussian noise, standard deviation0.05",
                    "output_shape": list(actual.shape),
                    "finite": bool(torch.isfinite(actual).all()),
                    "maximum_absolute_error": float(error.abs().max()),
                    "relative_rms_error": float(error.square().mean().sqrt()
                        / reference.square().mean().sqrt().clamp_min(1e-12)),
                    "scope": "One short fixed-shape CPU graph; no mobile backend or full-track parity"
                }
        result["phase"] = "complete"
    except Exception as error:
        # Preserve a completed capture if only execution/parity timed out.
        result.update(error_type=type(error).__name__, error=str(error)[:5000])
    result["seconds"] = time.monotonic() - started
    return result


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--source", type=Path, required=True)
    parser.add_argument("--checkpoint", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--check-parity", action="store_true",
                        help="Execute a short CPU graph; subject to the same total120s budget")
    args = parser.parse_args()
    revision = subprocess.check_output(["git", "-C", str(args.source), "rev-parse", "HEAD"], text=True).strip()
    if revision != SOURCE_COMMIT:
        raise ValueError("Unexpected source revision")
    weights, _ = load_weights(args.checkpoint)
    sys.path.insert(0, str(args.source))
    from look2hear.models.apollo import Apollo
    torch.set_num_threads(1)
    model = Apollo(sr=44100, win=20, feature_dim=256, layer=6).eval()
    model.load_state_dict(weights, strict=True)
    report = {"model": "Apollo", "source_revision": revision, "checkpoint_sha256": CHECKPOINT_SHA256,
              "torch_version": str(torch.__version__), "device": "cpu",
              "scope": "torch.export graph capture only; no ExecuTorch lowering, Android runtime, memory, thermal or battery validation"}
    signal.signal(signal.SIGALRM, timeout)
    signal.alarm(120)
    try:
        report["whole_waveform_model"] = attempt(model, (1, 2, 4410), args.check_parity)
        # A real-valued neural backbone could be paired with native STFT/band IO;
        # test it separately to identify whether the boundary is transform export.
        if report["whole_waveform_model"].get("error_type") != "TimeoutError":
            report["real_valued_backbone_only"] = attempt(model.net, (1, 80, 256, 10), args.check_parity)
    finally:
        signal.alarm(0)
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(json.dumps(report, indent=2)+"\n")
    print(json.dumps({key: value for key, value in report.items() if key not in ["whole_waveform_model", "real_valued_backbone_only"]}))
    for key in ["whole_waveform_model", "real_valued_backbone_only"]:
        value=report.get(key,{})
        print(key, "exported", value.get("exported"), "seconds", value.get("seconds"), "error", value.get("error_type"))


if __name__ == "__main__":
    main()
