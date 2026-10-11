#!/usr/bin/env python3
"""Підставний рекордер фікстури #1260: той самий словник запису.

Словник упізнається механізмом за ключем `schema` — без нього «поле запису» не
відрізнити від будь-якої іншої відповіді, і місце поля в записі лишилося б
неперевіреним.
"""

import argparse
import json
from pathlib import Path


def build_record(values: dict) -> dict:
    def text(key: str):
        value = values.get(key, "")
        return value if value != "" else None

    def integer(key: str):
        value = values.get(key, "")
        return int(value) if value.strip() else None

    return {
        "schema": 1,
        "kind": "mutation",
        "outcome": values["outcome"],
        "tests": integer("tests"),
        "failures": integer("failures"),
        "errors": integer("errors"),
        "artifactSha256": text("artifactSha256"),
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("record")
    parser.add_argument("--log", type=Path, required=True)
    parser.add_argument("values", nargs="*", metavar="key=value")
    args = parser.parse_args()
    values = dict(argument.split("=", 1) for argument in args.values)
    payload = build_record(values)
    args.log.parent.mkdir(parents=True, exist_ok=True)
    with args.log.open("a", encoding="utf-8") as handle:
        handle.write(json.dumps(payload, ensure_ascii=False, sort_keys=True) + "\n")
    print(json.dumps(payload, ensure_ascii=False, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
