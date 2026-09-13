"""SPIKE #777 — resolve one public video in-process. Never merged."""
import json
import yt_dlp


def resolve(url):
    opts = {"quiet": True, "no_warnings": True, "skip_download": True}
    with yt_dlp.YoutubeDL(opts) as ydl:
        info = ydl.extract_info(url, download=False)
    formats = info.get("formats") or []
    audio = [f for f in formats if f.get("vcodec") in (None, "none")]
    return json.dumps({
        "title": info.get("title"),
        "duration": info.get("duration"),
        "formats": len(formats),
        "audio_only": len(audio),
    }, ensure_ascii=False)
