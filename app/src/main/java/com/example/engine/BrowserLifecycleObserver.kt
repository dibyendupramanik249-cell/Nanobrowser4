package com.example.engine

import android.webkit.GeolocationPermissions
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.util.Log

class CustomChromeClient(
    private val onProgressChangedAction: ((Int) -> Unit)? = null
) : WebChromeClient() {

    override fun onProgressChanged(view: WebView?, newProgress: Int) {
        super.onProgressChanged(view, newProgress)
        onProgressChangedAction?.invoke(newProgress)
    }

    override fun onPermissionRequest(request: PermissionRequest?) {
        Log.d("CustomChromeClient", "Permission denied automatically: ${request?.resources?.joinToString()}")
        request?.deny()
    }

    override fun onGeolocationPermissionsShowPrompt(
        origin: String?,
        callback: GeolocationPermissions.Callback?
    ) {
        Log.d("CustomChromeClient", "Geolocation denied automatically for origin: $origin")
        callback?.invoke(origin, false, false)
    }
}
