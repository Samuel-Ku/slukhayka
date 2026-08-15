with open("app/src/main/java/com/example/ui/screens/FourReadWebScreen.kt", "r") as f:
    content = f.read()

import re

new_js = """    class HtmlJsInterface(val viewModel: com.example.ui.MainViewModel, val onImport: () -> Unit) {
        private val handler = android.os.Handler(android.os.Looper.getMainLooper())
        @android.webkit.JavascriptInterface
        fun processHTML(html: String, url: String) {
            handler.post {
                viewModel.importAndPlay4ReadHtml(url, html)
                onImport()
            }
        }
    }"""

content = re.sub(r'    class HtmlJsInterface \{.*?    \}', new_js, content, flags=re.DOTALL)
content = content.replace('addJavascriptInterface(HtmlJsInterface(), "AndroidHtml")', 'addJavascriptInterface(HtmlJsInterface(viewModel, onBookImported), "AndroidHtml")')

with open("app/src/main/java/com/example/ui/screens/FourReadWebScreen.kt", "w") as f:
    f.write(content)
