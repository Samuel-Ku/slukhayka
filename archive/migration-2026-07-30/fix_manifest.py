with open("app/src/main/AndroidManifest.xml", "r") as f:
    content = f.read()

if "<attribution" not in content:
    content = content.replace("<application", '    <attribution android:tag="audioPlayer" android:label="@string/app_name" />\n    <application')

with open("app/src/main/AndroidManifest.xml", "w") as f:
    f.write(content)
