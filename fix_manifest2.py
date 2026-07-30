with open("app/src/main/AndroidManifest.xml", "r") as f:
    content = f.read()

if "android:tag=\"null\"" not in content:
    content = content.replace("<application", '    <attribution android:tag="null" android:label="@string/app_name" />\n    <application')

with open("app/src/main/AndroidManifest.xml", "w") as f:
    f.write(content)
