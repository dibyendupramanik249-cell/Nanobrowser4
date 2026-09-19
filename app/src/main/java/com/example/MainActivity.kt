package com.example

import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.ClipData
import android.content.ComponentCallbacks2
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.media.MediaScannerConnection
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Message
import android.os.Process
import android.provider.MediaStore
import android.util.Base64
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.webkit.*
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream

class MainActivity : AppCompatActivity(), ComponentCallbacks2 {

    private lateinit var webView: WebView
    private lateinit var urlBar: EditText
    private lateinit var progressBar: ProgressBar
    private lateinit var desktopBtn: Button

    private var isDesktopMode = false
    private var defaultUserAgent: String = ""
    private val desktopUserAgent =
        "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36"

    private var fileUploadCallback: ValueCallback<Array<Uri>>? = null
    private var cameraOutputUri: Uri? = null
    private var pendingDownload: DownloadTask? = null

    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var mainFrameFailedOffline = false
    private var popupCaptureWebView: WebView? = null

    private var blobOutputStream: FileOutputStream? = null
    private var blobTempFile: File? = null

    data class DownloadTask(
        val url: String,
        val userAgent: String,
        val contentDisposition: String,
        val mimeType: String
    )

    private val blockedDomains = hashSetOf(
        "doubleclick.net", "googlesyndication.com", "google-analytics.com",
        "adservice.google.com", "facebook.net", "scorecardresearch.com",
        "criteo.com", "taboola.com", "outbrain.com", "amazon-adsystem.com"
    )

