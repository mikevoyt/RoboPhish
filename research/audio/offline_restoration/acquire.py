"""Reproducible 24-track corpus acquisition; never uploads audio.

The split is locked from metadata before analysis. Existing twelve recordings are
all development data: their previous 'holdout' label did not survive inspection.
Only eight new recording dates are eligible for the confirmatory split.
"""
import argparse
import hashlib
import json
from pathlib import Path
import re
import subprocess
import time

ROOT = Path(__file__).resolve().parent
PRIOR = ROOT.parent / "evidence/corpus.json"
DEV_CANDIDATES = ["1994-10-31", "2004-06-18", "2010-10-31", "2024-08-16", "1994-11-26", "2004-06-19", "2010-08-14", "2024-08-17"]
HOLDOUT_CANDIDATES = ["1991-12-31", "1992-12-31", "1994-06-18", "1997-11-22", "1999-12-31", "2003-02-28", "2011-08-05", "2016-10-31", "2021-08-06", "2025-07-25", "2025-07-26", "2022-07-23", "2013-07-26", "2000-06-24", "1995-06-16"]


def fetch(url, destination):
    destination = Path(destination)
    partial = destination.with_suffix(destination.suffix + ".part")
    subprocess.run(["curl", "--fail", "--location", "--silent", "--show-error", "--retry", "2", "--connect-timeout", "20", "--max-time", "180", "--user-agent", "RoboPhish-AudioResearch/2.0", "--output", str(partial), url], check=True)
    partial.replace(destination)


def metadata(date, cache):
    path = cache / (date + ".json")
    if not path.exists():
        fetch("https://phish.in/api/v2/shows/" + date, path)
    show = json.loads(path.read_text())
    return show.get("data", show)


def source_ok(show):
    tags = " ".join(t.get("name", "") for t in show.get("tags", []))
    notes = show.get("taper_notes") or ""
    if show.get("sbd") is True or re.search(r"\bSBD\b", tags, re.I):
        return False
    # Do not misclassify 'behind the soundboard' (a microphone position) as SBD.
    source = re.search(r"(?:source|recording)\s*[:=]([^\n\r]{0,250})", notes, re.I)
    if source and re.search(r"\b(?:SBD|soundboard)\b", source.group(1), re.I):
        return False
    return bool(re.search(r"Schoeps|Neumann|Nakamichi|AKG|B[& ]K|DPA|Audio.?Technica|AT ?40|Microtech|Ge[f]+ell|audience|\bAUD\b|nak ?300|Sony ECM|Sennheiser", notes, re.I))


def select(show, split):
    tracks = [t for t in show.get("tracks", []) if t.get("mp3_url") and 180000 <= t.get("duration", 0) <= 1200000 and not re.search(r"\bSBD\b", " ".join(tag.get("name", "") for tag in t.get("tags", [])), re.I)]
    if not tracks:
        return None
    track = tracks[len(tracks) // 2]
    return dict(date=show["date"], title=track["title"], track_id=track["id"], url=track["mp3_url"], duration=track["duration"] / 1000, source_notes=show.get("taper_notes", ""), source_tags=" ".join(t.get("name", "") for t in show.get("tags", [])), venue=show.get("venue_name"), location=show.get("venue", {}).get("location"), split=split, prior_study=False)


def lock_manifest(cache, manifest):
    if manifest.exists():
        rows = json.loads(manifest.read_text())["tracks"]
        if len(rows) != 24:
            raise RuntimeError("Existing manifest is incomplete; do not silently replace split")
        return rows
    rows = json.loads(PRIOR.read_text())
    for row in rows:
        row["split"] = "development"
        row["prior_study"] = True
    rejected = []
    for split, dates, target in [("development", DEV_CANDIDATES, 16), ("holdout", HOLDOUT_CANDIDATES, 24)]:
        for date in dates:
            if len(rows) == target:
                break
            show = metadata(date, cache)
            okay = source_ok(show)
            candidate = select(show, split) if okay else None
            print("SOURCE", date, "accepted" if candidate else "excluded", show.get("venue_name"), flush=True)
            if candidate:
                rows.append(candidate)
            else:
                rejected.append(date)
            time.sleep(0.25)
        if len(rows) != target:
            raise RuntimeError(f"Not enough eligible {split} recordings; currently {len(rows)}")
    frozen = dict(protocol_version=1, locked_before_audio_analysis=True, split_rule="12 previously inspected recordings plus four new development dates; eight entirely new recording dates held out until algorithm and thresholds freeze", tracks=rows, rejected_metadata_dates=rejected)
    manifest.write_text(json.dumps(frozen, indent=2) + "\n")
    return rows


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--cache", type=Path, required=True)
    parser.add_argument("--manifest", type=Path, default=ROOT / "manifest.json")
    parser.add_argument("--metadata-only", action="store_true")
    args = parser.parse_args()
    args.cache.mkdir(parents=True, exist_ok=True)
    meta = args.cache / "metadata"
    meta.mkdir(exist_ok=True)
    rows = lock_manifest(meta, args.manifest)
    if args.metadata_only:
        print(json.dumps([dict(date=r["date"], title=r["title"], split=r["split"]) for r in rows], indent=2))
        return
    downloaded = []
    for row in rows:
        path = args.cache / (row["date"] + ".mp3")
        if not path.exists():
            fetch(row["url"], path)
        digest = hashlib.file_digest(path.open("rb"), "sha256").hexdigest()
        if row.get("sha256") and digest != row["sha256"]:
            raise RuntimeError("Source changed: " + row["date"])
        info = json.loads(subprocess.check_output(["ffprobe", "-v", "error", "-show_format", "-show_streams", "-of", "json", str(path)]))
        stream = next(s for s in info["streams"] if s["codec_type"] == "audio")
        downloaded.append({**row, "sha256": digest, "bytes": path.stat().st_size, "duration": float(info["format"]["duration"]), "sample_rate": int(stream["sample_rate"]), "channels": stream["channels"]})
        print("ACQUIRED", row["date"], row["split"], path.stat().st_size, flush=True)
        (ROOT / "acquisition.json").write_text(json.dumps(dict(tracks=downloaded, complete=len(downloaded) == 24), indent=2) + "\n")


if __name__ == "__main__":
    main()
