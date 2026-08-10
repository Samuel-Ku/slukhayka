with open("app/src/main/java/com/example/ui/screens/FourReadWebScreen.kt", "r") as f:
    lines = f.readlines()

for i, line in enumerate(lines):
    if "AndroidHtml.processHTML(htmls.join('" in line:
        start_idx = i
        break

end_idx = start_idx
while "})();\", null)" not in lines[end_idx]:
    end_idx += 1

# Replace start_idx to end_idx
new_code = "                                        \"AndroidHtml.processHTML(htmls.join('---IFRAME---'), window.location.href); \" +\n                                        \"})();\", null)\n"

lines = lines[:start_idx] + [new_code] + lines[end_idx+1:]

with open("app/src/main/java/com/example/ui/screens/FourReadWebScreen.kt", "w") as f:
    f.writelines(lines)
