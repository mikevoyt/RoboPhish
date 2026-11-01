"""Separate, post-holdout exploratory stress study of six documented AUD tapes.

Never changes the locked 24-track corpus, its split, or the frozen processor.
Metadata defects are historical taper observations, not our listening ratings.
Some sources were previously mastered; this does not establish residual severity.
"""
import argparse
from datetime import datetime, timezone
import json
from pathlib import Path
import re
import subprocess

import acquire
import evaluate as evaluation

ROOT = Path(__file__).resolve().parent
MANIFEST = ROOT / "hard-case-manifest.json"
EVIDENCE = ROOT / "evidence/exploratory-hard-cases.json"
INVENTORY_URL = (
    "https://phish.in/api/v2/shows?year_range=1987-1991"
    "&audio_status=complete_or_partial&per_page=1000&sort=date:asc"
)
SELECTIONS = [
    ("1989-03-03", "Foam", "Set 1",
     "AUD cassette of unknown generation; taper describes many analog generations, "
     "severe hiss and incorrect pitch. Pitch adjustment and graphic EQ were already attempted.",
     "hiss|pitch|generations"),
    ("1989-04-30", "Divided Sky", "Set 1",
     "Unknown AUD cassette; master distortion and first-side print-through audible in quiet passages. "
     "Print-through applicability to this song is not confirmed from the notes.",
     "distortion|print through"),
    ("1990-01-29", "Jesus Just Left Chicago", "Set 2",
     "Unknown microphones on the bar, master cassette; distant perspective, nearby screaming "
     "and conversation, and level fluctuations. The notes describe the source overall.",
     "distant|fluctuations"),
    ("1990-04-29", "Walk Away", "Set 1",
     "AUD master cassette; source notes explicitly identify an obstruction and phase disturbance "
     "during Walk Away, plus boom/wind. Prior mastering included denoising, EQ, excitation and limiting.",
     "boomy|phase|Mastering info"),
    ("1991-03-09", "Reba", "Set 1",
     "Sennheiser microphones to cassette; documented warble/dropouts at the Reba climax, "
     "bass-heavy/boomy room sound and level issues. Notes explicitly describe little hiss.",
     "hiss|warbling|boomy"),
    ("1991-10-31", "Harry Hood", "Set 2",
     "FOB AUD analog cassette, estimated fifth generation or more; muddy second set, wow/flutter, "
     "pitch, level and microphone-direction changes. Notes also identify a cut immediately at Hood's end.",
     "5th|wow|muddy"),
]


def write_json(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, indent=2) + "\n")


def locked_core():
    frozen = json.loads((ROOT / "frozen-protocol.json").read_text())
    if evaluation.fingerprint() != frozen["core_fingerprint"]:
        raise RuntimeError("Frozen core changed; exploratory study cannot silently retune")
    if evaluation.protocol_fingerprint() != frozen["protocol_fingerprint"]:
        raise RuntimeError("Locked 24-track evaluation protocol changed")
    return frozen["core_fingerprint"]


