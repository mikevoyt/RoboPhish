"""Link a frozen experiment to formatting-only source edits with opcode proof.

Does not accept algorithm changes, incomplete development runs, or altered metric
protocols. The original compiled-source fingerprints remain in the record.
"""
import json
from pathlib import Path
from evaluate import ROOT, fingerprint, protocol_fingerprint, file_hash


def main():
    path=ROOT/"frozen-protocol.json"
    frozen=json.loads(path.read_text())
    proof_path=ROOT/"evidence/formatting-equivalence.json"
    proof=json.loads(proof_path.read_text())
    current=fingerprint()
    if frozen["protocol_fingerprint"]!=protocol_fingerprint():
        raise RuntimeError("Evaluation protocol changed")
    if frozen["development_summary"]["recordings"]!=16 or frozen["development_summary"]["checks_passed"]!=16:
        raise RuntimeError("Complete passing development evidence required")
    if proof.get("bytecode_instructions_equal") is not True or not proof["before"]["javap_sha256"] or proof["before"]["javap_sha256"]!=proof["after"]["javap_sha256"]:
        raise RuntimeError("Missing bytecode-instruction equivalence")
    for name,digest in frozen["core_fingerprint"].items():
        basename=Path(name).name
        if basename in proof["before"]["source_sha256"]:
            if digest!=proof["before"]["source_sha256"][basename] or current[name]!=proof["after"]["source_sha256"][basename]:
                raise RuntimeError("Source hashes do not match equivalence evidence")
        elif current[name]!=digest:
            raise RuntimeError("Unproved source change: "+name)
    frozen["original_development_fingerprint"]=frozen["core_fingerprint"]
    frozen["core_fingerprint"]=current
    frozen["formatting_equivalence"]={"path":str(proof_path.relative_to(ROOT)),"sha256":file_hash(proof_path),"explanation":"Readable formatting and comments only; compiled JVM instructions and constants match for all four DSP/profile classes. No algorithm or threshold changes."}
    path.write_text(json.dumps(frozen,indent=2)+"\n")
    print("Frozen formatted source linked to unchanged development bytecode.")


if __name__=="__main__":main()
