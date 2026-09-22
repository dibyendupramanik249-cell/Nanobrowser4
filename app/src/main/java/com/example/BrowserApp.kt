package com.example

import android.app.Application
import java.io.File

class BrowserApp : Application() {

    override fun onCreate() {
        super.onCreate()
        try {
            // Clean up any previously corrupted HTTP Cache directory injected by prior runs
            val badSubdir = File(cacheDir, "WebView/Default/HTTP Cache/Code Cache")
            if (badSubdir.exists()) {
                File(cacheDir, "WebView").deleteRecursively()
            }
        } catch (_: Exception) {}
    }
}

