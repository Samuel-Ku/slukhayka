package com.example.ui.screens

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Bitmap
import android.util.Log
import androidx.core.net.toUri
import android.net.http.SslError
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.ui.MainViewModel
import com.example.ui.theme.*

@SuppressLint("SetJavaScriptEnabled")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FourReadWebScreen(
    viewModel: MainViewModel,
    onBookImported: () -> Unit
) {
    val context = LocalContext.current
    var urlInput by remember { mutableStateOf("https://4read.org/") }
    var currentWebUrl by remember { mutableStateOf("https://4read.org/") }
    var isLoading by remember { mutableStateOf(false) }
    var hasWebError by remember { mutableStateOf(false) }
    var webErrorMsg by remember { mutableStateOf("") }
    var webViewInstance by remember { mutableStateOf<WebView?>(null) }
    var canGoBack by remember { mutableStateOf(false) }
    var canGoForward by remember { mutableStateOf(false) }

    class HtmlJsInterface(val viewModel: MainViewModel, val onImport: () -> Unit) {
        private val handler = android.os.Handler(android.os.Looper.getMainLooper())
        @android.webkit.JavascriptInterface
        fun processHTML(html: String, url: String) {
            handler.post {
                if (html.isNotBlank() && html.length > 50) {
                    viewModel.importAndPlay4ReadHtml(url, html)
                } else {
                    viewModel.importAndPlay4ReadUrl(url)
                }
                onImport()
            }
        }
    }

    val quickLinks = listOf(
        "Головна" to "https://4read.org/",
        "Нейромант" to "https://4read.org/2172-ybson-vylyam-neyromant.html",
        "1984" to "https://4read.org/1984.html",
        "451° Фаренгейт" to "https://4read.org/fahrenheit-451.html",
        "Дюна" to "https://4read.org/dune.html",
        "Солярис" to "https://4read.org/solaris.html",
        "Пикник на обочине" to "https://4read.org/roadside-picnic.html",
        "Мастер и Маргарита" to "https://4read.org/master-i-margarita.html"
    )

    fun executeImportScript() {
        val instance = webViewInstance
        if (instance != null) {
            instance.evaluateJavascript(
                "(function() { " +
                    "var htmls = [document.documentElement.outerHTML]; " +
                    "var iframes = document.querySelectorAll('iframe'); " +
                    "for (var i = 0; i < iframes.length; i++) { " +
                    "  try { htmls.push(iframes[i].contentDocument.documentElement.outerHTML); } catch(e) {} " +
                    "} " +
                    "AndroidHtml.processHTML(htmls.join('---IFRAME---'), window.location.href); " +
                    "})();"
            ) { result ->
                if (result == null || result == "null" || result.isBlank()) {
                    viewModel.importAndPlay4ReadUrl(currentWebUrl)
                    onBookImported()
                }
            }
        } else {
            viewModel.importAndPlay4ReadUrl(currentWebUrl)
            onBookImported()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(CyberBg)
            .testTag("4read_web_screen")
    ) {
        // Header Controls
        Surface(
            color = CyberSurface,
            tonalElevation = 4.dp,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                // Top Action Bar with browser controls
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(
                            onClick = { webViewInstance?.goBack() },
                            enabled = canGoBack
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Назад",
                                tint = if (canGoBack) CyberPrimary else CyberTextSecondary.copy(alpha = 0.4f)
                            )
                        }

                        IconButton(
                            onClick = { webViewInstance?.goForward() },
                            enabled = canGoForward
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                                contentDescription = "Вперед",
                                tint = if (canGoForward) CyberPrimary else CyberTextSecondary.copy(alpha = 0.4f)
                            )
                        }

                        IconButton(
                            onClick = { webViewInstance?.reload() }
                        ) {
                            Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = "Оновити",
                                tint = CyberPrimary
                            )
                        }

                        IconButton(
                            onClick = {
                                currentWebUrl = "https://4read.org/"
                                urlInput = "https://4read.org/"
                                webViewInstance?.loadUrl("https://4read.org/")
                            }
                        ) {
                            Icon(
                                imageVector = Icons.Default.Home,
                                contentDescription = "Головна",
                                tint = CyberPrimary
                            )
                        }
                    }

                    Button(
                        onClick = {
                            executeImportScript()
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = CyberPrimary),
                        shape = RoundedCornerShape(12.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.PlayArrow,
                            contentDescription = null,
                            tint = CyberOnPrimary,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "Слухати книгу",
                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                            color = CyberOnPrimary
                        )
                    }
                }

                Spacer(modifier = Modifier.height(6.dp))

                // Input Bar for URL or Title Search
                OutlinedTextField(
                    value = urlInput,
                    onValueChange = { urlInput = it },
                    placeholder = { Text("Введіть посилання або назву...") },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Default.Link,
                            contentDescription = "URL",
                            tint = CyberPrimary
                        )
                    },
                    trailingIcon = {
                        Row {
                            IconButton(onClick = {
                                try {
                                    val intent = Intent(Intent.ACTION_VIEW, currentWebUrl.toUri())
                                    context.startActivity(intent)
                                } catch (_: Exception) {}
                            }) {
                                Icon(imageVector = Icons.AutoMirrored.Filled.OpenInNew, contentDescription = "Браузер", tint = CyberSecondary)
                            }
                            if (urlInput.isNotEmpty()) {
                                IconButton(onClick = {
                                    val input = urlInput.trim()
                                    val target = if (input.startsWith("http")) {
                                        input
                                    } else {
                                        "https://4read.org/index.php?do=search&subaction=search&story=${java.net.URLEncoder.encode(input, "UTF-8")}"
                                    }
                                    currentWebUrl = target
                                    webViewInstance?.loadUrl(target)
                                }) {
                                    Icon(imageVector = Icons.Default.Search, contentDescription = "Пошук", tint = CyberPrimary)
                                }
                            }
                        }
                    },
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = CyberCardBg,
                        unfocusedContainerColor = CyberCardBg,
                        focusedBorderColor = CyberPrimary,
                        unfocusedBorderColor = CyberCardBorder
                    ),
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(8.dp))

                // Quick Shortcuts
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(quickLinks) { (title, url) ->
                        AssistChip(
                            onClick = {
                                urlInput = url
                                currentWebUrl = url
                                webViewInstance?.loadUrl(url)
                            },
                            label = {
                                Text(
                                    text = title,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    fontSize = 12.sp
                                )
                            },
                            colors = AssistChipDefaults.assistChipColors(
                                containerColor = CyberCardBg,
                                labelColor = CyberTextPrimary
                            ),
                            border = AssistChipDefaults.assistChipBorder(
                                enabled = true,
                                borderColor = CyberCardBorder
                            )
                        )
                    }
                }
            }
        }

        if (isLoading) {
            LinearProgressIndicator(
                modifier = Modifier.fillMaxWidth(),
                color = CyberPrimary,
                trackColor = CyberCardBg
            )
        }

        // WebView displaying 4read.org
        Box(modifier = Modifier.weight(1f)) {
            AndroidView(
                factory = { ctx ->
                    Log.w("FourReadWeb", "WebView factory creating WebView")
                    WebView(ctx).apply {
                        Log.w("FourReadWeb", "WebView created, loading url=$currentWebUrl")
                        // Phase 2.5 hotfix (SEC-006/007): disable third-party
                        // cookies and the deprecated WebSQL database surface.
                        android.webkit.CookieManager.getInstance().setAcceptThirdPartyCookies(this@apply, false)
                        settings.apply {
                            javaScriptEnabled = true
                            domStorageEnabled = true
                            // Phase 2.5 hotfix (SEC-006): WebSQL databaseEnabled
                            // is deprecated and widens the JS-bridge blast radius.
                            databaseEnabled = false
                            // Phase 2.5 hotfix (SEC-005): never load http
                            // subresources from https pages.
                            mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
                            // Phase 2.5 hotfix (SEC-027): require an explicit
                            // user gesture for media playback.
                            mediaPlaybackRequiresUserGesture = true
                            useWideViewPort = false
                            loadWithOverviewMode = false
                            // Phase 2.5 hotfix (SEC-003): file/content access
                            // combined with the JS interface lets a compromised
                            // page reach file:// content.
                            allowFileAccess = false
                            allowContentAccess = false

                            // Phase 2.5 hotfix (SEC-018): drop the hardcoded
                            // SM-S918B model so every user doesn't appear as
                            // one developer's device.
                            userAgentString = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
                        }
                        webChromeClient = android.webkit.WebChromeClient()
                        webViewClient = object : WebViewClient() {
                            override fun shouldOverrideUrlLoading(view: WebView?, request: android.webkit.WebResourceRequest?): Boolean {
                                val url = request?.url?.toString() ?: return false
                                val scheme = request?.url?.scheme?.lowercase() ?: ""
                                // Phase 2.5 hotfix (SEC-011): whitelist http(s)
                                // only. Reject intent://, market://, javascript:,
                                // tel:, mailto:, data:, content:, file: to close
                                // the implicit-intent abuse vector.
                                return if (scheme == "http" || scheme == "https") {
                                    false // Let WebView load it natively
                                } else {
                                    // Silently drop non-http(s) intents.
                                    Log.w("FourReadWeb", "Blocked non-http navigation: $url")
                                    true
                                }
                            }

                            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                                super.onPageStarted(view, url, favicon)
                                Log.w("FourReadWeb", "onPageStarted: url=$url")
                                isLoading = true
                                hasWebError = false
                                url?.let {
                                    currentWebUrl = it
                                    urlInput = it
                                }
                                canGoBack = view?.canGoBack() ?: false
                                canGoForward = view?.canGoForward() ?: false
                            }

                            override fun shouldInterceptRequest(
                                view: WebView?,
                                request: android.webkit.WebResourceRequest?
                            ): android.webkit.WebResourceResponse? {
                                return super.shouldInterceptRequest(view, request)
                            }

                            override fun onPageFinished(view: WebView?, url: String?) {
                                super.onPageFinished(view, url)
                                Log.w("FourReadWeb", "onPageFinished: url=$url")
                                isLoading = false
                                url?.let {
                                    currentWebUrl = it
                                    urlInput = it
                                }
                                canGoBack = view?.canGoBack() ?: false
                                canGoForward = view?.canGoForward() ?: false
                                
                                view?.evaluateJavascript(
                                    "(function() { " +
                                    "var style = document.createElement('style');" +
                                    "style.innerHTML = '.sect-rek, .rek, #rek, .reklama, .banner, .adsbygoogle, [id^=yandex_rtb_], iframe[src*=\"ads\"], .dle_b_pid_nav, [id^=bn_] { display: none !important; margin: 0 !important; padding: 0 !important; height: 0 !important; }';" +
                                    "document.head.appendChild(style);" +
                                    "})();", null
                                )
                            }

                            // Phase 2.5 hotfix (SEC-001 / SF-009): cancel the
                            // connection instead of `handler.proceed()`. The
                            // old behavior silently accepted every TLS error
                            // (expired cert, wrong host, MITM) and loaded the
                            // page anyway.
                            override fun onReceivedSslError(
                                view: WebView?,
                                handler: android.webkit.SslErrorHandler?,
                                error: android.net.http.SslError?
                            ) {
                                Log.w("FourReadWeb", "SSL error: ${error?.toString()}")
                                handler?.cancel()
                                hasWebError = true
                                webErrorMsg = "SSL помилка: ${error?.primaryError?.let { sSLErrorToString(it) } ?: "невідомо"}"
                            }

                            override fun onReceivedError(
                                view: WebView?,
                                request: android.webkit.WebResourceRequest?,
                                error: android.webkit.WebResourceError?
                            ) {
                                super.onReceivedError(view, request, error)
                                Log.w("FourReadWeb", "onReceivedError: url=${request?.url} errCode=${error?.errorCode} desc=${error?.description}")
                                if (request?.isForMainFrame == true) {
                                    isLoading = false
                                    val errCode = error?.errorCode ?: 0
                                    if (errCode == WebViewClient.ERROR_HOST_LOOKUP || errCode == WebViewClient.ERROR_CONNECT || errCode == WebViewClient.ERROR_TIMEOUT) {
                                        hasWebError = true
                                        webErrorMsg = error?.description?.toString() ?: "ERR_NAME_NOT_RESOLVED"
                                    }
                                }
                            }
                        }
                        addJavascriptInterface(HtmlJsInterface(viewModel, onBookImported), "AndroidHtml")
                        loadUrl(currentWebUrl)
                        webViewInstance = this
                    }
                },
                update = { webView ->
                    webViewInstance = webView
                },
                modifier = Modifier.fillMaxSize()
            )

            if (hasWebError) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = CyberCardBg),
                    border = androidx.compose.foundation.BorderStroke(1.dp, CyberSecondary),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp)
                        .align(Alignment.TopCenter)
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Warning,
                                contentDescription = null,
                                tint = CyberSecondary,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Мережева помилка (${webErrorMsg.ifBlank { "ERR_NAME_NOT_RESOLVED" }})",
                                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                                color = CyberTextPrimary,
                                modifier = Modifier.weight(1f)
                            )
                            IconButton(onClick = { hasWebError = false }) {
                                Icon(imageVector = Icons.Default.Close, contentDescription = "Закрити", tint = CyberTextSecondary)
                            }
                        }
                        Text(
                            text = "Якщо сторінка 4read.org не завантажується через локальну мережу, відкрийте в браузері або натисніть 'Слухати книгу'.",
                            style = MaterialTheme.typography.bodySmall,
                            color = CyberTextSecondary
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = {
                                    try {
                                        val intent = Intent(Intent.ACTION_VIEW, currentWebUrl.toUri())
                                        context.startActivity(intent)
                                    } catch (_: Exception) {}
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = CyberPrimary),
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Icon(imageVector = Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null, modifier = Modifier.size(14.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("В браузері", fontSize = 12.sp)
                            }

                            OutlinedButton(
                                onClick = {
                                    hasWebError = false
                                    isLoading = true
                                    webViewInstance?.loadUrl(currentWebUrl)
                                },
                                shape = RoundedCornerShape(8.dp),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = CyberPrimary)
                            ) {
                                Text("Оновити", fontSize = 12.sp)
                            }

                            OutlinedButton(
                                onClick = {
                                    executeImportScript()
                                },
                                shape = RoundedCornerShape(8.dp),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = CyberSecondary)
                            ) {
                                Text("Слухати книгу", fontSize = 12.sp)
                            }
                        }
                    }
                }
            }
        }
    }

    // Phase 2.5 hotfix (PERF-003 / PERF-020): every time the user leaves the
    // 4read Web tab we used to leak the WebView (renderer process, JS engine,
    // CookieManager session, native heap) and the `AndroidHtml` JavascriptInterface
    // held a strong reference to MainViewModel + a Handler. Destroy the view
    // and detach the JS bridge on disposal.
    DisposableEffect(Unit) {
        onDispose {
            webViewInstance?.let { wv ->
                try {
                    wv.removeJavascriptInterface("AndroidHtml")
                    wv.stopLoading()
                    wv.destroy()
                } catch (_: Exception) {}
            }
            webViewInstance = null
        }
    }
}

/**
 * Phase 2.5 hotfix helper: convert an [SslError] primary error code into a
 * short Ukrainian description for the user-facing error card.
 */
private fun sSLErrorToString(code: Int): String = when (code) {
    SslError.SSL_DATE_INVALID -> "термін дії сертифіката минув або ще не почався"
    SslError.SSL_EXPIRED -> "термін дії сертифіката завершився"
    SslError.SSL_IDMISMATCH -> "домен не відповідає сертифікату"
    SslError.SSL_INVALID -> "невалідний сертифікат"
    SslError.SSL_NOTYETVALID -> "термін дії сертифіката ще не почався"
    SslError.SSL_UNTRUSTED -> "недовірений сертифікат (можливий MITM)"
    else -> "помилка TLS ($code)"
}
