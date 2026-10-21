#!/usr/bin/env python3
"""Whitelist a single public Work's diagnostic files; never glob repository data."""
import hashlib
import json
import os
from pathlib import Path
import shutil
import sys

def upload_flag(value):
    target = os.environ.get('GITHUB_OUTPUT')
    if target:
        with open(target, 'a') as stream:
            stream.write(f'upload_allowed={value}\n')

upload_flag('false')
payload, stage = map(Path, sys.argv[1:])
raw, artifact = stage / 'native', stage / 'artifact'
artifact.mkdir(exist_ok=False)
allowed = [
    'work-id.txt', 'unprefixed-text.utf8', 'actual-prefixed-text.utf8',
    'actual-token-ids.i32be', 'heap-inputIds.i64be', 'heap-attention.i64be',
    'heap-tokenTypes.i64be', 'bound-input-0.i64be', 'bound-input-1.i64be',
    'bound-input-2.i64be', 'actual-hidden-java.f32be',
    'actual-hidden-native-read.f32be', 'actual-pooled-sum.f32be',
    'actual-norm.f64be', 'actual-pool-return.f32be', 'actual-embed-return.f32be',
    'launcher-return.f32be', 'launcher-provenance.properties',
    'observer.properties', 'vm.stdout', 'vm.stderr',
]
sha = lambda path: hashlib.sha256(path.read_bytes()).hexdigest()
reasons = []
props = {}
if (raw / 'observer.properties').is_file():
    for line in (raw / 'observer.properties').read_text().splitlines():
        if line and not line.startswith('#') and '=' in line:
            key, value = line.split('=', 1)
            props[key] = value
native_complete = props.get('complete') == 'true' and props.get('forcedTimeout') == 'false'
expected = json.loads((payload / 'mac-input-pins.json').read_text())
for name, digest in expected.items():
    if not (raw / name).is_file() or sha(raw / name) != digest:
        reasons.append('Missing or changed same-Work input boundary: ' + name)
if props.get('actual.seqLen') != '302':
    reasons.append('Actual token count must remain MacV2 302')
for index, name in enumerate(['input_ids', 'attention_mask', 'token_type_ids']):
    if props.get(f'bound-input-{index}.name') != name or props.get(f'bound-input-{index}.shape') != '[1, 302]':
        reasons.append('Bound input name/shape differs from MacV2: ' + name)
if props.get('selected-output.name') != 'last_hidden_state' or props.get('selected-output.shape') != '[1, 302, 384]':
    reasons.append('Selected output name/shape differs from MacV2')
for name in allowed:
    source = raw / name
    if source.is_file():
        if source.stat().st_size > 512 * 1024:
            reasons.append('Refused oversized whitelisted file: ' + name)
            continue
        shutil.copyfile(source, artifact / name)
# No model, tokenizer, dependency JAR, source/class bodies, profiles or catalog.
shutil.copyfile(payload / 'one-work.tsv', artifact / 'one-work.tsv')
producer = payload / 'build/producer.json'
if producer.is_file():
    shutil.copyfile(producer, artifact / 'producer.json')
state = {
    'scope': 'One frozen public Work only; no listener data or other31 texts',
    'nativeComplete': native_complete,
    'sameMacV2InputAnd302Tokens': not reasons,
    'reasons': reasons,
    'qualityGO': False,
    'platformOrKernelCauseProven': False,
    'nativeLibraryMappingObserved': False,
    'files': {p.name: {'bytes': p.stat().st_size, 'sha256': sha(p)} for p in sorted(artifact.iterdir())},
}
(artifact / 'scope.json').write_text(json.dumps(state, indent=2) + '\n')
if sum(p.stat().st_size for p in artifact.iterdir()) > 2 * 1024 * 1024:
    raise SystemExit('Artifact exceeds reviewed 2MiB public-Work budget; upload must not run')
upload_flag('true')
if not native_complete or reasons:
    raise SystemExit('Incomplete diagnostic or changed inputs; artifact is failure evidence, not parity')
