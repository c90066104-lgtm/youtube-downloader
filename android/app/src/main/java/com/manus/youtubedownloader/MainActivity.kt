package com.manus.youtubedownloader

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.os.Bundle
import android.view.View
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebResourceErrorCompat
import androidx.webkit.WebViewClientCompat

class MainActivity : AppCompatActivity() {
    private lateinit var webView: WebView
    private lateinit var statusText: TextView
    private lateinit var startupProgress: ProgressBar
    private lateinit var retryButton: Button
    private lateinit var proxy: LocalCorsProxy
    private lateinit var downloadBridge: NativeDownloadBridge
    private var webViewConfigured = false
    private var proxyRunning = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        webView = findViewById(R.id.webView)
        statusText = findViewById(R.id.statusText)
        startupProgress = findViewById(R.id.startupProgress)
        retryButton = findViewById(R.id.retryButton)
        retryButton.setOnClickListener { retryInitialization() }

        downloadBridge = NativeDownloadBridge(applicationContext)
        if (!startLocalProxy()) return
        configureWebView()
        webViewConfigured = true
        openWebsite()
    }

    private fun startLocalProxy(): Boolean {
        return try {
            proxy = LocalCorsProxy()
            proxy.start(SOCKET_TIMEOUT_MS, false)
            proxyRunning = true
            statusText.text = "Proxy local ativo em 127.0.0.1:${proxy.listeningPort}"
            true
        } catch (e: Exception) {
            statusText.text = "Falha ao iniciar o proxy local: ${e.message ?: "erro desconhecido"}"
            startupProgress.visibility = View.GONE
            retryButton.visibility = View.VISIBLE
            false
        }
    }

    private fun retryInitialization() {
        if (!proxyRunning && !startLocalProxy()) return
        if (!webViewConfigured) {
            configureWebView()
            webViewConfigured = true
        }
        openWebsite()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun configureWebView() {
        val assetLoader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .addPathHandler("/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(false)
            mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            loadsImagesAutomatically = true
            databaseEnabled = false
        }
        webView.addJavascriptInterface(AndroidProxyBridge(proxy), "AndroidProxy")
        webView.addJavascriptInterface(downloadBridge, "AndroidDownloader")
        webView.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(message: ConsoleMessage): Boolean {
                if (message.messageLevel() == ConsoleMessage.MessageLevel.ERROR) {
                    android.util.Log.e(TAG, "WebView ${message.sourceId()}:${message.lineNumber()}: ${message.message()}")
                }
                return true
            }
        }
        webView.webViewClient = object : WebViewClientCompat() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest) =
                assetLoader.shouldInterceptRequest(request.url)

            @Suppress("DEPRECATION")
            override fun shouldInterceptRequest(view: WebView, url: String) =
                assetLoader.shouldInterceptRequest(android.net.Uri.parse(url))

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                // Keep the native bridges exposed only while the official HTTPS site is loaded.
                return !isTrustedSiteUrl(request.url.toString())
            }

            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                startupProgress.visibility = View.VISIBLE
                retryButton.visibility = View.GONE
                statusText.text = "Carregando site… · proxy em 127.0.0.1:${proxy.listeningPort}"
            }

            override fun onPageFinished(view: WebView, url: String) {
                if (!isTrustedSiteUrl(url)) return
                startupProgress.visibility = View.GONE
                retryButton.visibility = View.GONE
                statusText.text = "Site pronto · proxy local ativo em 127.0.0.1:${proxy.listeningPort}"
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceErrorCompat) {
                if (request.isForMainFrame) {
                    startupProgress.visibility = View.GONE
                    statusText.text = "Não foi possível abrir o site: ${error.description}"
                    retryButton.visibility = View.VISIBLE
                }
            }
        }
    }

    private fun openWebsite() {
        webView.loadUrl(SITE_URL)
    }

    private fun isTrustedSiteUrl(value: String): Boolean {
        val uri = android.net.Uri.parse(value)
        return uri.scheme == "https" && uri.host == SITE_HOST
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (::webView.isInitialized && webView.canGoBack()) webView.goBack() else super.onBackPressed()
    }

    override fun onDestroy() {
        if (::proxy.isInitialized) proxy.stop()
        proxyRunning = false
        if (::downloadBridge.isInitialized) downloadBridge.close()
        if (::webView.isInitialized) {
            webView.removeJavascriptInterface("AndroidProxy")
            webView.removeJavascriptInterface("AndroidDownloader")
            webView.stopLoading()
            webView.destroy()
        }
        super.onDestroy()
    }

    private class AndroidProxyBridge(private val proxy: LocalCorsProxy) {
        @android.webkit.JavascriptInterface
        fun getProxyUrl(): String = proxy.localUrl
    }

    companion object {
        private const val TAG = "YouTubeDownloader"
        private const val SOCKET_TIMEOUT_MS = 5_000
        private const val SITE_HOST = "youtube-downloader-dmf.pages.dev"
        private const val SITE_URL = "https://$SITE_HOST/"
    }
}
