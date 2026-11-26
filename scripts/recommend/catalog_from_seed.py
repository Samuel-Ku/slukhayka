#!/usr/bin/env python3
"""Build the worker input from the app's bundled public catalogue seed.

The seed is the cold-start catalogue shipped in the APK; every entry is a real
public book card. `workKey` is the app's Work identity ([MergeKey.keyFor]):
normalized title + '|' + normalized author. The Kotlin test
`SeedCatalogKeyTest` checks these keys against the real MergeKey implementation,
so the Python port cannot silently drift.
"""
import argparse
import json
from pathlib import Path

from merge_key import merge_key


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--seed", type=Path,
                        default=Path(__file__).parents[2] / "app/src/main/assets/catalog_seed.json")
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    rows = json.loads(args.seed.read_text())
    if not isinstance(rows, list):
        parser.error("seed must be a JSON array")
    works = {}
    for row in rows:
        title, author = row.get("title", ""), row.get("author", "")
        key = merge_key(title, author)
        if not key:
            continue
        language = row.get("language") or ""
        text = ". ".join(part for part in (title.strip(), author.strip(), language) if part)
        works.setdefault(key, text)
    payload = {"works": [{"workKey": key, "text": text} for key, text in sorted(works.items())]}
    args.output.write_text(json.dumps(payload, ensure_ascii=False, indent=2) + "\n")
    print(json.dumps({"seed": len(rows), "works": len(works), "output": str(args.output)}))


if __name__ == "__main__":
    main()
