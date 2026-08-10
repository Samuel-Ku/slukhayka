with open("app/src/main/java/com/example/ui/screens/FourReadWebScreen.kt", "r") as f:
    content = f.read()

import re

new_js = """                                    webViewInstance?.evaluateJavascript("(function() { " +
                                        "var htmls = [document.documentElement.outerHTML]; " +
                                        "var iframes = document.querySelectorAll('iframe'); " +
                                        "for (var i = 0; i < iframes.length; i++) { " +
                                        "  try { htmls.push(iframes[i].contentDocument.documentElement.outerHTML); } catch(e) {} " +
                                        "} " +
                                        "AndroidHtml.processHTML(htmls.join('\\n\\n---IFRAME---\\n\\n'), window.location.href); " +
                                        "})();", null)"""

content = re.sub(r'                                    webViewInstance\?\.evaluateJavascript\("\(function\(\) \{ AndroidHtml\.processHTML\(document\.documentElement\.outerHTML, window\.location\.href\); \}\)\(\);", null\)', new_js, content)

with open("app/src/main/java/com/example/ui/screens/FourReadWebScreen.kt", "w") as f:
    f.write(content)