def prepare(cache):
    """Lock metadata selections before any new audio is downloaded or analyzed."""
    frozen_core = locked_core()
    if MANIFEST.exists():
        manifest = json.loads(MANIFEST.read_text())
        if manifest["frozen_core_fingerprint"] != frozen_core:
            raise RuntimeError("Existing exploratory manifest belongs to another processor")
        return manifest
    meta = cache / "metadata"
    meta.mkdir(parents=True, exist_ok=True)
    inventory = meta / "early-shows.json"
    if not inventory.exists():
        acquire.fetch(INVENTORY_URL, inventory)
    original_dates = {row["date"] for row in json.loads((ROOT / "manifest.json").read_text())["tracks"]}
    rows = []
    for date, title, set_name, rationale, pattern in SELECTIONS:
        if date in original_dates:
            raise RuntimeError("Exploratory recording overlaps locked corpus")
        show = acquire.metadata(date, meta)
        tracks = [t for t in show["tracks"] if t["title"] == title and t["set_name"] == set_name]
        if len(tracks) != 1 or not tracks[0].get("mp3_url"):
            raise RuntimeError("Ambiguous or missing full recording: " + date)
        track = tracks[0]
        tags = [t["name"] for t in show.get("tags", []) + track.get("tags", [])]
        notes = show.get("taper_notes", "").replace("\r", "")
        if show.get("sbd") is True or any(re.search(r"\b(?:D?SBD|soundboard)\b", t, re.I) for t in tags):
            raise RuntimeError("Soundboard tag: " + date)
        source_lines = re.findall(r"(?:Source\s*:\s*[^\n]+)", notes, re.I)
        if any(re.search(r"\b(?:D?SBD|soundboard)\b", t, re.I) for t in source_lines):
            raise RuntimeError("Soundboard source lineage: " + date)
        if not acquire.source_ok(show):
            raise RuntimeError("No microphone/audience lineage evidence: " + date)
        evidence = [p for p in re.split(r"\n\s*\n", notes) if re.search(pattern, p, re.I)]
        if not evidence:
            raise RuntimeError("Missing documented defect evidence: " + date)
        rows.append(dict(date=date, title=title, set_name=set_name, track_id=track["id"],
                         url=track["mp3_url"], duration=track["duration"] / 1000,
                         venue=show.get("venue_name"), location=show.get("venue", {}).get("location"),
                         split="exploratory", source_tags=tags, source_notes=notes,
                         source_lineage=source_lines, selection_reason=rationale,
                         documented_defect_paragraphs=evidence,
                         metadata_url="https://phish.in/api/v2/shows/" + date,
                         metadata_sha256=evaluation.file_hash(meta / (date + ".json"))))
    if len(rows) != 6 or len(original_dates) + len(rows) > 30:
        raise RuntimeError("Unexpected corpus size")
    manifest = dict(study="Post-holdout exploratory stress cases; never confirmatory holdout",
                    locked_at_utc=datetime.now(timezone.utc).isoformat(),
                    locked_before_audio_download_and_analysis=True,
                    selection_basis="Reviewed 1987–1991 public metadata; chosen for explicit documented defects, not age alone. No 1987/88 source had comparably clear residual defect evidence in this selection.",
                    caveat="Historical taper notes do not quantify current defects; previously applied mastering can alter them. No listening quality claims or noise labels are inferred from recording age.",
                    frozen_core_fingerprint=frozen_core, inventory_url=INVENTORY_URL,
                    inventory_sha256=evaluation.file_hash(inventory), tracks=rows)
    write_json(MANIFEST, manifest)
    return manifest


def download(cache, manifest):
    acquisitions = []
    for row in manifest["tracks"]:
        source = cache / (row["date"] + ".mp3")
        if not source.exists():
            acquire.fetch(row["url"], source)
        info = json.loads(subprocess.check_output([
            "ffprobe", "-v", "error", "-show_format", "-show_streams", "-of", "json", str(source)]))
        audio = next(s for s in info["streams"] if s["codec_type"] == "audio")
        acquisitions.append({**row, "sha256": evaluation.file_hash(source),
                             "bytes": source.stat().st_size,
                             "duration": float(info["format"]["duration"]),
                             "sample_rate": int(audio["sample_rate"]), "channels": audio["channels"]})
        print("ACQUIRED", row["date"], row["title"], source.stat().st_size, flush=True)
    write_json(cache / "acquisition.json", dict(complete=True, tracks=acquisitions))
    return acquisitions


