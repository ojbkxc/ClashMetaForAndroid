package com.github.kr328.clash.design

import android.annotation.SuppressLint
import android.content.Context
import android.view.View
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import com.github.kr328.clash.design.databinding.DesignV2boardWebviewBinding
import com.github.kr328.clash.design.util.applyFrom
import com.github.kr328.clash.design.util.layoutInflater
import com.github.kr328.clash.design.util.root

class V2BoardDesign(context: Context) : Design<V2BoardDesign.Request>(context) {
    sealed class Request {
        object Close : Request()
        object Back : Request()
        object Refresh : Request()
        object OpenInBrowser : Request()
    }

    private val binding = DesignV2boardWebviewBinding
        .inflate(context.layoutInflater, context.root, false)

    private val titleView: android.widget.TextView? =
        binding.root.findViewById(R.id.activity_bar_title_view)

    override val root: View
        get() = binding.root

    var webViewClient: WebViewClient?
        get() = binding.webView.webViewClient
        set(value) {
            binding.webView.webViewClient = value ?: WebViewClient()
        }

    init {
        binding.self = this

        binding.activityBarLayout.applyFrom(context)

        binding.root.findViewById<android.widget.ImageView>(R.id.btn_open_in_browser)?.setOnClickListener {
            requests.trySend(Request.OpenInBrowser)
        }

        binding.webView.settings.apply {
            @SuppressLint("SetJavaScriptEnabled")
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false
            loadWithOverviewMode = true
            useWideViewPort = true
            cacheMode = android.webkit.WebSettings.LOAD_DEFAULT
            mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
<<<<<<< Updated upstream
            // 配置 localStorage 和数据库的持久化路径
            // 注意：AppCache 相关 API 已废弃，localStorage 通过 domStorageEnabled=true 自动启用
            @Suppress("DEPRECATION")
            if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.R) {
                val databasePath = context.applicationContext.getDir("v2board_webview", Context.MODE_PRIVATE).absolutePath
                setDatabasePath(databasePath)
            }
        }
        
        // Android 11+ WebView 数据目录配置（如果需要隔离数据）
        // 注意：setDataDirectorySuffix 是进程级别的设置，需要在 WebView 创建前设置
        // 这里仅做配置提示，实际使用需要在 Application 中提前设置

        // 持久化 Cookie - 允许第三方 cookies（V2Board 服务器）
        android.webkit.CookieManager.getInstance().apply {
            setAcceptCookie(true)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
                setAcceptThirdPartyCookies(binding.webView, true)
            }
=======
>>>>>>> Stashed changes
        }

        binding.webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                binding.progressBar.progress = newProgress
                binding.progressBar.visibility =
                    if (newProgress in 0..99) View.VISIBLE else View.GONE
            }

            override fun onReceivedTitle(view: WebView?, title: String?) {
                if (!title.isNullOrBlank() && title != "about:blank") {
                    titleView?.text = title
                }
            }
        }

        binding.webView.setDownloadListener { url, _, _, _, _ ->
            try {
                val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))
                context.startActivity(intent)
            } catch (_: Exception) {}
        }
    }

    fun loadUrl(url: String) {
        binding.webView.loadUrl(url)
    }

    fun canGoBack(): Boolean = binding.webView.canGoBack()

    fun goBack() {
        binding.webView.goBack()
    }

    fun reload() {
        binding.webView.reload()
    }

    fun getCurrentUrl(): String {
        return binding.webView.url ?: ""
    }

    fun evaluateAuthCheck() {
        val js = """
            (function() {
                try {
                    // 获取后端API地址
                    function getApiUrl() {
                        try {
                            var envUrl = (window.EnvConfig && window.EnvConfig.serverUrl) || '';
                            if (envUrl && envUrl.indexOf('http') === 0) return envUrl;
                        } catch(e) {}
                        return window.location.origin || '';
                    }

                    // AuroraForV2board uses vue-ls with namespace __AURORA__
                    var auth = localStorage.getItem('__AURORA__authorization') || '';
                    if (!auth) {
                        auth = localStorage.getItem('authorization') || '';
                    }
                    if (!auth) {
                        auth = localStorage.getItem('auth_data') || '';
                    }
                    if (!auth) {
                        var cookies = document.cookie.split(';');
                        for (var i = 0; i < cookies.length; i++) {
                            var c = cookies[i].trim();
                            if (c.indexOf('auth_data=') === 0) {
                                auth = c.substring('auth_data='.length);
                                break;
                            }
                        }
                    }
                    if (auth) {
                        AndroidBridge.onAuthData(auth, '', getApiUrl());
                    }
                } catch(e) {}
            })();
        """.trimIndent()
        binding.webView.evaluateJavascript(js, null)
    }

    fun addJavascriptInterface(obj: Any, name: String) {
        binding.webView.addJavascriptInterface(obj, name)
    }

    fun evaluateJavascript(script: String) {
        binding.webView.evaluateJavascript(script, null)
    }

    fun showNoServerUrl() {
        binding.webView.visibility = View.GONE
        binding.emptyView.visibility = View.VISIBLE
    }

    // 不销毁 WebView，保留 cookie 和 localStorage 避免重复登录
    fun destroyWebView() {
<<<<<<< Updated upstream
    }

    fun removeJavascriptInterface(name: String) {
        binding.webView.removeJavascriptInterface(name)
=======
        binding.webView.destroy()
>>>>>>> Stashed changes
    }
}
