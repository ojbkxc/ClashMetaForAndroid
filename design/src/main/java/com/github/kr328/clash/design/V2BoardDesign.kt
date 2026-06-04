package com.github.kr328.clash.design

import android.annotation.SuppressLint
import android.content.Context
import android.view.View
import android.webkit.WebChromeClient
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
                        AndroidBridge.onAuthData(auth, '', '');
                    }
                } catch(e) {}
            })();
        """.trimIndent()
        binding.webView.evaluateJavascript(js, null)
    }

    fun addJavascriptInterface(obj: Any, name: String) {
        binding.webView.addJavascriptInterface(obj, name)
    }

    fun showNoServerUrl() {
        binding.webView.visibility = View.GONE
        binding.emptyView.visibility = View.VISIBLE
    }

    fun destroyWebView() {
        binding.webView.destroy()
    }
}
