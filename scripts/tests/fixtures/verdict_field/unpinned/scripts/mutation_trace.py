#!/usr/bin/env python3
"""Підставний рекордер фікстури #1260: форма — як у справжнього mutation_trace.py.

Словник запису впізнається механізмом за ключем `schema`. Форма CLI — та сама,
що в справжнього рекордера: `record` це ПІДКОМАНДА, а не позиційний аргумент
перед опцією.

Це не косметика. Форму `record --log X key=value …`, де позиційний аргумент
стоїть ПЕРЕД опцією, різні patch-версії CPython розбирають по-різному: 3.12.3
(це `python3` в ubuntu-24.04, тобто в джобі script-tests) лишає `key=value`
«unrecognized arguments», а 3.12.12 розбирає їх нормально. Справжній
`mutation_trace.py` підкоманду використовує — і саме тому він від цього вільний;
фікстура, яка вдає справжній інструмент, мусить бути вільною теж.
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


def record(args: argparse.Namespace) -> int:
    values = dict(argument.split("=", 1) for argument in args.values)
    payload = build_record(values)
    args.log.parent.mkdir(parents=True, exist_ok=True)
    with args.log.open("a", encoding="utf-8") as handle:
        handle.write(json.dumps(payload, ensure_ascii=False, sort_keys=True) + "\n")
    print(json.dumps(payload, ensure_ascii=False, sort_keys=True))
    return 0


def parser() -> argparse.ArgumentParser:
    result = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    subparsers = result.add_subparsers(dest="command", required=True)
    recorder = subparsers.add_parser("record", help="append one run record")
    recorder.add_argument("--log", type=Path, required=True)
    recorder.add_argument("values", nargs="*", metavar="key=value")
    recorder.set_defaults(handler=record)
    return result


def main() -> int:
    args = parser().parse_args()
    return args.handler(args)


if __name__ == "__main__":
    raise SystemExit(main())
