package com.example

import android.annotation.SuppressLint
import android.app.Activity
import android.app.DownloadManager
import android.content.Context
import android.content.Intent
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
import android.view.View
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
    private val FILE_CHOOSER_REQUEST_CODE = 1001

    private var lastFailedUrl: String? = null
    private var hasNetworkError = false
    private lateinit var connectivityManager: ConnectivityManager
    private lateinit var networkCallback: ConnectivityManager.NetworkCallback

    private val desktopUserAgent =
        "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36"

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
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

        webView.loadUrl("https://www.google.com")
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun configureWebView() {
        val settings = webView.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true // Fixes Google AI Overview / client SPA routing
        settings.databaseEnabled = true
        settings.useWideViewPort = true
        settings.loadWithOverviewMode = true
        settings.setSupportZoom(true)
        settings.builtInZoomControls = true
        settings.displayZoomControls = false

        // Clean user agent: strip embedded WebView flags that break Google AI features
        val rawUa = settings.userAgentString
        val cleanMobileUa = rawUa.replace("; wv", "").replace(Regex("Version/\\d+\\.\\d+\\s?"), "")
        settings.userAgentString = cleanMobileUa

        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(webView, true)
        }

        webView.addJavascriptInterface(AndroidBlobBridge(), "AndroidBlobBridge")

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                return false
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?
            ) {
                if (request?.isForMainFrame == true) {
                    hasNetworkError = true
                    lastFailedUrl = request.url.toString()
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
                }

                // Fix desktop mode scaling overlap
                if (isDesktopMode) {
                    val script = """
                        (function() {
                            var meta = document.querySelector('meta[name="viewport"]');
                            if (!meta) {
                                meta = document.createElement('meta');
                                meta.name = 'viewport';
                                document.head.appendChild(meta);
                            }
                            meta.content = 'width=1280, initial-scale=' + (screen.width / 1280.0);
                        })();
                    """.trimIndent()
                    view?.evaluateJavascript(script, null)
                }
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
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
                    startActivityForResult(intent, FILE_CHOOSER_REQUEST_CODE)
                } catch (e: Exception) {
                    fileUploadCallback = null
                    return false
                }
                return true
            }
        }

        webView.setDownloadListener { url, userAgent, contentDisposition, mimetype, _ ->
            handleDownload(url, userAgent, contentDisposition, mimetype)
        }
    }

    private fun setupNetworkAutoReconnect() {
        connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()

        networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                runOnUiThread {
                    if (hasNetworkError && !lastFailedUrl.isNullOrEmpty()) {
                        webView.loadUrl(lastFailedUrl!!)
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
        if (isDesktopMode) {
            settings.userAgentString = desktopUserAgent
        } else {
            val rawUa = WebSettings.getDefaultUserAgent(this)
            settings.userAgentString = rawUa.replace("; wv", "").replace(Regex("Version/\\d+\\.\\d+\\s?"), "")
        }
        webView.reload()
    }

    private fun loadFormattedUrl(input: String) {
        val target = when {
            input.startsWith("http://") || input.startsWith("https://") -> input
            input.contains(".") && !input.contains(" ") -> "https://$input"
            else -> "https://www.google.com/search?q=${Uri.encode(input)}"
        }
        hasNetworkError = false
        lastFailedUrl = target
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

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == FILE_CHOOSER_REQUEST_CODE) {
            val results = if (resultCode == Activity.RESULT_OK && data != null) {
                data.data?.let { arrayOf(it) }
            } else null
            fileUploadCallback?.onReceiveValue(results)
            fileUploadCallback = null
        }
    }

    private fun hideKeyboard() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(urlEditText.windowToken, 0)
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

        // Recursive clean exit: 0.00 B cache footprint
        webView.stopLoading()
        webView.clearHistory()
        webView.clearCache(true)
        webView.destroy()
        cacheDir.deleteRecursively()
        super.onDestroy()
        Process.killProcess(Process.myPid())
    }
}
