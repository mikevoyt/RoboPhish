# Reproducing the saved-audio research

This guide reproduces the recorded desktop experiments. For the Android debug APK, including installation over Dev46, use [Saved audio versions](../../../docs/saved-audio-versions.md). The desktop classical and Apollo candidates are separate from the processor included in the app. See [METHOD.md](METHOD.md) for the scope and limitations of each result.

## Recorded environments

The DSP evaluation used macOS 15.5, Apple Silicon arm64, Python **3.11.2**, FFmpeg/FFprobe **8.0.1**, and Android Studio's OpenJDK **21.0.8**. [environment.json](evidence/environment.json) records these versions. Python 3.11 or newer is required by `hashlib.file_digest`; Java 17 or newer is required by the Java source. The Android Gradle build uses JDK 17 separately. The evaluator prefers `/Applications/Android Studio.app/Contents/jbr/Contents/Home/bin/{java,javac}` if present, otherwise the tools on `PATH`.

The DSP environment is pinned by [requirements.txt](requirements.txt), which includes the parent requirements:

| Package | Version |
| --- | --- |
| numpy | 2.1.3 |
| scipy | 1.14.1 |
| soundfile | 0.12.1 |
| pyloudnorm | 0.1.1 |
| matplotlib | 3.9.2 |
| nara_wpe | 0.0.11 |

Apollo used a **separate** Python 3.11.2 environment on the same macOS/arm64 host. Its NumPy and SoundFile versions differ. Do not install the DSP requirements into the neural environment. The complete installed Python package snapshot used by the neural experiment is included below; it was read from the local `audio-neural-probe/runtime-requirements.txt` and the direct dependencies were checked against the installed environment. Neither `torchaudio`, `einops` nor `omegaconf` was installed or required by this runner. The checkpoint loader uses inert placeholders for unused OmegaConf metadata.

FFmpeg/FFprobe, Git, curl, Java and enough disk space for original MP3s, temporary PCM, full WAVs and listening FLACs must be available separately. Full-track experiments take substantially more space than the downloaded sources. No audio or model weights are committed. Dependency installation and source acquisition need network access; inference uses local files. Matching the recorded environment improves comparability; another CPU/backend or decoder version is not evidence of identical numerical output or timing.

## Make a scratch copy before running scripts

Some scripts write beside themselves: `acquire.py` replaces `acquisition.json`; hard-case and classical scripts write evidence. Run them in a new scratch copy, keeping the published checkout and evidence untouched. The commands below copy only the research tree and Java source required by the desktop harness. This is sufficient for research, not an Android build. Use absolute paths and a new, nonexistent destination:

```sh
set -eu
export ROBO_SOURCE_REPO='/absolute/path/to/RoboPhish'
export ROBO_REPRO_ROOT='/absolute/path/to/new-reproduction'
test -d "$ROBO_SOURCE_REPO/research/audio/offline_restoration"
test ! -e "$ROBO_REPRO_ROOT"
mkdir -p "$ROBO_REPRO_ROOT/repo/mobile/src/main/java/com/bayapps/android/robophish/audio"
cp -R "$ROBO_SOURCE_REPO/research" "$ROBO_REPRO_ROOT/repo/research"
cp -R "$ROBO_SOURCE_REPO/mobile/src/main/java/com/bayapps/android/robophish/audio/offline" \
  "$ROBO_REPRO_ROOT/repo/mobile/src/main/java/com/bayapps/android/robophish/audio/offline"
export ROBO_STUDY="$ROBO_REPRO_ROOT/repo/research/audio/offline_restoration"
export ROBO_CORPUS="$ROBO_REPRO_ROOT/audio-corpus"
export ROBO_HARD_CASES="$ROBO_REPRO_ROOT/audio-hard-cases"

python3.11 -m venv "$ROBO_REPRO_ROOT/dsp-env"
export ROBO_DSP_PY="$ROBO_REPRO_ROOT/dsp-env/bin/python"
"$ROBO_DSP_PY" -m pip install -r "$ROBO_STUDY/requirements.txt"
cd "$ROBO_STUDY"
```

All following research commands assume these exported variables and this working directory. Original local artifact locations were under `/Users/mikevoyt/Documents/Codex/2026-11-01/task-3/`: `audio-corpus`, `audio-hard-cases`, `audio-evaluation-final-development`, `audio-evaluation-final-holdout`, `audio-classical-probe`, `audio-classical-full-track` and `audio-neural-probe`. Those directories are not supplied by a Git checkout.

## Acquire and verify the exact recordings

