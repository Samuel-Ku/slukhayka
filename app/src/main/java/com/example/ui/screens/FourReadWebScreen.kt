package com.example.ui.screens

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
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
    var urlInput by remember { mutableStateOf("https://4read.org/") }
    var currentWebUrl by remember { mutableStateOf("https://4read.org/") }
    var isLoading by remember { mutableStateOf(false) }
    var hasWebError by remember { mutableStateOf(false) }
    var webErrorMsg by remember { mutableStateOf("") }
    var webViewInstance by remember { mutableStateOf<WebView?>(null) }
    val allBooks by viewModel.allBooks.collectAsState()

    val quickLinks = listOf(
        "Нейромант" to "https://4read.org/2172-ybson-vylyam-neyromant.html",
        "1984" to "https://4read.org/1984.html",
        "451° Фаренгейт" to "https://4read.org/fahrenheit-451.html",
        "Дюна" to "https://4read.org/dune.html",
        "Солярис" to "https://4read.org/solaris.html",
        "Пикник на обочине" to "https://4read.org/roadside-picnic.html",
        "Мастер и Маргарита" to "https://4read.org/master-i-margarita.html",
        "Шерлок Холмс" to "https://4read.org/sherlock-holmes.html"
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(CyberBg)
            .testTag("4read_web_screen")
    ) {
        // Header
        Surface(
            color = CyberSurface,
            tonalElevation = 4.dp,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Language,
                            contentDescription = null,
                            tint = CyberPrimary,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "4read.org Web Catalog",
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontWeight = FontWeight.Bold,
                                fontSize = 18.sp
                            ),
                            color = CyberTextPrimary
                        )
                    }

                    Button(
                        onClick = {
                            viewModel.importAndPlay4ReadUrl(currentWebUrl)
                            onBookImported()
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
                            text = "Play This Book",
                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                            color = CyberOnPrimary
                        )
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Input Bar for URL or Title Search
                OutlinedTextField(
                    value = urlInput,
                    onValueChange = { urlInput = it },
                    placeholder = { Text("Paste 4read.org link or title...") },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Default.Link,
                            contentDescription = "URL",
                            tint = CyberPrimary
                        )
                    },
                    trailingIcon = {
                        Row {
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
                                    Icon(imageVector = Icons.Default.ArrowForward, contentDescription = "Go", tint = CyberPrimary)
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

                Spacer(modifier = Modifier.height(10.dp))

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

        // WebView displaying 4read.org or fallback mirror when network DNS fails
        Box(modifier = Modifier.weight(1f)) {
            AndroidView(
                factory = { context ->
                    WebView(context).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        webViewClient = object : WebViewClient() {
                            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                                super.onPageStarted(view, url, favicon)
                                isLoading = true
                                hasWebError = false
                                url?.let {
                                    currentWebUrl = it
                                    urlInput = it
                                }
                            }

                            override fun onPageFinished(view: WebView?, url: String?) {
                                super.onPageFinished(view, url)
                                isLoading = false
                                url?.let {
                                    currentWebUrl = it
                                    urlInput = it
                                }
                            }

                            override fun onReceivedError(
                                view: WebView?,
                                request: android.webkit.WebResourceRequest?,
                                error: android.webkit.WebResourceError?
                            ) {
                                super.onReceivedError(view, request, error)
                                if (request?.isForMainFrame == true) {
                                    isLoading = false
                                    hasWebError = true
                                    webErrorMsg = error?.description?.toString() ?: "net::ERR_NAME_NOT_RESOLVED"
                                }
                            }
                        }
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
                Surface(
                    color = CyberBg,
                    modifier = Modifier.fillMaxSize()
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Card(
                            colors = CardDefaults.cardColors(containerColor = CyberCardBg),
                            border = androidx.compose.foundation.BorderStroke(1.dp, CyberPrimary),
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        imageVector = Icons.Default.Warning,
                                        contentDescription = null,
                                        tint = CyberSecondary,
                                        modifier = Modifier.size(24.dp)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = "4read.org Mirror Mode Active",
                                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                        color = CyberTextPrimary
                                    )
                                }
                                Spacer(modifier = Modifier.height(6.dp))
                                Text(
                                        text = "Домен 4read.org недоступний (помилка: ${webErrorMsg.ifBlank { "net::ERR_NAME_NOT_RESOLVED" }}). Увімкнено швидкий дзеркальний аудіокаталог 4read Mirror з можливістю імпорту та онлайн-слухання.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = CyberTextSecondary
                                    )
                                Spacer(modifier = Modifier.height(12.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Button(
                                        onClick = {
                                            hasWebError = false
                                            isLoading = true
                                            webViewInstance?.loadUrl(currentWebUrl)
                                        },
                                        colors = ButtonDefaults.buttonColors(containerColor = CyberPrimary),
                                        shape = RoundedCornerShape(10.dp)
                                    ) {
                                        Icon(imageVector = Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text("Повторити спробу", fontSize = 13.sp)
                                    }

                                    OutlinedButton(
                                        onClick = {
                                            viewModel.importAndPlay4ReadUrl(currentWebUrl)
                                            onBookImported()
                                        },
                                        shape = RoundedCornerShape(10.dp),
                                        colors = ButtonDefaults.outlinedButtonColors(contentColor = CyberPrimary)
                                    ) {
                                        Text("Імпортувати посилання", fontSize = 13.sp)
                                    }
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        Text(
                            text = "📚 Дзеркальний Каталог 4read (Доступні книги)",
                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                            color = CyberTextPrimary,
                            modifier = Modifier.align(Alignment.Start)
                        )

                        Spacer(modifier = Modifier.height(10.dp))

                        androidx.compose.foundation.lazy.LazyColumn(
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                            modifier = Modifier.fillMaxSize()
                        ) {
                            items(allBooks) { book ->
                                Card(
                                    colors = CardDefaults.cardColors(containerColor = CyberCardBg),
                                    shape = RoundedCornerShape(12.dp),
                                    border = androidx.compose.foundation.BorderStroke(1.dp, CyberCardBorder),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(12.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = book.title,
                                                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                                                color = CyberTextPrimary,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                            Text(
                                                text = "${book.author} • ${book.genre}",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = CyberTextSecondary
                                            )
                                        }

                                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                            IconButton(
                                                onClick = {
                                                    viewModel.playAudiobook(book, autoPlay = true)
                                                    onBookImported()
                                                }
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Default.PlayArrow,
                                                    contentDescription = "Слухати",
                                                    tint = CyberPrimary
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
