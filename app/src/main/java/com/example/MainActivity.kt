package com.example

import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.ComponentCallbacks2
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Process
import android.os.StrictMode
import android.provider.MediaStore
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

    private val fileChooserLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            val dataUri = result.data?.data
            if (dataUri != null) {
                // User picked an image from Gallery
                fileUploadCallback?.onReceiveValue(arrayOf(dataUri))
            } else {
                // User took a photo with Camera
                val photoFile = File(cacheDir, "camera_capture.jpg")
                if (photoFile.exists() && photoFile.length() > 0) {
                    fileUploadCallback?.onReceiveValue(arrayOf(Uri.fromFile(photoFile)))
                } else {
                    val clipData = result.data?.clipData
                    if (clipData != null && clipData.itemCount > 0) {
                        val uris = Array(clipData.itemCount) { i -> clipData.getItemAt(i).uri }
                        fileUploadCallback?.onReceiveValue(uris)
                    } else {
                        val results = WebChromeClient.FileChooserParams.parseResult(result.resultCode, result.data)
                        fileUploadCallback?.onReceiveValue(results)
                    }
                }
            }
        } else {
            fileUploadCallback?.onReceiveValue(null)
        }
        fileUploadCallback = null
    }

    private val blockedDomains = hashSetOf(
        "doubleclick.net", "googlesyndication.com", "google-analytics.com",
        "adservice.google.com", "facebook.net", "scorecardresearch.com",
        "criteo.com", "taboola.com", "outbrain.com", "amazon-adsystem.com"
    )

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Bypass file URI checks on Android 7 to allow camera capture without complex providers
        val policy = StrictMode.VmPolicy.Builder()
        StrictMode.setVmPolicy(policy.build())

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

        defaultUserAgent = s.userAgentString

        s.offscreenPreRaster = false
        s.mediaPlaybackRequiresUserGesture = true
        s.cacheMode = WebSettings.LOAD_NO_CACHE

        val cookieManager = CookieManager.getInstance()
        cookieManager.setAcceptCookie(true)
        cookieManager.setAcceptThirdPartyCookies(webView, false)

        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                if (newProgress < 100) {
                    progressBar.visibility = View.VISIBLE
                    progressBar.progress = newProgress
                } else {
                    progressBar.visibility = View.GONE
                }
            }

            override fun onPermissionRequest(request: PermissionRequest?) {
                runOnUiThread {
                    request?.grant(request.resources)
                }
            }

            override fun onShowFileChooser(
                webView: WebView?,
                filePathCallback: ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?
            ): Boolean {
                fileUploadCallback?.onReceiveValue(null)
                fileUploadCallback = filePathCallback

                val photoFile = File(cacheDir, "camera_capture.jpg")
                if (photoFile.exists()) photoFile.delete()
                cameraOutputUri = Uri.fromFile(photoFile)

                val captureIntent = Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
                    putExtra(MediaStore.EXTRA_OUTPUT, cameraOutputUri)
                }

                val contentIntent = fileChooserParams?.createIntent() ?: Intent(Intent.ACTION_GET_CONTENT).apply {
                    type = "*/*"
                    addCategory(Intent.CATEGORY_OPENABLE)
                }

                val chooserIntent = Intent(Intent.ACTION_CHOOSER).apply {
                    putExtra(Intent.EXTRA_INTENT, contentIntent)
                    putExtra(Intent.EXTRA_TITLE, "Select Camera or File")
                    if (captureIntent.resolveActivity(packageManager) != null) {
                        putExtra(Intent.EXTRA_INITIAL_INTENTS, arrayOf(captureIntent))
                    }
                }

                try {
                    fileChooserLauncher.launch(chooserIntent)
                } catch (_: Exception) {
                    fileUploadCallback?.onReceiveValue(null)
                    fileUploadCallback = null
                    return false
                }
                return true
            }
        }

        webView.setDownloadListener { url, userAgent, contentDisposition, mimetype, _ ->
            if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P &&
                checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
            ) {
                requestPermissions(arrayOf(android.Manifest.permission.WRITE_EXTERNAL_STORAGE), 102)
                return@setDownloadListener
            }

            try {
                val fileName = URLUtil.guessFileName(url, contentDisposition, mimetype)
                val request = DownloadManager.Request(Uri.parse(url)).apply {
                    setMimeType(mimetype)
                    addRequestHeader("User-Agent", userAgent)
                    val cookies = CookieManager.getInstance().getCookie(url)
                    if (cookies != null) {
                        addRequestHeader("Cookie", cookies)
                    }
                    setDescription("Downloading file")
                    setTitle(fileName)
                    setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                    setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
                    allowScanningByMediaScanner()
                }
                val dm = getSystemService(DOWNLOAD_SERVICE) as DownloadManager
                dm.enqueue(request)
                Toast.makeText(this@MainActivity, "Download started: $fileName", Toast.LENGTH_SHORT).show()
            } catch (_: Exception) {
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                } catch (_: Exception) {}
            }
        }

        webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                urlBar.setText(url)
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                // Force desktop viewport to prevent responsive sites from collapsing into mobile layout
                if (isDesktopMode) {
                    view?.evaluateJavascript(
                        """
                        (function() {
                            var meta = document.querySelector('meta[name="viewport"]');
                            if (!meta) {
                                meta = document.createElement('meta');
                                meta.name = 'viewport';
                                document.head.appendChild(meta);
                            }
                            meta.setAttribute('content', 'width=1100');
                        })();
                        """.trimIndent(),
                        null
                    )
                }
            }

            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                var url = request?.url?.toString() ?: return false

                // Prevent mobile subdomain redirects while desktop mode is active
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
                        Intent.parseUri(url, Intent.URI_INTENT_SCHEME)
                    } else {
                        Intent(Intent.ACTION_VIEW, Uri.parse(url))
                    }
                    if (intent.resolveActivity(packageManager) != null) {
                        startActivity(intent)
                        return true
                    }
                    val fallback = intent.getStringExtra("browser_fallback_url")
                    if (!fallback.isNullOrEmpty()) {
                        view?.loadUrl(fallback)
                        return true
                    }
                } catch (_: Exception) {}
                return true
            }

            override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                val url = request?.url ?: return null
                val host = url.host ?: ""

                if (blockedDomains.any { host.contains(it) }) {
                    return WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
                }

                return super.shouldInterceptRequest(view, request)
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
