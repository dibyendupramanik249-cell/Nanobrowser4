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
import android.os.Message
import android.os.Environment
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
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.Charset

class MainActivity : AppCompatActivity(), ComponentCallbacks2 {

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

    // --- Offline auto-recovery state (issue 3) ---
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var mainFrameFailedOffline = false

    // --- New-window capture (issue 2) ---
    private var popupCaptureWebView: WebView? = null

    // --- Chunked blob download state (corrupted-download fix) ---
    private var blobOutputStream: FileOutputStream? = null
    private var blobTempFile: File? = null

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

    private val blockedDomains = hashSetOf(
        "doubleclick.net", "googlesyndication.com", "google-analytics.com",
        "adservice.google.com", "facebook.net", "scorecardresearch.com",
        "criteo.com", "taboola.com", "outbrain.com", "amazon-adsystem.com"
    )

    // Bridge for CHUNKED blob → file streaming. The old single-shot base64
    // transfer corrupted large files (multi-MB MP4 renders from Google Flow)
    // because of string-size limits on the JS→Java bridge.
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

        // Issue 2: allow new-window navigations (Google SERP tabs such as
        // "AI Mode" use target=_blank / window.open). Without multiple-window
        // support the request is silently swallowed and the tap does nothing.
        s.setSupportMultipleWindows(true)
        s.javaScriptCanOpenWindowsAutomatically = true

        defaultUserAgent = s.userAgentString

        s.offscreenPreRaster = false
        s.mediaPlaybackRequiresUserGesture = true
        s.cacheMode = WebSettings.LOAD_NO_CACHE

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

