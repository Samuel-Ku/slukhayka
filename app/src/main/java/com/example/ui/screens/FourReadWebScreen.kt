package com.example.ui.screens

import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.viewinterop.AndroidView
import com.example.ui.MainViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FourReadWebScreen(
    viewModel: MainViewModel,
    onBookImported: () -> Unit
) {
    val url by viewModel.webFallbackUrl.collectAsState()
    val targetUrl = url ?: "https://4read.org"

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("4read.org", style = MaterialTheme.typography.titleMedium) },
                navigationIcon = {
                    IconButton(
                        onClick = { viewModel.closeWebFallback() },
                        modifier = Modifier.testTag("close_web_fallback_button")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Назад"
                        )
                    }
                }
            )
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            AndroidView(
                factory = { context ->
                    WebView(context).apply {
                        settings.javaScriptEnabled = true
                        webViewClient = WebViewClient()
                        loadUrl(targetUrl)
                    }
                },
                update = { webView ->
                    webView.loadUrl(targetUrl)
                },
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}
