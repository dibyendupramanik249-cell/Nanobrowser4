package com.example

import android.annotation.SuppressLint
import android.app.Activity
import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Process
import android.util.Base64
import android.view.KeyEvent
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.*
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import java.io.File
import java.io.FileOutputStream

class MainActivity : Activity() {

    private lateinit var webView: WebView
    private lateinit var urlEditText: EditText
    private lateinit var desktopButton: Button

    private var isDesktopMode = false
    private var fileUploadCallback: ValueCallback<Array<Uri>>? = null
    private var pendingPermissionRequest: PermissionRequest? = null

    private val FILE_CHOOSER_REQUEST_CODE = 1001
    private val PERMISSION_REQUEST_CODE = 1002

    private var lastTargetUrl: String = "https://www.google.com"
    private var hasNetworkError = false
    private lateinit var connectivityManager: ConnectivityManager
    private lateinit var networkCallback: ConnectivityManager.NetworkCallback

    // Clean Chrome Mobile UA recognized by Google OAuth, AI Overview, and Google Lens
    private val mobileUserAgent =
        "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Mobile Safari/537.36"

    // Clean Desktop Chrome UA
    private val desktopUserAgent =
        "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36"

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // fitsSystemWindows prevents system status bar from overlapping top bar
        val rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            fitsSystemWindows = true
        }

        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            val paddingPx = (6 * resources.displayMetrics.density).toInt()
            setPadding(paddingPx, paddingPx, paddingPx, paddingPx)
        }

        urlEditText = EditText(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f)
            hint = "Search or type URL"
            imeOptions = EditorInfo.IME_ACTION_GO
            isSingleLine = true
            setSelectAllOnFocus(true)
            setOnEditorActionListener { _, actionId, event ->
                if (actionId == EditorInfo.IME_ACTION_GO ||
                    (event != null && event.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)
                ) {
                    val input = text.toString().trim()
                    loadFormattedUrl(input)
                    hideKeyboard()
                    true
                } else {
                    false
                }
            }
        }

        desktopButton = Button(this).apply {
            text = "D"
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            setOnClickListener {
                toggleDesktopMode()
            }
        }

        topBar.addView(urlEditText)
        topBar.addView(desktopButton)

        webView = WebView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1.0f
            )
        }

        rootLayout.addView(topBar)
        rootLayout.addView(webView)
        setContentView(rootLayout)

        configureWebView()
        setupNetworkAutoReconnect()

        loadFormattedUrl(lastTargetUrl)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun configureWebView() {
        val settings = webView.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.databaseEnabled = true
        settings.javaScriptCanOpenWindowsAutomatically = true
        settings.mediaPlaybackRequiresUserGesture = false

        // Viewport and layout overview settings
        settings.useWideViewPort = true
        settings.loadWithOverviewMode = true
        settings.setSupportZoom(true)
        settings.builtInZoomControls = true
        settings.displayZoomControls = false

        settings.userAgentString = mobileUserAgent

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            CookieManager.getInstance().apply {
                setAcceptCookie(true)
                setAcceptThirdPartyCookies(webView, true)
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            try {
                ServiceWorkerController.getInstance().serviceWorkerWebSettings.apply {
                    allowContentAccess = true
                    allowFileAccess = true
                    blockNetworkLoads = false
                }
            } catch (_: Exception) {}
        }

        webView.addJavascriptInterface(AndroidBlobBridge(), "AndroidBlobBridge")

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val url = request?.url?.toString() ?: return false
                return if (url.startsWith("http://") || url.startsWith("https://")) {
                    false
                } else {
                    try {
                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                        startActivity(intent)
                        true
                    } catch (e: Exception) {
                        true
                    }
                }
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?
            ) {
                if (request?.isForMainFrame == true) {
                    hasNetworkError = true
                    val failedUrl = request.url.toString()
                    if (!failedUrl.contains("chromewebdata")) {
                        lastTargetUrl = failedUrl
                    }
                }
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                url?.let {
                    if (!it.contains("chromewebdata")) {
                        urlEditText.setText(it)
                    }
                }
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                if (url != null && !url.contains("chromewebdata")) {
                    hasNetworkError = false
                    lastTargetUrl = url
                }

                // In desktop mode, remove mobile viewport tags so desktop sites scale correctly
                if (isDesktopMode) {
                    val script = """
                        (function() {
                            var metas = document.querySelectorAll('meta[name="viewport"]');
                            for (var i = 0; i < metas.length; i++) {
                                metas[i].parentNode.removeChild(metas[i]);
                            }
                        })();
                    """.trimIndent()
                    view?.evaluateJavascript(script, null)
                }
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            // Fix: Handles Google Lens live camera and voice search permissions
            override fun onPermissionRequest(request: PermissionRequest) {
                runOnUiThread {
                    val requestedResources = request.resources
                    val permissionsNeeded = mutableListOf<String>()

                    for (resource in requestedResources) {
                        if (resource == PermissionRequest.RESOURCE_VIDEO_CAPTURE) {
                            if (checkSelfPermission(android.Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                                permissionsNeeded.add(android.Manifest.permission.CAMERA)
                            }
                        }
                        if (resource == PermissionRequest.RESOURCE_AUDIO_CAPTURE) {
                            if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                                permissionsNeeded.add(android.Manifest.permission.RECORD_AUDIO)
                            }
                        }
                    }

                    if (permissionsNeeded.isNotEmpty()) {
                        pendingPermissionRequest = request
                        requestPermissions(permissionsNeeded.toTypedArray(), PERMISSION_REQUEST_CODE)
                    } else {
                        request.grant(request.resources)
                    }
                }
            }

            // Fix: Handles file uploads and Google Lens image picker
            override fun onShowFileChooser(
                view: WebView?,
                filePathCallback: ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?
            ): Boolean {
                fileUploadCallback?.onReceiveValue(null)
                fileUploadCallback = filePathCallback

                val intent = fileChooserParams?.createIntent() ?: Intent(Intent.ACTION_GET_CONTENT).apply {
                    type = "*/*"
                    addCategory(Intent.CATEGORY_OPENABLE)
                }

                return try {
                    startActivityForResult(intent, FILE_CHOOSER_REQUEST_CODE)
                    true
                } catch (e: Exception) {
                    try {
                        val fallback = Intent(Intent.ACTION_GET_CONTENT).apply {
                            type = "*/*"
                            addCategory(Intent.CATEGORY_OPENABLE)
                        }
                        startActivityForResult(fallback, FILE_CHOOSER_REQUEST_CODE)
                        true
                    } catch (ex: Exception) {
                        fileUploadCallback?.onReceiveValue(null)
                        fileUploadCallback = null
                        false
                    }
                }
            }
        }

        webView.setDownloadListener { url, userAgent, contentDisposition, mimetype, _ ->
            handleDownload(url, userAgent, contentDisposition, mimetype)
        }
    }

    // Auto-detects data restore and automatically reloads without restarting the app
    private fun setupNetworkAutoReconnect() {
        connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()

        networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                runOnUiThread {
                    if (hasNetworkError) {
                        hasNetworkError = false
                        webView.postDelayed({
                            if (!isFinishing && !isDestroyed) {
                                webView.loadUrl(lastTargetUrl)
                            }
                        }, 500)
                    }
                }
            }
        }
        connectivityManager.registerNetworkCallback(request, networkCallback)
    }

    private fun toggleDesktopMode() {
        isDesktopMode = !isDesktopMode
        desktopButton.text = if (isDesktopMode) "M" else "D"

        val settings = webView.settings
        settings.userAgentString = if (isDesktopMode) desktopUserAgent else mobileUserAgent
        webView.setInitialScale(0)
        webView.reload()
    }

    private fun loadFormattedUrl(input: String) {
        val target = when {
            input.startsWith("http://") || input.startsWith("https://") -> input
            input.contains(".") && !input.contains(" ") -> "https://$input"
            else -> "https://www.google.com/search?q=${Uri.encode(input)}"
        }
        lastTargetUrl = target
        hasNetworkError = false
        webView.loadUrl(target)
    }

    private fun handleDownload(url: String, userAgent: String, contentDisposition: String, mimetype: String) {
        if (url.startsWith("blob:")) {
            val js = """
                (function() {
                    var xhr = new XMLHttpRequest();
                    xhr.open('GET', '$url', true);
                    xhr.responseType = 'blob';
                    xhr.onload = function() {
                        var reader = new FileReader();
                        reader.readAsDataURL(xhr.response);
                        reader.onloadend = function() {
                            window.AndroidBlobBridge.processBlobData(reader.result, '$mimetype');
                        };
                    };
                    xhr.send();
                })();
            """.trimIndent()
            webView.evaluateJavascript(js, null)
            return
        }

        val request = DownloadManager.Request(Uri.parse(url)).apply {
            setMimeType(mimetype)
            addRequestHeader("User-Agent", userAgent)
            setDescription("Downloading file...")
            setTitle(URLUtil.guessFileName(url, contentDisposition, mimetype))
            setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            setDestinationInExternalPublicDir(
                Environment.DIRECTORY_DOWNLOADS,
                URLUtil.guessFileName(url, contentDisposition, mimetype)
            )
        }
        val dm = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        dm.enqueue(request)
        Toast.makeText(this, "Download started", Toast.LENGTH_SHORT).show()
    }

    inner class AndroidBlobBridge {
        @JavascriptInterface
        fun processBlobData(base64Data: String, mimeType: String) {
            try {
                val pureBase64 = base64Data.substringAfter(",")
                val fileBytes = Base64.decode(pureBase64, Base64.DEFAULT)
                val ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(mimeType) ?: "bin"
                val file = File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                    "blob_${System.currentTimeMillis()}.$ext"
                )
                FileOutputStream(file).use { it.write(fileBytes) }
                runOnUiThread {
                    Toast.makeText(this@MainActivity, "Saved to Downloads", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(this@MainActivity, "Blob export failed", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST_CODE) {
            val granted = grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }
            if (granted) {
                pendingPermissionRequest?.grant(pendingPermissionRequest?.resources)
            } else {
                pendingPermissionRequest?.deny()
            }
            pendingPermissionRequest = null
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == FILE_CHOOSER_REQUEST_CODE) {
            if (fileUploadCallback == null) return

            var results: Array<Uri>? = null
            if (resultCode == Activity.RESULT_OK && data != null) {
                results = WebChromeClient.FileChooserParams.parseResult(resultCode, data)
                if (results == null || results.isEmpty()) {
                    val clipData = data.clipData
                    if (clipData != null && clipData.itemCount > 0) {
                        val uriList = ArrayList<Uri>()
                        for (i in 0 until clipData.itemCount) {
                            uriList.add(clipData.getItemAt(i).uri)
                        }
                        results = uriList.toTypedArray()
                    } else {
                        data.data?.let { uri ->
                            results = arrayOf(uri)
                        }
                    }
                }
            }

            fileUploadCallback?.onReceiveValue(results)
            fileUploadCallback = null
        }
    }

    private fun hideKeyboard() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(urlEditText.windowToken, 0)
    }

    // Battery optimization: pause timers and animations when screen off or minimized
    override fun onPause() {
        super.onPause()
        webView.onPause()
        webView.pauseTimers()
    }

    override fun onResume() {
        super.onResume()
        webView.onResume()
        webView.resumeTimers()
    }

    override fun onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack()
        } else {
            super.onBackPressed()
        }
    }

    override fun onDestroy() {
        try {
            connectivityManager.unregisterNetworkCallback(networkCallback)
        } catch (_: Exception) {}

        fileUploadCallback?.onReceiveValue(null)
        fileUploadCallback = null
        pendingPermissionRequest?.deny()
        pendingPermissionRequest = null

        // 0.00 B cache footprint on clean exit
        webView.stopLoading()
        webView.clearHistory()
        webView.clearCache(true)
        webView.destroy()
        cacheDir.deleteRecursively()
        super.onDestroy()
        Process.killProcess(Process.myPid())
    }
}
