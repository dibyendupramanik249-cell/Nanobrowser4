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
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.MotionEvent
import android.view.inputmethod.InputMethodManager
import android.webkit.*
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.nio.charset.Charset

class MainActivity : AppCompatActivity(), ComponentCallbacks2 {

    private lateinit var rootLayout: LinearLayout
    private lateinit var topBar: LinearLayout
    private var chromeHidden = false
    private var lastStripTap = 0L
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
    private var navigationCounter = 0

    // --- New-window capture (issue 2) ---
    private var popupCaptureWebView: WebView? = null

    // v34: blob: URLs that already went through one full in-page retry.
    // Prevents a consent-dialog loop when the image simply cannot be saved.
    private val blobPageRetried = HashSet<String>()

    // --- Chunked blob download state (corrupted-download fix) ---
    private var blobOutputStream: FileOutputStream? = null
    private var blobTempFile: File? = null
    // v21: chunk integrity + real file type (see handleBlobChunk / sniffFileType)
    private var blobNextOffset = 0L
    private var blobDetectedMime = ""
    private var blobDetectedExt = ""
    @Volatile private var lastSavedTime = 0L
    @Volatile private var lastSavedName = ""

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
            finishBlobDownload(mimeType, totalSize, null)
        }

        @JavascriptInterface
        fun finishBlobWithName(mimeType: String, totalSize: Long, suggestedName: String?) {
            finishBlobDownload(mimeType, totalSize, suggestedName)
        }

        // v22: the old blob JS failed SILENTLY (only console.error) — the
        // user saw "Downloading..." but no file ever appeared. Now the page
        // reports the real reason, and we show it (suppressing if file was just saved).
        @JavascriptInterface
        fun blobFailed(message: String) {
            runOnUiThread {
                if (System.currentTimeMillis() - lastSavedTime < 8000L) {
                    return@runOnUiThread
                }
                val friendlyMsg = if (message.contains("block", ignoreCase = true) || message.contains("not found", ignoreCase = true)) {
                    "Download could not be completed"
                } else {
                    message
                }
                Toast.makeText(this@MainActivity, "Save failed: $friendlyMsg", Toast.LENGTH_SHORT).show()
            }
        }

        // v42: every in-app path failed (e.g. AI Studio files that reject our
        // direct downloader with 403) — hand the URL to the SYSTEM download
        // manager, a completely different network stack. No loop risk: this
        // never calls back into the direct downloader.
        @JavascriptInterface
        fun systemDownload(url: String, mimeType: String) {
            runOnUiThread {
                val ua = if (isDesktopMode) desktopUserAgent else defaultUserAgent
                val err = if (url.startsWith("http"))
                    enqueueSystemDownload(url, ua, "", mimeType, allowUnnamed = true)
                else "not a downloadable link"
                if (err == null) {
                    // enqueued — system shows progress + our toast
                } else {
                    Toast.makeText(this@MainActivity, "Save failed: $err", Toast.LENGTH_LONG).show()
                }
            }
        }

        // v26: the in-page image fetch was blocked (page policy/CORS) — fall
        // back to the direct downloader with browser-style headers. Must hop
        // to the main thread: WebView methods are main-thread-only.
        @JavascriptInterface
        fun pageImageFetchFailed(url: String) {
            runOnUiThread {
                // v34: blob: URLs must NEVER go to the native HTTP downloader —
                // Java's URLConnection can't speak the blob protocol (that
                // was the "unknown protocol: blob" error). Give the URL one
                // pass through the full in-page chain (registry -> fetch ->
                // XHR -> canvas); if that has already failed too, be honest.
                if (url.startsWith("blob:")) {
                    if (blobPageRetried.add(url)) {
                        executeDownload(
                            DownloadTask(
                                url,
                                if (isDesktopMode) desktopUserAgent else defaultUserAgent,
                                "",
                                ""
                            )
                        )
                    } else {
                        Toast.makeText(
                            this@MainActivity,
                            "Save failed: unable to save image",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                } else {
                    downloadMediaDirect(url, null)
                }
            }
        }

        // v35: direct bridge from DOM extraction to avoid canvas taint and download media directly
        @JavascriptInterface
        fun saveDirectMediaFromDom(imageUrl: String, mimeType: String?) {
            runOnUiThread {
                val safeMime = if (!mimeType.isNullOrEmpty()) mimeType else if (imageUrl.contains("video")) "video/mp4" else "image/jpeg"
                if (imageUrl.startsWith("blob:")) {
                    executeDownload(
                        DownloadTask(
                            imageUrl,
                            if (isDesktopMode) desktopUserAgent else defaultUserAgent,
                            "",
                            safeMime
                        )
                    )
                } else {
                    val task = DownloadTask(imageUrl, if (isDesktopMode) desktopUserAgent else defaultUserAgent, "", safeMime)
                    downloadMediaDirect(imageUrl, task)
                }
            }
        }

        // v28: ask before saving on-screen media
        @JavascriptInterface
        fun askSaveVisibleImage(imageUrl: String) {
            runOnUiThread {
                val isVideo = imageUrl.contains(".mp4") || imageUrl.contains(".webm") || imageUrl.contains("video")
                val label = if (isVideo) "video" else "image"
                AlertDialog.Builder(this@MainActivity)
                    .setTitle("Save $label")
                    .setMessage("Would you like to save the $label shown on screen?")
                    .setPositiveButton("Save $label") { _, _ ->
                        if (imageUrl.startsWith("blob:")) {
                            executeDownload(
                                DownloadTask(
                                    imageUrl,
                                    if (isDesktopMode) desktopUserAgent else defaultUserAgent,
                                    "",
                                    if (isVideo) "video/mp4" else "image/png"
                                )
                            )
                        } else {
                            val task = DownloadTask(
                                imageUrl,
                                if (isDesktopMode) desktopUserAgent else defaultUserAgent,
                                "",
                                if (isVideo) "video/mp4" else "image/png"
                            )
                            downloadMediaDirect(imageUrl, task)
                        }
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

        rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
        }

        setupWindowInsets()

        topBar = LinearLayout(this).apply {
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

            // v45: tap-to-edit fixed. The old listeners wiped the text on
            // EVERY touch — including touches meant to place the cursor — so
            // fixing a typo destroyed everything typed. Now the text only
            // clears once, when the bar GAINS focus (fresh start = ready for
            // a new URL), and further taps place the cursor normally.
            setOnFocusChangeListener { _, hasFocus ->
                if (hasFocus) setText("")
            }
            setOnTouchListener { v, e ->
                if (e.action == MotionEvent.ACTION_UP) {
                    try {
                        val imm = v.context.getSystemService(InputMethodManager::class.java)
                        imm?.showSoftInput(v, InputMethodManager.SHOW_IMPLICIT)
                    } catch (_: Exception) {}
                }
                false // never consume — cursor placement must keep working
            }

            // v45: long-press the URL bar -> Copy / Share / Paste-and-go.
            // All handled by Android's own clipboard and share-sheet —
            // zero extra RAM, nothing runs in the background.
            setOnLongClickListener {
                val current = try { webView.url ?: urlBar.text.toString() } catch (_: Exception) { urlBar.text.toString() }
                val options = arrayOf("Copy URL", "Share URL", "Paste and go")
                AlertDialog.Builder(this@MainActivity)
                    .setTitle("URL options")
                    .setItems(options) { _, which ->
                        when (which) {
                            0 -> {
                                try {
                                    val cm = getSystemService(android.content.ClipboardManager::class.java)
                                    cm?.setPrimaryClip(ClipData.newPlainText("URL", current))
                                    Toast.makeText(applicationContext, "URL copied", Toast.LENGTH_SHORT).show()
                                } catch (_: Exception) {}
                            }
                            1 -> {
                                try {
                                    val send = Intent(Intent.ACTION_SEND).apply {
                                        type = "text/plain"
                                        putExtra(Intent.EXTRA_TEXT, current)
                                    }
                                    startActivity(Intent.createChooser(send, "Share URL"))
                                } catch (_: Exception) {}
                            }
                            2 -> {
                                try {
                                    val cm = getSystemService(android.content.ClipboardManager::class.java)
                                    val clip = cm?.primaryClip?.getItemAt(0)?.text?.toString()?.trim() ?: ""
                                    if (clip.isNotEmpty()) {
                                        loadInput(clip)
                                        clearFocus()
                                    } else {
                                        Toast.makeText(applicationContext, "Clipboard is empty", Toast.LENGTH_SHORT).show()
                                    }
                                } catch (_: Exception) {}
                            }
                        }
                    }
                    .show()
                true
            }

            setOnEditorActionListener { _, actionId, event ->
                if (actionId == EditorInfo.IME_ACTION_GO ||
                    (event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)
                ) {
                    loadInput(text.toString())
                    clearFocus()
                    val imm = getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager
                    imm?.hideSoftInputFromWindow(windowToken, 0)
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
        registerDocumentStartHooks()
        registerNetworkRecovery()
        webView.loadUrl("https://www.google.com")
    }

    private fun setupWindowInsets() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        ViewCompat.setOnApplyWindowInsetsListener(rootLayout) { view, insets ->
            val systemBars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime()
            )
            view.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }
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

        // v32: strip the "; wv" WebView marker from the user-agent. Google
        // sign-in pages reject WebView UAs with 403 disallowed_useragent —
        // presenting the plain Chrome-style UA keeps logins working inside
        // the browser. (Standard practice — every third-party browser ships a
        // modified UA.) All download headers use the same string.
        defaultUserAgent = s.userAgentString.replace("; wv", "")
        s.userAgentString = defaultUserAgent

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
        cookieManager.setAcceptThirdPartyCookies(webView, true)

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
                val temp = WebView(view.context).apply {
                    // Hidden background interceptor doesn't need hardware acceleration or GPU rendernodes
                    setLayerType(View.LAYER_TYPE_SOFTWARE, null)
                }
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
                    override fun onRenderProcessGone(v: WebView?, detail: RenderProcessGoneDetail?): Boolean {
                        try { v?.destroy() } catch (_: Exception) {}
                        if (popupCaptureWebView === v) popupCaptureWebView = null
                        return true
                    }

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
                            val isMedia = target.contains("googleusercontent.com") ||
                                    target.contains("generativelanguage.googleapis.com") ||
                                    target.contains("storage.googleapis.com") ||
                                    target.contains("download=true") ||
                                    target.contains("=download") ||
                                    target.endsWith(".png") || target.endsWith(".jpg") || target.endsWith(".jpeg") ||
                                    target.endsWith(".webp") || target.endsWith(".mp4") || target.endsWith(".webm") || target.endsWith(".gif") || target.endsWith(".pdf") || target.endsWith(".zip") || target.endsWith(".apk") || target.endsWith(".mp3") || target.endsWith(".m4a") || target.endsWith(".wav") || target.endsWith(".ogg") || target.endsWith(".mov") || target.endsWith(".svg") || target.endsWith(".bmp") || target.endsWith(".txt") || target.endsWith(".rtf") || target.endsWith(".doc") || target.endsWith(".docx") || target.endsWith(".xls") || target.endsWith(".xlsx") || target.endsWith(".csv") || target.endsWith(".ppt") || target.endsWith(".pptx") || target.endsWith(".py") || target.endsWith(".kt") || target.endsWith(".xml") || target.endsWith(".bat") || target.endsWith(".exe") || target.endsWith(".jar")
                            captured = true
                            if (isMedia) {
                                val dlTask = DownloadTask(
                                    target,
                                    if (isDesktopMode) desktopUserAgent else defaultUserAgent,
                                    "",
                                    ""
                                )
                                view.post { executeDownload(dlTask) }
                            } else {
                                view.loadUrl(normalizeAiModeUrl(target))
                            }
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
                        v?.evaluateJavascript(getBlobInterceptionScript(), null)
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
                            val isMedia = url.contains("googleusercontent.com") ||
                                    url.contains("generativelanguage.googleapis.com") ||
                                    url.contains("storage.googleapis.com") ||
                                    url.contains("download=true") ||
                                    url.contains("=download") ||
                                    url.endsWith(".png") || url.endsWith(".jpg") || url.endsWith(".jpeg") ||
                                    url.endsWith(".webp") || url.endsWith(".mp4") || url.endsWith(".webm") || url.endsWith(".gif") || url.endsWith(".pdf") || url.endsWith(".zip") || url.endsWith(".apk") || url.endsWith(".mp3") || url.endsWith(".m4a") || url.endsWith(".wav") || url.endsWith(".ogg") || url.endsWith(".mov") || url.endsWith(".svg") || url.endsWith(".bmp") || url.endsWith(".txt") || url.endsWith(".rtf") || url.endsWith(".doc") || url.endsWith(".docx") || url.endsWith(".xls") || url.endsWith(".xlsx") || url.endsWith(".csv") || url.endsWith(".ppt") || url.endsWith(".pptx") || url.endsWith(".py") || url.endsWith(".kt") || url.endsWith(".xml") || url.endsWith(".bat") || url.endsWith(".exe") || url.endsWith(".jar")
                            captured = true
                            if (isMedia) {
                                val dlTask = DownloadTask(
                                    url,
                                    if (isDesktopMode) desktopUserAgent else defaultUserAgent,
                                    "",
                                    ""
                                )
                                view.post { executeDownload(dlTask) }
                            } else {
                                view.loadUrl(normalizeAiModeUrl(url))
                            }
                            v?.post {
                                try { v.destroy() } catch (_: Exception) {}
                                if (popupCaptureWebView === v) popupCaptureWebView = null
                            }
                        }
                    }
                }
                temp.setDownloadListener { dlUrl, dlUserAgent, contentDisposition, mimetype, _ ->
                    val dlTask = DownloadTask(dlUrl, dlUserAgent, contentDisposition, mimetype)
                    view.post { executeDownload(dlTask) }
                    temp.post {
                        try { temp.destroy() } catch (_: Exception) {}
                        if (popupCaptureWebView === temp) popupCaptureWebView = null
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

                val isSaveMode = fileChooserParams?.mode == WebChromeClient.FileChooserParams.MODE_SAVE
                val contentIntent = if (isSaveMode) {
                    fileChooserParams?.createIntent() ?: Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                        addCategory(Intent.CATEGORY_OPENABLE)
                        type = fileChooserParams?.acceptTypes?.firstOrNull()?.takeIf { it.isNotEmpty() } ?: "*/*"
                        val defaultFilename = fileChooserParams?.filenameHint?.takeIf { !it.isNullOrEmpty() } ?: "project.zip"
                        putExtra(Intent.EXTRA_TITLE, defaultFilename)
                    }
                } else {
                    fileChooserParams?.createIntent() ?: Intent(Intent.ACTION_GET_CONTENT).apply {
                        type = "*/*"
                        addCategory(Intent.CATEGORY_OPENABLE)
                    }
                }

                // NOTE: the camera is deliberately NOT injected into the chooser
                // via EXTRA_INITIAL_INTENTS anymore. Mixing ACTION_IMAGE_CAPTURE
                // into a chooser's initial intents breaks the returned result on
                // many Android 11+ devices — the picked file never reaches the
                // page and the site re-shows its upload menu. The camera is now
                // used only when the site explicitly requests capture
                // (<input type="file" capture>).
                var launchIntent = if (isSaveMode) {
                    contentIntent
                } else {
                    Intent(Intent.ACTION_CHOOSER).apply {
                        putExtra(Intent.EXTRA_INTENT, contentIntent)
                        putExtra(Intent.EXTRA_TITLE, "Select file")
                    }
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
                // Ensure blob registry and deferred revocation hooks are active as early as possible
                view?.evaluateJavascript(getBlobInterceptionScript(), null)
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
                // v47: immersive browsing — once a page is open, the URL bar
                // and the system status bar step aside and the website takes
                // the whole screen. Touch the top of the screen to bring the
                // bar back (ready for a new URL).
                if (!urlBar.hasFocus()) hideChrome()
                // Ensure hook is alive even if page re-evaluated or dynamically modified
                view?.evaluateJavascript(getBlobInterceptionScript(), null)
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

                if (url.startsWith("blob:")) {
                    executeDownload(
                        DownloadTask(
                            url,
                            if (isDesktopMode) desktopUserAgent else defaultUserAgent,
                            "",
                            ""
                        )
                    )
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

            override fun onRenderProcessGone(view: WebView?, detail: RenderProcessGoneDetail?): Boolean {
                val deadView = view ?: webView
                val lastUrl = deadView.url?.takeIf { it.isNotEmpty() } ?: urlBar.text?.toString()?.takeIf { it.isNotEmpty() } ?: "https://www.google.com"
                try {
                    (deadView.parent as? ViewGroup)?.removeView(deadView)
                    deadView.destroy()
                } catch (_: Exception) {}

                if (deadView === webView) {
                    val didCrash = detail?.didCrash() == true
                    webView = WebView(this@MainActivity).apply {
                        layoutParams = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            0,
                            1f
                        )
                        if (didCrash) {
                            // If renderer process crashed, fallback to software rendering
                            setLayerType(View.LAYER_TYPE_SOFTWARE, null)
                        }
                    }
                    rootLayout.addView(webView)
                    applyStrictEngineSettings()
                    registerDocumentStartHooks()
                    webView.loadUrl(lastUrl)
                }
                return true
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
    // v47: immersive browsing. hideChrome() hands the whole screen to the
    // website (URL bar + Android status bar step aside, swipe gestures keep
    // working). Touching the top strip calls it back up, focused on an empty
    // URL bar, keyboard ready — the v45 tap-to-clear behavior chains into
    // this perfectly. Zero RAM/battery cost: pure visibility flags.
    private fun hideChrome() {
        chromeHidden = true
        topBar.visibility = View.GONE
        // v49: the system fullscreen flags were removed — they were the one
        // heavy change vs v44 (screen recomposition) and the suspect behind
        // YouTube Shorts quality drops and the warm phone. Hiding the URL
        // bar alone gives the page the extra space at zero cost; the Android
        // status bar stays visible like in v44.
    }

    private fun showChrome() {
        chromeHidden = false
        topBar.visibility = View.VISIBLE
        // v50: the bar comes back showing the CURRENT URL — not cleared —
        // so the long-press options (Copy URL / Share URL) and DESK are
        // immediately usable. One tap on the bar then clears it and opens
        // the keyboard for a new URL (v45 behavior). Both worlds at once:
        // the futuristic full screen AND the URL options whenever wanted.
        try {
            val current = webView.url
            if (!current.isNullOrEmpty()) urlBar.setText(current)
        } catch (_: Exception) {}
    }

    // v48 (user's own design): double-tap the top strip to show the bar,
    // double-tap it again to hide it. A single accidental tap passes straight
    // through to the page — the bar never appears or disappears by mistake.
    // Double-tap to show gives full access to the URL options (DESK button,
    // long-press copy/share) whenever needed, so immersive mode and the
    // options coexist.
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
            val strip = 44 * resources.displayMetrics.density
            if (ev.y < strip) {
                val now = System.currentTimeMillis()
                if (now - lastStripTap < 350) {
                    lastStripTap = 0
                    if (chromeHidden) {
                        showChrome()
                    } else {
                        urlBar.clearFocus()
                        try {
                            val imm = getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager
                            imm?.hideSoftInputFromWindow(urlBar.windowToken, 0)
                        } catch (_: Exception) {}
                        hideChrome()
                    }
                    return true
                }
                lastStripTap = now
            } else {
                lastStripTap = 0
            }
        }
        return super.dispatchTouchEvent(ev)
    }

    // v20 fix: clearCache() alone only purges the HTTP cache — the heavy
    // stuff under app_webview (Service Worker CacheStorage, GPUCache,
    // Code Cache) survived it, so the budget kept tripping while the
    // footprint never shrank. The flush now also runs the same deep clean
    // the exit-wipe uses. Cookies/login/history are untouched; the
    // exit-wipe still erases everything on close.
    private fun checkCacheBudget() {
        // v33: throttle — measuring on EVERY page load walks the cache dirs
        // far more often than needed. Every 8th load keeps the 20 MB
        // ceiling honest (worst case: a few pages of overshoot) at 1/8th
        // the directory scans.
        navigationCounter++
        if (navigationCounter % 3 != 0) return
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
                        try {
                            webView.clearCache(true)
                            WebStorage.getInstance().deleteAllData()
                        } catch (_: Exception) {}
                    }
                    try {
                        cleanDir(cacheDir)
                        cleanDir(codeCacheDir)
                        externalCacheDir?.let { cleanDir(it) }
                    } catch (_: Exception) {}
                    // v44: restored from v39 — the deep Chromium clean. clearCache
                    // + WebStorage miss Code Cache and GPUCache (the heaviest,
                    // fully re-creatable folders under app_webview).
                    try { cleanChromiumCache(webviewDir) } catch (_: Exception) {}
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
    // v33: document-start instrumentation (from the Gemini engineering
    // report — the genuinely correct root-cause fix). Diagnosis: sites
    // create a blob: URL, fire the download click, then revoke the URL in
    // the SAME JS frame — while Android's download pipeline is still
    // catching up asynchronously. By the time we fetch the blob, the page
    // has already invalidated it → "site blocked the download". Fix:
    // hooks installed BEFORE any page script runs —
    //   - createObjectURL: keep a live reference to every Blob created
    //   - revokeObjectURL: defer the actual revocation by 45s so the
    //     download has time to read the bytes
    // Registered for ALL origins (a generic browser capability, no
    // site-specific targeting) and silently skipped on system WebViews
    // too old to support document-start scripts.
    private fun getBlobInterceptionScript(): String {
        return """
            (function() {
                try {
                    if (window.__pulseBlobHookInstalled) return;
                    window.__pulseBlobHookInstalled = true;
                    window.__blobRegistry = window.__blobRegistry || new Map();
                    window.__blobFilenames = window.__blobFilenames || new Map();
                    window.__activeBlobStreams = window.__activeBlobStreams || new Set();

                    function streamBlobWithBridge(BR, blob, customMime, customName) {
                        if (!BR || !blob) return;
                        var CHUNK = 262144;
                        var mime = customMime || blob.type || 'application/octet-stream';
                        var size = blob.size || 0;
                        var offset = 0;
                        var fileName = customName || '';

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
                                        if (BR.finishBlobWithName) {
                                            BR.finishBlobWithName(mime, size, fileName);
                                        } else {
                                            BR.finishBlob(mime, size);
                                        }
                                    }
                                }
                            };
                            reader.readAsDataURL(slice);
                        }

                        if (size > 0) {
                            next();
                        } else {
                            if (BR.finishBlobWithName) {
                                BR.finishBlobWithName(mime, 0, fileName);
                            } else {
                                BR.finishBlob(mime, 0);
                            }
                        }
                    }

                    // Standard W3C File System Access API polyfill (used by modern web apps like Google AI Studio)
                    if (!window.showSaveFilePicker || !window.__pulseSavePickerHooked) {
                        try {
                            window.showSaveFilePicker = async function(options) {
                                var suggestedName = (options && options.suggestedName) || 'project.zip';
                                var BR = window.AndroidBlobBridge || (window.top && window.top.AndroidBlobBridge);
                                return {
                                    kind: 'file',
                                    name: suggestedName,
                                    getFile: async function() { return new Blob([], { type: 'application/zip' }); },
                                    queryPermission: async function() { return 'granted'; },
                                    requestPermission: async function() { return 'granted'; },
                                    createWritable: async function() {
                                        var chunks = [];
                                        return {
                                            write: async function(data) {
                                                if (data) {
                                                    if (data.type === 'write' && data.data !== undefined) {
                                                        chunks.push(data.data);
                                                    } else {
                                                        chunks.push(data);
                                                    }
                                                }
                                            },
                                            seek: async function() {},
                                            truncate: async function() {},
                                            close: async function() {
                                                var blob = new Blob(chunks, { type: 'application/zip' });
                                                if (BR) {
                                                    streamBlobWithBridge(BR, blob, 'application/zip', suggestedName);
                                                }
                                            }
                                        };
                                    }
                                };
                            };
                            window.__pulseSavePickerHooked = true;
                        } catch (_) {}
                    }

                    // Direct anchor download capture:
                    // Captures <a download> clicks synchronously at the moment of trigger,
                    // streaming the file cleanly and preventing duplicate download attempts.
                    function captureAnchorDownload(a, event) {
                        try {
                            if (!a) return;
                            var href = a.href || a.getAttribute('href') || '';
                            if (!href || href.indexOf('blob:') !== 0) return;
                            var downloadAttr = a.getAttribute('download') || a.download || '';
                            var filename = downloadAttr.trim();
                            if (!filename || filename === 'true') {
                                try {
                                    var p = new URL(href).pathname;
                                    filename = (window.__blobFilenames && (window.__blobFilenames.get(href) || window.__blobFilenames.get(p))) || 'project.zip';
                                } catch (_) {
                                    filename = 'project.zip';
                                }
                            }
                            if (filename && filename !== 'true') {
                                window.__blobFilenames.set(href, filename);
                                try {
                                    var p = new URL(href).pathname;
                                    if (p) window.__blobFilenames.set(p, filename);
                                } catch (_) {}
                            }

                            if (window.__activeBlobStreams && window.__activeBlobStreams.has(href)) {
                                if (event && typeof event.preventDefault === 'function') event.preventDefault();
                                return;
                            }
                            window.__activeBlobStreams = window.__activeBlobStreams || new Set();
                            window.__activeBlobStreams.add(href);

                            // Cancel default navigation to prevent WebView from firing a second duplicate download
                            if (event && typeof event.preventDefault === 'function') {
                                event.preventDefault();
                            }

                            var win = a.ownerDocument ? (a.ownerDocument.defaultView || window) : window;
                            var BR = win.AndroidBlobBridge || window.AndroidBlobBridge || (window.top && window.top.AndroidBlobBridge);
                            if (!BR) return;

                            function startStream(b) {
                                if (!b) return;
                                streamBlobWithBridge(BR, b, b.type, filename);
                            }

                            // 1. Check registry
                            var regBlob = (window.__blobRegistry && window.__blobRegistry.get(href)) ||
                                          (win.__blobRegistry && win.__blobRegistry.get(href)) ||
                                          (window.top && window.top.__blobRegistry && window.top.__blobRegistry.get(href));
                            if (regBlob) {
                                startStream(regBlob);
                                return;
                            }

                            // 2. Fetch immediately while the blob is alive
                            win.fetch(href).then(function(res) {
                                if (!res.ok && res.status !== 0) throw new Error('HTTP ' + res.status);
                                return res.blob();
                            }).then(function(b) {
                                if (b) {
                                    if (window.__blobRegistry) window.__blobRegistry.set(href, b);
                                    startStream(b);
                                }
                            }).catch(function() {
                                try {
                                    var xhr = new win.XMLHttpRequest();
                                    xhr.open('GET', href, true);
                                    xhr.responseType = 'blob';
                                    xhr.onload = function() {
                                        if (xhr.response) {
                                            if (window.__blobRegistry) window.__blobRegistry.set(href, xhr.response);
                                            startStream(xhr.response);
                                        }
                                    };
                                    xhr.onerror = function() {
                                        if (window.__lastZipBlob) {
                                            startStream(window.__lastZipBlob);
                                        }
                                    };
                                    xhr.send();
                                } catch (_) {
                                    if (window.__lastZipBlob) {
                                        startStream(window.__lastZipBlob);
                                    }
                                }
                            });
                        } catch (_) {}
                    }

                    var origClick = HTMLAnchorElement.prototype.click;
                    if (origClick && !origClick.__pulseHooked) {
                        HTMLAnchorElement.prototype.click = function() {
                            captureAnchorDownload(this, null);
                            return origClick.apply(this, arguments);
                        };
                        HTMLAnchorElement.prototype.click.__pulseHooked = true;
                    }

                    document.addEventListener('click', function(e) {
                        var el = e.target;
                        while (el && el.tagName !== 'A') {
                            el = el.parentElement;
                        }
                        if (el && el.tagName === 'A') {
                            var h = el.href || el.getAttribute('href') || '';
                            if (h.indexOf('blob:') === 0 || el.getAttribute('download') !== null) {
                                captureAnchorDownload(el, e);
                            }
                        }
                    }, true);

                    function installUrlHooks(targetObj) {
                        if (!targetObj) return;
                        var origCreate = targetObj.createObjectURL;
                        if (origCreate && !origCreate.__pulseHooked) {
                            targetObj.createObjectURL = function(blob) {
                                var u = origCreate.call(targetObj, blob);
                                try {
                                    if (blob && (blob instanceof Blob || typeof blob.slice === 'function' || blob.size !== undefined)) {
                                        window.__blobRegistry.set(u, blob);
                                        try {
                                            var p = new URL(u).pathname;
                                            if (p) window.__blobRegistry.set(p, blob);
                                        } catch (_) {}
                                        if (blob.type && blob.type.indexOf('zip') >= 0) {
                                            window.__lastZipBlob = blob;
                                            try { if (window.top) window.top.__lastZipBlob = blob; } catch (_) {}
                                        }
                                        if (blob.name) {
                                            window.__blobFilenames.set(u, blob.name);
                                        }
                                        // Bounded retention (3 min) auto-cleans memory with zero RAM leak
                                        setTimeout(function() {
                                            try {
                                                if (window.__blobRegistry) {
                                                    window.__blobRegistry.delete(u);
                                                    try {
                                                        var p = new URL(u).pathname;
                                                        window.__blobRegistry.delete(p);
                                                    } catch (_) {}
                                                }
                                            } catch (_) {}
                                        }, 180000);
                                    }
                                } catch (e) {}
                                return u;
                            };
                            targetObj.createObjectURL.__pulseHooked = true;
                        }

                        var origRevoke = targetObj.revokeObjectURL;
                        if (origRevoke && !origRevoke.__pulseHooked) {
                            targetObj.revokeObjectURL = function(u) {
                                // Defer revocation by 120s so Android download pipeline has plenty of time
                                setTimeout(function() {
                                    try { origRevoke.call(targetObj, u); } catch (e) {}
                                    try {
                                        if (window.__blobRegistry) {
                                            window.__blobRegistry.delete(u);
                                            try {
                                                var p = new URL(u).pathname;
                                                window.__blobRegistry.delete(p);
                                            } catch (_) {}
                                        }
                                    } catch (_) {}
                                }, 120000);
                            };
                            targetObj.revokeObjectURL.__pulseHooked = true;
                        }
                    }

                    installUrlHooks(window.URL);
                    if (window.webkitURL && window.webkitURL !== window.URL) {
                        installUrlHooks(window.webkitURL);
                    }
                } catch (e) {}
            })();
        """.trimIndent()
    }

    private fun registerDocumentStartHooks() {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) return
        try {
            WebViewCompat.addDocumentStartJavaScript(webView, getBlobInterceptionScript(), setOf("*"))
        } catch (_: Exception) {}
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

        // Handle client-side Blob URLs (Gemini, Google Flow, web video renderers, AI Studio project exports).
        if (url.startsWith("blob:")) {
            // Deduplication: If a file was saved in the last 8 seconds (e.g. via File System Access API),
            // do not trigger a duplicate secondary download attempt for the already-saved blob.
            if (System.currentTimeMillis() - lastSavedTime < 8000L) {
                return
            }
            val safeMime = task.mimeType.ifEmpty { "application/octet-stream" }
            val escapedUrl = url.replace("\\", "\\\\").replace("'", "\\'")
            val escapedMime = safeMime.replace("\\", "\\\\").replace("'", "\\'")
            val js = """
                (function() {
                    var BR = window.AndroidBlobBridge;
                    var targetUrl = '$escapedUrl';
                    var safeMime = '$escapedMime';

                    if (window.__activeBlobStreams && window.__activeBlobStreams.has(targetUrl)) {
                        return; // Stream already triggered by anchor download capture
                    }

                    function streamBlob(blob, customMime, customName) {
                        if (window.__activeBlobStreams) window.__activeBlobStreams.add(targetUrl);
                        var CHUNK = 262144;
                        var mime = customMime || blob.type || safeMime;
                        var size = blob.size;
                        var offset = 0;
                        var fileName = customName || (window.__blobFilenames && window.__blobFilenames.get(targetUrl)) || '';
                        if (!fileName) {
                            try {
                                var p = new URL(targetUrl).pathname;
                                if (p && window.__blobFilenames && window.__blobFilenames.get(p)) {
                                    fileName = window.__blobFilenames.get(p);
                                }
                            } catch (_) {}
                        }
                        if (!fileName && (mime.indexOf('zip') >= 0 || targetUrl.indexOf('zip') >= 0)) {
                            fileName = 'project.zip';
                        }
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
                                        if (BR.finishBlobWithName) {
                                            BR.finishBlobWithName(mime, size, fileName);
                                        } else {
                                            BR.finishBlob(mime, size);
                                        }
                                    }
                                }
                            };
                            reader.readAsDataURL(slice);
                        }
                        if (size > 0) {
                            next();
                        } else {
                            if (BR.finishBlobWithName) {
                                BR.finishBlobWithName(mime, 0, fileName);
                            } else {
                                BR.finishBlob(mime, 0);
                            }
                        }
                    }
                    function fail(msg) {
                        if (window.__activeBlobStreams && window.__activeBlobStreams.size > 0) {
                            return; // A stream is already in progress, suppress spurious failure
                        }
                        BR.blobFailed(String(msg));
                    }

                    function lookupBlob(u) {
                        if (!u) return null;
                        function checkReg(reg) {
                            if (!reg) return null;
                            if (reg.has(u)) return reg.get(u);
                            try {
                                var p = new URL(u).pathname;
                                if (p && reg.has(p)) return reg.get(p);
                            } catch (_) {}
                            for (var entry of reg.entries()) {
                                if (entry[0] === u || u.indexOf(entry[0]) >= 0 || entry[0].indexOf(u) >= 0) {
                                    return entry[1];
                                }
                            }
                            return null;
                        }

                        var b = checkReg(window.__blobRegistry);
                        if (b) return b;

                        try {
                            if (window.top && window.top.__blobRegistry) {
                                var tb = checkReg(window.top.__blobRegistry);
                                if (tb) return tb;
                            }
                        } catch (_) {}

                        // Check across frame windows if present
                        try {
                            var frames = document.querySelectorAll('iframe');
                            for (var i = 0; i < frames.length; i++) {
                                try {
                                    var cwin = frames[i].contentWindow;
                                    if (cwin && cwin.__blobRegistry) {
                                        var fb = checkReg(cwin.__blobRegistry);
                                        if (fb) return fb;
                                    }
                                } catch (_) {}
                            }
                        } catch (_) {}

                        if (window.__lastZipBlob) return window.__lastZipBlob;
                        try { if (window.top && window.top.__lastZipBlob) return window.top.__lastZipBlob; } catch (_) {}

                        // If not found by URL, check if there is an active zip blob (AI Studio export)
                        if (window.__blobRegistry && window.__blobRegistry.size > 0) {
                            for (var entry of window.__blobRegistry.entries()) {
                                var bl = entry[1];
                                if (bl && bl.type && bl.type.indexOf('zip') >= 0) {
                                    return bl;
                                }
                            }
                            var all = Array.from(window.__blobRegistry.values());
                            return all[all.length - 1];
                        }
                        return null;
                    }

                    var targetBlob = lookupBlob(targetUrl);
                    if (targetBlob) {
                        streamBlob(targetBlob);
                        return;
                    }

                    // Visible media fallback for images and videos
                    function tryMedia() {
                        if (window.__lastZipBlob) {
                            streamBlob(window.__lastZipBlob, 'application/zip', 'project.zip');
                            return;
                        }
                        function collectMedia(root, acc) {
                            var list = root.querySelectorAll('img, video');
                            for (var i = 0; i < list.length; i++) { acc.push(list[i]); }
                            var all = root.querySelectorAll('*');
                            for (var j = 0; j < all.length; j++) {
                                if (all[j].shadowRoot) { collectMedia(all[j].shadowRoot, acc); }
                            }
                            return acc;
                        }
                        var media = collectMedia(document, []);
                        var best = null, bestArea = 0;
                        for (var i = 0; i < media.length; i++) {
                            var el = media[i];
                            var w = el.naturalWidth || el.videoWidth || el.clientWidth || 0;
                            var h = el.naturalHeight || el.videoHeight || el.clientHeight || 0;
                            if (w * h < 10000 && !el.duration) continue;
                            var r = el.getBoundingClientRect();
                            var vis = r.top < (window.innerHeight - 20) && r.bottom > 60;
                            var area = Math.max(r.width, w) * Math.max(r.height, h);
                            if (vis && area > bestArea) {
                                bestArea = area;
                                best = el;
                            }
                        }
                        if (best) {
                            var src = best.currentSrc || best.src || '';
                            if (!src && best.querySelector) {
                                var s = best.querySelector('source');
                                if (s) src = s.src || '';
                            }
                            if (src.indexOf('blob:') === 0) {
                                var mb = lookupBlob(src);
                                if (mb) {
                                    streamBlob(mb);
                                    return;
                                }
                            }
                            if (best.tagName && best.tagName.toLowerCase() === 'img') {
                                try {
                                    var c = document.createElement('canvas');
                                    c.width = best.naturalWidth || best.width || 300;
                                    c.height = best.naturalHeight || best.height || 300;
                                    var ctx = c.getContext('2d');
                                    ctx.drawImage(best, 0, 0);
                                    c.toBlob(function(b) {
                                        if (b) { streamBlob(b); }
                                        else if (src) { BR.saveDirectMediaFromDom(src, 'image/jpeg'); }
                                        else { fail('unable to read download content'); }
                                    }, 'image/png');
                                    return;
                                } catch (e) {
                                    if (src) {
                                        BR.saveDirectMediaFromDom(src, 'image/jpeg');
                                        return;
                                    }
                                }
                            }
                            if (src) {
                                BR.saveDirectMediaFromDom(src, best.tagName && best.tagName.toLowerCase() === 'video' ? 'video/mp4' : 'image/jpeg');
                                return;
                            }
                        }
                        var lateBlob = lookupBlob(targetUrl);
                        if (lateBlob) {
                            streamBlob(lateBlob);
                            return;
                        }
                        fail('unable to read download content');
                    }

                    function tryXhr() {
                        try {
                            var xhr = new XMLHttpRequest();
                            xhr.open('GET', targetUrl);
                            xhr.responseType = 'blob';
                            xhr.onload = function() {
                                if (xhr.response && xhr.response.size > 0) {
                                    streamBlob(xhr.response);
                                } else {
                                    tryMedia();
                                }
                            };
                            xhr.onerror = function() { tryMedia(); };
                            xhr.send();
                        } catch (e) { tryMedia(); }
                    }

                    try {
                        fetch(targetUrl)
                        .then(function(r) {
                            if (!r.ok && r.status !== 0) throw new Error('HTTP ' + r.status);
                            return r.blob();
                        })
                        .then(function(b) {
                            if (b && b.size > 0) {
                                streamBlob(b);
                            } else {
                                tryXhr();
                            }
                        })
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

        // Direct media download for images/videos or Google/Gemini media URLs
        val isMediaDownload = task.mimeType.startsWith("image/") ||
                task.mimeType.startsWith("video/") ||
                task.mimeType.contains("zip") ||
                task.contentDisposition.contains(".zip") ||
                url.contains(".zip") ||
                url.contains("googleusercontent.com") ||
                url.contains("generativelanguage.googleapis.com") ||
                url.contains("storage.googleapis.com") ||
                url.contains("aistudio.google.com") ||
                url.endsWith(".png") || url.endsWith(".jpg") || url.endsWith(".jpeg") ||
                url.endsWith(".webp") || url.endsWith(".mp4") || url.endsWith(".webm") || url.endsWith(".gif") || url.endsWith(".pdf") || url.endsWith(".zip") || url.endsWith(".apk") || url.endsWith(".mp3") || url.endsWith(".m4a") || url.endsWith(".wav") || url.endsWith(".ogg") || url.endsWith(".mov") || url.endsWith(".svg") || url.endsWith(".bmp")

        if (isMediaDownload) {
            downloadMediaDirect(url, task)
            return
        }

        // Standard HTTP / HTTPS Downloads via DownloadManager
        if (enqueueSystemDownload(url, task.userAgent, task.contentDisposition, task.mimeType) == null) {
            return
        }
        downloadMediaDirect(url, task)
    }

    // v42: shared system-downloader path. Returns false when Android cannot
    // name the file (bare ".bin" — caller should use our own byte-sniffing
    // downloader) or when enqueueing fails outright.
    private fun enqueueSystemDownload(
        url: String,
        userAgent: String,
        contentDisposition: String,
        mimeType: String,
        allowUnnamed: Boolean = false
    ): String? { // null = success, otherwise the failure reason
        return try {
            val fileName = URLUtil.guessFileName(url, contentDisposition, mimeType)
            if (!allowUnnamed && fileName.endsWith(".bin")) {
                // v41: Android could not identify this file (no extension in the
                // URL, no useful mime) — exam-paper sites do this and the file
                // lands as .bin. Use our own downloader instead: it reads the
                // actual bytes (%PDF -> .pdf, PK... -> zip family, ...) and also
                // respects the server's filename header.
                return "no recognizable file name"
            }
            val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            downloadsDir.mkdirs()

            val request = DownloadManager.Request(Uri.parse(url)).apply {
                if (mimeType.isNotEmpty()) {
                    setMimeType(mimeType)
                }
                addRequestHeader("User-Agent", userAgent)
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
            null
        } catch (e: Exception) {
            e.message ?: "system download failed"
        }
    }

    // v23: "Save image" dialog for the long-press menu. Routes the image
    // URL through the right saver: data: URIs, blob: URLs (in-page path)
    // and everything else via our own direct downloader with cookies.
    private fun showSaveImageDialog(imageUrl: String) {
        val isVideo = imageUrl.contains(".mp4") || imageUrl.contains(".webm") || imageUrl.contains("video")
        val label = if (isVideo) "video" else "image"
        AlertDialog.Builder(this)
            .setTitle("Save $label")
            .setMessage("Download this $label to Downloads?")
            .setPositiveButton("Download") { _, _ ->
                when {
                    imageUrl.startsWith("data:") -> saveRawBase64(imageUrl, "")
                    imageUrl.startsWith("blob:") ->
                        executeDownload(
                            DownloadTask(
                                imageUrl,
                                if (isDesktopMode) desktopUserAgent else defaultUserAgent,
                                "",
                                if (isVideo) "video/mp4" else "image/png"
                            )
                        )
                    else -> downloadMediaDirect(imageUrl, null)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun downloadImageViaPage(url: String) {
        if (url.startsWith("blob:")) {
            executeDownload(DownloadTask(url, if (isDesktopMode) desktopUserAgent else defaultUserAgent, "", ""))
        } else {
            downloadMediaDirect(url, null)
        }
    }

    private fun downloadImageDirect(url: String) {
        downloadMediaDirect(url, null)
    }

    private fun fallbackToDomExtraction(failedUrl: String) {
        runOnUiThread {
            val escUrl = failedUrl.replace("'", "\\'")
            val js = """
                (function() {
                    var BR = window.AndroidBlobBridge;
                    if (!BR) return;
                    try {
                        var failedUrl = '$escUrl';
                        function sendBlob(blob, customMime) {
                            var CHUNK = 65536;
                            var mime = customMime || blob.type || 'image/png';
                            var size = blob.size;
                            var offset = 0;
                            function next() {
                                var slice = blob.slice(offset, Math.min(offset + CHUNK, size));
                                var reader = new FileReader();
                                reader.onloadend = function() {
                                    if (reader.readyState === FileReader.DONE) {
                                        var res = reader.result;
                                        var b64 = res.substring(res.indexOf(',') + 1);
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

                        // 1. Direct registry lookup
                        if (window.__blobRegistry) {
                            if (window.__blobRegistry.has(failedUrl)) {
                                sendBlob(window.__blobRegistry.get(failedUrl));
                                return;
                            }
                            try {
                                var parsedPath = new URL(failedUrl, location.href).pathname;
                                if (parsedPath && window.__blobRegistry.has(parsedPath)) {
                                    sendBlob(window.__blobRegistry.get(parsedPath));
                                    return;
                                }
                            } catch (_) {}
                        }

                        // 2. Locate media element on screen
                        function collectMedia(root, acc) {
                            var list = root.querySelectorAll('img, video');
                            for (var i = 0; i < list.length; i++) { acc.push(list[i]); }
                            var all = root.querySelectorAll('*');
                            for (var j = 0; j < all.length; j++) {
                                if (all[j].shadowRoot) { collectMedia(all[j].shadowRoot, acc); }
                            }
                            return acc;
                        }
                        var imgs = collectMedia(document, []);
                        var target = null;
                        for (var i = 0; i < imgs.length; i++) {
                            var el = imgs[i];
                            var s = el.currentSrc || el.src || '';
                            if (s === failedUrl || (s && failedUrl.indexOf(s) !== -1) || (failedUrl.indexOf(s) !== -1)) {
                                target = el;
                                break;
                            }
                        }
                        if (!target) {
                            var bestArea = 0;
                            for (var j = 0; j < imgs.length; j++) {
                                var e = imgs[j];
                                var r = e.getBoundingClientRect();
                                var w = e.naturalWidth || e.videoWidth || r.width || 0;
                                var h = e.naturalHeight || e.videoHeight || r.height || 0;
                                var area = w * h;
                                if (area > bestArea && r.top < (window.innerHeight - 20) && r.bottom > 60) {
                                    bestArea = area;
                                    target = e;
                                }
                            }
                        }

                        if (target) {
                            var src = target.currentSrc || target.src || '';
                            if (!src && target.querySelector) {
                                var sTag = target.querySelector('source');
                                if (sTag) src = sTag.src || '';
                            }
                            var isVideo = target.tagName && target.tagName.toLowerCase() === 'video';
                            var defaultMime = isVideo ? 'video/mp4' : 'image/jpeg';

                            if (src && src.indexOf('blob:') === 0 && window.__blobRegistry && window.__blobRegistry.has(src)) {
                                sendBlob(window.__blobRegistry.get(src), defaultMime);
                                return;
                            }

                            if (src && src.indexOf('http') === 0) {
                                fetch(src, { credentials: 'include' })
                                    .then(function(r) {
                                        if (!r.ok) throw new Error('HTTP ' + r.status);
                                        return r.blob();
                                    })
                                    .then(function(b) {
                                        sendBlob(b, defaultMime);
                                    })
                                    .catch(function() {
                                        if (!isVideo && target.tagName && target.tagName.toLowerCase() === 'img') {
                                            try {
                                                var c = document.createElement('canvas');
                                                c.width = target.naturalWidth || target.width || 300;
                                                c.height = target.naturalHeight || target.height || 300;
                                                var ctx = c.getContext('2d');
                                                ctx.drawImage(target, 0, 0);
                                                c.toBlob(function(b) {
                                                    if (b) {
                                                        sendBlob(b, 'image/png');
                                                    } else {
                                                        BR.saveDirectMediaFromDom(src, defaultMime);
                                                    }
                                                }, 'image/png');
                                                return;
                                            } catch (_) {}
                                        }
                                        BR.saveDirectMediaFromDom(src, defaultMime);
                                    });
                                return;
                            } else if (src && src.indexOf('blob:') === 0) {
                                fetch(src)
                                    .then(function(r) { return r.blob(); })
                                    .then(function(b) { sendBlob(b, defaultMime); })
                                    .catch(function() {
                                        if (src.indexOf('http') === 0) { BR.systemDownload(src, defaultMime); }
                                        else { BR.blobFailed('unable to read download content'); }
                                    });
                                return;
                            }
                        }
                        // v43: same-origin page-context fetch FIRST — runs
                        // inside the site with the full session, exactly how the
                        // site's own download button works. Only if that fails
                        // do we hand the URL to the system downloader.
                        if (failedUrl.indexOf('http') === 0) {
                            fetch(failedUrl, { credentials: 'include' })
                                .then(function(r) { if (!r.ok) { throw new Error('HTTP ' + r.status); } return r.blob(); })
                                .then(function(b) { sendBlob(b, (b && b.type) || ''); })
                                .catch(function() { BR.systemDownload(failedUrl, ''); });
                        } else {
                            BR.blobFailed('media not found');
                        }
                    } catch (err) {
                        BR.blobFailed(err && err.message ? err.message : 'unable to read download content');
                    }
                })();
            """.trimIndent()
            webView.evaluateJavascript(js, null)
        }
    }

    private data class DirectDownloadStrategy(
        val referer: String?,
        val cookie: String?,
        val secFetchMode: String,
        val secFetchDest: String,
        val secFetchSite: String?,
        val acceptHeader: String,
        val tag: String
    )

    private fun getMergedGoogleCookies(targetUrl: String, refererUrl: String?): String {
        val cookieMgr = CookieManager.getInstance()
        val domains = listOf(
            targetUrl,
            refererUrl ?: "",
            "https://gemini.google.com",
            "https://www.google.com",
            "https://google.com",
            "https://accounts.google.com",
            "https://photos.google.com",
            "https://drive.google.com",
            "https://docs.google.com",
            "https://play.google.com"
        )
        val cookieMap = LinkedHashMap<String, String>()
        for (d in domains) {
            if (d.isNotEmpty()) {
                val c = cookieMgr.getCookie(d)
                if (!c.isNullOrEmpty()) {
                    for (pair in c.split(";")) {
                        val p = pair.trim()
                        val eq = p.indexOf('=')
                        if (eq > 0) {
                            val k = p.substring(0, eq).trim()
                            val v = p.substring(eq + 1).trim()
                            if (k.isNotEmpty() && v.isNotEmpty()) {
                                cookieMap[k] = v
                            }
                        }
                    }
                }
            }
        }
        return cookieMap.entries.joinToString("; ") { "${it.key}=${it.value}" }
    }

    // Direct media downloader:
    // Streams in 64KB buffer chunks directly to a temporary file on disk (ZERO RAM increase).
    // Bypasses 403 Forbidden errors from Google/Gemini CDNs (lh3.googleusercontent.com) by:
    // 1. Trying browser document download navigation with merged Google session cookies
    // 2. Trying subresource fetch with proper Origin / Referer / Sec-Fetch-Site
    // 3. Trying clean no-referrer requests (avoiding Google anti-hotlink blocks)
    // 4. Following HTTP 30x redirects safely
    // 5. Sniffing magic bytes for accurate .mp4 / .webm / .jpg / .png extensions
    private fun downloadMediaDirect(url: String, task: DownloadTask? = null) {
        if (url.startsWith("data:")) {
            saveRawBase64(url, task?.mimeType ?: "")
            return
        }
        if (url.startsWith("blob:")) {
            executeDownload(
                DownloadTask(
                    url,
                    if (isDesktopMode) desktopUserAgent else defaultUserAgent,
                    "",
                    task?.mimeType ?: ""
                )
            )
            return
        }
        if (url.isEmpty() || (!url.startsWith("http://") && !url.startsWith("https://"))) {
            runOnUiThread {
                Toast.makeText(this, "Save failed: invalid download address", Toast.LENGTH_LONG).show()
            }
            return
        }
        Toast.makeText(this, "Downloading...", Toast.LENGTH_SHORT).show()
        val refererUrl = try { webView.url } catch (_: Exception) { null } ?: url
        val ua = if (isDesktopMode) desktopUserAgent else defaultUserAgent
        val isVideoHint = task?.mimeType?.startsWith("video/") == true ||
                url.contains(".mp4") || url.contains(".webm") || url.contains("video")

        Thread {
            var tempPartFile: File? = null
            try {
                val cookieMgr = CookieManager.getInstance()
                val urlCookies = cookieMgr.getCookie(url)
                val mergedCookie = getMergedGoogleCookies(url, refererUrl)

                val attempts = listOf(
                    // 1. Browser Navigation Download: As top-level document download with referer and merged auth cookies
                    DirectDownloadStrategy(
                        referer = refererUrl,
                        cookie = mergedCookie,
                        secFetchMode = "navigate",
                        secFetchDest = "document",
                        secFetchSite = null,
                        acceptHeader = "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8",
                        tag = "nav-doc-ref-cookie"
                    ),
                    // 2. Subresource Fetch: As image/video element with referer and merged auth cookies
                    DirectDownloadStrategy(
                        referer = refererUrl,
                        cookie = mergedCookie,
                        secFetchMode = "no-cors",
                        secFetchDest = if (isVideoHint) "video" else "image",
                        secFetchSite = null,
                        acceptHeader = "image/avif,image/webp,image/apng,image/svg+xml,image/*,*/*;q=0.8",
                        tag = "subres-ref-cookie"
                    ),
                    // 3. Gemini Root Origin Referer
                    DirectDownloadStrategy(
                        referer = "https://gemini.google.com/",
                        cookie = mergedCookie,
                        secFetchMode = "no-cors",
                        secFetchDest = if (isVideoHint) "video" else "image",
                        secFetchSite = "cross-site",
                        acceptHeader = "*/*",
                        tag = "gemini-ref-cookie"
                    ),
                    // 4. Authenticated Clean Request (No Referer - bypasses hotlink filters on Google UserContent)
                    DirectDownloadStrategy(
                        referer = null,
                        cookie = mergedCookie,
                        secFetchMode = "navigate",
                        secFetchDest = "document",
                        secFetchSite = "none",
                        acceptHeader = "*/*",
                        tag = "no-ref-cookie"
                    ),
                    // 5. Target URL cookies only
                    DirectDownloadStrategy(
                        referer = null,
                        cookie = urlCookies,
                        secFetchMode = "navigate",
                        secFetchDest = "document",
                        secFetchSite = "none",
                        acceptHeader = "*/*",
                        tag = "url-cookie-only"
                    ),
                    // 6. Clean request without cookie or referer
                    DirectDownloadStrategy(
                        referer = null,
                        cookie = null,
                        secFetchMode = "navigate",
                        secFetchDest = "document",
                        secFetchSite = "none",
                        acceptHeader = "*/*",
                        tag = "clean-no-cookie"
                    )
                )

                var success = false
                var lastCode = 0
                var lastMsg = ""

                for (strat in attempts) {
                    var c: HttpURLConnection? = null
                    try {
                        var currentUrl = url
                        var redirects = 0
                        while (redirects < 5) {
                            c = (URL(currentUrl).openConnection() as HttpURLConnection).apply {
                                connectTimeout = 15_000
                                readTimeout = 20_000
                                instanceFollowRedirects = false
                                setRequestProperty("User-Agent", ua)
                                val targetHost = try { URI(currentUrl).host?.lowercase() ?: "" } catch (_: Exception) { "" }
                                val refHost = try { if (!strat.referer.isNullOrEmpty()) URI(strat.referer).host?.lowercase() ?: "" else "" } catch (_: Exception) { "" }
                                val isGoogleDomain = targetHost.endsWith(".google.com") || targetHost.endsWith(".googleapis.com") || targetHost.endsWith(".googleusercontent.com")

                                if (!isGoogleDomain) {
                                    setRequestProperty("X-Requested-With", packageName)
                                }
                                setRequestProperty("Accept", strat.acceptHeader)
                                setRequestProperty("Accept-Language", "en-US,en;q=0.9")
                                if (isGoogleDomain) {
                                    setRequestProperty("Sec-Ch-Ua", "\"Chromium\";v=\"130\", \"Google Chrome\";v=\"130\", \"Not?A_Brand\";v=\"99\"")
                                } else {
                                    setRequestProperty("Sec-Ch-Ua", "\"Android WebView\";v=\"130\", \"Chromium\";v=\"130\", \"Not?A_Brand\";v=\"99\"")
                                }
                                setRequestProperty("Sec-Ch-Ua-Mobile", "?1")
                                setRequestProperty("Sec-Ch-Ua-Platform", "\"Android\"")
                                setRequestProperty("Upgrade-Insecure-Requests", "1")

                                val computedSite = strat.secFetchSite ?: when {
                                    strat.referer.isNullOrEmpty() -> "none"
                                    targetHost.isNotEmpty() && targetHost == refHost -> "same-origin"
                                    targetHost.endsWith(".google.com") && refHost.endsWith(".google.com") -> "same-site"
                                    else -> "cross-site"
                                }

                                setRequestProperty("Sec-Fetch-Site", computedSite)
                                setRequestProperty("Sec-Fetch-Mode", strat.secFetchMode)
                                setRequestProperty("Sec-Fetch-Dest", strat.secFetchDest)
                                if (strat.secFetchMode == "navigate") {
                                    setRequestProperty("Sec-Fetch-User", "?1")
                                }

                                if (!strat.cookie.isNullOrEmpty()) {
                                    setRequestProperty("Cookie", strat.cookie)
                                }
                                if (!strat.referer.isNullOrEmpty()) {
                                    setRequestProperty("Referer", strat.referer)
                                    val origin = try {
                                        val u = URI(strat.referer)
                                        "${u.scheme}://${u.host}"
                                    } catch (_: Exception) { null }
                                    if (!origin.isNullOrEmpty()) {
                                        setRequestProperty("Origin", origin)
                                    }
                                }
                            }
                            val code = c.responseCode
                            if (code in 300..399) {
                                val loc = c.getHeaderField("Location")
                                c.disconnect()
                                if (!loc.isNullOrEmpty()) {
                                    currentUrl = if (loc.startsWith("http")) loc else URL(URL(currentUrl), loc).toString()
                                    redirects++
                                    continue
                                }
                            }
                            break
                        }

                        val conn = c ?: continue
                        lastCode = conn.responseCode
                        if (conn.responseCode in 200..299) {
                            val partName = "Direct_${System.currentTimeMillis()}.part"
                            val partFile = File(cacheDir, partName)
                            tempPartFile = partFile
                            val firstBytes = ByteArray(128)
                            var firstRead = 0
                            partFile.outputStream().use { out ->
                                val buf = ByteArray(65536)
                                var read: Int
                                while (conn.inputStream.read(buf).also { read = it } != -1) {
                                    if (firstRead < 128) {
                                        val toCopy = minOf(read, 128 - firstRead)
                                        System.arraycopy(buf, 0, firstBytes, firstRead, toCopy)
                                        firstRead += toCopy
                                    }
                                    out.write(buf, 0, read)
                                }
                            }
                            if (partFile.length() > 0) {
                                val sniffed = sniffFileType(firstBytes)
                                if (sniffed.first == "text/html") {
                                    // v38: the server returned a web page instead of the
                                    // file — delete it and tell the user, instead of saving
                                    // an HTML file with a fake .mp4/.jpg extension.
                                    partFile.delete()
                                    runOnUiThread {
                                        Toast.makeText(this@MainActivity,
                                            "Download failed: the site returned a web page, not the file",
                                            Toast.LENGTH_LONG).show()
                                    }
                                    success = true // handled — skip publish + fallbacks
                                    break
                                }
                                val isVideo = sniffed.first.startsWith("video/") ||
                                        conn.contentType?.contains("video") == true ||
                                        (task?.mimeType?.contains("video") == true) ||
                                        url.contains(".mp4") || url.contains(".webm")
                                val ext = sniffed.second.ifEmpty {
                                    // v40/v41: not a known media type — take the
                                    // REAL name from the server's filename header
                                    // or the URL (.pdf, .zip, .apk, .docx, ...)
                                    // instead of inventing .mp4/.jpg.
                                    val disp = conn.getHeaderField("Content-Disposition")
                                    val guessed = URLUtil.guessFileName(url, disp, conn.contentType)
                                    val g = guessed.substringAfterLast('.', "").lowercase()
                                    when {
                                        g.length in 2..5 && g != "bin" -> ".$g"
                                        sniffed.first == "application/zip" -> ".zip"
                                        else -> ".bin"
                                    }
                                }
                                val mime = sniffed.first.ifEmpty {
                                    if (isVideo) "video/mp4" else "application/octet-stream"
                                }
                                val prefix = if (isVideo) "Pulse_Video_" else "Pulse_File_"
                                val guessed = if (!task?.contentDisposition.isNullOrEmpty()) {
                                    URLUtil.guessFileName(url, task?.contentDisposition, mime)
                                } else null
                                val fileName = if (!guessed.isNullOrEmpty() && guessed.contains(".")) {
                                    guessed
                                } else {
                                    "$prefix${System.currentTimeMillis()}$ext"
                                }
                                publishToDownloads(partFile, fileName, mime)
                                runOnUiThread {
                                    Toast.makeText(this@MainActivity, "Saved to Downloads: $fileName", Toast.LENGTH_LONG).show()
                                }
                                success = true
                                break
                            }
                        } else {
                            lastMsg = "HTTP " + conn.responseCode
                        }
                    } catch (e: Exception) {
                        lastMsg = e.message ?: "Connection failed"
                    } finally {
                        c?.disconnect()
                    }
                }

                if (!success) {
                    tempPartFile?.delete()
                    if (lastCode == 403 || lastCode == 401 || lastCode == 0) {
                        fallbackToDomExtraction(url)
                    } else {
                        val errorDetail = if (lastCode > 0) "Error $lastCode" else lastMsg
                        runOnUiThread {
                            Toast.makeText(this@MainActivity, "Save failed: $errorDetail", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            } catch (e: Exception) {
                tempPartFile?.delete()
                fallbackToDomExtraction(url)
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
                runOnUiThread {
                    Toast.makeText(this@MainActivity, "Downloading...", Toast.LENGTH_SHORT).show()
                }
                closeBlobStream()
                val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                downloadsDir.mkdirs()
                // Clean up stale .part files abandoned by interrupted
                // downloads (public dir + v31 cache-dir fallback alike).
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
                val partName = "Pulse_Download_${System.currentTimeMillis()}.part"
                var f = File(downloadsDir, partName)
                blobOutputStream = try {
                    f.outputStream()
                } catch (_: Exception) {
                    // v31: Android 10 scoped storage blocks File writes into
                    // public Downloads — assemble in the app's private cache
                    // dir instead (auto-wiped on exit) and publish through
                    // MediaStore when the download finishes.
                    f = File(cacheDir, partName)
                    f.outputStream()
                }
                blobTempFile = f
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
            // v38: an HTML response means the "download" was actually a web
            // page (error / consent interstitial) — callers must reject it,
            // never save it under a media extension.
            run {
                val h = String(b, 0, minOf(b.size, 16)).trim().lowercase()
                h.startsWith("<!doctype") || h.startsWith("<html") || h.startsWith("<head")
            } -> "text/html" to ".html"
            b.size > 12 && b[0] == 0x1A.toByte() && b[1] == 0x45.toByte() &&
                b[2] == 0xDF.toByte() && b[3] == 0xA3.toByte() -> "video/webm" to ".webm"
            b.size > 12 && at(4, 'f') && at(5, 't') && at(6, 'y') && at(7, 'p') -> "video/mp4" to ".mp4"
            b.size > 4 && b[0] == 0xFF.toByte() && b[1] == 0xD8.toByte() -> "image/jpeg" to ".jpg"
            b.size > 8 && b[0] == 0x89.toByte() && at(1, 'P') && at(2, 'N') && at(3, 'G') -> "image/png" to ".png"
            b.size > 12 && at(0, 'R') && at(1, 'I') && at(2, 'F') && at(3, 'F') &&
                at(8, 'W') && at(9, 'E') && at(10, 'B') && at(11, 'P') -> "image/webp" to ".webp"
            b.size > 4 && at(0, 'G') && at(1, 'I') && at(2, 'F') -> "image/gif" to ".gif"
            b.size > 4 && at(0, '%') && at(1, 'P') && at(2, 'D') -> "application/pdf" to ".pdf"
            // v41: ZIP-based files (zip, docx, xlsx, pptx, apk, jar, epub).
            // The real extension comes from the URL / server filename.
            b.size > 3 && at(0, 'P') && at(1, 'K') -> "application/zip" to ""
            // v40: common audio formats (was media-only before)
            b.size > 3 && at(0, 'I') && at(1, 'D') && at(2, '3') -> "audio/mpeg" to ".mp3"
            b.size > 3 && at(0, 'O') && at(1, 'g') && at(2, 'g') -> "audio/ogg" to ".ogg"
            b.size > 12 && at(0, 'R') && at(1, 'I') && at(2, 'F') && at(3, 'F') &&
                at(8, 'W') && at(9, 'A') && at(10, 'V') -> "audio/wav" to ".wav"
            else -> "" to ""
        }
    }

    private fun finishBlobDownload(mimeType: String, totalSize: Long, suggestedName: String? = null) {
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
            // v43: also sniff the saved part-file's real bytes, and name
            // zip/gzip/text honestly instead of inventing .mp4.
            val head = ByteArray(128)
            var headRead = 0
            try {
                part.inputStream().use { ins ->
                    while (headRead < 128) {
                        val r = ins.read(head, headRead, 128 - headRead)
                        if (r <= 0) break
                        headRead += r
                    }
                }
            } catch (_: Exception) { }
            val sniffed = sniffFileType(head)
            val ext = when {
                blobDetectedExt.isNotEmpty() -> blobDetectedExt
                sniffed.second.isNotEmpty() -> sniffed.second
                sniffed.first == "application/zip" -> ".zip"
                mimeType.contains("video/webm") -> ".webm"
                mimeType.contains("video/mp4") || mimeType.contains("video/quicktime") -> ".mp4"
                mimeType.contains("image/png") -> ".png"
                mimeType.contains("image/jpeg") || mimeType.contains("image/jpg") -> ".jpg"
                mimeType.contains("image/webp") -> ".webp"
                mimeType.contains("application/pdf") -> ".pdf"
                mimeType.contains("application/zip") || mimeType.contains("x-zip") -> ".zip"
                mimeType.contains("application/gzip") -> ".gz"
                mimeType.contains("text/") -> ".txt"
                else -> ".bin"
            }
            val finalMime = blobDetectedMime.ifEmpty { sniffed.first }.ifEmpty { mimeType }.ifEmpty { "application/octet-stream" }
            val isVideo = finalMime.startsWith("video/") || ext in listOf(".mp4", ".webm", ".mkv")
            val isImage = finalMime.startsWith("image/") || ext in listOf(".jpg", ".jpeg", ".png", ".webp", ".gif")

            val cleanSuggested = suggestedName?.trim()?.replace(Regex("[/\\\\:*?\"<>|]"), "_")?.takeIf { it.isNotEmpty() && !it.endsWith(".bin") }
            val fileName = if (cleanSuggested != null) {
                if (cleanSuggested.contains(".")) cleanSuggested else "$cleanSuggested$ext"
            } else {
                val prefix = if (isVideo) "Pulse_Video_" else if (isImage) "Pulse_Image_" else "Pulse_File_"
                "$prefix${System.currentTimeMillis()}$ext"
            }
            if (publishToDownloads(part, fileName, finalMime)) {
                lastSavedTime = System.currentTimeMillis()
                lastSavedName = fileName
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

    // v31: publish an assembled file into public Downloads. Fast path is a
    // plain same-dir rename (works everywhere File access is allowed). If the
    // .part was assembled in the cache dir (Android 10), copy it in. If plain
    // file access fails entirely, insert through MediaStore — which needs no
    // storage permission at all. Returns true on success.
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
        } catch (_: Exception) { /* fall through to MediaStore */ }
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
                // MediaStore entries are indexed immediately — no scanner needed.
                true
            } catch (_: Exception) {
                false
            }
        } else {
            false
        }
    }

    // v31: single-shot variant of the scoped-storage fallback — used when a
    // plain write into public Downloads throws (Android 10). Images go into
    // the Images collection so they show up in gallery apps like Google Photos.
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
                    // v21: scan with the CORRECT mime — the old code always
                    // told Android "video/mp4", so images saved from data:
                    // URLs never showed up in Google Photos.
                    val scanMime = sniffed.first.ifEmpty {
                        header.substringAfter("data:", "").substringBefore(';').ifEmpty { "application/octet-stream" }
                    }

                    val isVideo = scanMime.startsWith("video/") || extension in listOf(".mp4", ".webm", ".mkv")
                    val isImage = scanMime.startsWith("image/") || extension in listOf(".jpg", ".jpeg", ".png", ".webp", ".gif")
                    val prefix = if (isVideo) "Pulse_Video_" else if (isImage) "Pulse_Image_" else "Pulse_File_"
                    val fileName = "$prefix${System.currentTimeMillis()}$extension"
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
                        // v31: scoped storage (Android 10) — MediaStore fallback.
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
        try { WebStorage.getInstance().deleteAllData() } catch (_: Exception) {}
        webView.destroy()

        cleanDir(cacheDir)
        cleanDir(codeCacheDir)
        externalCacheDir?.let { cleanDir(it) }

        // v44: restored from v39 — deep-clean the heavy Chromium folders that
        // survive the wipes above, then hard-kill the process so nothing
        // lingers in RAM after exit (zero background memory).
        try {
            val webviewDir = File(applicationInfo.dataDir, "app_webview")
            cleanChromiumCache(webviewDir)
        } catch (_: Exception) {}
        super.onDestroy()
        android.os.Process.killProcess(android.os.Process.myPid())
    }

    private fun cleanDir(dir: File?) {
        if (dir != null && dir.isDirectory) {
            dir.listFiles()?.forEach { file ->
                val name = file.name.lowercase()
                // Never touch Chromium / WebView internal cache folders and index files
                if (name == "webview" || name == "app_webview" || name == "org.chromium.android_webview" ||
                    name == "default" || name.contains("cache")) {
                    return@forEach
                }
                if (file.isDirectory) cleanDir(file)
                file.delete()
            }
        }
    }

    // v39/v44: deep Chromium cache clean — "Cache" substring-matches GPUCache,
    // Code Cache and Cache; "Service Worker" matches SW storage.
    private fun cleanChromiumCache(dir: File) {
        val targets = listOf("Cache", "Service Worker")
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