The original `manifest.json` has expected hashes for only 12 of its 24 entries. The published `acquisition.json` contains all 24 hashes and sizes; use it as the expected identity. Do not treat a newly downloaded file's own hash as proof that it matches the published source. If a hash differs or an archive URL no longer works, stop that reproduction and retain the mismatch; substituting another recording changes the experiment.

Create immutable expected-record copies and a strict acquisition manifest from the published data. The six exploratory sources have their complete identities in `evidence/exploratory-hard-cases.json`:

```sh
"$ROBO_DSP_PY" - <<'PY'
import json, os
from pathlib import Path
root, study = Path(os.environ['ROBO_REPRO_ROOT']), Path(os.environ['ROBO_STUDY'])
main = json.loads((study / 'acquisition.json').read_text())
assert main['complete'] and len(main['tracks']) == 24
hard = json.loads((study / 'evidence/exploratory-hard-cases.json').read_text())
assert hard['complete'] and len(hard['tracks']) == 6
extra = {'complete': True, 'tracks': [r['metadata'] for r in hard['tracks']]}
for label, data in [('corpus', main), ('hard-cases', extra)]:
    assert all(len(r['sha256']) == 64 and r['bytes'] > 0 for r in data['tracks'])
    (root / (label + '-expected.json')).write_text(json.dumps(data, indent=2) + '\n')
(root / 'strict-acquisition-manifest.json').write_text(json.dumps({'tracks': main['tracks']}, indent=2) + '\n')
PY

"$ROBO_DSP_PY" acquire.py --cache "$ROBO_CORPUS" \
  --manifest "$ROBO_REPRO_ROOT/strict-acquisition-manifest.json"
"$ROBO_DSP_PY" hard_cases.py --cache "$ROBO_HARD_CASES" --action download

"$ROBO_DSP_PY" - <<'PY'
import hashlib, json, os
from pathlib import Path
root = Path(os.environ['ROBO_REPRO_ROOT'])
checked = []
for label, env in [('corpus', 'ROBO_CORPUS'), ('hard-cases', 'ROBO_HARD_CASES')]:
    expected = json.loads((root / (label + '-expected.json')).read_text())
    for row in expected['tracks']:
        path = Path(os.environ[env]) / (row['date'] + '.mp3')
        with path.open('rb') as stream:
            actual = hashlib.file_digest(stream, 'sha256').hexdigest()
        if actual != row['sha256'] or path.stat().st_size != row['bytes']:
            raise RuntimeError('Published source identity mismatch: ' + str(path))
        checked.append({'date': row['date'], 'sha256': actual, 'bytes': path.stat().st_size})
(root / 'verified-source-identities.json').write_text(json.dumps(checked, indent=2) + '\n')
print('Verified', len(checked), 'published MP3 identities')
PY

# Retain fresh acquisition diagnostics separately, then use published metadata.
cp "$ROBO_STUDY/acquisition.json" "$ROBO_REPRO_ROOT/fresh-corpus-acquisition.json"
cp "$ROBO_HARD_CASES/acquisition.json" "$ROBO_REPRO_ROOT/fresh-hard-case-acquisition.json"
cp "$ROBO_REPRO_ROOT/corpus-expected.json" "$ROBO_STUDY/acquisition.json"
cp "$ROBO_REPRO_ROOT/hard-cases-expected.json" "$ROBO_HARD_CASES/acquisition.json"
```

Run the verification block again after processing if source storage might have changed. The main evaluator consumes acquisition metadata but does not independently verify every compressed MP3 hash before decode; the explicit verification above is therefore required. Do not run later commands after an unsuccessful acquisition or identity check.

## Reproduce the frozen Android processor on desktop

Verify the committed algorithm and protocol fingerprints before any run. Reuse the published freeze; do not pass `--freeze`, remove a guard, regenerate a manifest or retune against already-inspected recordings:

```sh
"$ROBO_DSP_PY" - <<'PY'
import json
from pathlib import Path
import evaluate
frozen = json.loads(Path('frozen-protocol.json').read_text())
assert evaluate.fingerprint() == frozen['core_fingerprint'], 'Java source changed'
assert evaluate.protocol_fingerprint() == frozen['protocol_fingerprint'], 'Protocol changed'
print('Published processor and protocol fingerprints match')
PY

"$ROBO_DSP_PY" evaluate.py --cache "$ROBO_CORPUS" \
  --output "$ROBO_REPRO_ROOT/audio-evaluation-final-development" \
  --split development --keep-full-wav
"$ROBO_DSP_PY" evaluate.py --cache "$ROBO_CORPUS" \
  --output "$ROBO_REPRO_ROOT/audio-evaluation-final-holdout" \
  --split holdout --keep-full-wav
"$ROBO_DSP_PY" hard_cases.py --cache "$ROBO_HARD_CASES" --action evaluate
```

