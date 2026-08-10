with open("app/src/main/java/com/example/ui/screens/FourReadWebScreen.kt", "r") as f:
    content = f.read()

content = content.replace("                        webViewInstance = this", '                        addJavascriptInterface(HtmlJsInterface(), "AndroidHtml")\n                        webViewInstance = this')

content = content.replace('viewModel.importAndPlay4ReadUrl(currentWebUrl)\n                                    onBookImported()', 'webViewInstance?.evaluateJavascript("(function() { AndroidHtml.processHTML(document.documentElement.outerHTML, window.location.href); })();", null)')

with open("app/src/main/java/com/example/ui/screens/FourReadWebScreen.kt", "w") as f:
    f.write(content)
