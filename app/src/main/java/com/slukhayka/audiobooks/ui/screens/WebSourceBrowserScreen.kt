package com.slukhayka.audiobooks.ui.screens

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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.webkit.ProxyConfig
import androidx.webkit.ProxyController
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.slukhayka.audiobooks.BuildConfig
import com.slukhayka.audiobooks.data.privacy.WebViewSessionPrivacy
import com.slukhayka.audiobooks.ui.MainViewModel
import com.slukhayka.audiobooks.ui.theme.*

/**
 * Spec-13 T3 — the browser surface for a WebView-pattern source (sluhay.com
 * first; sluhayknigi joins later). A fullscreen pushed destination (#73
 * decisions: NOT a tab, NOT a bottom sheet). The user passes the Cloudflare
 * challenge in-session, browses and searches with the site's own UI; the
 * «Додати до медіатеки» action captures the current page's DOM and imports
 * it through the adapter (metadata + inline playlist, per-source Referer).
 *
 * Security contract (SEC-003 / SEC-011): NO `addJavascriptInterface` — page
 * data crosses through `evaluateJavascript` with an origin check against the
 * source's own domain; only http(s) navigation is allowed; mixed content,
 * file/content access and third-party cookies stay disabled.
 *
 * Spec-38 T3 (#255) — sessions live under the same privacy route as the
 * transport ([WebViewSessionPrivacy]): navigation goes through the official
 * webkit proxy controller when a route is installed (a route WebView cannot
 * carry refuses honestly instead of silently going direct); third-party
 * cookies are rejected; geolocation is hard-off with prompt-deny; the
 * sensor/geolocation JS-API surface is removed by a document-start lockdown
 * script; entering a source purges stored cookies so two sources' sessions
 * never coexist (per-source isolation). No configured route = exactly the
 * pre-spec behaviour («прямо», system defaults).
 */
