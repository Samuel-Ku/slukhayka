import urllib.request
import re

url = "https://4read.org/7611-vkradi-mene-zaraz-mistichna-audiokniga-pro-petlju-chasu-ta-kohannja.html"
req = urllib.request.Request(url, headers={'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36'})
html = urllib.request.urlopen(req).read().decode('utf-8')

# extractAudioFromHtml
audio_urls = []
fileJsRegex = re.compile(r'file\s*:\s*["\']([^"\']+)["\']', re.IGNORECASE)
for m in fileJsRegex.finditer(html):
    rawFile = m.group(1)
    rawFile = rawFile.replace("{v1}", "https://4read.org/m3u/")
    if ".mp3" in rawFile or ".m4a" in rawFile or ".m3u8" in rawFile or ".m3u" in rawFile or ".txt" in rawFile or "/audio/" in rawFile:
        for piece in rawFile.replace(";", ",").split(","):
            clean = piece.strip()
            if clean.startswith("http"):
                audio_urls.append(clean)
            elif clean.startswith("/"):
                audio_urls.append("https://4read.org" + clean)

print("Audio URLs from HTML:", audio_urls)

expanded = []
for stream in audio_urls:
    if stream.endswith(".m3u") or stream.endswith(".txt"):
        try:
            req_m3u = urllib.request.Request(stream, headers={'User-Agent': 'Mozilla/5.0', 'Referer': 'https://4read.org/'})
            playlistContent = urllib.request.urlopen(req_m3u).read().decode('utf-8')
            print("Playlist content preview:", playlistContent[:100])
            if playlistContent.strip().startswith("[{"):
                jsonFileRegex = re.compile(r'"file"\s*:\s*"([^"]+)"', re.IGNORECASE)
                for m in jsonFileRegex.finditer(playlistContent):
                    expanded.append(m.group(1))
            else:
                for line in playlistContent.split("\n"):
                    clean = line.strip()
                    if clean.startswith("http"):
                        expanded.append(clean)
        except Exception as e:
            print("Error downloading m3u:", e)
            expanded.append(stream)
    else:
        expanded.append(stream)

print("Expanded URLs:", expanded)