The first two runs process 16 complete development and eight complete reserved recordings. The separate hard-case run processes six complete exploratory recordings. The desktop harness retains unchanged PCM/WAV for comparison when the processor abstains; the Android app records **Original retained** and creates no duplicate output. Published holdout results are already known, so a rerun is a replication, not a new untouched validation set. The retained development WAVs are also the inputs used to fit the historical classical cohort reference.

Optional diagnostic runs, also into fresh output directories:

```sh
"$ROBO_DSP_PY" controlled_degradation.py --corpus "$ROBO_CORPUS" \
  --output "$ROBO_REPRO_ROOT/controlled-results"
OPENBLAS_NUM_THREADS=1 OMP_NUM_THREADS=1 "$ROBO_DSP_PY" model_comparison.py \
  --corpus "$ROBO_CORPUS" --output "$ROBO_REPRO_ROOT/wpe-results"
```

## Classical candidate: frozen path limitation

`classical_probe.py` hardcodes `TASK = Path('/Users/mikevoyt/Documents/Codex/2026-11-01/task-3')` and has no source-root argument. `classical_full_track.py` imports it. Changing this constant changes the frozen script's hash. The scripts cannot currently reproduce unchanged on an arbitrary directory layout using only CLI options.

For an exact-script run, the following absolute paths must resolve to the verified data, in a disposable environment where that layout is available. The scratch study still supplies its own published `acquisition.json`, `classical-reference.json`, preregistrations and Java source. Do not overwrite existing artifact directories to reconstruct the layout.

| Path under the hardcoded task root | Required content |
| --- | --- |
| `audio-corpus/YYYY-MM-DD.mp3` | The 24 verified original recordings |
| `audio-hard-cases/YYYY-MM-DD.mp3` | The six verified exploratory recordings |
| `audio-hard-cases/acquisition.json` | The six published metadata/identity records produced above |
| `audio-evaluation-final-development/YYYY-MM-DD-enhanced.wav` | Needed only to reconstruct the original 16-track reference fit; not required to render with the supplied frozen reference |

The original task's directories already satisfy this layout. On another machine, explicitly reconstruct that layout or acknowledge that exact-script reproduction is blocked. A future configurable-path change needs its own reviewed fingerprint/equivalence record; do not silently edit frozen scripts or delete preregistrations. `prepare` verifies an existing freeze rather than refitting it.

With the layout available, use fresh outputs from the scratch study:

```sh
"$ROBO_DSP_PY" classical_probe.py --action prepare \
  --output "$ROBO_REPRO_ROOT/classical-passages"
"$ROBO_DSP_PY" classical_probe.py --action render \
  --output "$ROBO_REPRO_ROOT/classical-passages"
"$ROBO_DSP_PY" classical_full_track.py --action prepare \
  --output "$ROBO_REPRO_ROOT/classical-full-track"
"$ROBO_DSP_PY" classical_full_track.py --action render \
  --output "$ROBO_REPRO_ROOT/classical-full-track"
```

The passage script uses six fixed 20-second passages. The full-track wrapper validates one fixed, complete 2016-10-31 recording using the same frozen candidate. Neither is a full-corpus classical-candidate study or an Android implementation. It may rewrite evidence only in the scratch study. Consult METHOD for the measured identity/abstention and dynamics outcomes instead of assuming every produced file contains a correction.

## Apollo environment, source and checkpoint

Create a separate environment. This package snapshot records the local experiment; the upstream macOS requirements include additional packages that this inspected runner did not need:

