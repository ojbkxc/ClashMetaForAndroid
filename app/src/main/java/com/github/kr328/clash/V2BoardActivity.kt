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
import com.github.kr328.clash.common.log.Log
import com.github.kr328.clash.design.V2BoardDesign
import com.github.kr328.clash.design.ui.ToastDuration
import com.github.kr328.clash.util.withProfile
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
                // 页面开始加载时就注入 auth_data，确保前端 router guard 能读取
                if (view != null) {
                    restoreAuthToLocalStorage(view)
                }
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                if (!pageLoaded && view != null) {
                    pageLoaded = true
                    if (url != null && !url.startsWith("file://")) {
                        // 先注入 auth_data 到 localStorage，确保前端 router guard 能读取
                        restoreAuthToLocalStorage(view)
                        // 再注入登录检测
                        injectAuthDetector(view)

                        // 如果已登录但还没有触发过登录检测，检查是否需要自动同步
                        if (!loginDetected && sync.session.isLoggedIn) {
                            // 检查是否有活动配置
                            launch {
                                val hasActive = withProfile {
                                    val active = queryActive()
                                    active != null && active.imported
                                }
                                if (!hasActive) {
                                    // 没有配置，先获取后端地址，再触发同步
                                    loginDetected = true
                                    // 从页面获取后端地址
                                    if (sync.config.serverUrl.isBlank()) {
                                        view.evaluateJavascript(
                                            "(window.EnvConfig && window.EnvConfig.serverUrl) || window.location.origin || '';"
                                        ) { result ->
                                            val url = result?.removeSurrounding("\"") ?: ""
                                            if (url.isNotBlank() && url.startsWith("http")) {
                                                sync.config.serverUrl = url
                                                sync.resetApi()
                                            }
                                        }
                                    }
                                    withContext(Dispatchers.Main) {
                                        design?.showToast(
                                            "正在自动同步订阅...",
                                            ToastDuration.Short
                                        )
                                        fetchSubscribeViaJs()
                                    }
                                }
                            }
                        }
                    }
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

    // 将已保存的 auth_data 注入到 WebView 的 localStorage
    // 确保前端 router guard 能读取到登录状态
    private fun restoreAuthToLocalStorage(webView: WebView) {
        val authData = sync.session.authData
        if (authData.isBlank()) return

        val js = """
            (function() {
                try {
                    var key = '__AURORA__authorization';
                    var current = localStorage.getItem(key) || '';
                    if (!current || current !== '${authData.replace("'", "\\'")}') {
                        localStorage.setItem(key, '${authData.replace("'", "\\'")}');
                    }
                } catch(e) {}
            })();
        """.trimIndent()
        webView.evaluateJavascript(js, null)
    }

    private fun injectAuthDetector(webView: WebView) {
        val js = """
            (function() {
                var _detected = false;

                // 获取后端API地址：优先从EnvConfig，其次用当前页面地址
                function getApiUrl() {
                    try {
                        var envUrl = (window.EnvConfig && window.EnvConfig.serverUrl) || '';
                        if (envUrl && envUrl.indexOf('http') === 0) return envUrl;
                    } catch(e) {}
                    return window.location.origin || '';
                }

                function handleAuthData(authData, token) {
                    if (_detected || !authData) return;
                    _detected = true;
                    try {
                        AndroidBridge.onAuthData(authData, token || '', getApiUrl());
                    } catch(e) {}
                }

                // 1. Intercept fetch API responses
                var origFetch = window.fetch;
                if (origFetch) {
                    window.fetch = function() {
                        return origFetch.apply(this, arguments).then(function(response) {
                            try {
                                var url = (typeof arguments[0] === 'string') ? arguments[0] : (arguments[0].url || '');
                                if (url.indexOf('/passport/auth/login') !== -1 || url.indexOf('/passport/auth/token2Login') !== -1) {
                                    response.clone().json().then(function(data) {
                                        if (data && data.data && data.data.auth_data) {
                                            handleAuthData(data.data.auth_data, data.data.token);
                                        }
                                    }).catch(function() {});
                                }
                            } catch(e) {}
                            return response;
                        });
                    };
                }

                // 2. Intercept XMLHttpRequest responses
                var origOpen = XMLHttpRequest.prototype.open;
                var origSend = XMLHttpRequest.prototype.send;
                XMLHttpRequest.prototype.open = function(method, url) {
                    this._url = url;
                    return origOpen.apply(this, arguments);
                };
                XMLHttpRequest.prototype.send = function() {
                    this.addEventListener('load', function() {
                        try {
                            if (this._url && (this._url.indexOf('/passport/auth/login') !== -1 || this._url.indexOf('/passport/auth/token2Login') !== -1)) {
                                var data = JSON.parse(this.responseText);
                                if (data && data.data && data.data.auth_data) {
                                    handleAuthData(data.data.auth_data, data.data.token);
                                }
                            }
                        } catch(e) {}
                    });
                    return origSend.apply(this, arguments);
                };

                // 3. Intercept localStorage.setItem for authorization key
                // AuroraForV2board uses vue-ls with namespace __AURORA__
                var origSetItem = localStorage.setItem;
                localStorage.setItem = function(key, value) {
                    origSetItem.call(localStorage, key, value);
                    if ((key === 'authorization' || key === '__AURORA__authorization') && value) {
                        handleAuthData(value, '');
                    }
                };

                // 4. Poll localStorage for authorization key
                // AuroraForV2board stores auth_data with __AURORA__ namespace
                function checkExisting() {
                    try {
                        var auth = localStorage.getItem('__AURORA__authorization') || '';
                        if (!auth) {
                            auth = localStorage.getItem('authorization') || '';
                        }
                        if (!auth) {
                            auth = localStorage.getItem('auth_data') || '';
                        }
                        if (auth) {
                            handleAuthData(auth, '');
                        }
                    } catch(e) {}
                }
                checkExisting();
                setInterval(checkExisting, 2000);
            })();
        """.trimIndent()
        webView.evaluateJavascript(js, null)
    }

    class AuthBridge(private val activity: V2BoardActivity) {
        @JavascriptInterface
        fun onAuthData(authData: String, token: String, serverUrl: String) {
            if (authData.isBlank()) return
            if (activity.loginDetected) return

            activity.loginDetected = true
            activity.sync.session.save(authData, token, "")

            Log.d("V2Board: onAuthData called, serverUrl=$serverUrl")

            // 保存后端API地址
            if (serverUrl.isNotBlank()) {
                activity.sync.config.serverUrl = serverUrl
                activity.sync.resetApi()
                Log.d("V2Board: Saved backend URL: $serverUrl")
            }

            activity.launch {
                withContext(Dispatchers.Main) {
                    activity.design?.showToast(
                        "登录成功，正在同步订阅...",
                        ToastDuration.Short
                    )
                    // 用 JavaScript 调用前端的 API，自动带正确的 authorization header
                    activity.fetchSubscribeViaJs()
                }
            }
        }

        @JavascriptInterface
        fun onSubscribeUrl(subscribeUrl: String) {
            if (subscribeUrl.isBlank()) return
            Log.d("V2Board: onSubscribeUrl called, url=$subscribeUrl")

            activity.launch {
                val syncResult = V2BoardAutoSync.sync(activity, subscribeUrl)

                withContext(Dispatchers.Main) {
                    if (syncResult.isSuccess) {
                        activity.design?.showToast(
                            syncResult.getOrNull() ?: "同步完成",
                            ToastDuration.Short
                        )
                    } else {
                        val error = syncResult.exceptionOrNull()
                        val errorMsg = if (error?.message != null) {
                            error.message
                        } else {
                            error?.javaClass?.simpleName ?: "Unknown error"
                        }
                        activity.design?.showToast(
                            "同步失败: $errorMsg",
                            ToastDuration.Long
                        )
                    }
                }
            }
        }

        @JavascriptInterface
        fun onSubscribeError(error: String) {
            Log.w("V2Board: onSubscribeError: $error")
            activity.launch {
                withContext(Dispatchers.Main) {
                    activity.design?.showToast(
                        "获取订阅失败: $error",
                        ToastDuration.Long
                    )
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
                        "已退出登录",
                        ToastDuration.Short
                    )
                }
            }
        }

        @JavascriptInterface
        fun getAboutInfo(): String {
            return try {
                val pkg = activity.packageManager.getPackageInfo(activity.packageName, 0)
                "${pkg.versionName ?: "N/A"}|${activity.packageName}"
            } catch (_: Exception) {
                "N/A|N/A"
            }
        }

    }

    // 用 WebView JavaScript 调用前端 API 获取订阅URL
    // 前端的请求自动带正确的 authorization header，不会出现 401/403
    private fun fetchSubscribeViaJs() {
        val serverUrl = sync.config.serverUrl.ifBlank { sync.getActiveUrl() }
        val js = """
            (function() {
                try {
                    var auth = localStorage.getItem('__AURORA__authorization') || '';
                    if (!auth) {
                        auth = localStorage.getItem('authorization') || '';
                    }
                    if (!auth) {
                        AndroidBridge.onSubscribeError('未找到登录凭证');
                        return;
                    }
                    // 使用后端地址，如果为空则用当前页面地址
                    var baseUrl = '${serverUrl.replace("'", "\\'")}' || window.location.origin;
                    var apiUrl = baseUrl + '/api/v1/user/getSubscribe';
                    fetch(apiUrl, {
                        method: 'GET',
                        headers: {
                            'authorization': auth,
                            'Accept': 'application/json'
                        }
                    }).then(function(r) { return r.json(); })
                    .then(function(json) {
                        if (json && json.data) {
                            var url = json.data.subscribe_url || '';
                            var token = json.data.token || '';
                            var finalUrl = url;
                            if (!finalUrl && token) {
                                finalUrl = baseUrl + '/api/v1/client/subscribe?token=' + token;
                            }
                            if (finalUrl) {
                                AndroidBridge.onSubscribeUrl(finalUrl);
                            } else {
                                AndroidBridge.onSubscribeError('服务器未返回订阅地址');
                            }
                        } else {
                            var msg = json.message || json.msg || JSON.stringify(json);
                            AndroidBridge.onSubscribeError('服务器返回异常: ' + msg);
                        }
                    }).catch(function(e) {
                        AndroidBridge.onSubscribeError('请求失败: ' + (e.message || e));
                    });
                } catch(e) {
                    AndroidBridge.onSubscribeError('脚本错误: ' + e.message);
                }
            })();
        """.trimIndent()
        design?.evaluateJavascript(js)
    }

    override fun finish() {
        if (isLoginMode && !loginDetected) {
            setResult(Activity.RESULT_CANCELED)
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
            val sync = V2BoardSync.getInstance(context)
            val url = getBaseUrl(context)
            // 已登录时打开用户仪表盘，未登录时打开登录页
            return if (sync.session.isLoggedIn) {
                createIntent(context, "$url/#/stage", isLogin = false)
            } else {
                createIntent(context, "$url/#/login", isLogin = true)
            }
        }

        fun openAbout(context: Context): Intent {
            return createIntent(context, "file:///android_asset/about.html")
        }

        fun openKnowledge(context: Context): Intent {
            val url = getBaseUrl(context)
            return createIntent(context, "$url/#/stage/knowledge")
        }
    }
}
