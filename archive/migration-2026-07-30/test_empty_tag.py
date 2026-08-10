with open("app/src/main/AndroidManifest.xml", "r") as f:
    manifest = f.read()
if "empty_tag" not in manifest:
    manifest = manifest.replace('<attribution android:tag=" " android:label="@string/app_name" />', '<attribution android:tag=" " android:label="@string/app_name" />\n    <attribution android:tag="@string/empty_tag" android:label="@string/app_name" />')
with open("app/src/main/AndroidManifest.xml", "w") as f:
    f.write(manifest)
