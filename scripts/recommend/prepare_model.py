#!/usr/bin/env python3
"""Online staging only; inference service must never run this helper.
Run as an unprivileged maintainer, inspect, then install read-only under /opt.
"""
import argparse
import os
from pathlib import Path
import re
import shutil
import tempfile


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--revision", required=True)
    args = parser.parse_args()
    if not re.fullmatch(r"[0-9a-f]{40}", args.revision):
        parser.error("revision must be a full model commit SHA")
    if args.output.exists():
        parser.error("output must be a new directory; existing models are never overwritten")
    os.environ["HF_HUB_DISABLE_TELEMETRY"] = "1"
    from huggingface_hub import snapshot_download
    path = Path(snapshot_download(repo_id="minishlab/potion-multilingual-128M", revision=args.revision,
                                 allow_patterns=["model.safetensors", "tokenizer.json", "config.json"]))
    args.output.parent.mkdir(parents=True, exist_ok=True)
    temporary = Path(tempfile.mkdtemp(dir=args.output.parent, prefix="content-model-"))
    try:
        for name in ("model.safetensors", "tokenizer.json", "config.json"):
            shutil.copyfile(path / name, temporary / name)
        (temporary / "REVISION").write_text(args.revision + "\n")
        temporary.rename(args.output)
    finally:
        if temporary.exists():
            shutil.rmtree(temporary)
    print(f"Staged pinned model: {args.output}")


if __name__ == "__main__":
    main()
