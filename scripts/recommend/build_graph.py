#!/usr/bin/env python3
"""Build a public Work-neighbour artifact on a CPU worker; never takes listener history.
Input: {"works": [{"workKey": <existing canonical key>, "text": <public metadata>}]}.
No publishing or scheduling side effects. Model and revision are part of every cache key.
"""
import argparse
import hashlib
import json
import math
import os
from pathlib import Path
import re
import sqlite3
import tempfile
import time

MODEL = "minishlab/potion-multilingual-128M"
# The exact files a staged model directory must contain; the systemd unit's
# ConditionPathExists list is checked against this tuple by the unit test.
MODEL_FILES = ("model.safetensors", "tokenizer.json", "config.json")
MAX_BYTES = 8 * 1024 * 1024
MAX_WORKS = 20_000
MAX_CATALOG_BYTES = 64 * 1024 * 1024


def atomic_write(path, data):
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = None
    try:
        with tempfile.NamedTemporaryFile(dir=path.parent, delete=False) as stream:
            temporary = stream.name
            stream.write(data)
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(temporary, path)
    finally:
        if temporary and os.path.exists(temporary):
            os.unlink(temporary)


def read_catalog(path):
    with Path(path).open("rb") as stream:
        content = stream.read(MAX_CATALOG_BYTES + 1)
    if len(content) > MAX_CATALOG_BYTES:
        raise ValueError("catalog exceeds 64 MiB")
    payload = json.loads(content)
    if not isinstance(payload, dict) or set(payload) != {"works"}:
        raise ValueError("only a public works catalog is accepted")
    rows = payload["works"]
    if not isinstance(rows, list) or not 2 <= len(rows) <= MAX_WORKS:
        raise ValueError("catalog must contain 2..20000 public Works")
    result = {}
    for row in rows:
        key, text = row["workKey"], row["text"]
        if not isinstance(key, str) or not key.strip() or len(key) > 512:
            raise ValueError("invalid canonical workKey")
        if key in result:
            raise ValueError("duplicate workKey")
        if not isinstance(text, str) or not text.strip() or len(text) > 4000:
            raise ValueError("text must contain 1..4000 characters of public metadata")
        if set(row) != {"workKey", "text"}:
            raise ValueError("only workKey and public text are accepted, no listener fields")
        result[key] = text
    return sorted(result.items())


def cached_vectors(catalog, database, model_id, revision, factory):
    import numpy as np
    database = Path(database)
    database.parent.mkdir(parents=True, exist_ok=True)
    with sqlite3.connect(database) as db:
        db.execute("CREATE TABLE IF NOT EXISTS vectors (key TEXT PRIMARY KEY, vector BLOB NOT NULL)")
        hashes = [hashlib.sha256((model_id + "@" + revision + "\0" + text).encode()).hexdigest()
                  for _, text in catalog]
        vectors = []
        missing = []
        for index, digest in enumerate(hashes):
            row = db.execute("SELECT vector FROM vectors WHERE key=?", (digest,)).fetchone()
            vectors.append(np.frombuffer(row[0], dtype="<f4").copy() if row else None)
            if row is None:
                missing.append(index)
        if missing:
            model = factory()
            for start in range(0, len(missing), 32):
                indices = missing[start:start + 32]
                batch = np.asarray(model.encode([catalog[i][1] for i in indices]), dtype="<f4")
                if batch.ndim != 2 or len(batch) != len(indices) or not np.isfinite(batch).all():
                    raise ValueError("invalid model output")
                for index, vector in zip(indices, batch):
                    if not np.linalg.norm(vector) > 0:
                        raise ValueError("zero model vector")
                    vectors[index] = vector
                    db.execute("INSERT OR REPLACE INTO vectors VALUES (?,?)", (hashes[index], vector.tobytes()))
        matrix = np.asarray(vectors, dtype=np.float32)
        if matrix.ndim != 2 or not np.isfinite(matrix).all():
            raise ValueError("invalid cached vectors")
        norms = np.linalg.norm(matrix, axis=1, keepdims=True)
        if not (norms > 0).all():
            raise ValueError("zero cached vector")
        # A cache is derived data; keep only the current corpus/model after a successful build.
        keep = set(hashes)
        db.executemany("DELETE FROM vectors WHERE key=?",
                       ((key,) for (key,) in db.execute("SELECT key FROM vectors").fetchall() if key not in keep))
        return matrix / norms, len(missing)


