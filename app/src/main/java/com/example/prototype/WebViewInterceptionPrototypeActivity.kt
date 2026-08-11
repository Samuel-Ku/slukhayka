package com.example.prototype

import android.app.Activity
import android.graphics.Color
import android.net.http.SslError
import android.os.Bundle
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.SslErrorHandler
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CopyOnWriteArrayList

/**
 * THROWAWAY PROTOTYPE — wayfinder map #70, ticket #71: WebView audio
 * interception on sluhay.com. NOT production. Do not reuse outside the
 * prototype; delete when the verdict is captured.
 *
 * Answers, on one real book:
 *  1. which layer catches the playback URL (shouldInterceptRequest log);
 *  2. what format the player delivers (mp3 / m3u8 / playlist XHR — visible
 *     in the log as the URL that appears when audio starts);
 *  3. is the URL playable OUTSIDE the session — HTTP probe (status) and real
 *     ExoPlayer playback, both with the WebView's cookies + UA on the app's
 *     own HTTP stack (the research #72 risk: cf_clearance is bound to the
 *     WebView's TLS fingerprint, so a different stack may get 403).
 *
 * Run: adb install the debug APK, then
 *   adb shell am start -n com.aistudio.audiobook.read/.prototype.WebViewInterceptionPrototypeActivity
 */
class WebViewInterceptionPrototypeActivity : Activity() {

    /** Must match the WebView UA exactly (cf_clearance is UA-bound). */
    private val ua = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"

    private val requestLog = CopyOnWriteArrayList<String>()
    private var lastAudioUrl: String? = null

