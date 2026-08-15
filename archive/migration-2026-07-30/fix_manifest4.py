with open("app/src/main/AndroidManifest.xml", "r") as f:
    content = f.read()

content = content.replace('    <attribution android:tag="" android:label="@string/app_name" />', '')

with open("app/src/main/AndroidManifest.xml", "w") as f:
    f.write(content)