    private val fileChooserLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            val data = result.data
            val pickedUri = data?.data ?: data?.clipData?.getItemAt(0)?.uri
            if (pickedUri != null) {
                fileUploadCallback?.onReceiveValue(arrayOf(pickedUri))
            } else if (cameraOutputUri != null) {
                fileUploadCallback?.onReceiveValue(arrayOf(cameraOutputUri!!))
            } else {
                val parsed = WebChromeClient.FileChooserParams.parseResult(result.resultCode, result.data)
                fileUploadCallback?.onReceiveValue(parsed)
            }
        } else {
            cameraOutputUri?.let { uri ->
                try { contentResolver.delete(uri, null, null) } catch (_: Exception) {}
            }
            fileUploadCallback?.onReceiveValue(null)
        }
        fileUploadCallback = null
        cameraOutputUri = null
    }

    inner class BlobDownloadBridge {
        @JavascriptInterface
        fun processBlobChunk(base64Chunk: String, mimeType: String, offset: Long, totalSize: Long) {
            handleBlobChunk(base64Chunk, mimeType, offset, totalSize)
        }

        @JavascriptInterface
        fun finishBlob(mimeType: String, totalSize: Long) {
            finishBlobDownload(mimeType, totalSize)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.statusBarColor = Color.parseColor("#121212")
        window.navigationBarColor = Color.BLACK

        val rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
            fitsSystemWindows = true
        }

        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.parseColor("#121212"))
            gravity = Gravity.CENTER_VERTICAL
        }

        urlBar = EditText(this).apply {
            setSingleLine(true)
            imeOptions = EditorInfo.IME_ACTION_GO
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
            hint = "Search or type URL"
            setBackgroundColor(Color.parseColor("#1E1E1E"))
            setPadding(28, 20, 28, 20)
            setOnFocusChangeListener { _, hasFocus -> if (hasFocus) setText("") }
            setOnEditorActionListener { _, actionId, event ->
                if (actionId == EditorInfo.IME_ACTION_GO ||
                    (event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)
                ) {
                    loadInput(text.toString())
                    clearFocus()
                    true
                } else {
                    false
                }
            }
        }

        desktopBtn = Button(this).apply {
            text = "DESK"
            textSize = 12f
            setTextColor(Color.GRAY)
            setBackgroundColor(Color.TRANSPARENT)
            setPadding(20, 0, 20, 0)
            setOnClickListener {
                isDesktopMode = !isDesktopMode
                if (isDesktopMode) {
                    webView.settings.userAgentString = desktopUserAgent
                    setTextColor(Color.CYAN)
                } else {
                    webView.settings.userAgentString = defaultUserAgent
                    setTextColor(Color.GRAY)
                }
                webView.reload()
            }
        }

        topBar.addView(urlBar, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        topBar.addView(desktopBtn, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.MATCH_PARENT))

        progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            visibility = View.GONE
        }

        // Set explicit white background so view swaps never flash root black
        webView = WebView(this).apply {
            setBackgroundColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        }

        rootLayout.addView(topBar)
        rootLayout.addView(progressBar)
        rootLayout.addView(webView)
        setContentView(rootLayout)

        checkRequiredPermissions()
        applyEngineSettings()
        registerNetworkRecovery()
        webView.loadUrl("https://www.google.com")
    }

    private fun checkRequiredPermissions() {
        val permissions = mutableListOf(
            android.Manifest.permission.CAMERA,
            android.Manifest.permission.RECORD_AUDIO,
            android.Manifest.permission.ACCESS_FINE_LOCATION,
            android.Manifest.permission.ACCESS_COARSE_LOCATION
        )
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
            permissions.add(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
            permissions.add(android.Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        val needed = permissions.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (needed.isNotEmpty()) {
            requestPermissions(needed.toTypedArray(), 101)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun applyEngineSettings() {
        val s = webView.settings

        s.javaScriptEnabled = true
        s.domStorageEnabled = true
        s.databaseEnabled = true
        s.setSupportZoom(true)
        s.builtInZoomControls = true
        s.displayZoomControls = false
        s.useWideViewPort = true
        s.loadWithOverviewMode = true

        s.setSupportMultipleWindows(true)
        s.javaScriptCanOpenWindowsAutomatically = true

        // Mask WebView identity: strip '; wv' and 'Version/X.X' tokens
        val rawUa = s.userAgentString
        defaultUserAgent = rawUa.replace("; wv", "").replace(Regex("Version/\\d+\\.\\d+\\s?"), "")
        s.userAgentString = defaultUserAgent

        // Keep memory minimal: limit RAM caches while keeping scripts functional
        s.cacheMode = WebSettings.LOAD_DEFAULT
        s.offscreenPreRaster = false
        s.mediaPlaybackRequiresUserGesture = true

        // Strict cookie isolation: allow first-party session data, block third-party trackers
        val cookieManager = CookieManager.getInstance()
        cookieManager.setAcceptCookie(true)
        cookieManager.setAcceptThirdPartyCookies(webView, false)

        webView.addJavascriptInterface(BlobDownloadBridge(), "AndroidBlobBridge")

        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                if (newProgress < 100) {
                    progressBar.visibility = View.VISIBLE
                    progressBar.progress = newProgress
                } else {
                    progressBar.visibility = View.GONE
                }
            }

            override fun onCreateWindow(
                view: WebView?,
                isDialog: Boolean,
                isUserGesture: Boolean,
                resultMsg: Message?
            ): Boolean {
                val transport = resultMsg?.obj as? WebView.WebViewTransport ?: return false
                if (view == null || resultMsg == null) return false

                try { popupCaptureWebView?.destroy() } catch (_: Exception) {}

                // Ephemeral capture view: enable JS so Google's redirection scripts execute
                val temp = WebView(view.context).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.userAgentString = defaultUserAgent
                }

                var captured = false
                temp.webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(v: WebView?, request: WebResourceRequest?): Boolean {
                        val target = request?.url?.toString() ?: return false
                        if (target == "about:blank" || target.startsWith("data:")) return false

                        if (!captured && (target.startsWith("http://") || target.startsWith("https://"))) {
                            captured = true
                            view.loadUrl(target)
                            v?.post {
                                try { v.destroy() } catch (_: Exception) {}
                                if (popupCaptureWebView === v) popupCaptureWebView = null
                            }
                        }
                        return true
                    }

                    override fun onPageStarted(v: WebView?, url: String?, favicon: Bitmap?) {
                        super.onPageStarted(v, url, favicon)
                        if (!captured && url != null &&
                            (url.startsWith("http://") || url.startsWith("https://")) &&
                            url != "about:blank"
                        ) {
                            captured = true
                            view.loadUrl(url)
                            v?.post {
                                try { v.destroy() } catch (_: Exception) {}
                                if (popupCaptureWebView === v) popupCaptureWebView = null
                            }
                        }
                    }
                }

                popupCaptureWebView = temp
                transport.webView = temp
                resultMsg.sendToTarget()

                temp.postDelayed({
                    if (popupCaptureWebView === temp) {
                        try { temp.destroy() } catch (_: Exception) {}
                        popupCaptureWebView = null
                    }
                }, 10_000L)
                return true
            }

            override fun onPermissionRequest(request: PermissionRequest?) {
                runOnUiThread { request?.grant(request.resources) }
            }

            override fun onGeolocationPermissionsShowPrompt(
                origin: String?,
                callback: GeolocationPermissions.Callback?
            ) {
                val hasLocation =
                    checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
                    checkSelfPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
                callback?.invoke(origin, hasLocation, false)
            }

            override fun onShowFileChooser(
                webView: WebView?,
                filePathCallback: ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?
            ): Boolean {
                fileUploadCallback?.onReceiveValue(null)
                fileUploadCallback = filePathCallback

                val intent = fileChooserParams?.createIntent() ?: Intent(Intent.ACTION_GET_CONTENT).apply {
                    type = "*/*"
                    addCategory(Intent.CATEGORY_OPENABLE)
                }

                try {
                    fileChooserLauncher.launch(Intent.createChooser(intent, "Select file"))
                } catch (_: Exception) {
                    fileUploadCallback?.onReceiveValue(null)
                    fileUploadCallback = null
                    return false
                }
                return true
            }
        }

        webView.setDownloadListener { url, userAgent, contentDisposition, mimetype, _ ->
            val task = DownloadTask(url, userAgent, contentDisposition, mimetype)
            if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P &&
                checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
            ) {
                pendingDownload = task
                requestPermissions(arrayOf(android.Manifest.permission.WRITE_EXTERNAL_STORAGE), 102)
                return@setDownloadListener
            }
            executeDownload(task)
        }

        webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                mainFrameFailedOffline = false
                urlBar.setText(url)
            }

            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val url = request?.url?.toString() ?: return false

                if (url.startsWith("http://") || url.startsWith("https://") || url.startsWith("about:blank")) {
                    return false
                }

                try {
                    val intent = Intent.parseUri(url, Intent.URI_INTENT_SCHEME).apply { component = null }
                    if (intent.resolveActivity(packageManager) != null) {
                        startActivity(intent)
                        return true
                    }
                    val fallback = intent.getStringExtra("browser_fallback_url") ?: intent.dataString
                    if (!fallback.isNullOrEmpty() && fallback.startsWith("http")) {
                        view?.loadUrl(fallback)
                        return true
                    }
                } catch (_: Exception) {}
                return true
            }

            override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                val host = request?.url?.host ?: return null

                // Neutralize trackers with valid empty JS to avoid syntax errors inside host bundles
                if (blockedDomains.any { host.contains(it) }) {
                    return WebResourceResponse(
                        "application/javascript",
                        "utf-8",
                        ByteArrayInputStream(ByteArray(0))
                    )
                }
                return super.shouldInterceptRequest(view, request)
            }

            override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                super.onReceivedError(view, request, error)
                if (request?.isForMainFrame == true) {
                    mainFrameFailedOffline = true
                }
            }
        }
    }

    private fun registerNetworkRecovery() {
        val cm = getSystemService(ConnectivityManager::class.java) ?: return
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                runOnUiThread {
                    if (mainFrameFailedOffline && !isDestroyed) {
                        mainFrameFailedOffline = false
                        webView.reload()
                    }
                }
            }
        }
        networkCallback = callback
        cm.registerNetworkCallback(
            NetworkRequest.Builder().addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build(),
            callback
        )
    }

    private fun executeDownload(task: DownloadTask) {
        val url = task.url
        if (url.startsWith("blob:")) {
            val js = """
                (function() {
                    fetch('$url')
                    .then(function(r) { return r.blob(); })
                    .then(function(b) {
                        var CHUNK = 262144;
                        var offset = 0;
                        function next() {
                            var slice = b.slice(offset, offset + CHUNK);
                            var reader = new FileReader();
                            reader.onloadend = function() {
                                if (reader.readyState === FileReader.DONE) {
                                    var s = reader.result;
                                    var b64 = s.substring(s.indexOf(',') + 1);
                                    window.AndroidBlobBridge.processBlobChunk(b64, b.type, offset, b.size);
                                    offset += CHUNK;
                                    if (offset < b.size) { setTimeout(next, 0); }
                                    else { window.AndroidBlobBridge.finishBlob(b.type, b.size); }
                                }
                            };
                            reader.readAsDataURL(slice);
                        }
                        if (b.size > 0) { next(); } else { window.AndroidBlobBridge.finishBlob(b.type, 0); }
                    })
                    .catch(function(err) { console.error(err); });
                })();
            """.trimIndent()
            webView.evaluateJavascript(js, null)
            return
        }

        try {
            val fileName = URLUtil.guessFileName(url, task.contentDisposition, task.mimeType)
            val request = DownloadManager.Request(Uri.parse(url)).apply {
                if (task.mimeType.isNotEmpty()) setMimeType(task.mimeType)
                addRequestHeader("User-Agent", task.userAgent)
                CookieManager.getInstance().getCookie(url)?.let { addRequestHeader("Cookie", it) }
                setTitle(fileName)
                setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
            }
            val dm = getSystemService(DOWNLOAD_SERVICE) as DownloadManager
            dm.enqueue(request)
            Toast.makeText(this, "Downloading $fileName", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Download failed: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun handleBlobChunk(base64Chunk: String, mimeType: String, offset: Long, totalSize: Long) {
        try {
            if (offset == 0L) {
                closeBlobStream()
                val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).apply { mkdirs() }
                val temp = File(dir, "Download_${System.currentTimeMillis()}.part")
                blobTempFile = temp
                blobOutputStream = temp.outputStream()
            }
            val bytes = Base64.decode(base64Chunk, Base64.DEFAULT)
            blobOutputStream?.write(bytes)
        } catch (_: Exception) {
            closeBlobStream()
            blobTempFile?.delete()
            blobTempFile = null
        }
    }

    private fun finishBlobDownload(mimeType: String, totalSize: Long) {
        try {
            closeBlobStream()
            val part = blobTempFile ?: return
            val ext = when {
                mimeType.contains("video/mp4") -> ".mp4"
                mimeType.contains("video/webm") -> ".webm"
                mimeType.contains("image/png") -> ".png"
                mimeType.contains("image/jpeg") -> ".jpg"
                else -> ".bin"
            }
            val target = File(part.parentFile, part.nameWithoutExtension + ext)
            if (part.renameTo(target)) {
                MediaScannerConnection.scanFile(this, arrayOf(target.absolutePath), arrayOf(mimeType), null)
                runOnUiThread { Toast.makeText(this, "Saved: ${target.name}", Toast.LENGTH_SHORT).show() }
            }
        } finally {
            blobTempFile = null
        }
    }

    private fun closeBlobStream() {
        try { blobOutputStream?.close() } catch (_: Exception) {}
        blobOutputStream = null
    }

    private fun loadInput(input: String) {
        val clean = input.trim()
        val target = when {
            clean.startsWith("http://") || clean.startsWith("https://") -> clean
            clean.contains(".") && !clean.contains(" ") -> "https://$clean"
            else -> "https://www.google.com/search?q=" + Uri.encode(clean)
        }
        webView.loadUrl(target)
    }

    override fun onResume() {
        super.onResume()
        webView.onResume()
        webView.resumeTimers()
    }

    override fun onPause() {
        super.onPause()
        webView.onPause()
        webView.pauseTimers()
    }

    // Memory governor: release non-essential cache structures when Android signals pressure
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) {
            webView.clearCache(false)
            System.gc()
        }
    }

    override fun onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack()
        } else {
            super.onBackPressed()
        }
    }

    override fun onDestroy() {
        networkCallback?.let {
            try { getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(it) } catch (_: Exception) {}
        }
        networkCallback = null

        closeBlobStream()
        try { popupCaptureWebView?.destroy() } catch (_: Exception) {}
        popupCaptureWebView = null

        webView.clearCache(true)
        webView.destroy()

        super.onDestroy()
        Process.killProcess(Process.myPid())
    }
}