@SuppressLint("SetJavaScriptEnabled")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WebSourceBrowserScreen(
    viewModel: MainViewModel,
    sourceId: String,
    homeUrl: String,
    displayName: String,
    onClose: () -> Unit
) {
    val context = LocalContext.current
    var urlInput by remember { mutableStateOf(homeUrl) }
    var currentWebUrl by remember { mutableStateOf(homeUrl) }
    var isLoading by remember { mutableStateOf(false) }
    var hasWebError by remember { mutableStateOf(false) }
    var webErrorMsg by remember { mutableStateOf("") }
    var webViewInstance by remember { mutableStateOf<WebView?>(null) }
    var canGoBack by remember { mutableStateOf(false) }
    var canGoForward by remember { mutableStateOf(false) }
    var isImporting by remember { mutableStateOf(false) }
    var importResult by remember { mutableStateOf("") }

    // Spec-38 T3 (#255): the WebView session rides the SAME resolved route as
    // the transport (WebViewSessionPrivacy reads TransportPrivacy). Resolved
    // once per screen entry; a routed session on a WebView without proxy
    // override support (or under the relay route) refuses below instead of
    // silently navigating direct.
    val proxyOverrideSupported = runCatching {
        WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)
    }.getOrDefault(false)
    val sessionRoute = remember(proxyOverrideSupported) {
        WebViewSessionPrivacy.resolve(proxyOverrideSupported = proxyOverrideSupported)
    }
    val lockdownScript = remember { WebViewSessionPrivacy.lockdownScript() }

    // Install / clear the webkit proxy override to match the transport's exit.
    // No addDirect() fallback rule exists in the config: a chosen proxy that
    // cannot connect fails navigation honestly, like the shared fetcher.
    LaunchedEffect(sessionRoute) {
        if (!proxyOverrideSupported) return@LaunchedEffect
        val resolution = sessionRoute as? WebViewSessionPrivacy.RouteResolution.Ok ?: return@LaunchedEffect
        runCatching {
            val controller = ProxyController.getInstance()
            val executor = java.util.concurrent.Executor { it.run() }
            when (val route = resolution.route) {
                is WebViewSessionPrivacy.SessionRoute.SystemDefault ->
                    controller.clearProxyOverride(executor) {}
                is WebViewSessionPrivacy.SessionRoute.Routed ->
                    controller.setProxyOverride(
                        ProxyConfig.Builder().addProxyRule(route.proxyRule).build(),
                        executor
                    ) {}
            }
        }.onFailure { Log.w("WebSource", "WebView privacy-route override failed", it) }
    }

    // Spec-13 T3: intercepted audio URLs of the session, in order. The adapter
    // reads chapters from the inline playlist in the page HTML, so this is a
    // secondary signal («Додати цю книгу» when the user already pressed the
    // site's «Слухати») — the request-log parser collapses Range repeats.
    var lastCapturedAudioCount by remember { mutableStateOf(0) }

    /**
     * Captures the current page's DOM via evaluateJavascript (no JS bridge —
     * SEC-003) and imports it. The origin check keeps the import scoped to the
     * source's own domain: a cross-origin page inside the WebView (a redirect
     * to a third-party login, an embedded player host) must never become a
     * library card of this source.
     */
    fun importCurrentPage() {
        val instance = webViewInstance ?: return
        val pageUrl = currentWebUrl
        val allowedHost = runCatching {
            homeUrl.toUri().host?.lowercase()?.removePrefix("www.")
        }.getOrNull() ?: return
        val actualHost = runCatching { pageUrl.toUri().host?.lowercase()?.removePrefix("www.") }.getOrNull() ?: ""
        if (actualHost != allowedHost) {
            importResult = "Це не сторінка $displayName — додавання доступне лише з книг джерела"
            return
        }
        isImporting = true
        importResult = ""
        instance.evaluateJavascript(
            "(function(){return document.documentElement.outerHTML;})()"
        ) { raw ->
            val decoded = raw?.trim()?.let { r ->
                val inner = if (r.startsWith("\"") && r.endsWith("\"")) {
                    r.substring(1, r.length - 1)
                } else {
                    r
                }
                unescapeCapturedHtml(inner)
            } ?: ""
            if (decoded.isNotBlank() && decoded.length > 200) {
                viewModel.importWebSourcePage(sourceId, pageUrl, decoded)
                importResult = "Книгу додано до медіатеки"
            } else {
                importResult = "Сторінку не вдалося прочитати"
            }
            isImporting = false
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .testTag("web_source_browser")
    ) {
        Surface(
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 4.dp,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = onClose) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Закрити",
                                tint = MaterialTheme.colorScheme.onSurface
                            )
                        }
                        IconButton(
                            onClick = { webViewInstance?.goBack() },
                            enabled = canGoBack
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Назад",
                                tint = if (canGoBack) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                            )
                        }
                        IconButton(
                            onClick = { webViewInstance?.goForward() },
                            enabled = canGoForward
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                                contentDescription = "Вперед",
                                tint = if (canGoForward) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                            )
                        }
                        IconButton(onClick = { webViewInstance?.reload() }) {
                            Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = "Оновити",
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                        IconButton(onClick = { webViewInstance?.loadUrl(homeUrl) }) {
                            Icon(
                                imageVector = Icons.Default.Home,
                                contentDescription = "Головна",
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    }

                    // «Додати до медіатеки» — the primary import action
                    // (manual; auto-capture happens when the user pressed the
                    // site's «Слухати» first — the captured tracks show below).
                    Button(
                        onClick = { importCurrentPage() },
                        enabled = !isImporting,
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                        shape = RoundedCornerShape(AppDimens.RadiusCard),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Add,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = if (isImporting) "Додаю…" else "Додати до медіатеки",
                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                    }
                    // Spec-15 T3: the WebView catalogue hydration tool — a
                    // debug-only one-time crawl of the source's catalogue into
                    // Room (this surface is itself debug-gated, T2, so the
                    // tool never ships in release). Snapshots metadata + cover
                    // + URL as normal Source rows through the shared import
                    // path; the result counts render below.
                    if (BuildConfig.DEBUG) {
                        val isHydrating = viewModel.isHydrating.collectAsState().value
                        val hydrationResult = viewModel.hydrationResult.collectAsState().value
                        OutlinedButton(
                            onClick = { viewModel.hydrateWebSourceCatalog(sourceId) },
                            enabled = !isHydrating,
                            shape = RoundedCornerShape(AppDimens.RadiusCard),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = if (isHydrating) "Гідратую…" else "Гідратувати каталог",
                                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold)
                            )
                        }
                    }
                }

                // Spec-15 T3: hydration outcome — found/imported/failed counts.
                if (BuildConfig.DEBUG) {
                    val hydrationResult = viewModel.hydrationResult.collectAsState().value
                    if (hydrationResult != null) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = buildString {
                                append("Каталог: знайдено ${hydrationResult.found}, імпортовано ${hydrationResult.imported}")
                                if (hydrationResult.merged > 0) append(", об'єднано ${hydrationResult.merged}")
                                append(", помилок ${hydrationResult.failed}")
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.secondary
                        )
                    }
                }

                if (importResult.isNotBlank()) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = importResult,
                        style = MaterialTheme.typography.labelMedium,
                        color = if (importResult.startsWith("Книгу")) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.error
                        }
                    )
                }
                if (lastCapturedAudioCount > 0) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Захоплено $lastCapturedAudioCount аудіо-файл(и) — натисни «Додати до медіатеки»",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.secondary
                    )
                }

                Spacer(modifier = Modifier.height(6.dp))

                OutlinedTextField(
                    value = urlInput,
                    onValueChange = { urlInput = it },
                    placeholder = { Text("Введіть адресу…") },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Default.Link,
                            contentDescription = "URL",
                            tint = MaterialTheme.colorScheme.primary
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
                                Icon(imageVector = Icons.AutoMirrored.Filled.OpenInNew, contentDescription = "Браузер", tint = MaterialTheme.colorScheme.secondary)
                            }
                            if (urlInput.isNotBlank()) {
                                IconButton(onClick = {
                                    val input = urlInput.trim()
                                    val target = if (input.startsWith("http")) input else "https://$input"
                                    currentWebUrl = target
                                    webViewInstance?.loadUrl(target)
                                }) {
                                    Icon(imageVector = Icons.Default.Link, contentDescription = "Перейти", tint = MaterialTheme.colorScheme.primary)
                                }
                            }
                        }
                    },
                    singleLine = true,
                    shape = RoundedCornerShape(AppDimens.RadiusCardLg),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }

        if (isLoading) {
            LinearProgressIndicator(
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.surfaceContainerHighest
            )
        }

        Box(modifier = Modifier.weight(1f)) {
            when (sessionRoute) {
                // Spec-38 T3: honest refusal — the chosen route cannot ride
                // this device's WebView (or is the relay route), so the
                // session does not happen at all rather than leaking direct.
                is WebViewSessionPrivacy.RouteResolution.Refused -> {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.secondary),
                        shape = RoundedCornerShape(AppDimens.RadiusCard),
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
                                    tint = MaterialTheme.colorScheme.secondary,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "Браузер джерела вимкнено маршрутом приватності",
                                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                                    color = MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.weight(1f)
                                )
                                IconButton(onClick = onClose) {
                                    Icon(imageVector = Icons.Default.Close, contentDescription = "Закрити", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                            Text(
                                text = sessionRoute.reason,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
                else -> Box(modifier = Modifier.fillMaxSize()) {
            AndroidView(
                factory = { ctx ->
                    // Spec-38 T3 per-source cookie isolation: entering a
                    // source purges every stored cookie, so two sources'
                    // sessions can never coexist in the single WebView jar
                    // (third-party cookies stay rejected below). The current
                    // source re-earns its own clearance during this session;
                    // cookies survive the close so the adapter's server-side
                    // playlist fetches keep riding them until the next entry.
                    runCatching {
                        android.webkit.CookieManager.getInstance().apply {
                            removeAllCookies(null)
                            flush()
                        }
                    }
                    WebView(ctx).apply {
                        android.webkit.CookieManager.getInstance().setAcceptThirdPartyCookies(this@apply, false)
                        settings.apply {
                            javaScriptEnabled = true
                            domStorageEnabled = true
                            databaseEnabled = false
                            mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
                            mediaPlaybackRequiresUserGesture = true
                            useWideViewPort = false
                            loadWithOverviewMode = false
                            allowFileAccess = false
                            allowContentAccess = false
                            // Spec-38 T3: geolocation JS-API hard-off; the
                            // prompt below denies as a second layer.
                            // (setter only — no getter to synthesize from)
                            @Suppress("DEPRECATION")
                            setGeolocationEnabled(false)
                            // Spec-38 T3: NO UA override — the WebView sends
                            // its own genuine system UA, which is exactly the
                            // identity the transport reports into
                            // BrowserIdentity (spec-38 T1). The old hardcoded
                            // Chrome/124 string was itself a fingerprint.
                        }
                        webChromeClient = object : android.webkit.WebChromeClient() {
                            override fun onGeolocationPermissionsShowPrompt(
                                origin: String?,
                                callback: android.webkit.GeolocationPermissions.Callback?
                            ) {
                                // Deny always — belt and braces over
                                // geolocationEnabled=false above.
                                callback?.invoke(origin, false, false)
                            }
                        }
                        // Spec-38 T3: remove the sensor/geolocation JS-API
                        // surface before any page script runs. Document-start
                        // injection where supported; otherwise a best-effort
                        // page-start fallback (timing races page scripts — an
                        // honest degradation, not a guarantee).
                        val documentStartSupported = runCatching {
                            WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)
                        }.getOrDefault(false)
                        var injectLockdownOnPageStart = false
                        if (documentStartSupported) {
                            runCatching {
                                WebViewCompat.addDocumentStartJavaScript(
                                    this@apply, lockdownScript, setOf("*")
                                )
                            }.onFailure { injectLockdownOnPageStart = true }
                        } else {
                            injectLockdownOnPageStart = true
                        }
                        webViewClient = object : WebViewClient() {
                            override fun shouldOverrideUrlLoading(view: WebView?, request: android.webkit.WebResourceRequest?): Boolean {
                                val url = request?.url?.toString() ?: return false
                                val scheme = request?.url?.scheme?.lowercase() ?: ""
                                // SEC-011: whitelist http(s) only.
                                return if (scheme == "http" || scheme == "https") {
                                    false
                                } else {
                                    Log.w("WebSource", "Blocked non-http navigation: $url")
                                    true
                                }
                            }

                            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                                super.onPageStarted(view, url, favicon)
                                // Spec-38 T3 lockdown fallback for WebViews
                                // without document-start injection.
                                if (injectLockdownOnPageStart) {
                                    view?.evaluateJavascript(lockdownScript, null)
                                }
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
                                val url = request?.url?.toString() ?: return null
                                // Ad/tracker block for a quieter session (same
                                // list as the #71 prototype).
                                val host = runCatching { request.url?.host?.lowercase() }.getOrNull().orEmpty()
                                if (AD_HOST_SUFFIXES.any { host == it || host.endsWith(".$it") }) {
                                    return emptyWebResponse()
                                }
                                // Spec-13 T3: observe audio requests for the
                                // auto-capture hint. No state is shared with the
                                // page (SEC-003: no JS bridge). The log line is
                                // the session-side evidence for the S04 device
                                // checkpoint: the player's TransferListener
                                // then logs the response code + Referer.
                                if (looksLikeAudio(url)) {
                                    lastCapturedAudioCount += 1
                                    Log.w("WebSource", "Audio request in session: $url")
                                }
                                return null
                            }

                            override fun onPageFinished(view: WebView?, url: String?) {
                                super.onPageFinished(view, url)
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
                                        "style.innerHTML = '.sect-rek, .rek, #rek, .reklama, .banner, .adsbygoogle, [id^=yandex_rtb_], iframe[src*=\\\"ads\\\"] { display: none !important; height: 0 !important; }';" +
                                        "document.head.appendChild(style);" +
                                        "})();", null
                                )
                            }

                            override fun onReceivedSslError(
                                view: WebView?,
                                handler: android.webkit.SslErrorHandler?,
                                error: android.net.http.SslError?
                            ) {
                                Log.w("WebSource", "SSL error: ${error?.toString()}")
                                handler?.cancel()
                                hasWebError = true
                                webErrorMsg = "SSL помилка: ${error?.primaryError ?: 0}"
                            }

                            override fun onReceivedError(
                                view: WebView?,
                                request: android.webkit.WebResourceRequest?,
                                error: android.webkit.WebResourceError?
                            ) {
                                super.onReceivedError(view, request, error)
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
                        loadUrl(homeUrl)
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
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                    border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.secondary),
                    shape = RoundedCornerShape(AppDimens.RadiusCard),
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
                                tint = MaterialTheme.colorScheme.secondary,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Мережева помилка (${webErrorMsg.ifBlank { "ERR_NAME_NOT_RESOLVED" }})",
                                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.weight(1f)
                            )
                            IconButton(onClick = { hasWebError = false }) {
                                Icon(imageVector = Icons.Default.Close, contentDescription = "Закрити", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        Text(
                            text = "Якщо сторінка не завантажується, перевірте з'єднання або відкрийте в браузері.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
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
                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                                shape = RoundedCornerShape(AppDimens.RadiusInner)
                            ) {
                                Icon(imageVector = Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null, modifier = Modifier.size(14.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("В браузері", style = MaterialTheme.typography.labelMedium)
                            }
                            OutlinedButton(
                                onClick = {
                                    hasWebError = false
                                    isLoading = true
                                    webViewInstance?.loadUrl(currentWebUrl)
                                },
                                shape = RoundedCornerShape(AppDimens.RadiusInner)
                            ) {
                                Text("Оновити", style = MaterialTheme.typography.labelMedium)
                            }
                        }
                    }
                }
            }
                }
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            webViewInstance?.let { wv ->
                try {
                    wv.stopLoading()
                    wv.destroy()
                } catch (_: Exception) {}
            }
            webViewInstance = null
            // Spec-38 T3 session hygiene: persist the jar state cleanly; the
            // current source's cookies stay until the next source entry
            // purges them (per-source isolation happens at entry).
            runCatching { android.webkit.CookieManager.getInstance().flush() }
        }
    }
}

/** Ad/tracker host suffixes blocked during browsing (from the #71 prototype). */
private val AD_HOST_SUFFIXES = listOf(
    "adskeeper.com",
    "googlesyndication.com",
    "adtrafficquality.google",
    "google-analytics.com",
    "googletagmanager.com",
    "doubleclick.net",
    "mc.yandex.com"
)

private fun looksLikeAudio(url: String): Boolean {
    val lower = url.lowercase()
    return lower.contains(".mp3") || lower.contains(".m4a") || lower.contains(".m4b") ||
        lower.contains(".aac") || lower.contains(".ogg") || lower.contains(".opus") ||
        lower.contains(".m3u8") || lower.contains(".pl.txt")
}

/** Empty 200 response for blocked hosts. */
private fun emptyWebResponse(): android.webkit.WebResourceResponse =
    android.webkit.WebResourceResponse(
        "text/plain", "utf-8", 200, "OK",
        emptyMap(), java.io.ByteArrayInputStream(byteArrayOf())
    )

/**
 * Unescapes the JSON string literal that `evaluateJavascript` returns for the
 * captured DOM. The WebView JSON-encodes the JS string, escaping `<` as
 * `\u003C`, `>` as `\u003E`, quotes as `\"` and backslashes as `\\`; a naive
 * `replace("\\\\", "\\")` pass leaves `\u003C` literal so the HTML parser's
 * `<meta …>` / `<li …>` patterns never match (found on-device during #88:
 * sluhay title/author stayed empty while cover/playlist parsed fine).
 */
internal fun unescapeCapturedHtml(raw: String): String {
    val out = StringBuilder(raw.length)
    var i = 0
    while (i < raw.length) {
        val c = raw[i]
        if (c != '\\' || i + 1 >= raw.length) {
            out.append(c)
            i++
            continue
        }
        val next = raw[i + 1]
        when (next) {
            'u' -> {
                val hex = if (i + 5 < raw.length) raw.substring(i + 2, i + 6) else ""
                if (hex.length == 4 && hex.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' }) {
                    out.append(hex.toInt(16).toChar())
                    i += 6
                } else {
                    out.append(c)
                    i++
                }
            }
            '\\' -> { out.append('\\'); i += 2 }
            '"' -> { out.append('"'); i += 2 }
            'n' -> { out.append('\n'); i += 2 }
            't' -> { out.append('\t'); i += 2 }
            'r' -> { out.append('\r'); i += 2 }
            else -> { out.append(next); i += 2 }
        }
    }
    return out.toString()
}