            // --- Visible diagnostics (kept for real page errors) ---
            // Page console errors are surfaced as Toasts so we can see WHY a
            // button does nothing without needing logcat. Ad-blocker noise
            // (CORS failures on blocked domains, empty-response codes) is
            // filtered out — those are EXPECTED, not bugs.
            override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
                val cm = consoleMessage ?: return super.onConsoleMessage(consoleMessage)
                val msg = cm.message() ?: ""
                if (cm.messageLevel() == ConsoleMessage.MessageLevel.ERROR &&
                    !msg.contains("ERR_BLOCKED_BY_CLIENT") &&
                    !msg.contains("ERR_EMPTY_RESPONSE") &&
                    !msg.contains("ERR_INVALID_RESPONSE") &&
                    !msg.contains("Access to XMLHttpRequest") &&
                    !blockedDomains.any { msg.contains(it, ignoreCase = true) }
                ) {
                    val short = if (msg.length > 90) msg.substring(0, 90) + "…" else msg
                    Toast.makeText(applicationContext, "JS error: $short", Toast.LENGTH_LONG).show()
                }
                return super.onConsoleMessage(consoleMessage)
            }

            // Issue 2: Google's SERP tabs (e.g. "AI Mode") open via
            // target=_blank / window.open. Handing THIS WebView back through
            // the transport renders a blank/black page on modern WebView
            // builds, so instead we give WebView a throwaway hidden WebView
            // whose only job is to capture the target URL, then load that URL
            // into the main WebView. The temp view is destroyed right away,
            // so the memory cost is a few MB for a fraction of a second.
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
                // FIX: a fresh WebView has JavaScript DISABLED by default — popup
                // flows that run scripts inside the popup before/while navigating
                // (suspected for AI Overview → "AI Mode") died silently. Give the
                // popup the same engine settings as the main view.
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
                        // CRITICAL: window.open flows (e.g. Google's AI Overview
                        // → "AI Mode") create the popup with about:blank FIRST and
                        // set its location afterwards. Blocking the blank load
                        // kills the whole flow — so let it through.
                        if (target == "about:blank" || target.startsWith("data:")) {
                            return false
                        }
                        if (!captured && (target.startsWith("http://") || target.startsWith("https://"))) {
                            captured = true
                            view.loadUrl(target)
                            v?.post {
                                try { v.destroy() } catch (_: Exception) {}
                                if (popupCaptureWebView === v) popupCaptureWebView = null
                            }
                        }
                        return true // the temp view never actually loads real content
                    }

                    // Fallback capture: POST navigations can bypass
                    // shouldOverrideUrlLoading on some WebView builds.
                    override fun onPageStarted(v: WebView?, url: String?, favicon: Bitmap?) {
                        super.onPageStarted(v, url, favicon)
                        if (!captured && url != null &&
                            (url.startsWith("http://") || url.startsWith("https://"))
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

                // If nothing was ever captured (dead popup), free the popup
                // slot and the memory after 15s — Chromium refuses to create
                // a new popup while one is still pending.
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

            // Location integration (AI Overview fix candidate + general browser
            // health): the DEFAULT onGeolocationPermissionsShowPrompt never
            // answers the page at all, so any site awaiting navigator.geolocation
            // hangs forever — including JS that runs before opening a new view
            // (e.g. Google's AI Overview → "AI Mode" continuation). ALWAYS
            // answer: grant if the app holds the runtime location permission,
            // deny cleanly otherwise. Denying instantly is fine — the page's
            // promise settles and its script continues either way.
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

                // NOTE: the camera is deliberately NOT injected into the chooser
                // via EXTRA_INITIAL_INTENTS anymore. Mixing ACTION_IMAGE_CAPTURE
                // into a chooser's initial intents breaks the returned result on
                // many Android 11+ devices — the picked file never reaches the
                // page and the site re-shows its upload menu. The camera is now
                // used only when the site explicitly requests capture
                // (<input type="file" capture>).
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
                // A new navigation is under way — reset the offline-failure flag.
                mainFrameFailedOffline = false
                urlBar.setText(url)

                // Desktop-mode zoom fix: WebView CARRIES the previous page's
                // pinch-zoom level into the next page load (documented WebView
                // behaviour — no public reset API). Toggling overview mode off
                // and back on + initialScale(0) is the known way to force each
                // new desktop page to re-fit to the screen instead of loading
                // "zoomed in". Mobile pages reset naturally via their viewport
                // meta, so this only runs in desktop mode.
                if (isDesktopMode) {
                    view?.settings?.loadWithOverviewMode = false
                    view?.settings?.loadWithOverviewMode = true
                    view?.setInitialScale(0)
                }
            }

            // Issue 1: the old code injected a viewport meta with width=1100
            // AFTER the page had already laid out and WebView had computed its
            // initial fit-zoom. Changing the viewport post-layout never
            // re-triggers the overview fit, so the page rendered wider than
            // the screen and the user had to pinch-zoom out manually.
            // Desktop mode is now driven purely by the desktop User-Agent +
            // useWideViewPort + loadWithOverviewMode, which lets WebView size
            // and fit the page correctly at load time.

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
                            // Never let a web page direct-launch a specific app
                            // component through an intent:// link.
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
                    // Issue 2: intent:// links carry the real https URL in their
                    // data — recover it so navigations (e.g. Google AI Mode)
                    // never die silently when no app can handle the intent.
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

            override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                val url = request?.url ?: return null
                val host = url.host ?: ""

                if (blockedDomains.any { host.contains(it) }) {
                    // Empty 200 + permissive CORS: pages XHR these ad endpoints
                    // cross-origin. A CORS-less empty response makes every such
                    // XHR FAIL — console errors, retry loops, slow page init
                    // (seen on mobile AI Mode). With ACAO:* the request
                    // "succeeds" instantly with an empty body and the page's
                    // scripts move on without stalling.
                    return WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0))).apply {
                        responseHeaders = mapOf("Access-Control-Allow-Origin" to "*")
                    }
                }

                // Desktop mode: responsive sites choose mobile/desktop layout by
                // VIEWPORT WIDTH, not by User-Agent — so the desktop UA alone
                // leaves them looking mobile. Rewrite the viewport meta inside
                // the HTML BEFORE the renderer lays the page out (the old
                // post-layout JS injection is what caused the zoom-in bug).
                if (isDesktopMode && request.isForMainFrame &&
                    request.method.equals("GET", ignoreCase = true) &&
                    (url.scheme == "https" || url.scheme == "http")
                ) {
                    val rewritten = forceDesktopViewport(url.toString(), request.requestHeaders)
                    if (rewritten != null) return rewritten
                    // fall through — on any failure WebView loads it normally
                }

                return super.shouldInterceptRequest(view, request)
            }

            // Issue 3: remember when the main document itself failed with a
            // connectivity-type error, so the network callback can retry once
            // data comes back.
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

    // Issue 3: auto-recover from "webpage not available". Register a single
    // system network callback (no polling, no threads of our own — effectively
    // zero RAM). When a network becomes available and the last main-frame load
    // failed with a connectivity error, reload once.
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

        // Handle client-side Blob URLs (Google Flow, web video renderers).
        // Chunked transfer: the old single-shot base64 string corrupted
        // large files (JS→Java bridge string-size limit).
        if (url.startsWith("blob:")) {
            Toast.makeText(this, "Downloading...", Toast.LENGTH_SHORT).show()
            val js = """
                (function() {
                    fetch('$url')
                    .then(function(response) { return response.blob(); })
                    .then(function(blob) {
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
                                    window.AndroidBlobBridge.processBlobChunk(b64, mime, offset, size);
                                    offset += CHUNK;
                                    if (offset < size) {
                                        setTimeout(next, 0);
                                    } else {
                                        window.AndroidBlobBridge.finishBlob(mime, size);
                                    }
                                }
                            };
                            reader.readAsDataURL(slice);
                        }
                        if (size > 0) { next(); } else { window.AndroidBlobBridge.finishBlob(mime, 0); }
                    })
                    .catch(function(err) { console.error(err); });
                })();
            """.trimIndent()
            webView.evaluateJavascript(js, null)
            return
        }

        // Handle inline Data URIs
        if (url.startsWith("data:")) {
            saveRawBase64(url, task.mimeType)
            return
        }

        // Standard HTTP / HTTPS Downloads via DownloadManager
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

    // ---- Desktop-mode site compatibility (responsive-site fix) ----
    // Fetches the main-frame HTML ourselves, forces the viewport meta to
    // width=1100, and serves the modified document — all BEFORE the renderer
    // lays the page out, so the desktop layout renders with a correct fit-zoom
    // (unlike the old post-layout injection). Only the main document is
    // buffered (typically a few hundred KB, transient). Any failure returns
    // null, letting WebView load the page untouched.
    private fun forceDesktopViewport(url: String, headers: Map<String, String>): WebResourceResponse? {
        var conn: HttpURLConnection? = null
        try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 15_000
                instanceFollowRedirects = true
                for ((k, v) in headers) {
                    if (k.equals("Accept-Encoding", ignoreCase = true)) continue
                    try { setRequestProperty(k, v) } catch (_: Exception) {}
                }
                setRequestProperty("User-Agent", desktopUserAgent)
                setRequestProperty("Accept-Encoding", "identity") // plain text — no gzip juggling
                val cookies = CookieManager.getInstance().getCookie(url)
                if (!cookies.isNullOrEmpty()) setRequestProperty("Cookie", cookies)
            }
            if (conn.responseCode !in 200..299) return null

            // If the server ignored our "identity" request and compressed the
            // body anyway, bail out — serving compressed bytes as HTML would
            // corrupt the page.
            val encoding = conn.contentEncoding
            if (encoding != null && !encoding.equals("identity", ignoreCase = true)) return null

            // CRITICAL: WebResourceResponse needs the BARE mime type. Passing
            // the full header value ("text/html; charset=UTF-8") makes WebView
            // fail to recognise the document as HTML — it then renders the raw
            // source code as plain text. Strip everything after the ';'.
            val rawType = conn.contentType ?: "text/html"
            val mime = rawType.substringBefore(';').trim().ifEmpty { "text/html" }
            if (!mime.contains("html", ignoreCase = true)) return null

            val bytes = conn.inputStream.use { it.readBytes() }
            // Very large documents: serve as downloaded, skip the rewrite.
            if (bytes.size > 5_000_000) {
                return WebResourceResponse(mime, "utf-8", ByteArrayInputStream(bytes))
            }

            // Sniff the charset from the header AND the HTML head (the meta
            // charset tag) — decoding with the wrong charset would corrupt
            // every non-ASCII character in the page.
            val head = String(bytes.copyOfRange(0, minOf(2048, bytes.size)), charset("ISO-8859-1"))
            val charsetName = Regex("charset\\s*=\\s*[\\\"']?\\s*([A-Za-z0-9_\\-]+)")
                .find(rawType + " " + head)?.groupValues?.get(1)
                ?.takeIf { runCatching { Charset.isSupported(it) }.getOrDefault(false) } ?: "utf-8"

            val finalBytes: ByteArray = try {
                var html = String(bytes, charset(charsetName))
                val metaTag = Regex(
                    """<meta[^>]*name\s*=\s*("viewport"|'viewport')[^>]*>""",
                    RegexOption.IGNORE_CASE
                )
                val contentAttr = Regex(
                    """content\s*=\s*("[^"]*"|'[^']*')""",
                    RegexOption.IGNORE_CASE
                )
                var rewrote = false
                html = metaTag.replace(html) { m ->
                    val tag = m.value
                    val newTag = if (contentAttr.containsMatchIn(tag)) {
                        contentAttr.replace(tag, "content=\"width=1100\"")
                    } else {
                        tag.replaceFirst(">", " content=\"width=1100\">")
                    }
                    if (newTag != tag) rewrote = true
                    newTag
                }
                if (rewrote) html.toByteArray(charset(charsetName)) else bytes
            } catch (_: Exception) {
                bytes // any charset trouble → serve untouched bytes
            }

            return WebResourceResponse(mime, charsetName, ByteArrayInputStream(finalBytes))
        } catch (_: Exception) {
            return null
        } finally {
            conn?.disconnect()
        }
    }

    // ---- Chunked blob download (corrupted-file fix) ----
    // Chunks arrive sequentially from the page's single JS thread, so plain
    // append-writes are safe. Peak memory stays flat (one 256KB chunk at a
    // time) instead of holding the whole file in RAM as base64 + bytes.
    private fun handleBlobChunk(base64Chunk: String, mimeType: String, offset: Long, totalSize: Long) {
        try {
            if (offset == 0L) {
                closeBlobStream()
                val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                downloadsDir.mkdirs()
                // Clean up stale .part files abandoned by interrupted downloads
                try {
                    downloadsDir.listFiles { f ->
                        f.name.endsWith(".part") && f.lastModified() < System.currentTimeMillis() - 3_600_000L
                    }?.forEach { it.delete() }
                } catch (_: Exception) {}
                val f = File(downloadsDir, "Flow_Video_${System.currentTimeMillis()}.part")
                blobTempFile = f
                blobOutputStream = f.outputStream()
            }
            val bytes = Base64.decode(base64Chunk, Base64.DEFAULT)
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
                mimeType.contains("video/webm") -> ".webm"
                mimeType.contains("video/mp4") || mimeType.contains("video/quicktime") -> ".mp4"
                mimeType.contains("image/png") -> ".png"
                mimeType.contains("image/jpeg") || mimeType.contains("image/jpg") -> ".jpg"
                mimeType.contains("image/webp") -> ".webp"
                mimeType.contains("application/pdf") -> ".pdf"
                mimeType.contains("audio/") -> ".m4a"
                else -> ".mp4"
            }
            val finalFile = File(part.parentFile, part.nameWithoutExtension + ext)
            if (!part.renameTo(finalFile)) {
                runOnUiThread {
                    Toast.makeText(this@MainActivity, "Saved to Downloads: ${part.name}", Toast.LENGTH_LONG).show()
                }
                return
            }
            MediaScannerConnection.scanFile(
                this,
                arrayOf(finalFile.absolutePath),
                arrayOf(mimeType.ifEmpty { "video/mp4" }),
                null
            )
            runOnUiThread {
                Toast.makeText(this@MainActivity, "Saved to Downloads: ${finalFile.name}", Toast.LENGTH_LONG).show()
            }
        } catch (e: Exception) {
            runOnUiThread {
                Toast.makeText(this@MainActivity, "Save failed: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        } finally {
            blobTempFile = null
        }
    }

    private fun closeBlobStream() {
        try { blobOutputStream?.close() } catch (_: Exception) {}
        blobOutputStream = null
    }

    private fun saveRawBase64(dataUri: String, mimeTypeHint: String) {
        Thread {
            try {
                val commaIndex = dataUri.indexOf(",")
                if (commaIndex != -1) {
                    val header = dataUri.substring(0, commaIndex)
                    val rawBase64 = dataUri.substring(commaIndex + 1)
                    val bytes = Base64.decode(rawBase64, Base64.DEFAULT)

                    val extension = when {
                        header.contains("video/mp4") || mimeTypeHint.contains("video/mp4") -> ".mp4"
                        header.contains("video/webm") || mimeTypeHint.contains("video/webm") -> ".webm"
                        header.contains("image/png") -> ".png"
                        header.contains("image/jpeg") || header.contains("image/jpg") -> ".jpg"
                        header.contains("image/webp") -> ".webp"
                        header.contains("application/pdf") -> ".pdf"
                        else -> ".mp4"
                    }

                    val fileName = "Flow_Video_${System.currentTimeMillis()}$extension"
                    val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                    downloadsDir.mkdirs()
                    val targetFile = File(downloadsDir, fileName)

                    targetFile.outputStream().use { it.write(bytes) }

                    MediaScannerConnection.scanFile(
                        this@MainActivity,
                        arrayOf(targetFile.absolutePath),
                        arrayOf("video/mp4"),
                        null
                    )

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

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) {
            webView.clearCache(true)
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
            try {
                getSystemService(ConnectivityManager::class.java)
                    ?.unregisterNetworkCallback(it)
            } catch (_: Exception) {
            }
        }
        networkCallback = null

        closeBlobStream()
        try { popupCaptureWebView?.destroy() } catch (_: Exception) {}
        popupCaptureWebView = null

        webView.clearCache(true)
        webView.clearFormData()
        webView.clearHistory()
        webView.destroy()

        cleanDir(cacheDir)
        cleanDir(codeCacheDir)
        externalCacheDir?.let { cleanDir(it) }

        val webviewDir = File(applicationInfo.dataDir, "app_webview")
        if (webviewDir.exists()) {
            cleanChromiumCache(webviewDir)
        }

        super.onDestroy()
        Process.killProcess(Process.myPid())
    }

    private fun cleanDir(dir: File?) {
        if (dir != null && dir.isDirectory) {
            dir.listFiles()?.forEach { file ->
                if (file.isDirectory) cleanDir(file)
                file.delete()
            }
        }
    }

    private fun cleanChromiumCache(dir: File) {
        val targets = listOf("Cache", "Code Cache", "GPUCache", "Service Worker")
        dir.listFiles()?.forEach { file ->
            if (file.isDirectory) {
                if (targets.any { file.name.contains(it, ignoreCase = true) }) {
                    file.deleteRecursively()
                } else {
                    cleanChromiumCache(file)
                }
            }
        }
    }
}
