package com.example.ui

import android.annotation.SuppressLint
import android.view.ViewGroup
import android.webkit.WebSettings
import android.webkit.WebView
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.example.engine.CustomChromeClient
import com.example.engine.CustomWebClient
import com.example.ui.components.Omnibar

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun BrowserScreen(
    viewModel: BrowserViewModel,
    modifier: Modifier = Modifier
) {
    val currentUrl by viewModel.currentUrl.collectAsState()
    val progress by viewModel.progress.collectAsState()
    val isDesktopMode by viewModel.isDesktopMode.collectAsState()
    val isDataSaver by viewModel.isDataSaver.collectAsState()
    val purgeTrigger by viewModel.purgeTrigger.collectAsState()

    val webView = remember(purgeTrigger) { mutableListOf<WebView>() }

    Column(modifier = modifier.fillMaxSize()) {
        Omnibar(
            currentUrl = currentUrl,
            isDesktopMode = isDesktopMode,
            isDataSaver = isDataSaver,
            onUrlSubmit = { viewModel.loadUrl(it) },
            onToggleDesktop = { viewModel.toggleDesktopMode() },
            onToggleDataSaver = { viewModel.toggleDataSaver() },
            onGeminiHelp = { 
                System.gc()
                viewModel.triggerPurgeAndLoad("https://dibyendupramanik.pythonanywhere.com") 
            }
        )

        if (progress in 1..99) {
            LinearProgressIndicator(
                progress = { progress / 100f },
                modifier = Modifier.fillMaxWidth()
            )
        }

        AndroidView(
            modifier = Modifier.weight(1f),
            factory = { context ->
                WebView(context).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                    
                    settings.apply {
                        javaScriptEnabled = true
                        domStorageEnabled = true
                        databaseEnabled = true
                        setSupportZoom(true)
                        builtInZoomControls = true
                        displayZoomControls = false
                        cacheMode = WebSettings.LOAD_DEFAULT
                    }

                    webViewClient = CustomWebClient(
                        onPageFinishedAction = { url -> 
                            if (url != null && url != "about:blank") {
                                viewModel.setUrlFromEngine(url) 
                            }
                        }
                    )
                    
                    webChromeClient = CustomChromeClient(
                        onProgressChangedAction = { viewModel.updateProgress(it) }
                    )
                    
                    webView.add(this)
                }
            },
            update = { view ->
                view.settings.useWideViewPort = isDesktopMode
                view.settings.loadWithOverviewMode = isDesktopMode
                val desktopUa = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/115.0.0.0 Safari/537.36"
                val mobileUa = WebSettings.getDefaultUserAgent(view.context)
                view.settings.userAgentString = if (isDesktopMode) desktopUa else mobileUa

                view.settings.blockNetworkImage = isDataSaver

                if (view.url != currentUrl && currentUrl != "about:blank") {
                    view.loadUrl(currentUrl)
                }
            },
            onRelease = { view ->
                view.stopLoading()
                view.loadUrl("about:blank")
                view.clearCache(false)
                view.destroy()
            }
        )
    }

    BackHandler(enabled = webView.isNotEmpty() && webView[0].canGoBack()) {
        webView[0].goBack()
    }
}
