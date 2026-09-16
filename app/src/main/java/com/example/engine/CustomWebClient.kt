package com.example.engine

import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.graphics.Bitmap
import android.util.Log
import com.example.data.CookieFirewall
import java.io.ByteArrayInputStream

class CustomWebClient(
    private val onPageFinishedAction: ((String?) -> Unit)? = null
) : WebViewClient() {

    override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
        super.onPageStarted(view, url, favicon)
        CookieFirewall.evaluateUrl(url)
        
        val script = """
            javascript:(function() {
                window.Notification = function() { return null; };
                window.Notification.permission = 'denied';
                window.Notification.requestPermission = function() { return Promise.resolve('denied'); };
                if (navigator.serviceWorker) {
                    navigator.serviceWorker.register = function() { return Promise.reject(new Error('Service workers disabled by browser policy')); };
                }
            })();
        """.trimIndent()
        view?.evaluateJavascript(script, null)
    }

    override fun shouldInterceptRequest(
        view: WebView?,
        request: WebResourceRequest?
    ): WebResourceResponse? {
        val url = request?.url?.toString()?.lowercase() ?: return super.shouldInterceptRequest(view, request)
        
        if (url.endsWith(".woff") || url.endsWith(".woff2") || url.endsWith(".ttf")) {
            Log.d("CustomWebClient", "Blocked font request: $url")
            return WebResourceResponse("text/plain", "UTF-8", ByteArrayInputStream(ByteArray(0)))
        }

        return super.shouldInterceptRequest(view, request)
    }

    override fun onPageFinished(view: WebView?, url: String?) {
        super.onPageFinished(view, url)
        onPageFinishedAction?.invoke(url)
    }
}
