#!/usr/bin/env python3
"""Sign and stage a built graph for publication; separate from the model process.

The model service never sees the signing key. The operator runs this process
with the graph, the private key and the matching public key; it refuses to
stage anything unless the signature verifies. The output is the exact graph
bytes plus `<graph>.sig` (base64 DER ECDSA P-256, SHA-256) for the HTTPS store.
"""
import argparse
import base64
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile

MAX_BYTES = 8 * 1024 * 1024
SIGNATURE_MAX_BYTES = 4 * 1024


def sign(graph: Path, key: Path) -> bytes:
    with tempfile.TemporaryDirectory() as directory:
        raw = Path(directory) / "graph.sig"
        subprocess.run(
            ["openssl", "dgst", "-sha256", "-sign", str(key), "-out", str(raw), str(graph)],
            check=True, capture_output=True)
        return raw.read_bytes()


def verify(graph: Path, public_key: Path, signature: bytes) -> bool:
    with tempfile.TemporaryDirectory() as directory:
        raw = Path(directory) / "graph.sig"
        raw.write_bytes(signature)
        result = subprocess.run(
            ["openssl", "dgst", "-sha256", "-verify", str(public_key),
             "-signature", str(raw), str(graph)],
            capture_output=True, text=True)
        return result.returncode == 0 and "Verified OK" in result.stdout


def validate(graph: Path) -> bytes:
    if graph.stat().st_size > MAX_BYTES:
        raise ValueError("graph exceeds 8 MiB")
    content = graph.read_bytes()
    payload = json.loads(content)
    if not isinstance(payload, dict) or payload.get("schemaVersion") != 1:
        raise ValueError("graph is not schemaVersion 1")
    keys, rows = payload.get("workKeys"), payload.get("neighbors")
    if not isinstance(keys, list) or not isinstance(rows, list) or len(keys) != len(rows):
        raise ValueError("graph workKeys/neighbors mismatch")
    if not 2 <= len(keys) <= 20_000:
        raise ValueError("graph must contain 2..20000 works")
    return content


def deliver(graph: Path, key: Path, public_key: Path, output_dir: Path) -> dict:
    content = validate(graph)
    signature = sign(graph, key)
    if not signature or len(signature) > SIGNATURE_MAX_BYTES:
        raise ValueError("signature is missing or too large")
    if not verify(graph, public_key, signature):
        raise ValueError("signature does not verify with the supplied public key")
    output_dir.mkdir(parents=True, exist_ok=True)
    graph_out = output_dir / "graph.json"
    signature_out = output_dir / "graph.json.sig"
    graph_tmp = output_dir / ".graph.json.tmp"
    signature_tmp = output_dir / ".graph.json.sig.tmp"
    try:
        graph_tmp.write_bytes(content)
        signature_tmp.write_text(base64.b64encode(signature).decode("ascii") + "\n")
        os.chmod(graph_tmp, 0o644)
        os.chmod(signature_tmp, 0o644)
        os.replace(graph_tmp, graph_out)
        os.replace(signature_tmp, signature_out)
    finally:
        graph_tmp.unlink(missing_ok=True)
        signature_tmp.unlink(missing_ok=True)
    return {"graph": str(graph_out), "signature": str(signature_out),
            "bytes": len(content), "signatureBytes": len(signature)}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--graph", required=True, type=Path)
    parser.add_argument("--key", required=True, type=Path)
    parser.add_argument("--public-key", required=True, type=Path)
    parser.add_argument("--output-dir", required=True, type=Path)
    args = parser.parse_args()
    print(json.dumps(deliver(args.graph, args.key, args.public_key, args.output_dir)))


if __name__ == "__main__":
    main()