def build_graph(catalog, matrix, revision, now, top_k=24, ttl_days=7):
    import numpy as np
    if not 1 <= top_k <= 32 or not 1 <= ttl_days <= 7:
        raise ValueError("top-k 1..32; ttl-days 1..7")
    if len(matrix) != len(catalog):
        raise ValueError("catalog/vector mismatch")
    neighbors = []
    # Never allocate N x N: at 20000 Works, each score block is <= 10 MiB.
    for start in range(0, len(matrix), 128):
        scores = matrix[start:start + 128] @ matrix.T
        for offset, row in enumerate(scores):
            row[start + offset] = -1
            # Stable sort resolves equal similarities by canonical Work order.
            indices = np.argsort(-row, kind="stable")[:top_k]
            neighbors.append([[int(i), round(min(1.0, float(row[i])), 6)]
                              for i in indices if math.isfinite(float(row[i])) and row[i] > 0.000001])
    return {"schemaVersion": 1, "model": MODEL, "revision": revision,
            "generatedAt": now, "expiresAt": now + ttl_days * 86_400_000,
            "catalogSha256": hashlib.sha256(json.dumps(catalog, ensure_ascii=False).encode()).hexdigest(),
            "workKeys": [key for key, _ in catalog], "neighbors": neighbors}


def load_model(revision, model_dir=None):
    if model_dir is not None:
        model_dir = Path(model_dir)
        # Deployment copies pinned weights to a root-owned, read-only directory.
        # Missing local files fail here; never substitute an online model.
        if not model_dir.is_dir() or (model_dir / "REVISION").read_text().strip() != revision:
            raise ValueError("local model revision mismatch")
        for name in MODEL_FILES:
            if not (model_dir / name).is_file():
                raise ValueError("local model is incomplete")
        os.environ["HF_HUB_OFFLINE"] = "1"
        os.environ["HF_HUB_DISABLE_TELEMETRY"] = "1"
        from model2vec import StaticModel
        return StaticModel.from_pretrained(str(model_dir), force_download=False)
    from model2vec import StaticModel
    from huggingface_hub import snapshot_download
    path = snapshot_download(repo_id=MODEL, revision=revision,
                             allow_patterns=["*.json", "model.safetensors", "vocab.txt"])
    return StaticModel.from_pretrained(path)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--catalog", required=True, type=Path)
    parser.add_argument("--cache", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--revision", required=True, help="Pinned 40-character Hugging Face commit SHA")
    parser.add_argument("--model-dir", type=Path, help="Use pre-staged local weights, never download")
    parser.add_argument("--top-k", type=int, default=24)
    parser.add_argument("--ttl-days", type=int, default=7)
    args = parser.parse_args()
    if not re.fullmatch(r"[0-9a-f]{40}", args.revision):
        parser.error("--revision must be an immutable 40-character commit SHA")
    if not 1 <= args.top_k <= 32 or not 1 <= args.ttl_days <= 7:
        parser.error("top-k 1..32; ttl-days 1..7")
    # Set before importing NumPy/BLAS. The worker stays usable for other work.
    for name in ("OPENBLAS_NUM_THREADS", "OMP_NUM_THREADS", "MKL_NUM_THREADS", "TOKENIZERS_PARALLELISM"):
        os.environ[name] = "false" if name == "TOKENIZERS_PARALLELISM" else "2"
    catalog = read_catalog(args.catalog)
    matrix, computed = cached_vectors(catalog, args.cache, MODEL, args.revision,
                                       lambda: load_model(args.revision, args.model_dir))
    graph = build_graph(catalog, matrix, args.revision, int(time.time() * 1000), args.top_k, args.ttl_days)
    content = json.dumps(graph, ensure_ascii=False, separators=(",", ":")).encode()
    if len(content) > MAX_BYTES:
        raise ValueError("artifact exceeds Android 8 MiB limit; reduce --top-k")
    atomic_write(args.output, content)
    print(json.dumps({"works": len(catalog), "computed": computed, "bytes": len(content),
                      "output": str(args.output)}))


if __name__ == "__main__":
    main()