    private lateinit var webView: WebView
    private lateinit var logView: TextView
    private lateinit var statusView: TextView
    private var player: ExoPlayer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(18, 18, 22))
        }

        webView = WebView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
            )
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                userAgentString = this@WebViewInterceptionPrototypeActivity.ua
                mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                mediaPlaybackRequiresUserGesture = true
                allowFileAccess = false
                allowContentAccess = false
            }
            CookieManager.getInstance().setAcceptCookie(true)
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, false)
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(
                    view: WebView?, request: WebResourceRequest?
                ): Boolean {
                    val url = request?.url?.toString() ?: return false
                    val scheme = request.url?.scheme?.lowercase() ?: ""
                    return !(scheme == "http" || scheme == "https")
                }

                override fun shouldInterceptRequest(
                    view: WebView?,
                    request: WebResourceRequest?
                ): WebResourceResponse? {
                    val url = request?.url?.toString() ?: return null
                    val hdrs = runCatching { request.requestHeaders }.getOrNull().orEmpty()
                    val interesting = hdrs.filterKeys {
                        it.equals("Accept", true) || it.equals("Range", true) ||
                            it.equals("Referer", true) || it.equals("X-Requested-With", true)
                    }
                    log("[REQ] ${if (request.isForMainFrame) "MAIN" else "sub "} $url" +
                        (if (interesting.isEmpty()) "" else " hdrs=$interesting"))
                    if (looksLikeAudio(url)) {
                        lastAudioUrl = url
                        log("[AUDIO?] $url")
                        runOnUiThread { updateStatus("Останній аудіо-URL захоплено. Натисни «HTTP-проба» або «Програти через ExoPlayer».") }
                    }
                    return null
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    log("[PAGE_FINISHED] $url")
                    runOnUiThread { updateStatus("Сторінку завантажено. Якщо бачиш challenge Cloudflare — пройди його, відкрий книгу і натисни «Слухати».") }
                }

                override fun onReceivedSslError(
                    view: WebView?, handler: SslErrorHandler?, error: SslError?
                ) {
                    log("[SSL_ERROR] ${error?.toString()}")
                    handler?.cancel()
                }
            }
        }

        logView = TextView(this).apply {
            textSize = 11f
            setTextColor(Color.parseColor("#9AE6B4"))
            setBackgroundColor(Color.rgb(10, 14, 12))
            typeface = android.graphics.Typeface.MONOSPACE
        }
        val logScroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
            )
            addView(logView)
        }

        statusView = TextView(this).apply {
            textSize = 13f
            setTextColor(Color.WHITE)
            setPadding(12, 8, 12, 8)
        }

        val btnProbe = Button(this).apply { text = "HTTP-проба (власний стек)" }
        val btnPlay = Button(this).apply { text = "Програти через ExoPlayer" }
        val btnClear = Button(this).apply { text = "Очистити лог" }
        btnProbe.setOnClickListener { probeLastAudioUrl() }
        btnPlay.setOnClickListener { playLastAudioUrl() }
        btnClear.setOnClickListener { requestLog.clear(); renderLog() }

        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(btnProbe, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(btnPlay, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(btnClear, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }

        root.addView(webView)
        root.addView(statusView)
        root.addView(logScroll)
        root.addView(buttons)
        setContentView(root)

        log("[PROTOTYPE] start. Load https://sluhay.com/ …")
        webView.loadUrl("https://sluhay.com/")
    }

    override fun onDestroy() {
        player?.release()
        player = null
        webView.stopLoading()
        webView.destroy()
        super.onDestroy()
    }

    private fun looksLikeAudio(url: String): Boolean {
        val lower = url.lowercase()
        return lower.contains(".mp3") || lower.contains(".m4a") || lower.contains(".m4b") ||
            lower.contains(".aac") || lower.contains(".ogg") || lower.contains(".opus") ||
            lower.contains(".m3u8") || lower.contains("/play") || lower.contains("/stream") ||
            lower.contains("audio") || lower.contains("file")
    }

    private fun probeLastAudioUrl() {
        val url = lastAudioUrl
        if (url == null) {
            updateStatus("Немає захопленого аудіо-URL — відкрий книгу на sluhay.com і натисни «Слухати».")
            return
        }
        log("[PROBE] start $url")
        updateStatus("HTTP-проба …")
        Thread {
            val result = runCatching {
                val conn = URL(url).openConnection() as HttpURLConnection
                conn.requestMethod = "GET"
                conn.setRequestProperty("Range", "bytes=0-2047")
                conn.setRequestProperty("User-Agent", ua)
                conn.setRequestProperty("X-Requested-With", "XMLHttpRequest")
                CookieManager.getInstance().getCookie(url)?.let { cookie ->
                    if (cookie.isNotBlank()) conn.setRequestProperty("Cookie", cookie)
                }
                conn.connectTimeout = 15000
                conn.readTimeout = 15000
                val status = conn.responseCode
                val type = conn.contentType ?: ""
                val server = conn.getHeaderField("Server") ?: ""
                val contentRange = conn.getHeaderField("Content-Range") ?: ""
                val length = conn.getHeaderField("Content-Length") ?: ""
                val isCf = server.contains("cloudflare", true)
                conn.disconnect()
                "status=$status type=$type server=$server range=$contentRange len=$length cf=$isCf"
            }.getOrElse { e -> "ERROR: ${e.javaClass.simpleName}: ${e.message}" }
            runOnUiThread {
                log("[PROBE] $result")
                updateStatus("HTTP-проба: $result")
            }
        }.start()
    }

    private fun playLastAudioUrl() {
        val url = lastAudioUrl
        if (url == null) {
            updateStatus("Немає захопленого аудіо-URL.")
            return
        }
        player?.release()
        val cookie = CookieManager.getInstance().getCookie(url).orEmpty()
        val dataSource = DefaultHttpDataSource.Factory()
            .setDefaultRequestProperties(
                buildMap {
                    put("User-Agent", ua)
                    put("X-Requested-With", "XMLHttpRequest")
                    if (cookie.isNotBlank()) put("Cookie", cookie)
                }
            )
        val p = ExoPlayer.Builder(this).setMediaSourceFactory(
            androidx.media3.exoplayer.source.DefaultMediaSourceFactory(dataSource)
        ).build()
        player = p
        p.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                val s = when (state) {
                    Player.STATE_IDLE -> "IDLE"
                    Player.STATE_BUFFERING -> "BUFFERING"
                    Player.STATE_READY -> "READY (грає)"
                    Player.STATE_ENDED -> "ENDED"
                    else -> "$state"
                }
                log("[PLAYER] state=$s url=$url")
                runOnUiThread { updateStatus("Плеєр: $s") }
            }

            override fun onPlayerError(error: PlaybackException) {
                log("[PLAYER] ERROR ${error.errorCodeName}: ${error.message}")
                runOnUiThread { updateStatus("Плеєр ERROR ${error.errorCodeName} — URL поза сесією НЕ відтворюваний.") }
            }
        })
        log("[PLAYER] prepare $url (cookie=${if (cookie.isBlank()) "none" else "present"})")
        p.setMediaItem(MediaItem.fromUri(url))
        p.prepare()
        p.play()
    }

    private fun log(line: String) {
        requestLog += line
        renderLog()
    }

    private fun renderLog() {
        runOnUiThread {
            logView.text = requestLog.joinToString("\n")
        }
    }

    private fun updateStatus(text: String) {
        statusView.text = text
    }
}