```sh
python3.11 -m venv "$ROBO_REPRO_ROOT/neural-env"
export ROBO_NEURAL_PY="$ROBO_REPRO_ROOT/neural-env/bin/python"
cat > "$ROBO_REPRO_ROOT/neural-runtime-requirements.txt" <<'PINS'
anyio==4.16.0
certifi==2026.10.23
cffi==2.1.1
click==8.5.0
filelock==4.2.6
fsspec==2026.9.0
future==1.0.0
h11==0.16.0
hf-xet==1.8.0
httpcore==1.0.9
httpx==0.28.1
huggingface_hub==1.27.0
idna==3.20
Jinja2==3.1.6
MarkupSafe==3.0.4
mpmath==1.3.0
networkx==3.6.1
numpy==1.26.4
packaging==26.3
pycparser==3.12
pyloudnorm==0.1.1
PyYAML==6.0.3
scipy==1.14.1
soundfile==0.14.0
sympy==1.14.0
torch==2.11.0
tqdm==4.70.1
typing_extensions==4.16.0
PINS
"$ROBO_NEURAL_PY" -m pip install -r "$ROBO_REPRO_ROOT/neural-runtime-requirements.txt"

export ROBO_APOLLO_SOURCE="$ROBO_REPRO_ROOT/Apollo"
export ROBO_APOLLO_CHECKPOINT="$ROBO_REPRO_ROOT/pytorch_model.bin"
git clone https://github.com/JusperLee/Apollo.git "$ROBO_APOLLO_SOURCE"
git -C "$ROBO_APOLLO_SOURCE" checkout --detach e84bcacc59d5455f05d86a5c97dd4aeb3c14dbb6
curl --fail --location --output "$ROBO_APOLLO_CHECKPOINT.part" \
  https://huggingface.co/JusperLee/Apollo/resolve/c68bd80/pytorch_model.bin
mv "$ROBO_APOLLO_CHECKPOINT.part" "$ROBO_APOLLO_CHECKPOINT"
```

These are the official source/checkpoint locations used in the record. Apollo's inspected source/model terms are CC BY-SA 4.0; retain its attribution and review the upstream terms before redistributing derivatives. The commands acquire local research inputs, not APK assets. Validate the checkpoint and frozen code before loading weights:

```sh
"$ROBO_NEURAL_PY" - <<'PY'
import hashlib, json, os, subprocess
from pathlib import Path
study = Path(os.environ['ROBO_STUDY'])
source = Path(os.environ['ROBO_APOLLO_SOURCE'])
freeze = json.loads((study / 'evidence/neural-apollo-freeze.json').read_text())
revision = subprocess.check_output(['git', '-C', str(source), 'rev-parse', 'HEAD'], text=True).strip()
assert revision == freeze['source_commit']
assert not subprocess.check_output(['git', '-C', str(source), 'status', '--porcelain'], text=True).strip()
for path, expected in [
    (Path(os.environ['ROBO_APOLLO_CHECKPOINT']), freeze['checkpoint_sha256']),
    (source / 'inference.py', freeze['upstream_inference_sha256']),
    (study / 'neural_probe.py', freeze['script_sha256']),
]:
    with path.open('rb') as stream:
        actual = hashlib.file_digest(stream, 'sha256').hexdigest()
    assert actual == expected, str(path) + ': frozen identity mismatch'
print('Apollo checkout, checkpoint and probe match published identities')
PY
```

The expected checkpoint SHA-256 is `99d9af7f1ff20e63c393035513a655392818d66b4d7fc23d658175c1f15e8d76`. `neural_probe.py` also enforces the source revision and checkpoint digest and uses its restricted `weights_only=True` loader. Do not replace it with upstream checkpoint-loading commands.

Run from the scratch study. The recorded inference device was Apple MPS; the script checks availability. On other hosts explicitly pass `--device cpu`, which is supported but much slower and was not benchmarked for the entire validation set. Changing backend requires recording that difference. Use new output directories; validation refuses to overwrite an existing freeze:

```sh
"$ROBO_NEURAL_PY" neural_probe.py --source "$ROBO_APOLLO_SOURCE" \
  --checkpoint "$ROBO_APOLLO_CHECKPOINT" --corpus "$ROBO_CORPUS" \
  --output "$ROBO_REPRO_ROOT/apollo-development" --device mps
"$ROBO_NEURAL_PY" neural_probe.py --source "$ROBO_APOLLO_SOURCE" \
  --checkpoint "$ROBO_APOLLO_CHECKPOINT" --corpus "$ROBO_CORPUS" \
  --output "$ROBO_REPRO_ROOT/apollo-validation" --device mps --validation-stage
"$ROBO_NEURAL_PY" export_probe.py --source "$ROBO_APOLLO_SOURCE" \
  --checkpoint "$ROBO_APOLLO_CHECKPOINT" \
  --output "$ROBO_REPRO_ROOT/apollo-export.json"
```

The first command evaluates four development passages with natural and controlled-codec conditions. The second evaluates one complete 1996-12-06 recording plus two reserved 14-second contexts with 10-second measurement regions. It does **not** render all 24 recordings. Export capture is a CPU diagnostic, not an Android runtime test; `--check-parity` adds a separate, potentially slow execution comparison. Full-track timing, tail fidelity, absent listener ratings and mobile limitations remain as reported in METHOD. Reproduced output is new evidence alongside the published record; do not replace the original freeze or claim a fresh untouched holdout.