def analyze(cache):
    frozen_core = locked_core()
    acquisition = json.loads((cache / "acquisition.json").read_text())
    if not acquisition["complete"] or len(acquisition["tracks"]) != 6:
        raise RuntimeError("Need all six explicitly selected sources")
    out = cache / "evaluation"
    out.mkdir(exist_ok=True)
    classes = out / "classes"
    java = evaluation.compile_java(classes)
    if json.loads((classes / "source-fingerprint.json").read_text()) != frozen_core:
        raise RuntimeError("Compiled engine differs from frozen core")
    listening = out / "listening"
    listening.mkdir(exist_ok=True)
    rows, trials, answer_key = [], [], []
    for metadata in acquisition["tracks"]:
        date, rate, channels = metadata["date"], metadata["sample_rate"], metadata["channels"]
        source = cache / (date + ".mp3")
        if evaluation.file_hash(source) != metadata["sha256"]:
            raise RuntimeError("Source changed before analysis: " + date)
        original, processed = out / (date + "-original.s16"), out / (date + "-processed.s16")
        engine_path = out / (date + "-engine.json")
        print("RENDER", date, flush=True)
        evaluation.decode(source, original, rate, channels)
        evaluation.run([java, "-Xmx512m", "-cp", classes, "OfflineRender",
                        original, processed, engine_path, rate, channels], stdout=subprocess.DEVNULL)
        engine = json.loads(engine_path.read_text())
        passthrough = engine["profile"]["passthrough"]
        original_hash, processed_hash = evaluation.file_hash(original), evaluation.file_hash(processed)
        identical = original_hash == processed_hash
        if passthrough and not identical:
            raise RuntimeError("Abstained render changed decoded samples")
        a, ae, af = evaluation.cached_original_metrics(original, rate, channels, cache / "metric-cache")
        b, be, bf = (a, ae, af) if identical else evaluation.metrics(processed, rate, channels)
        comparison = evaluation.compare(a, b, ae, be, af, bf, passthrough)
        row = dict(metadata=metadata, engine=engine, original=a, enhanced=b, comparison=comparison,
                   decoded_original_sha256=original_hash, rendered_pcm_sha256=processed_hash,
                   bit_exact=identical, source_sha256_after=evaluation.file_hash(source))
        rows.append(row)
        write_json(out / (date + "-metrics.json"), row)
        clips, keys = evaluation.listening_pair(original, processed, metadata, rate, channels, listening)
        for clip in clips:
            clip["full_original"] = "../../" + date + ".mp3"
            clip["full_rendered"] = "../" + date + "-processed.wav"
        trials.extend(clips)
        answer_key.extend(keys)
        evaluation.run(["ffmpeg", "-v", "error", "-y", "-f", "s16le", "-ar", rate,
                        "-ac", channels, "-i", processed, "-c:a", "pcm_s16le", out / (date + "-processed.wav")])
        original.unlink()
        processed.unlink()
        locked_core()
        evidence = dict(study="Exploratory extension after locked holdout; no tuning or confirmatory claims",
                        complete=len(rows) == 6, manifest_sha256=evaluation.file_hash(MANIFEST),
                        script_sha256=evaluation.file_hash(Path(__file__)),
                        core_fingerprint=frozen_core, summary=evaluation.summarize(rows), tracks=rows,
                        limitations="Documented historical defects are not independent reference audio. All listening ratings remain empty. Preservation bounds cannot prove improvement or soundboard equivalence.")
        write_json(EVIDENCE, evidence)
        write_json(out / "exploratory-results.json", evidence)
        write_json(listening / "trials.json", trials)
        write_json(listening / "answer-key.json", answer_key)
        evaluation.make_page(listening, trials)
        print("RESULT", date, "abstained", passthrough, "bit_exact", identical,
              "checks_passed", comparison["all_preservation_checks_pass"], flush=True)
    print(json.dumps(evaluation.summarize(rows), indent=2), flush=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--cache", type=Path, required=True)
    parser.add_argument("--action", choices=["prepare", "download", "evaluate"], required=True)
    args = parser.parse_args()
    args.cache.mkdir(parents=True, exist_ok=True)
    manifest = prepare(args.cache)
    if args.action == "download":
        download(args.cache, manifest)
    elif args.action == "evaluate":
        analyze(args.cache)
    else:
        print(json.dumps([dict(date=t["date"], title=t["title"], reason=t["selection_reason"])
                          for t in manifest["tracks"]], indent=2))


if __name__ == "__main__":
    main()
