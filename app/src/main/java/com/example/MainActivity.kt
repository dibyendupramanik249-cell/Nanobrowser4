package com.example

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.app.DownloadManager
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.util.Base64
import android.view.KeyEvent
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.URLUtil
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import com.example.engine.BrowserLifecycleObserver
import com.example.engine.CustomChromeClient
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.util.concurrent.ConcurrentHashMap

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private val activeStreams = ConcurrentHashMap<String, StreamHolder>()

    private data class StreamHolder(
        val outputStream: OutputStream,
        val uri: Uri?,
        val tempFile: File?
    )

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        webView = WebView(this)
        setContentView(webView)

        // Passed as positional arguments to avoid named-parameter compilation errors
        val lifecycleObserver = BrowserLifecycleObserver(application) {
            clearMemoryAndCookies()
        }
        lifecycle.addObserver(lifecycleObserver)
        webView.webChromeClient = CustomChromeClient()

        webView.addJavascriptInterface(BlobInterface(), "AndroidBlobBridge")

        val settings: WebSettings = webView.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.databaseEnabled = true
        settings.useWideViewPort = true
        settings.loadWithOverviewMode = true
        settings.setSupportZoom(true)
        settings.builtInZoomControls = true
        settings.displayZoomControls = false
        settings.allowFileAccess = false
        settings.allowContentAccess = true

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
                return false
            }
        }

        webView.setDownloadListener { url, userAgent, contentDisposition, mimetype, _ ->
            if (url != null) {
                startDownload(url, userAgent, contentDisposition, mimetype)
            }
        }

        webView.setOnLongClickListener {
            val result = webView.hitTestResult
            val type = result.type

            if (type == WebView.HitTestResult.IMAGE_TYPE ||
                type == WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE
            ) {
                val mediaUrl = result.extra
                if (!mediaUrl.isNullOrEmpty()) {
                    showDownloadConfirmation(mediaUrl)
                    return@setOnLongClickListener true
                }
            }
            false
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (webView.canGoBack()) {
                    webView.goBack()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })

        webView.loadUrl("https://gemini.google.com")
    }

    private fun showDownloadConfirmation(url: String) {
        AlertDialog.Builder(this)
            .setTitle("Download Media")
            .setMessage("Do you want to download this item?")
            .setPositiveButton("Download") { _, _ ->
                startDownload(url, null, null, null)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun startDownload(
        url: String,
        userAgent: String?,
        contentDisposition: String?,
        mimetype: String?
    ) {
        val cleanMime = mimetype?.takeIf { it.isNotBlank() } ?: "image/png"
        val fileName = URLUtil.guessFileName(url, contentDisposition, cleanMime)

        if (url.startsWith("blob:") || url.startsWith("data:")) {
            streamBlobToDisk(url, cleanMime, fileName)
        } else {
            downloadWithSystemManager(url, userAgent, cleanMime, fileName)
        }
    }

    private fun downloadWithSystemManager(
        url: String,
        userAgent: String?,
        mimetype: String,
        fileName: String
    ) {
        try {
            val cookies = CookieManager.getInstance().getCookie(url)
            val request = DownloadManager.Request(Uri.parse(url)).apply {
                if (!cookies.isNullOrEmpty()) {
                    addRequestHeader("Cookie", cookies)
                }
                if (!userAgent.isNullOrEmpty()) {
                    addRequestHeader("User-Agent", userAgent)
                }
                setMimeType(mimetype)
                setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
            }

            val dm = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            dm.enqueue(request)

            Toast.makeText(this, "Download started: $fileName", Toast.LENGTH_SHORT).show()
            clearMemoryAndCookies()
        } catch (e: Exception) {
            Toast.makeText(this, "Download failed: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun streamBlobToDisk(blobUrl: String, mimeType: String, fileName: String) {
        val streamId = System.currentTimeMillis().toString()
        val safeFileName = fileName.replace("'", "\\'")

        val script = """
            (function() {
                try {
                    fetch('$blobUrl')
                    .then(response => {
                        if (!response.ok) throw new Error('Fetch failed');
                        return response.blob();
                    })
                    .then(async blob => {
                        const chunkSize = 64 * 1024;
                        const totalChunks = Math.ceil(blob.size / chunkSize);
                        const suggestedName = '$safeFileName';
                        const detectedMime = blob.type || '$mimeType';

                        AndroidBlobBridge.onStreamStart('$streamId', suggestedName, detectedMime);

                        for (let i = 0; i < totalChunks; i++) {
                            const start = i * chunkSize;
                            const end = Math.min(start + chunkSize, blob.size);
                            const slice = blob.slice(start, end);

                            const buffer = await slice.arrayBuffer();
                            const bytes = new Uint8Array(buffer);
                            let binary = '';
                            const len = bytes.byteLength;
                            for (let j = 0; j < len; j++) {
                                binary += String.fromCharCode(bytes[j]);
                            }
                            const base64Chunk = btoa(binary);
                            AndroidBlobBridge.onStreamChunk('$streamId', base64Chunk);
                        }

                        AndroidBlobBridge.onStreamEnd('$streamId', suggestedName);
                    })
                    .catch(error => {
                        AndroidBlobBridge.onStreamError('$streamId', error.message || error.toString());
                    });
                } catch (err) {
                    AndroidBlobBridge.onStreamError('$streamId', err.message || err.toString());
                }
            })();
        """.trimIndent()

        webView.post {
            webView.evaluateJavascript(script, null)
        }
    }

    private fun clearMemoryAndCookies() {
        val cookieManager = CookieManager.getInstance()
        cookieManager.removeSessionCookies(null)
        cookieManager.flush()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK && webView.canGoBack()) {
            webView.goBack()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    inner class BlobInterface {
        @JavascriptInterface
        fun onStreamStart(streamId: String, fileName: String, mimeType: String) {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val contentValues = ContentValues().apply {
                        put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                        put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                        put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                    }
                    val resolver = this@MainActivity.contentResolver
                    val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
                    val os = uri?.let { resolver.openOutputStream(it) }

                    if (os != null) {
                        activeStreams[streamId] = StreamHolder(os, uri, null)
                    }
                } else {
                    val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                    if (!dir.exists()) dir.mkdirs()
                    val targetFile = File(dir, fileName)
                    val os = FileOutputStream(targetFile)
                    activeStreams[streamId] = StreamHolder(os, null, targetFile)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        @JavascriptInterface
        fun onStreamChunk(streamId: String, base64Chunk: String) {
            val holder = activeStreams[streamId] ?: return
            try {
                val bytes = Base64.decode(base64Chunk, Base64.DEFAULT)
                holder.outputStream.write(bytes)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        @JavascriptInterface
        fun onStreamEnd(streamId: String, fileName: String) {
            val holder = activeStreams.remove(streamId) ?: return
            try {
                holder.outputStream.flush()
                holder.outputStream.close()

                webView.post {
                    Toast.makeText(this@MainActivity, "Saved: $fileName", Toast.LENGTH_SHORT).show()
                    clearMemoryAndCookies()
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        @JavascriptInterface
        fun onStreamError(streamId: String, error: String) {
            val holder = activeStreams.remove(streamId)
            try {
                holder?.outputStream?.close()
            } catch (_: Exception) {}

            webView.post {
                Toast.makeText(this@MainActivity, "Download failed: $error", Toast.LENGTH_SHORT).show()
            }
        }
    }
}
