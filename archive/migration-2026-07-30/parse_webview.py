with open("app/src/main/java/com/example/ui/screens/FourReadWebScreen.kt", "r") as f:
    content = f.read()

import re

js_interface = """    class HtmlJsInterface {
        @android.webkit.JavascriptInterface
        fun processHTML(html: String, url: String) {
            viewModel.importAndPlay4ReadHtml(url, html)
            onBookImported()
        }
    }"""

content = content.replace("    var webViewInstance by remember { mutableStateOf<WebView?>(null) }", "    var webViewInstance by remember { mutableStateOf<WebView?>(null) }\n" + js_interface)

with open("app/src/main/java/com/example/ui/screens/FourReadWebScreen.kt", "w") as f:
    f.write(content)
