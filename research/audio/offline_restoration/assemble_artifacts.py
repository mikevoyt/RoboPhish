"""Consolidate completed evidence and local listening outputs without uploads."""
import argparse
import json
import os
import shutil
from pathlib import Path
from evaluate import ROOT,file_hash,make_page,summarize


def main():
    parser=argparse.ArgumentParser()
    parser.add_argument("--development",type=Path,required=True)
    parser.add_argument("--holdout",type=Path,required=True)
    parser.add_argument("--cache",type=Path,required=True)
    parser.add_argument("--output",type=Path,required=True)
    args=parser.parse_args()
    args.output.mkdir(exist_ok=True)
    evidence=ROOT/"evidence"
    all_rows,all_trials,all_keys=[],[],[]
    for split,directory,expected in [("development",args.development,16),("holdout",args.holdout,8)]:
        result_path=directory/(split+"-results.json")
        result=json.loads(result_path.read_text())
        if len(result["tracks"])!=expected:
            raise RuntimeError("Incomplete "+split+" results")
        all_rows.extend(result["tracks"])
        shutil.copy2(result_path,evidence/("final-"+split+".json"))
        trials=json.loads((directory/"listening/trials.json").read_text())
        for trial in trials:
            for filename in trial["files"]:
                shutil.copy2(directory/"listening"/filename,args.output/filename)
            trial["full_original"]=os.path.relpath(args.cache/(trial["date"]+".mp3"),args.output)
            trial["full_rendered"]=os.path.relpath(directory/(trial["date"]+"-enhanced.wav"),args.output)
            if not (args.output/trial["full_rendered"]).exists():
                raise RuntimeError("Missing full-track render")
            all_trials.append(trial)
        all_keys.extend(json.loads((directory/"listening/answer-key.json").read_text()))
    if len(all_trials)!=96:
        raise RuntimeError("Expected 96 comparison pairs")
    integrity=[]
    for row in all_rows:
        metadata=row["metadata"]
        after=file_hash(args.cache/(metadata["date"]+".mp3"))
        if after!=metadata["sha256"]:
            raise RuntimeError("Original source changed: "+metadata["date"])
        integrity.append(dict(date=metadata["date"],original_sha256=after,unchanged=True))
    summary=summarize(all_rows)
    summary["maximum_candidate_static_correction_db"]=summary.pop("maximum_static_correction_db")
    summary["maximum_applied_static_correction_db"]=max((r["engine"]["profile"]["maximumCorrectionDb"] for r in all_rows if not r["engine"]["profile"]["passthrough"]),default=0)
    summary["full_track_renders"]=len(all_rows)
    summary["blinded_pairs"]=len(all_trials)
    summary["flac_excerpt_files"]=len(all_trials)*2
    summary["preserved_original_files"]=len(integrity)
    summary["interpretation"]="The conservative algorithm abstained wherever no correction qualified. Exact unchanged copies validate pipeline preservation, not enhancement. No listener preference, semantic crowd preservation, or soundboard-like improvement has been established."
    (evidence/"final-corpus-summary.json").write_text(json.dumps(summary,indent=2)+"\n")
    (evidence/"source-integrity.json").write_text(json.dumps(integrity,indent=2)+"\n")
    (args.output/"trials.json").write_text(json.dumps(all_trials,indent=2)+"\n")
    (args.output/"answer-key.json").write_text(json.dumps(all_keys,indent=2)+"\n")
    (args.output/"summary.json").write_text(json.dumps(summary,indent=2)+"\n")
    make_page(args.output,all_trials)
    print(json.dumps(summary,indent=2))


if __name__=="__main__":main()
