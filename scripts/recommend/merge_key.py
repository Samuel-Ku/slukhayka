"""The app's Work identity, ported for catalogue exports.

`MergeKey.keyFor(title, author)` in Kotlin is the source of truth; the Kotlin
tests compare these keys against it. Keep this module dependency-free so both
the seed export and the public LibriVox export use exactly one implementation.
"""
import re
import unicodedata

SEO_PHRASES = sorted([
    "слухай безкоштовні аудіокниги онлайн українською мовою",
    "безкоштовні аудіокниги онлайн українською мовою",
    "аудіокниги українською мовою безкоштовно",
    "слухати онлайн аудіокнигу безкоштовно",
    "слухати онлайн аудіокнигу",
    "слухати онлайн аудіокнига",
    "слухати онлайн аудіокниги",
    "аудіокнигу слухати онлайн",
    "слухати аудіокнигу онлайн",
    "слухай аудіокнигу онлайн",
    "аудіокнига слухати онлайн",
    "слухати онлайн безкоштовно",
    "слухати онлайн українською",
    "аудіокнига українською",
    "аудіокниги українською",
    "аудіокнига онлайн",
    "слухати онлайн",
], key=len, reverse=True)

ENTITY = re.compile(r"&(?:#(?:[xX]([0-9a-fA-F]+)|(\d+))|(quot|amp));")
NON_TEXT = re.compile(r"[^\w ]", re.UNICODE)
WHITESPACE = re.compile(r"\s+")
LEADING_AUDIOBOOK = re.compile(r"^\s*(?:аудіокнига|аудіокнигу)\s*(?:[:\-–—|.]\s*)", re.IGNORECASE)
TRAILING_BARE = re.compile(
    r"(?:\s*-\s*|\s*[—–]\s*|\s*\(\s*|,\s*|\s*\|\s*)\s*(?:аудіокнига|аудіокниги|аудіокнигу)\s*\)?\s*$",
    re.IGNORECASE)
TRAILING_SEPARATOR = re.compile(r"(?:\s*-\s*|\s*[—–]\s*|\s*\(\s*|,\s*|\s*\|\s*)+$")


def decode_entities(value):
    def replace(match):
        hex_value, decimal, named = match.groups()
        if named == "quot":
            return '"'
        if named == "amp":
            return "&"
        code = int(hex_value, 16) if hex_value else int(decimal)
        if not 1 <= code <= 0x10FFFF or 0xD800 <= code <= 0xDFFF:
            return match.group(0)
        return chr(code)

    return ENTITY.sub(replace, value)


def normalize(value):
    value = value.strip().lower()
    value = "".join(ch for ch in value if ch.isspace() or ch.isalnum())
    return WHITESPACE.sub(" ", value).strip()


def normalize_title(raw):
    original = decode_entities(raw.strip())
    title = "".join(ch for ch in original if unicodedata.category(ch) != "So")
    title = title.replace("\ufe0f", "").replace("\u200d", "").strip()
    title = LEADING_AUDIOBOOK.sub("", title).strip()
    title = TRAILING_BARE.sub("", title).strip()
    changed = True
    while changed:
        changed = False
        for phrase in SEO_PHRASES:
            match = re.search(re.escape(phrase) + r"\s*\)?\s*$", title, re.IGNORECASE)
            if match:
                title = TRAILING_SEPARATOR.sub("", title[:match.start()]).strip()
                changed = True
                break
    title = title.split(":", 1)[0].split("—", 1)[0].split("–", 1)[0].split(" - ", 1)[0]
    return normalize(title or original)


def normalize_person(raw):
    return normalize(decode_entities(raw))


def merge_key(title, author):
    parts = [part for part in (normalize_title(title), normalize_person(author)) if part]
    return "|".join(parts) if len(parts) == 2 else ""
