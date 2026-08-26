#!/usr/bin/env python3
"""Live 4read playback-contract probe for scheduled/manual CI."""

from __future__ import annotations

import json
import os
import re
import subprocess
import sys
import urllib.parse
import tempfile


PAGE_URL = os.environ.get(
    "FOURREAD_PROBE_PAGE",
    "https://4read.org/5370-gu-goui-bunker-iluziia.html",
)
USER_AGENT = os.environ.get(
    "FOURREAD_PROBE_USER_AGENT",
    "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 "
    "(KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36",
)
REFERER = "https://4read.org/"


def fail(stage: str, url: str, detail: str, status: int | str = "n/a") -> None:
    host = urllib.parse.urlsplit(url).hostname or "unknown"
    print(
        f"FAIL stage={stage} host={host} http={status} detail={detail}",
        file=sys.stderr,
    )
    raise SystemExit(1)


def fetch(
    stage: str,
    url: str,
    *,
    include_referer: bool = True,
    range_header: str | None = None,
    limit: int = 2_000_000,
) -> tuple[int, str, bytes]:
    headers = {
        "User-Agent": USER_AGENT,
        "Accept": "*/*",
        "Accept-Language": "uk-UA,uk;q=0.9,en;q=0.8",
    }
    if include_referer:
        headers["Referer"] = REFERER
    if range_header is not None:
        headers["Range"] = range_header
    with tempfile.NamedTemporaryFile() as output:
        command = [
            "curl",
            "--location",
            "--compressed",
            "--silent",
            "--show-error",
            "--max-time",
            "20",
            "--max-filesize",
            str(limit),
            "--output",
            output.name,
            "--write-out",
            "%{http_code}\n%{content_type}\n",
        ]
        for name, value in headers.items():
            command.extend(("--header", f"{name}: {value}"))
        command.append(url)
        result = subprocess.run(command, capture_output=True, text=True, check=False)
        response_meta = result.stdout.splitlines()
        status = int(response_meta[0]) if response_meta and response_meta[0].isdigit() else 0
        content_type = response_meta[1].split(";", 1)[0] if len(response_meta) > 1 else ""
        if result.returncode != 0:
            detail = result.stderr.strip().splitlines()[-1] if result.stderr.strip() else f"curl exit {result.returncode}"
            fail(stage, url, detail, status)
        output.seek(0)
        body = output.read(limit)
    print(
        f"PASS stage={stage} host={urllib.parse.urlsplit(url).hostname} "
        f"http={status} referer={'yes' if include_referer else 'no'} "
        f"content_type={content_type} bytes={len(body)}"
    )
    return status, content_type, body


def encoded_url(raw_url: str) -> str:
    return urllib.parse.quote(raw_url.strip(), safe=":/?&=%#@+;,[]()!$")


def is_fourread_audio_host(url: str) -> bool:
    host = (urllib.parse.urlsplit(url).hostname or "").lower()
    return (
        host == "4read.org"
        or host.endswith(".4read.org")
        or host == "reasd.org"
        or host.endswith(".reasd.org")
    )


def playlist_url(page_html: str) -> str:
    match = re.search(
        r"file\s*:\s*[\"']([^\"']+\.(?:m3u|txt))[\"']",
        page_html,
        re.IGNORECASE,
    )
    if match is None:
        fail("page-parse", PAGE_URL, "playlist reference not found")
    return encoded_url(match.group(1).replace("{v1}", "https://4read.org/m3u/"))


def first_track(playlist_body: str, playlist: str) -> str:
    stripped = playlist_body.strip()
    if stripped.startswith("["):
        try:
            entries = json.loads(stripped)
            raw_track = entries[0]["file"]
        except (IndexError, KeyError, TypeError, json.JSONDecodeError):
            fail("playlist-parse", playlist, "JSON playlist has no first track")
    else:
        raw_track = next(
            (line.strip() for line in stripped.splitlines() if line.strip().startswith("http")),
            "",
        )
    if not raw_track:
        fail("playlist-parse", playlist, "playlist has no HTTP track")
    return encoded_url(raw_track)


def looks_like_mp3(body: bytes) -> bool:
    if body.startswith(b"ID3"):
        return True
    return any(
        body[index] == 0xFF and body[index + 1] & 0xE0 == 0xE0
        for index in range(min(len(body) - 1, 4096))
    )


def main() -> None:
    page_status, _, page_body = fetch("page", PAGE_URL)
    if page_status != 200:
        fail("page", PAGE_URL, "expected HTTP 200", page_status)

    playlist = playlist_url(page_body.decode("utf-8", errors="replace"))
    playlist_status, _, playlist_body = fetch("playlist", playlist)
    if playlist_status != 200:
        fail("playlist", playlist, "expected HTTP 200", playlist_status)

    track = first_track(playlist_body.decode("utf-8", errors="replace"), playlist)
    audio_status, content_type, audio_body = fetch(
        "audio",
        track,
        include_referer=is_fourread_audio_host(track),
        range_header="bytes=0-65535",
        limit=65_536,
    )
    if audio_status not in (200, 206):
        fail("audio", track, "expected HTTP 200 or 206", audio_status)
    if not content_type.startswith("audio/"):
        fail("audio", track, f"expected audio Content-Type, got {content_type}", audio_status)
    if len(audio_body) < 4096 or not looks_like_mp3(audio_body):
        fail("audio", track, "response is not a non-empty MP3 prefix", audio_status)


if __name__ == "__main__":
    main()
