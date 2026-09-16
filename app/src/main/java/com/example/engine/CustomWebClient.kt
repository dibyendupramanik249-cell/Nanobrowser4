package com.example.engine

import android.graphics.Bitmap
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
