#!/usr/bin/env python3
"""Generate and verify the synthetic Android render-path fixture (Apache-2.0).

No third-party recording is used. Requires Python 3, FFmpeg with libmp3lame,
and a JDK; no Python packages. The signal deliberately has a known artificial
defect so a conservative algorithm can exercise the changed-output path.
It is a software fixture, not evidence of improvement on concert recordings.
"""
import hashlib
import json
import math
from pathlib import Path
import random
import struct
import subprocess
import tempfile
import wave

ROOT = Path(__file__).resolve().parents[3]
ASSET = ROOT / "mobile/src/androidTest/assets/offline-coloration.mp3"
REPORT = Path(__file__).with_name("fixtures") / "offline-coloration-profile.json"
RATE, SECONDS, CHANNELS, SEED = 44100, 8, 2, 642


def main():
    with tempfile.TemporaryDirectory(prefix="robophish-coloration-") as temporary:
        directory = Path(temporary)
        original = directory / "source.wav"
        rng = random.Random(SEED)
        gain = 10 ** (15 / 40)
        omega = 2 * math.pi * 315 / RATE
        alpha = math.sin(omega) / (2 * 1.2)
        a0 = 1 + alpha / gain
        b0, b1, b2 = (1 + alpha * gain) / a0, -2 * math.cos(omega) / a0, (1 - alpha * gain) / a0
        a1, a2 = b1, (1 - alpha / gain) / a0
        z1 = z2 = 0.0
        peak = 0.0
        with wave.open(str(original), "wb") as audio:
            audio.setparams((CHANNELS, 2, RATE, 0, "NONE", "not compressed"))
            for _ in range(SECONDS):
                block = bytearray()
                for _ in range(RATE):
                    x = (rng.random() - 0.5) * 0.25
                    y = b0 * x + z1
                    z1, z2 = b1 * x - a1 * y + z2, b2 * x - a2 * y
                    peak = max(peak, abs(y))
                    assert abs(y) < 0.95, "Fixture must not clip"
                    block.extend(struct.pack("<hh", round(y * 32768), round(y * 0.86 * 32768)))
                audio.writeframes(block)
        ASSET.parent.mkdir(parents=True, exist_ok=True)
        subprocess.run(["ffmpeg", "-v", "error", "-y", "-i", str(original), "-map_metadata", "-1",
                        "-codec:a", "libmp3lame", "-q:a", "5", str(ASSET)], check=True)
        decoded = directory / "decoded.s16"
        subprocess.run(["ffmpeg", "-v", "error", "-y", "-i", str(ASSET), "-ar", str(RATE),
                        "-ac", str(CHANNELS), "-f", "s16le", str(decoded)], check=True)
        classes = directory / "classes"
        classes.mkdir()
        package = ROOT / "mobile/src/main/java/com/bayapps/android/robophish/audio/offline"
        sources = [package / "OfflineRestorationProfile.java", package / "OfflineRestorationDsp.java"]
        subprocess.run(["javac", "-d", str(classes), *map(str, sources),
                        str(Path(__file__).with_name("OfflineRender.java"))], check=True)
        result = directory / "result.json"
        subprocess.run(["java", "-cp", str(classes), "OfflineRender", str(decoded),
                        str(directory / "render.s16"), str(result), str(RATE), str(CHANNELS)],
                       check=True, stdout=subprocess.DEVNULL)
        evidence = json.loads(result.read_text())
        assert not evidence["profile"]["passthrough"], "Fixture must exercise a real changed-output render"
        assert evidence["new_clipped_samples"] == 0
        evidence["fixture"] = {
            "description": "8 seconds of seeded synthetic broadband noise with a known +15dB 315Hz Q1.2 bell; right channel is 0.86 times left.",
            "license": "Apache-2.0, same as repository; no third-party audio",
            "seed": SEED, "sample_rate": RATE, "channels": CHANNELS,
            "original_peak": peak, "mp3_sha256": hashlib.sha256(ASSET.read_bytes()).hexdigest(),
            "engine_sha256": {path.name: hashlib.sha256(path.read_bytes()).hexdigest() for path in sources},
            "scope": "Test signal for rendering and persistence. Not evidence of perceived improvement on concert audio."
        }
        REPORT.parent.mkdir(parents=True, exist_ok=True)
        REPORT.write_text(json.dumps(evidence, indent=2) + "\n")
        print(json.dumps({"asset": str(ASSET.relative_to(ROOT)), "bytes": ASSET.stat().st_size,
                          "passthrough": evidence["profile"]["passthrough"],
                          "maximum_correction_db": evidence["profile"]["maximumCorrectionDb"],
                          "new_clipped_samples": evidence["new_clipped_samples"]}, indent=2))


if __name__ == "__main__":
    main()
