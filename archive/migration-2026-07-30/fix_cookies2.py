with open("app/src/main/java/com/example/ui/screens/FourReadWebScreen.kt", "r") as f:
    content = f.read()

content = content.replace("                        android.webkit.CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)\n                        settings.apply {", "                        android.webkit.CookieManager.getInstance().setAcceptThirdPartyCookies(this@apply, true)\n                        settings.apply {")

with open("app/src/main/java/com/example/ui/screens/FourReadWebScreen.kt", "w") as f:
    f.write(content)
