package com.example.data

import android.webkit.CookieManager
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
        cookieManager.setAcceptThirdPartyCookies(null, false)
        Log.d("CookieFirewall", "Cookie Firewall Initialized: Cookies Disabled")
    }

    fun isAuthRoute(url: String?): Boolean {
        if (url == null) return false
        val lowerUrl = url.lowercase()
        return authWhitelist.any { lowerUrl.contains(it) }
    }

    fun evaluateUrl(url: String?) {
        if (isAuthRoute(url)) {
            if (!isAuthMode) {
                isAuthMode = true
                cookieManager.setAcceptCookie(true)
                Log.d("CookieFirewall", "Auth route detected. Cookies enabled for session.")
            }
        } else {
            if (isAuthMode) {
                isAuthMode = false
                cookieManager.setAcceptCookie(false)
                Log.d("CookieFirewall", "Standard route detected. Cookies disabled, session tokens preserved.")
            }
        }
    }
}
