package com.example

import android.annotation.SuppressLint
import android.app.AlertDialog
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

    // --- Blank-page watchdog state (v12) ---
    private var watchdogAttempts = 0
    private var watchdogUrl: String? = null

    // --- Bounded session cache (v16) ---
    private val cacheBudgetBytes = 20L * 1024 * 1024 // 20 MB cap
    @Volatile private var cacheCheckBusy = false

    // --- New-window capture (issue 2) ---
    private var popupCaptureWebView: WebView? = null

    // --- Chunked blob download state (corrupted-download fix) ---
    private var blobOutputStream: FileOutputStream? = null
    private var blobTempFile: File? = null
    // v21: chunk integrity + real file type (see handleBlobChunk / sniffFileType)
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

    private val blockedDomains = hashSetOf(
        "doubleclick.net", "googlesyndication.com", "google-analytics.com",
        "adservice.google.com", "facebook.net", "scorecardresearch.com",
        "criteo.com", "taboola.com", "outbrain.com", "amazon-adsystem.com",
        // v15: major ad exchanges / DSPs / verification / analytics — every
        // blocked resource is one the renderer never has to download, decode
        // or hold in memory.
        "adnxs.com", "adform.net", "adsrvr.org", "pubmatic.com",
        "rubiconproject.com", "openx.net", "smartadserver.com",
        "moatads.com", "adsafeprotected.com", "quantserve.com",
        "hotjar.com", "clarity.ms", "chartbeat.com", "mixpanel.com",
        "amplitude.com"
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

        // v22: the old blob JS failed SILENTLY (only console.error) — the
        // user saw "Downloading..." but no file ever appeared. Now the page
        // reports the real reason, and we show it.
        @JavascriptInterface
        fun blobFailed(message: String) {
            runOnUiThread {
                Toast.makeText(this@MainActivity, "Save failed: $message", Toast.LENGTH_LONG).show()
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

        // Issue 2: allow new-window navigations (Google SERP tabs such as
        // "AI Mode" use target=_blank / window.open). Without multiple-window
        // support the request is silently swallowed and the tap does nothing.
        s.setSupportMultipleWindows(true)
        s.javaScriptCanOpenWindowsAutomatically = true

        defaultUserAgent = s.userAgentString

        s.offscreenPreRaster = false
        s.mediaPlaybackRequiresUserGesture = true
        // Session HTTP cache (user-approved): the cache lives on DISK, so the
        // RAM footprint is only a small in-memory index (well under 1 MB) —
        // the renderer, the trim-purge and the exit-wipe all stay unchanged.
        // Why: Google's mobile AI Mode ships a heavy JS bundle; with
        // LOAD_NO_CACHE every entry re-downloaded it (the 2-5s black page).
        // With LOAD_DEFAULT, the first entry of a session pays the download
        // once and every following entry loads it from disk. The exit-wipe
        // in onDestroy still erases everything when the browser closes.
        s.cacheMode = WebSettings.LOAD_DEFAULT

        val cookieManager = CookieManager.getInstance()
        cookieManager.setAcceptCookie(true)
        cookieManager.setAcceptThirdPartyCookies(webView, false)

        webView.addJavascriptInterface(BlobDownloadBridge(), "AndroidBlobBridge")

        // v23: Gemini-style sites block their own download buttons (CSP /
        // cross-frame blobs — the v22 chain reports "site blocked the
        // download" there). But the image is already ON SCREEN with a plain
        // URL: a long-press on any image now offers to save it directly —
        // we fetch the bytes ourselves, outside the page's control.
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
                        // v22: some sites (e.g. gemini.google.com) trigger
                        // downloads as window.open("blob:...") popups — the
                        // old capture code swallowed those silently (no file,
                        // no error). Forward them to the main view's download
                        // pipeline: the blob is same-origin in the main page,
                        // so the injected fetch can read it.
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
                        return true // the temp view never actually loads real content
                    }

                    // Fallback capture: POST navigations can bypass
                    // shouldOverrideUrlLoading on some WebView builds.
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

            // Geolocation: the app holds NO location permissions (removed at
            // the user's request) — but we still ANSWER the page instantly
            // with a denial. The DEFAULT implementation never answers at all,
            // which hangs any site that awaits navigator.geolocation forever.
            // An instant "denied" lets the page's promise settle and its
            // scripts continue normally.
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
                // Blank-page watchdog (v12): arm the recovery check.
                scheduleBlankWatchdog(url)

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

            // Re-arm the blank-page watchdog AFTER the page reports finished —
            // the blank hang can happen after a successful load event (the page
            // paints only its loading spinner and then never renders).
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                scheduleBlankWatchdog(url)
                // Bound the on-disk cache footprint (v16): measure quietly on
                // a background thread after every page load; flush if too big.
                checkCacheBudget()
                // Proactive render kick: Google pages (especially AI Mode) can
                // freeze right after loading — a brief pause/resume ~1.5s in
                // prevents the user having to press Recents to unfreeze them.
                if (url != null && url.contains("google.com/search")) {
                    webView.postDelayed({ if (webView.url == url) kickRenderer() }, 1_500L)
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

    // AI Overview → AI Mode popups carry a heavy "continuation" URL — session
    // tokens tying the new view to the Overview conversation. Google's backend
    // STALLS the first answer-stream for such continuation URLs (the 2-5 minute
    // black page), while the CLEAN search URL (?q=...&udm=50) loads fast
    // (user-verified: the AI Mode tab bar and direct google.com/ai are always
    // fast, and both of those also go through the same popup capture). So when
    // a popup targets AI Mode, rebuild the clean URL for the same query —
    // same answer, no stall, and back-navigation still works. Non-AI-Mode
    // popups are captured untouched.
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

    // ---- Blank-page watchdog (v12) ----
    // User-recorded bug: a Google search page downloaded everything but never
    // rendered — blank screen with Google's small spinner for minutes, network
    // idle (0.00 KB/s). The exact trigger varies (stale cache entry from the
    // session cache, a stalled in-page view transition, a Google flake), so
    // instead of guessing: RECOVER. 12s after a google.com/search page starts
    // (re-armed after it finishes), if the view has no scrollable content at
    // all — a real results page ALWAYS scrolls — reload once with the HTTP
    // cache bypassed. Max 2 attempts per URL, so it can never loop.
    private val blankCheckRunnable = object : Runnable {
        override fun run() {
            val url = webView.url ?: return
            if (url.contains("google.com/search") &&
                !webView.canScrollVertically(1) &&
                !webView.canScrollVertically(-1) &&
                watchdogAttempts < 2
            ) {
                watchdogAttempts++
                // Step 1: render-kick — the user-verified cure (pausing and
                // resuming the app unfreezes the page without reloading it).
                kickRenderer()
                // Step 2: if STILL blank 6s later, reload with the network
                // cache bypassed.
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

    // Reproduces the user's verified cure programmatically: pause the WebView
    // for ~120ms and resume it. This is the standard workaround for the Chromium
    // WebView compositor stall where a loaded page stops repainting entirely
    // (frozen spinner, no animation, network idle) until the app is backgrounded
    // and foregrounded (pressing Recents and returning).
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

    // ---- Bounded session cache (v16, fixed v20) ----
    // The LOAD_DEFAULT session cache (v10) makes repeat AI Mode / site loads
    // fast, but it collects data on disk with no system-side size limit —
    // the user watched it grow in App Info. So: after each page load, measure
    // our full on-disk footprint on a background thread; above 20 MB, flush.
    // v20 fix: clearCache() alone only purges the HTTP cache — the heavy
    // stuff under app_webview (Service Worker CacheStorage, GPUCache,
    // Code Cache) survived it, so the budget kept tripping while the
    // footprint never shrank. The flush now also runs the same deep clean
    // the exit-wipe uses. Cookies/login/history are untouched; the
    // exit-wipe still erases everything on close.
    private fun checkCacheBudget() {
        if (cacheCheckBusy) return
        cacheCheckBusy = true
        Thread {
            try {
                val dirs = mutableListOf<File>(cacheDir, codeCacheDir)
                externalCacheDir?.let { dirs.add(it) }
                val webviewDir = File(applicationInfo.dataDir, "app_webview")
                if (webviewDir.isDirectory) dirs.add(webviewDir)
                var total = 0L
                for (d in dirs) total += dirSize(d)
                if (total > cacheBudgetBytes) {
                    runOnUiThread {
                        try { webView.clearCache(true) } catch (_: Exception) {}
                    }
                    // v20: also deep-clean the heavy on-disk folders. We are
                    // already on a background thread, so file deletion here
                    // never blocks the UI. Cookies, logins and history are
                    // not in these folders — they stay.
                    try {
                        cleanDir(cacheDir)
                        cleanDir(codeCacheDir)
                        externalCacheDir?.let { cleanDir(it) }
                        cleanChromiumCache(webviewDir)
                    } catch (_: Exception) {}
                }
            } catch (_: Exception) {
            } finally {
                cacheCheckBusy = false
            }
        }.start()
    }

    private fun dirSize(dir: File?): Long {
        if (dir == null || !dir.exists()) return 0L
        var size = 0L
        dir.listFiles()?.forEach {
            size += if (it.isDirectory) dirSize(it) else it.length()
        }
        return size
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
            // v22: three-stage retrieval. fetch() is blocked by the page's
            // own security policy (CSP) on some sites — e.g. gemini.google.com
            // — where the OLD code failed silently: the toast said
            // "Downloading..." but no chunk ever arrived, so no file appeared.
            // Chain: fetch -> XHR -> <img>+canvas (images); a real failure is
            // reported back to Java so the user finally SEES the reason.
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
                    function tryCanvas() {
                        var img = new Image();
                        img.onload = function() {
                            try {
                                var c = document.createElement('canvas');
                                c.width = img.naturalWidth;
                                c.height = img.naturalHeight;
                                c.getContext('2d').drawImage(img, 0, 0);
                                c.toBlob(function(b) {
                                    if (b) { streamBlob(b); } else { fail('image re-encode failed'); }
                                }, 'image/png');
                            } catch (e) { fail(e); }
                        };
                        img.onerror = function() { fail('site blocked the download'); };
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

    // v23: "Save image" dialog for the long-press menu. Routes the image
    // URL through the right saver: data: URIs, blob: URLs (in-page path)
    // and everything else via our own direct downloader with cookies.
    private fun showSaveImageDialog(imageUrl: String) {
        AlertDialog.Builder(this)
            .setTitle("Save image")
            .setMessage("Download this image to Downloads?")
            .setPositiveButton("Download") { _, _ ->
                when {
                    imageUrl.startsWith("data:") -> saveRawBase64(imageUrl, "")
                    imageUrl.startsWith("blob:") ->
                        executeDownload(DownloadTask(imageUrl, if (isDesktopMode) desktopUserAgent else defaultUserAgent, "", ""))
                    else -> downloadImageDirect(imageUrl)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // v23: direct image downloader — does NOT depend on the page at all, so
    // no site policy can block it. Fetches with our UA + the page's cookies,
    // sniffs the real type from the bytes, saves to Downloads and indexes it
    // with Android's media library (so Google Photos shows it correctly).
    private fun downloadImageDirect(url: String) {
        Toast.makeText(this, "Downloading...", Toast.LENGTH_SHORT).show()
        Thread {
            var conn: HttpURLConnection? = null
            try {
                conn = (URL(url).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 15_000
                    readTimeout = 15_000
                    instanceFollowRedirects = true
                    setRequestProperty("User-Agent", if (isDesktopMode) desktopUserAgent else defaultUserAgent)
                    val cookies = CookieManager.getInstance().getCookie(url)
                    if (!cookies.isNullOrEmpty()) setRequestProperty("Cookie", cookies)
                    setRequestProperty("Referer", webView.url ?: url)
                }
                if (conn.responseCode !in 200..299) {
                    throw RuntimeException("HTTP " + conn.responseCode)
                }
                val bytes = conn.inputStream.use { it.readBytes() }
                val sniffed = sniffFileType(bytes)
                val ext = sniffed.second.ifEmpty { ".jpg" }
                val fileName = "Pulse_Image_${System.currentTimeMillis()}$ext"
                val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                downloadsDir.mkdirs()
                val targetFile = File(downloadsDir, fileName)
                targetFile.outputStream().use { it.write(bytes) }
                MediaScannerConnection.scanFile(
                    this,
                    arrayOf(targetFile.absolutePath),
                    arrayOf(sniffed.first.ifEmpty { "image/jpeg" }),
                    null
                )
                runOnUiThread {
                    Toast.makeText(this@MainActivity, "Saved to Downloads: $fileName", Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(this@MainActivity, "Save failed: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            } finally {
                conn?.disconnect()
            }
        }.start()
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
            val charsetName = Regex("charset\\s*=\\s*[\\\"']?\\s*([A-Za-z0-9_\\\\-]+)")
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
            val bytes = Base64.decode(base64Chunk, Base64.DEFAULT)
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
                // v21: sniff the REAL type from the first chunk's magic bytes.
                // The page's blob.type is often empty or wrong — that gave
                // .mp4 names to webm videos (unplayable everywhere) and
                // indexed images with a wrong mime (hidden in Google Photos).
                val sniffed = sniffFileType(bytes)
                blobDetectedMime = sniffed.first
                blobDetectedExt = sniffed.second
                blobNextOffset = bytes.size.toLong()
            } else {
                // v21: chunk integrity. A dropped or misordered chunk used
                // to append silently misaligned bytes — a full-size but
                // CORRUPT file no player could open. Abort loudly instead.
                if (blobOutputStream == null) return // download already finished/aborted
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

    // v21: identify the real file type from magic bytes (independent of
    // whatever mime the page claimed). Returns (mime, extension).
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
            // v21: prefer the type sniffed from the actual bytes over the
            // page-supplied mime (often empty or plain wrong).
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
                arrayOf(blobDetectedMime.ifEmpty { mimeType.ifEmpty { "application/octet-stream" } }),
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
            blobDetectedMime = ""
            blobDetectedExt = ""
            blobNextOffset = 0L
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
                    // v21: scan with the CORRECT mime — the old code always
                    // told Android "video/mp4", so images saved from data:
                    // URLs never showed up in Google Photos.
                    val scanMime = sniffed.first.ifEmpty {
                        header.substringAfter("data:", "").substringBefore(';').ifEmpty { "application/octet-stream" }
                    }

                    val fileName = "Flow_Video_${System.currentTimeMillis()}$extension"
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
        // Purge from RUNNING_MODERATE up. UI_HIDDEN (going to background) is
        // deliberately EXCLUDED — flushing the HTTP cache there would undo
        // the session cache that makes repeat AI Mode loads fast.
        if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE &&
            level <= ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL
        ) {
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

        webView.removeCallbacks(blankCheckRunnable)
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
