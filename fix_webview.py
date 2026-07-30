with open("app/src/main/java/com/example/ui/screens/FourReadWebScreen.kt", "r") as f:
    content = f.read()

import re

new_client = """                        webViewClient = object : WebViewClient() {
                            override fun shouldOverrideUrlLoading(view: WebView?, request: android.webkit.WebResourceRequest?): Boolean {
                                val url = request?.url?.toString() ?: return false
                                if (url.startsWith("http://") || url.startsWith("https://")) {
                                    view?.loadUrl(url)
                                    return true
                                }
                                return false
                            }

                            override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
                                if (url != null && (url.startsWith("http://") || url.startsWith("https://"))) {
                                    view?.loadUrl(url)
                                    return true
                                }
                                return false
                            }

                            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {"""

content = content.replace("                        webViewClient = object : WebViewClient() {\n                            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {", new_client)

with open("app/src/main/java/com/example/ui/screens/FourReadWebScreen.kt", "w") as f:
    f.write(content)
