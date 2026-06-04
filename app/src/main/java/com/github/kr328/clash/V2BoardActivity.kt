package com.github.kr328.clash

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.http.SslError
import android.os.Bundle
import android.view.View
import android.webkit.*
import com.github.kr328.clash.design.V2BoardDesign
import com.github.kr328.clash.design.ui.ToastDuration
import com.github.kr328.clash.v2board.V2BoardSync
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext

class V2BoardActivity : BaseActivity<V2BoardDesign>() {

    private val sync by lazy { V2BoardSync.getInstance(this) }
    private var pageLoaded = false
    private var loginDetected = false
    private val isLoginMode: Boolean
        get() = intent.getBooleanExtra(EXTRA_IS_LOGIN, false)

    @SuppressLint("SetJavaScriptEnabled", "AddJavascriptInterface", "JavascriptInterface")
    override suspend fun main() {
        val design = V2BoardDesign(this)
        setContentDesign(design)

        val serverUrl = sync.getActiveUrl()
        if (serverUrl.isBlank()) {
            design.showNoServerUrl()
            while (isActive) {
                select<Unit> {
                    design.requests.onReceive {
                        when (it) {
                            V2BoardDesign.Request.Close -> finish()
                            V2BoardDesign.Request.Back -> {
                                if (design.canGoBack()) design.goBack() else finish()
                            }
                            V2BoardDesign.Request.Refresh -> design.reload()
                            V2BoardDesign.Request.OpenInBrowser -> {}
                        }
                    }
                }
            }
            return
        }

        val targetUrl = intent.getStringExtra(EXTRA_URL)
            ?: "$serverUrl/#/login"

        design.loadUrl(targetUrl)

        design.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                pageLoaded = false
                loginDetected = false
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                if (!pageLoaded && view != null) {
                    pageLoaded = true
                    injectAuthDetector(view)
                }
            }

