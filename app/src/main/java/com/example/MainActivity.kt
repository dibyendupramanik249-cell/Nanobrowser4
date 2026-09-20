package com.example

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.app.DownloadManager
import android.content.ClipData
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
import android.os.Message
import android.os.Environment
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
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var urlBar: EditText
    private lateinit var progressBar: ProgressBar
    private lateinit var desktopBtn: Button

    private var isDesktopMode = false
    private var defaultUserAgent: String = ""
    private val desktopUserAgent = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"

    private var fileUploadCallback: ValueCallback<Array<Uri>>? = null
    private var cameraOutputUri: Uri? = null

    private var pendingDownload: DownloadTask? = null

    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var mainFrameFailedOffline = false

    private var watchdogAttempts = 0
    private var watchdogUrl: String? = null

    private var popupCaptureWebView: WebView? = null

    private var blobOutputStream: FileOutputStream? = null
    private var blobTempFile: File? = null
    private var blobNextOffset = 0L
    private var blobDetectedMime = ""
    private var blobDetectedExt = ""

    data class DownloadTask(
        val url: String,
        val userAgent: String,
        val contentDisposition: String,
        val mimeType: String
    )

    private val fileChooserLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            val data = result.data
            val pickedUri = data?.data ?: data?.clipData?.getItemAt(0)?.uri
            if (pickedUri != null) {
                fileUploadCallback?.onReceiveValue(arrayOf(pickedUri))
                cameraOutputUri?.let { uri ->
                    try { contentResolver.delete(uri, null, null) } catch (_: Exception) {}
                }
            } else if (cameraOutputUri != null) {
                val hasContent = try {
                    contentResolver.openInputStream(cameraOutputUri!!)?.use { it.read() != -1 } ?: false
                } catch (_: Exception) {
                    false
                }

                if (hasContent) {
                    fileUploadCallback?.onReceiveValue(arrayOf(cameraOutputUri!!))
                } else {
                    val thumb = result.data?.extras?.get("data") as? Bitmap
                    if (thumb != null) {
                        try {
                            contentResolver.openOutputStream(cameraOutputUri!!)?.use { out ->
                                thumb.compress(Bitmap.CompressFormat.JPEG, 90, out)
                            }
                            fileUploadCallback?.onReceiveValue(arrayOf(cameraOutputUri!!))
                        } catch (_: Exception) {
                            fileUploadCallback?.onReceiveValue(null)
                        }
                    } else {
                        fileUploadCallback?.onReceiveValue(null)
                    }
                }
            } else {
                val results = WebChromeClient.FileChooserParams.parseResult(result.resultCode, result.data)
                fileUploadCallback?.onReceiveValue(results)
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

        @JavascriptInterface
        fun blobFailed(message: String) {
            runOnUiThread {
                Toast.makeText(this@MainActivity, "Save failed: $message", Toast.LENGTH_LONG).show()
            }
        }

        @JavascriptInterface
        fun pageImageFetchFailed(url: String) {
            runOnUiThread { downloadImageDirect(url) }
        }

        @JavascriptInterface
        fun askSaveVisibleImage(imageUrl: String) {
            runOnUiThread {
                AlertDialog.Builder(this@MainActivity)
                    .setTitle("Download blocked")
                    .setMessage("This site blocked its download button. Save the image shown on screen instead?")
                    .setPositiveButton("Save image") { _, _ ->
                        downloadImageViaPage(imageUrl)
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
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

            setOnClickListener { setText("") }
            setOnFocusChangeListener { _, hasFocus ->
                if (hasFocus) setText("")
            }

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
                    webView.settings.useWideViewPort = true
                    webView.settings.loadWithOverviewMode = true
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

        webView = WebView(this).apply {
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
        applyStrictEngineSettings()
        registerNetworkRecovery()
        webView.loadUrl("https://www.google.com")
    }

    private fun checkRequiredPermissions() {
        val permissions = mutableListOf(
            android.Manifest.permission.CAMERA,
            android.Manifest.permission.RECORD_AUDIO
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
    private fun applyStrictEngineSettings() {
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

        // Safety Fix 1: Strip the WebView flag to bypass Google's 403 disallowed_useragent login block
        defaultUserAgent = s.userAgentString.replace("; wv", "")
        s.userAgentString = defaultUserAgent

        s.offscreenPreRaster = false
        s.mediaPlaybackRequiresUserGesture = true
        s.cacheMode = WebSettings.LOAD_DEFAULT

        val cookieManager = CookieManager.getInstance()
        cookieManager.setAcceptCookie(true)
        cookieManager.setAcceptThirdPartyCookies(webView, false)

        webView.addJavascriptInterface(BlobDownloadBridge(), "AndroidBlobBridge")

        webView.setOnLongClickListener {
            val hit = webView.hitTestResult
            val target = hit.extra
            if ((hit.type == WebView.HitTestResult.IMAGE_TYPE ||
                 hit.type == WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE) &&
                !target.isNullOrEmpty()
            ) {
                showSaveImageDialog(target)
                true
            } else {
                false
            }
        }

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
                val transport = resultMsg?.obj as? WebView.WebViewTransport
                if (transport == null || view == null || resultMsg == null) {
                    return false
                }

                try { popupCaptureWebView?.destroy() } catch (_: Exception) {}
                val temp = WebView(view.context)
                temp.settings.javaScriptEnabled = true
                temp.settings.domStorageEnabled = true
                temp.settings.databaseEnabled = true
                temp.settings.cacheMode = WebSettings.LOAD_NO_CACHE
                temp.settings.userAgentString = if (isDesktopMode) desktopUserAgent else defaultUserAgent
                CookieManager.getInstance().setAcceptThirdPartyCookies(temp, false)
                var captured = false
                temp.webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(v: WebView?, request: WebResourceRequest?): Boolean {
                        val target = request?.url?.toString() ?: return false
                        if (target == "about:blank" || target.startsWith("data:")) {
                            return false
                        }
                        if (!captured && target.startsWith("blob:")) {
                            captured = true
                            val dlTask = DownloadTask(
                                target,
                                if (isDesktopMode) desktopUserAgent else defaultUserAgent,
                                "",
                                ""
                            )
                            view.post { executeDownload(dlTask) }
                            v?.post {
                                try { v.destroy() } catch (_: Exception) {}
                                if (popupCaptureWebView === v) popupCaptureWebView = null
                            }
                            return true
                        }
                        if (!captured && (target.startsWith("http://") || target.startsWith("https://"))) {
                            captured = true
                            view.loadUrl(normalizeAiModeUrl(target))
                            v?.post {
                                try { v.destroy() } catch (_: Exception) {}
                                if (popupCaptureWebView === v) popupCaptureWebView = null
                            }
                        }
                        return true 
                    }

                    override fun onPageStarted(v: WebView?, url: String?, favicon: Bitmap?) {
                        super.onPageStarted(v, url, favicon)
                        if (!captured && url != null && url.startsWith("blob:")) {
                            captured = true
                            val dlTask = DownloadTask(
                                url,
                                if (isDesktopMode) desktopUserAgent else defaultUserAgent,
                                "",
                                ""
                            )
                            view.post { executeDownload(dlTask) }
                            v?.post {
                                try { v.destroy() } catch (_: Exception) {}
                                if (popupCaptureWebView === v) popupCaptureWebView = null
                            }
                        }
                        if (!captured && url != null &&
                            (url.startsWith("http://") || url.startsWith("https://"))
                        ) {
                            captured = true
                            view.loadUrl(normalizeAiModeUrl(url))
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
                }, 15_000L)
                return true
            }

            override fun onPermissionRequest(request: PermissionRequest?) {
                runOnUiThread {
                    request?.grant(request.resources)
                }
            }

            override fun onGeolocationPermissionsShowPrompt(
                origin: String?,
                callback: GeolocationPermissions.Callback?
            ) {
                callback?.invoke(origin, false, false)
            }

            override fun onShowFileChooser(
                webView: WebView?,
                filePathCallback: ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?
            ): Boolean {
                fileUploadCallback?.onReceiveValue(null)
                fileUploadCallback = filePathCallback

                val captureRequested = try {
                    fileChooserParams?.isCaptureEnabled == true
                } catch (_: Exception) {
                    false
                }

                cameraOutputUri = if (captureRequested) {
                    try {
                        val values = ContentValues().apply {
                            put(MediaStore.Images.Media.TITLE, "IMG_${System.currentTimeMillis()}")
                            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                        }
                        contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                    } catch (_: Exception) {
                        null
                    }
                } else {
                    null
                }

                val contentIntent = fileChooserParams?.createIntent() ?: Intent(Intent.ACTION_GET_CONTENT).apply {
                    type = "*/*"
                    addCategory(Intent.CATEGORY_OPENABLE)
                }

                var launchIntent = Intent(Intent.ACTION_CHOOSER).apply {
                    putExtra(Intent.EXTRA_INTENT, contentIntent)
                    putExtra(Intent.EXTRA_TITLE, "Select file")
                }

                if (captureRequested && cameraOutputUri != null) {
                    val captureIntent = Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
                        putExtra(MediaStore.EXTRA_OUTPUT, cameraOutputUri)
                        clipData = ClipData.newUri(contentResolver, "photo", cameraOutputUri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                    }
                    if (captureIntent.resolveActivity(packageManager) != null) {
                        val resInfoList = packageManager.queryIntentActivities(captureIntent, PackageManager.MATCH_DEFAULT_ONLY)
                        for (resolveInfo in resInfoList) {
                            val pkg = resolveInfo.activityInfo.packageName
                            grantUriPermission(pkg, cameraOutputUri, Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        launchIntent = captureIntent
                    }
                }

                try {
                    fileChooserLauncher.launch(launchIntent)
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
                scheduleBlankWatchdog(url)

                if (isDesktopMode) {
                    view?.settings?.loadWithOverviewMode = false
                    view?.settings?.loadWithOverviewMode = true
                    view?.setInitialScale(0)
                }
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                scheduleBlankWatchdog(url)
                if (url != null && url.contains("google.com/search")) {
                    webView.postDelayed({ if (webView.url == url) kickRenderer() }, 1_500L)
                }
            }

            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                var url = request?.url?.toString() ?: return false

                if (isDesktopMode && url.contains("://m.")) {
                    url = url.replace("://m.", "://www.")
                    view?.loadUrl(url)
                    return true
                }

                if (url.startsWith("http://") || url.startsWith("https://") || url.startsWith("about:blank")) {
                    return false
                }

                try {
                    val intent = if (url.startsWith("intent:")) {
                        Intent.parseUri(url, Intent.URI_INTENT_SCHEME).apply {
                            component = null
                        }
                    } else {
                        Intent(Intent.ACTION_VIEW, Uri.parse(url))
                    }
                    if (intent.resolveActivity(packageManager) != null) {
                        startActivity(intent)
                        return true
                    }
                    val fallback = intent.getStringExtra("browser_fallback_url")
                    if (!fallback.isNullOrEmpty() && fallback.startsWith("http")) {
                        view?.loadUrl(fallback)
                        return true
                    }
                    val embedded = intent.dataString
                    if (embedded != null &&
                        (embedded.startsWith("http://") || embedded.startsWith("https://"))
                    ) {
                        view?.loadUrl(embedded)
                        return true
                    }
                } catch (_: Exception) {}
                return true
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?
            ) {
                super.onReceivedError(view, request, error)
                if (request?.isForMainFrame == true) {
                    mainFrameFailedOffline = when (error?.errorCode) {
                        WebViewClient.ERROR_HOST_LOOKUP,
                        WebViewClient.ERROR_CONNECT,
                        WebViewClient.ERROR_TIMEOUT,
                        WebViewClient.ERROR_IO,
                        WebViewClient.ERROR_UNKNOWN -> true
                        else -> false
                    }
                }
            }
        }
    }

    private fun normalizeAiModeUrl(url: String): String {
        return try {
            val u = Uri.parse(url)
            if (u.getQueryParameter("udm") == "50") {
                val q = u.getQueryParameter("q")
                if (!q.isNullOrEmpty()) {
                    return "https://www.google.com/search?q=" + Uri.encode(q) + "&udm=50"
                }
            }
            url
        } catch (_: Exception) {
            url
        }
    }

    private val blankCheckRunnable = object : Runnable {
        override fun run() {
            val url = webView.url ?: return
            if (url.contains("google.com/search") &&
                !webView.canScrollVertically(1) &&
                !webView.canScrollVertically(-1) &&
                watchdogAttempts < 2
            ) {
                watchdogAttempts++
                kickRenderer()
                webView.postDelayed({
                    if (webView.url == url &&
                        !webView.canScrollVertically(1) &&
                        !webView.canScrollVertically(-1)
                    ) {
                        Toast.makeText(applicationContext, "Page appeared blank — retrying", Toast.LENGTH_SHORT).show()
                        val oldMode = webView.settings.cacheMode
                        webView.settings.cacheMode = WebSettings.LOAD_NO_CACHE
                        webView.reload()
                        webView.postDelayed({
                            if (webView.settings.cacheMode == WebSettings.LOAD_NO_CACHE) {
                                webView.settings.cacheMode = oldMode
                            }
                        }, 8_000L)
                    }
                }, 6_000L)
            }
        }
    }

    private fun kickRenderer() {
        try {
            webView.onPause()
            webView.postDelayed({
                try {
                    webView.onResume()
                    webView.invalidate()
                } catch (_: Exception) {}
            }, 120L)
        } catch (_: Exception) {}
    }

    private fun scheduleBlankWatchdog(url: String?) {
        webView.removeCallbacks(blankCheckRunnable)
        if (url == null || !url.contains("google.com/search")) return
        if (watchdogUrl != url) {
            watchdogUrl = url
            watchdogAttempts = 0
        }
        webView.postDelayed(blankCheckRunnable, 12_000L)
    }

    private fun registerNetworkRecovery() {
        val cm = getSystemService(ConnectivityManager::class.java) ?: return
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                runOnUiThread {
                    if (mainFrameFailedOffline && !isDestroyed) {
                        mainFrameFailedOffline = false
                        Toast.makeText(this@MainActivity, "Back online — reloading", Toast.LENGTH_SHORT).show()
                        webView.reload()
                    }
                }
            }
        }
        networkCallback = callback
        cm.registerNetworkCallback(
            NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build(),
            callback
        )
    }

    private fun executeDownload(task: DownloadTask) {
        val url = task.url

        if (url.startsWith("blob:")) {
            Toast.makeText(this, "Downloading...", Toast.LENGTH_SHORT).show()
            val js = """
                (function() {
                    var BR = window.AndroidBlobBridge;
                    function streamBlob(blob) {
                        var CHUNK = 262144;
                        var mime = blob.type;
                        var size = blob.size;
                        var offset = 0;
                        function next() {
                            var slice = blob.slice(offset, offset + CHUNK);
                            var reader = new FileReader();
                            reader.onloadend = function() {
                                if (reader.readyState === FileReader.DONE) {
                                    var s = reader.result;
                                    var b64 = s.substring(s.indexOf(',') + 1);
                                    BR.processBlobChunk(b64, mime, offset, size);
                                    offset += CHUNK;
                                    if (offset < size) {
                                        setTimeout(next, 0);
                                    } else {
                                        BR.finishBlob(mime, size);
                                    }
                                }
                            };
                            reader.readAsDataURL(slice);
                        }
                        if (size > 0) { next(); } else { BR.finishBlob(mime, 0); }
                    }
                    function fail(msg) { BR.blobFailed(String(msg)); }
                    function tryBestImage() {
                        var imgs = document.querySelectorAll('img');
                        var best = null, bestArea = 0;
                        for (var i = 0; i < imgs.length; i++) {
                            var im = imgs[i];
                            var w = im.naturalWidth || 0, h = im.naturalHeight || 0;
                            if (w * h < 10000) { continue; }
                            var r = im.getBoundingClientRect();
                            var vis = r.top < (window.innerHeight - 20) && r.bottom > 80;
                            var area = Math.max(r.width, w) * Math.max(r.height, h);
                            if (vis && area > bestArea) { bestArea = area; best = im; }
                        }
                        var src = best ? (best.currentSrc || best.src || '') : '';
                        if (src.indexOf('http:') === 0 || src.indexOf('https:') === 0) {
                            BR.askSaveVisibleImage(src);
                        } else {
                            fail('site blocked the download');
                        }
                    }
                    function tryCanvas() {
                        var img = new Image();
                        img.onload = function() {
                            try {
                                var c = document.createElement('canvas');
                                c.width = img.naturalWidth;
                                c.height = img.naturalHeight;
                                c.getContext('2d').drawImage(img, 0, 0);
                                c.toBlob(function(b) {
                                    if (b) { streamBlob(b); } else { tryBestImage(); }
                                }, 'image/png');
                            } catch (e) { tryBestImage(); }
                        };
                        img.onerror = function() { tryBestImage(); };
                        img.src = '$url';
                    }
                    function tryXhr() {
                        try {
                            var xhr = new XMLHttpRequest();
                            xhr.open('GET', '$url');
                            xhr.responseType = 'blob';
                            xhr.onload = function() {
                                if (xhr.response) { streamBlob(xhr.response); } else { tryCanvas(); }
                            };
                            xhr.onerror = function() { tryCanvas(); };
                            xhr.send();
                        } catch (e) { tryCanvas(); }
                    }
                    try {
                        fetch('$url')
                        .then(function(r) { return r.blob(); })
                        .then(streamBlob)
                        .catch(tryXhr);
                    } catch (e) { tryXhr(); }
                })();
            """.trimIndent()
            webView.evaluateJavascript(js, null)
            return
        }

        if (url.startsWith("data:")) {
            saveRawBase64(url, task.mimeType)
            return
        }

        try {
            val fileName = URLUtil.guessFileName(url, task.contentDisposition, task.mimeType)
            val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            downloadsDir.mkdirs()

            val request = DownloadManager.Request(Uri.parse(url)).apply {
                if (task.mimeType.isNotEmpty()) {
                    setMimeType(task.mimeType)
                }
                addRequestHeader("User-Agent", task.userAgent)
                val cookies = CookieManager.getInstance().getCookie(url)
                if (!cookies.isNullOrEmpty()) {
                    addRequestHeader("Cookie", cookies)
                }
                addRequestHeader("Referer", webView.url ?: url)
                setDescription("Downloading file...")
                setTitle(fileName)
                setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
                @Suppress("DEPRECATION")
                allowScanningByMediaScanner()
            }

            val dm = getSystemService(DOWNLOAD_SERVICE) as DownloadManager
            dm.enqueue(request)
            Toast.makeText(this, "Download started: $fileName", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Download error: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showSaveImageDialog(imageUrl: String) {
        AlertDialog.Builder(this)
            .setTitle("Save image")
            .setMessage("Download this image to Downloads?")
            .setPositiveButton("Download") { _, _ ->
                when {
                    imageUrl.startsWith("data:") -> saveRawBase64(imageUrl, "")
                    imageUrl.startsWith("blob:") ->
                        executeDownload(DownloadTask(imageUrl, if (isDesktopMode) desktopUserAgent else defaultUserAgent, "", ""))
                    else -> downloadImageViaPage(imageUrl)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun downloadImageViaPage(url: String) {
        Toast.makeText(this, "Downloading...", Toast.LENGTH_SHORT).show()
        val js = """
            (function() {
                var BR = window.AndroidBlobBridge;
                function streamBlob(blob) {
                    var CHUNK = 262144;
                    var mime = blob.type;
                    var size = blob.size;
                    var offset = 0;
                    function next() {
                        var slice = blob.slice(offset, offset + CHUNK);
                        var reader = new FileReader();
                        reader.onloadend = function() {
                            if (reader.readyState === FileReader.DONE) {
                                var s = reader.result;
                                var b64 = s.substring(s.indexOf(',') + 1);
                                BR.processBlobChunk(b64, mime, offset, size);
                                offset += CHUNK;
                                if (offset < size) {
                                    setTimeout(next, 0);
                                } else {
                                    BR.finishBlob(mime, size);
                                }
                            }
                        };
                        reader.readAsDataURL(slice);
                    }
                    if (size > 0) { next(); } else { BR.finishBlob(mime, 0); }
                }
                function pageFetch(opts) {
                    return fetch('$url', opts).then(function(r) {
                        if (!r.ok) { throw new Error('HTTP ' + r.status); }
                        return r.blob();
                    });
                }
                pageFetch()
                .catch(function() {
                    return pageFetch({ credentials: 'include' });
                })
                .then(streamBlob)
                .catch(function() { BR.pageImageFetchFailed('$url'); });
            })();
        """.trimIndent()
        webView.evaluateJavascript(js, null)
    }

    private fun downloadImageDirect(url: String) {
        Toast.makeText(this, "Downloading...", Toast.LENGTH_SHORT).show()
        val refererUrl = try { webView.url } catch (_: Exception) { null } ?: url
        val ua = if (isDesktopMode) desktopUserAgent else defaultUserAgent
        Thread {
            try {
                var bytes: ByteArray? = null
                var lastErr: Exception? = null
                for (ref in listOf<String?>(null, refererUrl)) {
                    var c: HttpURLConnection? = null
                    try {
                        c = (URL(url).openConnection() as HttpURLConnection).apply {
                            connectTimeout = 15_000
                            readTimeout = 15_000
                            instanceFollowRedirects = true
                            setRequestProperty("User-Agent", ua)
                            setRequestProperty("Accept", "image/avif,image/webp,image/apng,image/svg+xml,image/*,*/*;q=0.8")
                            setRequestProperty("Accept-Language", "en-US,en;q=0.9")
                            val cookies = CookieManager.getInstance().getCookie(url)
                            if (!cookies.isNullOrEmpty()) setRequestProperty("Cookie", cookies)
                            if (ref != null) setRequestProperty("Referer", ref)
                        }
                        if (c.responseCode !in 200..299) {
                            throw RuntimeException("HTTP " + c.responseCode)
                        }
                        bytes = c.inputStream.use { it.readBytes() }
                    } catch (e: Exception) {
                        lastErr = e
                    } finally {
                        c?.disconnect()
                    }
                    if (bytes != null) break
                }
                val data = bytes ?: throw (lastErr ?: RuntimeException("download failed"))
                val sniffed = sniffFileType(data)
                val ext = sniffed.second.ifEmpty { ".jpg" }
                val fileName = "Pulse_Image_${System.currentTimeMillis()}$ext"
                val saveMime = sniffed.first.ifEmpty { "image/jpeg" }
                try {
                    val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                    downloadsDir.mkdirs()
                    val targetFile = File(downloadsDir, fileName)
                    targetFile.outputStream().use { it.write(data) }
                    MediaScannerConnection.scanFile(
                        this,
                        arrayOf(targetFile.absolutePath),
                        arrayOf(saveMime),
                        null
                    )
                } catch (_: Exception) {
                    saveBytesViaMediaStore(data, fileName, saveMime)
                }
                runOnUiThread {
                    Toast.makeText(this@MainActivity, "Saved to Downloads: $fileName", Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(this@MainActivity, "Save failed: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }.start()
    }

    private fun handleBlobChunk(base64Chunk: String, mimeType: String, offset: Long, totalSize: Long) {
        try {
            val bytes = Base64.decode(base64Chunk, Base64.DEFAULT)
            if (offset == 0L) {
                closeBlobStream()
                val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                downloadsDir.mkdirs()
                try {
                    downloadsDir.listFiles { f ->
                        f.name.endsWith(".part") && f.lastModified() < System.currentTimeMillis() - 3_600_000L
                    }?.forEach { it.delete() }
                } catch (_: Exception) {}
                try {
                    cacheDir.listFiles { f ->
                        f.name.endsWith(".part") && f.lastModified() < System.currentTimeMillis() - 3_600_000L
                    }?.forEach { it.delete() }
                } catch (_: Exception) {}
                val partName = "Flow_Video_${System.currentTimeMillis()}.part"
                var f = File(downloadsDir, partName)
                blobOutputStream = try {
                    f.outputStream()
                } catch (_: Exception) {
                    f = File(cacheDir, partName)
                    f.outputStream()
                }
                blobTempFile = f
                val sniffed = sniffFileType(bytes)
                blobDetectedMime = sniffed.first
                blobDetectedExt = sniffed.second
                blobNextOffset = bytes.size.toLong()
            } else {
                if (blobOutputStream == null) return 
                if (offset != blobNextOffset) {
                    closeBlobStream()
                    blobTempFile?.delete()
                    blobTempFile = null
                    runOnUiThread {
                        Toast.makeText(this@MainActivity, "Save failed: download interrupted", Toast.LENGTH_SHORT).show()
                    }
                    return
                }
                blobNextOffset += bytes.size
            }
            blobOutputStream?.write(bytes)
        } catch (e: Exception) {
            closeBlobStream()
            blobTempFile?.delete()
            blobTempFile = null
            runOnUiThread {
                Toast.makeText(this@MainActivity, "Save failed: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun sniffFileType(b: ByteArray): Pair<String, String> {
        fun at(i: Int, c: Char) = i < b.size && b[i] == c.toByte()
        return when {
            b.size > 12 && b[0] == 0x1A.toByte() && b[1] == 0x45.toByte() &&
                b[2] == 0xDF.toByte() && b[3] == 0xA3.toByte() -> "video/webm" to ".webm"
            b.size > 12 && at(4, 'f') && at(5, 't') && at(6, 'y') && at(7, 'p') -> "video/mp4" to ".mp4"
            b.size > 4 && b[0] == 0xFF.toByte() && b[1] == 0xD8.toByte() -> "image/jpeg" to ".jpg"
            b.size > 8 && b[0] == 0x89.toByte() && at(1, 'P') && at(2, 'N') && at(3, 'G') -> "image/png" to ".png"
            b.size > 12 && at(0, 'R') && at(1, 'I') && at(2, 'F') && at(3, 'F') &&
                at(8, 'W') && at(9, 'E') && at(10, 'B') && at(11, 'P') -> "image/webp" to ".webp"
            b.size > 4 && at(0, 'G') && at(1, 'I') && at(2, 'F') -> "image/gif" to ".gif"
            b.size > 4 && at(0, '%') && at(1, 'P') && at(2, 'D') -> "application/pdf" to ".pdf"
            else -> "" to ""
        }
    }

    private fun finishBlobDownload(mimeType: String, totalSize: Long) {
        try {
            closeBlobStream()
            val part = blobTempFile
            if (part == null || !part.exists() || part.length() == 0L) {
                part?.delete()
                runOnUiThread {
                    Toast.makeText(this@MainActivity, "Save failed: no data received", Toast.LENGTH_SHORT).show()
                }
                return
            }
            val ext = when {
                blobDetectedExt.isNotEmpty() -> blobDetectedExt
                mimeType.contains("video/webm") -> ".webm"
                mimeType.contains("video/mp4") || mimeType.contains("video/quicktime") -> ".mp4"
                mimeType.contains("image/png") -> ".png"
                mimeType.contains("image/jpeg") || mimeType.contains("image/jpg") -> ".jpg"
                mimeType.contains("image/webp") -> ".webp"
                mimeType.contains("application/pdf") -> ".pdf"
                mimeType.contains("audio/") -> ".m4a"
                else -> ".mp4"
            }
            val finalMime = blobDetectedMime.ifEmpty { mimeType.ifEmpty { "application/octet-stream" } }
            val fileName = part.nameWithoutExtension + ext
            if (publishToDownloads(part, fileName, finalMime)) {
                runOnUiThread {
                    Toast.makeText(this@MainActivity, "Saved to Downloads: $fileName", Toast.LENGTH_LONG).show()
                }
            } else {
                runOnUiThread {
                    Toast.makeText(this@MainActivity, "Save failed: storage permission denied", Toast.LENGTH_SHORT).show()
                }
            }
        } catch (e: Exception) {
            runOnUiThread {
                Toast.makeText(this@MainActivity, "Save failed: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        } finally {
            blobTempFile = null
            blobDetectedMime = ""
            blobDetectedExt = ""
            blobNextOffset = 0L
        }
    }

    private fun closeBlobStream() {
        try { blobOutputStream?.close() } catch (_: Exception) {}
        blobOutputStream = null
    }

    private fun publishToDownloads(part: File, fileName: String, mime: String): Boolean {
        try {
            val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            dir.mkdirs()
            val target = File(dir, fileName)
            if (part.parentFile?.absolutePath == dir.absolutePath) {
                if (part.renameTo(target)) {
                    MediaScannerConnection.scanFile(this, arrayOf(target.absolutePath), arrayOf(mime), null)
                    return true
                }
            } else {
                part.copyTo(target, overwrite = true)
                part.delete()
                MediaScannerConnection.scanFile(this, arrayOf(target.absolutePath), arrayOf(mime), null)
                return true
            }
        } catch (_: Exception) { }
        return if (Build.VERSION.SDK_INT >= 29) {
            try {
                val uri = contentResolver.insert(
                    if (mime.startsWith("image/")) MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                    else MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                    ContentValues().apply {
                        put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                        put(MediaStore.MediaColumns.MIME_TYPE, mime.ifEmpty { "application/octet-stream" })
                        put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                    }
                ) ?: return false
                contentResolver.openOutputStream(uri)?.use { out ->
                    part.inputStream().use { it.copyTo(out) }
                } ?: return false
                part.delete()
                true
            } catch (_: Exception) {
                false
            }
        } else {
            false
        }
    }

    private fun saveBytesViaMediaStore(data: ByteArray, fileName: String, mime: String) {
        if (Build.VERSION.SDK_INT < 29) throw RuntimeException("storage permission denied")
        val uri = contentResolver.insert(
            if (mime.startsWith("image/")) MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            else MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, mime.ifEmpty { "application/octet-stream" })
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            }
        ) ?: throw RuntimeException("cannot create download entry")
        contentResolver.openOutputStream(uri)?.use { it.write(data) }
            ?: throw RuntimeException("cannot open download stream")
    }

    private fun saveRawBase64(dataUri: String, mimeTypeHint: String) {
        Thread {
            try {
                val commaIndex = dataUri.indexOf(",")
                if (commaIndex != -1) {
                    val header = dataUri.substring(0, commaIndex)
                    val rawBase64 = dataUri.substring(commaIndex + 1)
                    val bytes = Base64.decode(rawBase64, Base64.DEFAULT)

                    val sniffed = sniffFileType(bytes)
                    val extension = when {
                        sniffed.second.isNotEmpty() -> sniffed.second
                        header.contains("video/mp4") || mimeTypeHint.contains("video/mp4") -> ".mp4"
                        header.contains("video/webm") || mimeTypeHint.contains("video/webm") -> ".webm"
                        header.contains("image/png") -> ".png"
                        header.contains("image/jpeg") || header.contains("image/jpg") -> ".jpg"
                        header.contains("image/webp") -> ".webp"
                        header.contains("application/pdf") -> ".pdf"
                        else -> ".mp4"
                    }
                    val scanMime = sniffed.first.ifEmpty {
                        header.substringAfter("data:", "").substringBefore(';').ifEmpty { "application/octet-stream" }
                    }

                    val fileName = "Flow_Video_${System.currentTimeMillis()}$extension"
                    try {
                        val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                        downloadsDir.mkdirs()
                        val targetFile = File(downloadsDir, fileName)
                        targetFile.outputStream().use { it.write(bytes) }
                        MediaScannerConnection.scanFile(
                            this@MainActivity,
                            arrayOf(targetFile.absolutePath),
                            arrayOf(scanMime),
                            null
                        )
                    } catch (_: Exception) {
                        saveBytesViaMediaStore(bytes, fileName, scanMime)
                    }

                    runOnUiThread {
                        Toast.makeText(this@MainActivity, "Saved to Downloads: $fileName", Toast.LENGTH_LONG).show()
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(this@MainActivity, "Save failed: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }.start()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 102 && grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            pendingDownload?.let {
                executeDownload(it)
                pendingDownload = null
            }
        }
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

    override fun onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack()
        } else {
            super.onBackPressed()
        }
    }

    override fun onDestroy() {
        networkCallback?.let {
            try {
                getSystemService(ConnectivityManager::class.java)
                    ?.unregisterNetworkCallback(it)
            } catch (_: Exception) {
            }
        }
        networkCallback = null

        webView.removeCallbacks(blankCheckRunnable)
        closeBlobStream()
        try { popupCaptureWebView?.destroy() } catch (_: Exception) {}
        popupCaptureWebView = null

        webView.clearCache(true)
        webView.clearFormData()
        webView.clearHistory()
        webView.destroy()

        super.onDestroy()
    }
}