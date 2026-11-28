#!/usr/bin/env python3
"""Export the public LibriVox catalogue into the worker's input format.

LibriVox is a supported public source with ~22.5k public-domain audiobooks.
The API returns the same `title` / first author the Android LibriVox adapter
uses, so `MergeKey.keyFor` produces the app's real Work keys. Descriptions are
included (trimmed) to give the embedding model more than a title.

No listener data, no source page, no audio URL: only public catalogue facts.
"""
import argparse
import html
import json
from pathlib import Path
import re
import time
from public_catalog_http import CatalogSecurityError, fetch_text

from merge_key import merge_key

API = ("https://librivox.org/api/feed/audiobooks/?format=json"
       "&limit={limit}&offset={offset}"
       "&fields=id,title,authors,language,description")
MAX_TEXT = 4000
TAG = re.compile(r"<[^>]+>")
SPACE = re.compile(r"\s+")


def fetch(offset, limit):
    last = None
    for attempt in range(3):
        try:
            return json.loads(fetch_text(API.format(limit=limit, offset=offset), timeout=60))
        except CatalogSecurityError:
            raise
        except Exception as error:  # bounded retry; a page is never guessed
            last = error
            time.sleep(1 + attempt)
    raise RuntimeError(f"LibriVox page at {offset} failed: {last}")


def first_author(raw):
    authors = raw.get("authors") or []
    if not authors or not isinstance(authors[0], dict):
        return ""
    first = authors[0]
    return " ".join(part for part in (first.get("first_name", ""), first.get("last_name", "")) if part).strip()


def clean(value):
    return SPACE.sub(" ", TAG.sub(" ", html.unescape(value or ""))).strip()


def work_text(title, author, language, description):
    body = clean(description)[:1200]
    text = ". ".join(part for part in (title.strip(), author.strip(), language.strip()) if part)
    if body:
        text = f"{text}. {body}"
    return text[:MAX_TEXT]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--limit", type=int, default=20_000)
    parser.add_argument("--page-size", type=int, default=500)
    args = parser.parse_args()
    works = {}
    fetched = duplicates = skipped = 0
    offset = 0
    while len(works) < args.limit and offset < 23_000:
        page = fetch(offset, args.page_size).get("books") or []
        if not page:
            break
        for raw in page:
            fetched += 1
            title = (raw.get("title") or "").strip()
            author = first_author(raw)
            key = merge_key(title, author)
            if not key:
                skipped += 1
                continue
            if key in works:
                duplicates += 1
                continue
            works[key] = work_text(title, author, raw.get("language") or "", raw.get("description") or "")
            if len(works) >= args.limit:
                break
        offset += args.page_size
        time.sleep(0.05)
    payload = {"works": [{"workKey": key, "text": text} for key, text in sorted(works.items())]}
    args.output.write_text(json.dumps(payload, ensure_ascii=False, separators=(",", ":")))
    print(json.dumps({"works": len(works), "fetched": fetched, "duplicates": duplicates,
                      "skipped": skipped, "bytes": args.output.stat().st_size,
                      "output": str(args.output)}))


if __name__ == "__main__":
    main()