            override fun onReceivedSslError(
                view: WebView?,
                handler: SslErrorHandler?,
                error: SslError?
            ) {
                handler?.proceed()
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?
            ) {
                if (request?.isForMainFrame == true) {
                    launch(Dispatchers.IO) {
                        val newDomain = sync.findWorkingDomain()
                        if (newDomain != null) {
                            val oldUrl = request.url?.toString() ?: ""
                            val path = oldUrl.substringAfter(sync.getActiveUrl(), "")
                            val newUrl = "$newDomain$path"
                            withContext(Dispatchers.Main) {
                                design.loadUrl(newUrl)
                            }
                        }
                    }
                }
            }
        }

        design.addJavascriptInterface(
            AuthBridge(this@V2BoardActivity),
            "AndroidBridge"
        )

        while (isActive) {
            select<Unit> {
                events.onReceive {
                    when (it) {
                        Event.ActivityStart -> {
                            if (pageLoaded) {
                                design.evaluateAuthCheck()
                            }
                        }
                        else -> Unit
                    }
                }
                design.requests.onReceive {
                    when (it) {
                        V2BoardDesign.Request.Close -> finish()
                        V2BoardDesign.Request.Back -> {
                            if (design.canGoBack()) {
                                design.goBack()
                            } else {
                                finish()
                            }
                        }
                        V2BoardDesign.Request.Refresh -> {
                            design.reload()
                        }
                        V2BoardDesign.Request.OpenInBrowser -> {
                            val url = design.getCurrentUrl()
                            if (url.isNotBlank()) {
                                try {
                                    startActivity(
                                        android.content.Intent(
                                            android.content.Intent.ACTION_VIEW,
                                            android.net.Uri.parse(url)
                                        )
                                    )
                                } catch (_: Exception) {}
                            }
                        }
                    }
                }
            }
        }
    }

    private fun injectAuthDetector(webView: WebView) {
        val js = """
            (function() {
                var _lastAuth = '';
                function checkAuth() {
                    try {
                        var auth = localStorage.getItem('auth_data') || '';
                        if (auth && auth !== _lastAuth) {
                            _lastAuth = auth;
                            var token = localStorage.getItem('token') || '';
                            var email = localStorage.getItem('email') || '';
                            AndroidBridge.onAuthData(auth, token, email);
                        }
                    } catch(e) {}
                }
                checkAuth();
                setInterval(checkAuth, 2000);

                var origSetItem = localStorage.setItem;
                localStorage.setItem = function(key, value) {
                    origSetItem.call(localStorage, key, value);
                    if (key === 'auth_data' || key === 'token') {
                        checkAuth();
                    }
                };

                var origRemoveItem = localStorage.removeItem;
                localStorage.removeItem = function(key) {
                    origRemoveItem.call(localStorage, key);
                    if (key === 'auth_data' || key === 'token') {
                        AndroidBridge.onLogout();
                    }
                };
            })();
        """.trimIndent()
        webView.evaluateJavascript(js, null)
    }

    class AuthBridge(private val activity: V2BoardActivity) {
        @JavascriptInterface
        fun onAuthData(authData: String, token: String, email: String) {
            if (authData.isBlank()) return
            if (activity.loginDetected) return

            activity.loginDetected = true
            activity.sync.session.save(authData, token, email)

            activity.launch {
                withContext(Dispatchers.Main) {
                    activity.design?.showToast(
                        "Login successful, syncing subscription...",
                        ToastDuration.Short
                    )
                }

                val syncResult = tryAutoSubscribe()

                withContext(Dispatchers.Main) {
                    if (syncResult.isSuccess) {
                        activity.design?.showToast(
                            syncResult.getOrNull() ?: "Sync completed",
                            ToastDuration.Short
                        )
                    } else {
                        activity.design?.showToast(
                            "Sync failed: ${syncResult.exceptionOrNull()?.message}",
                            ToastDuration.Long
                        )
                    }
                }

                if (activity.isLoginMode) {
                    withContext(Dispatchers.Main) {
                        activity.setResult(Activity.RESULT_OK)
                        activity.finish()
                    }
                }
            }
        }

        @JavascriptInterface
        fun onLogout() {
            activity.sync.session.clear()
            activity.loginDetected = false
            activity.launch {
                withContext(Dispatchers.Main) {
                    activity.design?.showToast(
                        "Logged out",
                        ToastDuration.Short
                    )
                }
            }
        }

        private suspend fun tryAutoSubscribe(): Result<String> {
            val result = activity.sync.fetchSubscribeUrl()
            if (result.isSuccess) {
                val subscribeUrl = result.getOrNull()!!
                return V2BoardAutoSync.sync(activity, subscribeUrl)
            }
            return result
        }
    }

    override fun finish() {
        if (isLoginMode && !loginDetected) {
            if (!sync.session.isLoggedIn) {
                setResult(Activity.RESULT_CANCELED)
            }
        }
        super.finish()
    }

    override fun onDestroy() {
        design?.destroyWebView()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_URL = "v2board_url"
        const val EXTRA_IS_LOGIN = "v2board_is_login"

        fun createIntent(context: Context, url: String? = null, isLogin: Boolean = false): Intent {
            return Intent(context, V2BoardActivity::class.java).apply {
                if (url != null) {
                    putExtra(EXTRA_URL, url)
                }
                if (isLogin) {
                    putExtra(EXTRA_IS_LOGIN, true)
                }
            }
        }

        private fun getBaseUrl(context: Context): String {
            val sync = V2BoardSync.getInstance(context)
            return sync.getActiveUrl()
        }

        fun openLogin(context: Context): Intent {
            val url = getBaseUrl(context)
            return createIntent(context, "$url/#/login", isLogin = true)
        }

        fun openDashboard(context: Context): Intent {
            val url = getBaseUrl(context)
            return createIntent(context, "$url/#/dashboard")
        }

        fun openPlans(context: Context): Intent {
            val url = getBaseUrl(context)
            return createIntent(context, "$url/#/plan")
        }

        fun openAbout(context: Context): Intent {
            val url = getBaseUrl(context)
            return createIntent(context, "$url/#/about")
        }

        fun openKnowledge(context: Context): Intent {
            val url = getBaseUrl(context)
            return createIntent(context, "$url/#/stage/knowledge")
        }
    }
}
