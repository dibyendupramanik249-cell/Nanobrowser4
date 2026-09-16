package com.example.data

import android.webkit.CookieManager
import android.webkit.WebView
import android.util.Log

object CookieFirewall {
    private val cookieManager = CookieManager.getInstance()
    private var isAuthMode = false
    private val authWhitelist = listOf(
        "accounts.google.com",
        "accounts.youtube.com",
        "oauth",
        "login",
        "signin",
        "auth"
    )

    fun initialize() {
        cookieManager.setAcceptCookie(false)
        Log.d("CookieFirewall", "Cookie Firewall Initialized: Cookies Disabled")
    }

    fun isAuthRoute(url: String?): Boolean {
        if (url == null) return false
        val lowerUrl = url.lowercase()
        return authWhitelist.any { lowerUrl.contains(it) }
    }

    fun evaluateUrl(webView: WebView?, url: String?) {
        val auth = isAuthRoute(url)
        if (auth != isAuthMode) {
            isAuthMode = auth
            cookieManager.setAcceptCookie(auth)
            webView?.let {
                cookieManager.setAcceptThirdPartyCookies(it, auth)
            }
            Log.d("CookieFirewall", "Auth mode switched to $auth. Cookies updated.")
        }
    }
}
