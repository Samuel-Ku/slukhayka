package com.slukhayka.audiobooks.ui.screens

import android.annotation.SuppressLint
import android.content.Context
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.webkit.ProxyConfig
import androidx.webkit.ProxyController
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.slukhayka.audiobooks.BuildConfig
import com.slukhayka.audiobooks.R
import com.slukhayka.audiobooks.data.privacy.WebViewSessionPrivacy
import com.slukhayka.audiobooks.data.source.SourceBrowserPolicy
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
    recoveryBookId: String? = null,
    recoveryChapterIndex: Int? = null,
    recoveryPositionMs: Long = 0L,
    onClose: () -> Unit
) {
    val context = LocalContext.current
    val sessionPrefs = remember {
        context.getSharedPreferences("source_browser_session", Context.MODE_PRIVATE)
    }
    var showMethodNotice by remember(sourceId) {
        mutableStateOf(
            sourceId == "4read" &&
                !sessionPrefs.getBoolean("4read_method_notice_seen", false)
        )
    }
    LaunchedEffect(sourceId) {
        if (sourceId == "4read") {
            sessionPrefs.edit().putBoolean("4read_method_notice_seen", true).apply()
        }
    }
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
    var structureMismatch by remember { mutableStateOf<com.slukhayka.audiobooks.data.imports.BrowserRecoveryCoordinator.Outcome.StructureMismatch?>(null) }
    var repairHtml by remember { mutableStateOf("") }
    var repairUrl by remember { mutableStateOf("") }
    var repairAudioUrls by remember { mutableStateOf(emptyList<String>()) }
    var showRepairConfirmation by remember { mutableStateOf(false) }
    val actionLabels = webSourceBrowserActionLabels(
        context = context,
        sourceName = displayName,
        currentAddress = currentWebUrl,
        enteredAddress = urlInput
    )

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
    val capturedAudioUrls = remember {
        java.util.Collections.synchronizedList(mutableListOf<String>())
    }

    // Spec-42 #427 — source-scoped boundary: the user cannot leave the
    // source's allowlist, whether via link, address field or programmatic
    // loadUrl. This message surfaces a blocked external navigation.
    var blockedNavMessage by remember { mutableStateOf("") }

    /**
     * Spec-42 #427 — central allowlist gate used for every navigation entry
     * point: in-page links (`shouldOverrideUrlLoading`), the address field's
     * Go action and programmatic `loadUrl`. Only `http`/`https` with a host in
     * [SourceBrowserPolicy] for [sourceId] is allowed; everything else is
     * blocked and honestly surfaced — the browser never silently leaves the
     * source.
     */
    fun isNavigationAllowed(url: String): Boolean {
        if (!SourceBrowserPolicy.isUrlAllowed(url, sourceId)) {
            Log.w("WebSource", "Blocked navigation outside allowlist for $sourceId: $url")
            return false
        }
        return true
    }

    /**
     * Captures the current page's DOM via evaluateJavascript (no JS bridge —
     * SEC-003) and imports it. The origin check keeps the import scoped to the
     * source's own domain: a cross-origin page inside the WebView (a redirect
     * to a third-party login, an embedded player host) must never become a
     * library card of this source. #430 — the whole flow is one operation:
     * capture → parse → identity guard → Track update → Player verdict → UI
     * outcome. `http(s)` prefix alone is never success.
     */
    fun importCurrentPage() {
        val instance = webViewInstance ?: return
        val pageUrl = currentWebUrl
        // Import remains scoped to the source's own allowlist (same gate as
        // navigation): an off-source page can never become a card of this
        // source.
        if (!SourceBrowserPolicy.isUrlAllowed(pageUrl, sourceId)) {
            importResult = "Це не сторінка $displayName — додавання доступне лише з книг джерела"
            return
        }
        // Snapshot the ordered audio candidates observed so far (deduplicated).
        val audioCandidates = synchronized(capturedAudioUrls) { capturedAudioUrls.toList() }
        isImporting = true
        importResult = ""
        structureMismatch = null
        blockedNavMessage = ""
        instance.evaluateJavascript(
            """(function(){
                const html = document.documentElement.outerHTML;
                const urls = [...html.matchAll(/file\s*:\s*["']([^"']+\.(?:m3u|txt)(?:\?[^"']*)?)["']/gi)].map(m => m[1]);
                const manifests = [...new Set(urls)].map(url => {
                    try {
                        const request = new XMLHttpRequest();
                        request.open('GET', url, false);
                        request.withCredentials = true;
                        request.send(null);
                        if (request.status !== 200) return '';
                        const body = request.responseText;
                        return '<i data-slukhayka-playlist="' + btoa(unescape(encodeURIComponent(body))) + '"></i>';
                    } catch (_) { return ''; }
                });
                return html + manifests.join('');
            })()"""
        ) { raw ->
            val decoded = raw?.trim()?.let { r ->
                val inner = if (r.startsWith("\"") && r.endsWith("\"")) {
                    r.substring(1, r.length - 1)
                } else {
                    r
                }
                unescapeCapturedHtml(inner)
            } ?: ""
            if (decoded.isBlank() || decoded.length < 200) {
                importResult = "Сторінку не вдалося прочитати"
                isImporting = false
                return@evaluateJavascript
            }
            if (recoveryBookId != null && recoveryChapterIndex != null) {
                // #430 — recovery: the coordinator verifies the real media-open
                // verdict; failure keeps the browser open with an honest message.
                viewModel.recoverWebSourcePage(
                    bookId = recoveryBookId,
                    sourceId = sourceId,
                    url = pageUrl,
                    html = decoded,
                    capturedAudioUrls = synchronized(capturedAudioUrls) { capturedAudioUrls.toList() },
                    chapterIndex = recoveryChapterIndex,
                    positionMs = recoveryPositionMs,
                    onComplete = { success ->
                    if (success) {
                        importResult = "Книгу оновлено"
                        // Success closes the browser and resumes the same
                        // Chapter/position.
                        onClose()
                    } else {
                        importResult = "Аудіо ще не знайдено. Відкрийте книгу та запустіть її на сайті, потім спробуйте ще раз."
                    }
                    isImporting = false
                    },
                    onStructureMismatch = { mismatch ->
                        importResult = mismatch.message
                        structureMismatch = mismatch
                        repairHtml = decoded
                        repairUrl = pageUrl
                        repairAudioUrls = audioCandidates
                        isImporting = false
                    }
                )
            } else {
                viewModel.importWebSourcePage(
                    sourceId,
                    pageUrl,
                    decoded,
                    capturedAudioUrls = synchronized(capturedAudioUrls) { capturedAudioUrls.toList() }
                ) { success ->
                    if (success) {
                        importResult = "Книгу додано до медіатеки"
                        onClose()
                    } else {
                        importResult = "Аудіо ще не знайдено. Відкрийте книгу та запустіть її на сайті, потім спробуйте ще раз."
                    }
                    isImporting = false
                }
            }
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
                        IconButton(
                            onClick = onClose,
                            modifier = Modifier
                                .size(AppDimens.TouchTarget)
                                .semantics { contentDescription = actionLabels.closeSource }
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurface
                            )
                        }
                        IconButton(
                            onClick = { webViewInstance?.goBack() },
                            enabled = canGoBack,
                            modifier = Modifier
                                .size(AppDimens.TouchTarget)
                                .semantics { contentDescription = actionLabels.back }
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = null,
                                tint = if (canGoBack) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                            )
                        }
                        IconButton(
                            onClick = { webViewInstance?.goForward() },
                            enabled = canGoForward,
                            modifier = Modifier
                                .size(AppDimens.TouchTarget)
                                .semantics { contentDescription = actionLabels.forward }
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                                contentDescription = null,
                                tint = if (canGoForward) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                            )
                        }
                        IconButton(
                            onClick = { webViewInstance?.reload() },
                            modifier = Modifier
                                .size(AppDimens.TouchTarget)
                                .semantics { contentDescription = actionLabels.reload }
                        ) {
                            Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                        IconButton(
                            onClick = {
                                if (!SourceBrowserPolicy.isUrlAllowed(homeUrl, sourceId)) {
                                    blockedNavMessage = "Перехід за межі $displayName заблоковано"
                                } else {
                                    blockedNavMessage = ""
                                    webViewInstance?.loadUrl(homeUrl)
                                }
                            },
                            modifier = Modifier
                                .size(AppDimens.TouchTarget)
                                .semantics { contentDescription = actionLabels.home }
                        ) {
                            Icon(
                                imageVector = Icons.Default.Home,
                                contentDescription = null,
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
                }

                // Spec-15 T3: the debug-only catalogue hydration tool stays
                // outside the browser's primary action row. Five browser
                // controls plus the import action already fill a narrow phone;
                // squeezing a labelled debug action beside them made its text
                // wrap vertically and pushed the whole browser below the fold.
                if (BuildConfig.DEBUG) {
                    val isHydrating = viewModel.isHydrating.collectAsState().value
                    Spacer(modifier = Modifier.height(6.dp))
                    OutlinedButton(
                        onClick = { viewModel.hydrateWebSourceCatalog(sourceId) },
                        enabled = !isHydrating,
                        shape = RoundedCornerShape(AppDimens.RadiusCard),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                        modifier = Modifier.align(Alignment.End)
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
                    if (structureMismatch != null) {
                        TextButton(onClick = { showRepairConfirmation = true }) { Text("Виправити структуру книги") }
                    }
                }
                if (blockedNavMessage.isNotBlank()) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = blockedNavMessage,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error
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

                if (showRepairConfirmation && structureMismatch != null) {
                    val mismatch = requireNotNull(structureMismatch)
                    AlertDialog(
                        onDismissRequest = { showRepairConfirmation = false },
                        title = { Text("Виправити структуру книги?") },
                        text = {
                            Text(
                                "Розділів було ${mismatch.storedChapterCount}, стане ${mismatch.capturedChapterCount}. " +
                                    "Будуть очищені локальні файли цієї структури, прогрес по розділах і закладки. " +
                                    "Назва книги, медіатека та джерело 4read залишаться."
                            )
                        },
                        confirmButton = {
                            TextButton(onClick = {
                                showRepairConfirmation = false
                                isImporting = true
                                viewModel.repairConfirmedWebSourceStructure(
                                    bookId = requireNotNull(recoveryBookId),
                                    sourceId = sourceId,
                                    url = repairUrl,
                                    html = repairHtml,
                                    capturedAudioUrls = repairAudioUrls
                                ) { repaired ->
                                    importResult = if (repaired) {
                                        structureMismatch = null
                                        "Структуру книги виправлено"
                                    } else {
                                        "Не вдалося підтвердити нову структуру. Дані не змінено."
                                    }
                                    isImporting = false
                                }
                            }) { Text("Виправити") }
                        },
                        dismissButton = {
                            TextButton(onClick = { showRepairConfirmation = false }) { Text("Скасувати") }
                        }
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
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                    },
                    trailingIcon = {
                        Row {
                            IconButton(
                                onClick = {
                                    try {
                                        val intent = Intent(Intent.ACTION_VIEW, currentWebUrl.toUri())
                                        context.startActivity(intent)
                                    } catch (_: Exception) {}
                                },
                                modifier = Modifier
                                    .size(AppDimens.TouchTarget)
                                    .semantics { contentDescription = actionLabels.openExternal }
                            ) {
                                Icon(imageVector = Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null, tint = MaterialTheme.colorScheme.secondary)
                            }
                            if (urlInput.isNotBlank()) {
                                IconButton(
                                    onClick = {
                                        val input = urlInput.trim()
                                        val target = if (input.startsWith("http")) input else "https://$input"
                                        // Spec-42 #427 — address field cannot bypass allowlist:
                                        // same gate as shouldOverrideUrlLoading.
                                        if (!SourceBrowserPolicy.isUrlAllowed(target, sourceId)) {
                                            blockedNavMessage = "Перехід за межі $displayName заблоковано"
                                            Log.w("WebSource", "Blocked address-bar navigation outside allowlist for $sourceId: $target")
                                        } else {
                                            blockedNavMessage = ""
                                            currentWebUrl = target
                                            webViewInstance?.loadUrl(target)
                                        }
                                    },
                                    modifier = Modifier
                                        .size(AppDimens.TouchTarget)
                                        .semantics { contentDescription = actionLabels.goToAddress }
                                ) {
                                    Icon(imageVector = Icons.Default.Link, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
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

        if (showMethodNotice) {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Row(
                    modifier = Modifier.padding(start = 12.dp, end = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Метод 4read змінився: для прослуховування потрібен браузер.",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = {
                        showMethodNotice = false
                        sessionPrefs.edit().putBoolean("4read_method_notice_seen", true).apply()
                    }) {
                        Text("Зрозуміло")
                    }
                }
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
                                IconButton(
                                    onClick = onClose,
                                    modifier = Modifier
                                        .size(AppDimens.TouchTarget)
                                        .semantics { contentDescription = actionLabels.closeRouteMessage }
                                ) {
                                    Icon(imageVector = Icons.Default.Close, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
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
                    WebView(ctx).apply {
                        // Keep first-party cookies per source across closing
                        // and reopening the browser, while purging the global
                        // jar before restoring this source's own snapshot.
                        // No third-party cookie is persisted or restored.
                        val cookieManager = android.webkit.CookieManager.getInstance()
                        val savedCookies = sessionPrefs.getString("cookies_$sourceId", null)
                        cookieManager.removeAllCookies {
                            if (!savedCookies.isNullOrBlank()) {
                                savedCookies.split(';')
                                    .map(String::trim)
                                    .filter(String::isNotBlank)
                                    .forEach { cookie -> cookieManager.setCookie(homeUrl, cookie) }
                            }
                            cookieManager.flush()
                            post { loadUrl(homeUrl) }
                        }
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
                                // SEC-011 + spec-42 #427: only http(s) and the
                                // selected source's allowlisted page hosts.
                                // Audio CDN hosts are observed as subresources,
                                // never as top-level navigation.
                                if (scheme != "http" && scheme != "https") {
                                    Log.w("WebSource", "Blocked non-http navigation: $url")
                                    return true
                                }
                                // Spec-42 #427 — top navigation (and every programmatic
                                // loadUrl path) accepts only the current source's
                                // allowlisted http(s) hosts. The address field
                                // uses the SAME gate — no bypass.
                                if (!SourceBrowserPolicy.isUrlAllowed(url, sourceId)) {
                                    Log.w("WebSource", "Blocked navigation outside allowlist for $sourceId: $url")
                                    blockedNavMessage = "Перехід за межі $displayName заблоковано"
                                    return true
                                }
                                blockedNavMessage = ""
                                return false
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
                                // #430 — new top-level page → fresh candidate set.
                                synchronized(capturedAudioUrls) { capturedAudioUrls.clear() }
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
                                val pageHost = runCatching {
                                    homeUrl.toUri().host?.lowercase()?.removePrefix("www.")
                                }.getOrNull()
                                if (looksLikeAudio(url) && SourceBrowserPolicy.allowsAudioHost(sourceId, host, pageHost)) {
                                    val isNew = synchronized(capturedAudioUrls) { capturedAudioUrls.add(url) }
                                    if (isNew) lastCapturedAudioCount = capturedAudioUrls.size
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
                                    sourceBrowserAdCleanupScript(), null
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
                        // Spec-42 #427 — programmatic open also through the same allowlist.
                        if (SourceBrowserPolicy.isUrlAllowed(homeUrl, sourceId)) {
                            loadUrl(homeUrl)
                        } else {
                            Log.w("WebSource", "Blocked initial load outside allowlist for $sourceId: $homeUrl")
                        }
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
                            IconButton(
                                onClick = { hasWebError = false },
                                modifier = Modifier
                                    .size(AppDimens.TouchTarget)
                                    .semantics { contentDescription = actionLabels.closeNetworkError }
                            ) {
                                Icon(imageVector = Icons.Default.Close, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
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
                                    // Spec-42 #427 — retry also through allowlist.
                                    if (!SourceBrowserPolicy.isUrlAllowed(currentWebUrl, sourceId)) {
                                        blockedNavMessage = "Перехід за межі $displayName заблоковано"
                                        return@OutlinedButton
                                    }
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
            // Spec-38 T3 session hygiene: persist only this source's
            // first-party cookie header. The next source entry purges the
            // global jar before restoring its own snapshot.
            runCatching {
                val cookieManager = android.webkit.CookieManager.getInstance()
                cookieManager.getCookie(homeUrl)?.takeIf { it.isNotBlank() }?.let { cookies ->
                    sessionPrefs.edit().putString("cookies_$sourceId", cookies).apply()
                }
                cookieManager.flush()
                sessionPrefs.edit().putBoolean("4read_method_notice_seen", true).apply()
            }
        }
    }
}

internal data class WebSourceBrowserActionLabels(
    val closeSource: String,
    val back: String,
    val forward: String,
    val reload: String,
    val home: String,
    val openExternal: String,
    val goToAddress: String,
    val closeRouteMessage: String,
    val closeNetworkError: String
)

internal fun webSourceBrowserActionLabels(
    context: Context,
    sourceName: String,
    currentAddress: String,
    enteredAddress: String
): WebSourceBrowserActionLabels = WebSourceBrowserActionLabels(
    closeSource = context.getString(R.string.a11y_browser_close_source, sourceName),
    back = context.getString(R.string.a11y_browser_back, sourceName),
    forward = context.getString(R.string.a11y_browser_forward, sourceName),
    reload = context.getString(R.string.a11y_browser_reload, sourceName),
    home = context.getString(R.string.a11y_browser_home, sourceName),
    openExternal = context.getString(R.string.a11y_browser_open_external, currentAddress),
    goToAddress = context.getString(R.string.a11y_browser_go_to_address, enteredAddress),
    closeRouteMessage = context.getString(R.string.a11y_browser_close_route_message),
    closeNetworkError = context.getString(R.string.a11y_browser_close_network_error)
)

/** Ad/tracker host suffixes blocked during browsing (from the #71 prototype). */
private val AD_HOST_SUFFIXES = listOf(
    "adskeeper.com",
    "adnxs.com",
    "adform.net",
    "adfox.ru",
    "adservice.google.com",
    "googleadservices.com",
    "googlesyndication.com",
    "adtrafficquality.google",
    "google-analytics.com",
    "googletagmanager.com",
    "doubleclick.net",
    "mc.yandex.com",
    "outbrain.com",
    "taboola.com"
)

/**
 * Removes advertising placeholders already injected into a source page and
 * keeps removing late async inserts. Network blocking alone is insufficient:
 * an ad slot can be supplied by a script cached before this WebView session.
 */
internal fun sourceBrowserAdCleanupScript(): String = """
    (function () {
      var selectors = '.sect-rek, .rek, #rek, .reklama, .banner, .adsbygoogle, ins.adsbygoogle, [id^="yandex_rtb_"], [id*="adskeeper" i], [class*="adskeeper" i], [data-adskeeper], [id*="reklam" i], [class*="reklam" i], iframe[src*="adskeeper"], iframe[src*="doubleclick"], iframe[src*="googlesyndication"], iframe[src*="adfox"]';
      var removeAds = function () {
        document.querySelectorAll(selectors).forEach(function (node) { node.remove(); });
      };
      if (window.__slukhaykaAdCleanupInstalled) {
        removeAds();
        return;
      }
      window.__slukhaykaAdCleanupInstalled = true;
      var style = document.createElement('style');
      style.textContent = selectors + ' { display: none !important; height: 0 !important; min-height: 0 !important; }';
      document.head.appendChild(style);
      removeAds();
      new MutationObserver(removeAds).observe(document.documentElement, { childList: true, subtree: true });
    })();
""".trimIndent()

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
