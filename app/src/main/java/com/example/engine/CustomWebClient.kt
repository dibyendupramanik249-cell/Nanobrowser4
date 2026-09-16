package com.example.engine

import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import com.example.data.CookieFirewall
import java.io.ByteArrayInputStream

class CustomWebClient(
    private val onPageFinishedAction: ((String?) -> Unit)? = null
) : WebViewClient() {

    override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
        super.onPageStarted(view, url, favicon)
        CookieFirewall.evaluateUrl(view, url)
        
        val script = """
            (function() {
                window.Notification = function() { return null; };
                window.Notification.permission = 'denied';
                window.Notification.requestPermission = function() { return Promise.resolve('denied'); };
                if (navigator.serviceWorker) {
                    navigator.serviceWorker.register = function() { 
                        return Promise.reject(new Error('Service workers disabled by browser policy')); 
                    };
                }
            })();
        """.trimIndent()
        view?.evaluateJavascript(script, null)
    }

    override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
        val url = request?.url?.toString() ?: return false
        
        // Let standard web protocols load inside WebView
        if (url.startsWith("http://") || url.startsWith("https://") || url.startsWith("about:blank")) {
            return false
        }

        // Handle Android intents (Google Lens, app deep links, play store redirects)
        try {
            val context = view?.context ?: return false
            val intent = if (url.startsWith("intent:")) {
                Intent.parseUri(url, Intent.URI_INTENT_SCHEME)
            } else {
                Intent(Intent.ACTION_VIEW, Uri.parse(url))
            }

            if (intent != null) {
                // Check if device has an app that can handle the intent
                if (intent.resolveActivity(context.packageManager) != null) {
                    context.startActivity(intent)
                    return true
                }
                
                // Fallback to browser URL if the app is missing
                val fallbackUrl = intent.getStringExtra("browser_fallback_url")
                if (!fallbackUrl.isNullOrEmpty()) {
                    view.loadUrl(fallbackUrl)
                    return true
                }
            }
        } catch (e: Exception) {
            Log.e("CustomWebClient", "Failed to resolve custom scheme or intent: $url", e)
        }

        return true // Prevent ERR_UNKNOWN_URL_SCHEME crash
    }

    override fun shouldInterceptRequest(
        view: WebView?,
        request: WebResourceRequest?
    ): WebResourceResponse? {
        val path = request?.url?.path?.lowercase() ?: return super.shouldInterceptRequest(view, request)
        
        if (path.endsWith(".woff") || path.endsWith(".woff2") || path.endsWith(".ttf") || path.endsWith(".otf")) {
            Log.d("CustomWebClient", "Dropped font request: $path")
            return WebResourceResponse("text/plain", "UTF-8", ByteArrayInputStream(ByteArray(0)))
        }
        return super.shouldInterceptRequest(view, request)
    }

    override fun onPageFinished(view: WebView?, url: String?) {
        super.onPageFinished(view, url)
        onPageFinishedAction?.invoke(url)
    }
}
