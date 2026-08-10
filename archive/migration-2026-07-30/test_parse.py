import re
playlistContent = """[{"title":"1","file":"https://s1.reasd.org/7611/\u0430\u0443\u0434\u0456\u043e\u043a\u043d\u0438\u0433\u0430 \u0412\u041a\u0420\u0410\u0414\u0418 \u041c\u0415\u041d\u0415 \u0417\u0410\u0420\u0410\u0417.m4a"}]"""

# Regex("\"file\"\\s*:\\s*\"([^\"]+)\"", RegexOption.IGNORE_CASE)
jsonFileRegex = re.compile(r'"file"\s*:\s*"([^"]+)"', re.IGNORECASE)
for m in jsonFileRegex.finditer(playlistContent):
    print("Found:", m.group(1))

