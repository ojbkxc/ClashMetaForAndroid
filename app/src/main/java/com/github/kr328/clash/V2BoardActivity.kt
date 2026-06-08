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
import com.github.kr328.clash.v2board.SyncLog
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
                // 不在 onPageStarted 注入，DOM 未就绪会失败
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                if (!pageLoaded && view != null) {
                    pageLoaded = true
                    if (url != null && !url.startsWith("file://")) {
                        // 先检查 localStorage 是否已有 auth_data（前端可能已刷新）
                        // 如果有，保存到本地；如果没有，注入保存的值
                        syncLocalStorageWithBackend(view)
                        // 再注入登录检测
                        injectAuthDetector(view)

                        // 如果已登录但页面停在登录页，强制跳转到仪表盘
                        // evaluateJavascript 是异步的，router guard 可能先于注入执行
                        if (sync.session.isLoggedIn && url.contains("/login")) {
                            val serverUrl = sync.config.serverUrl.ifBlank { sync.getActiveUrl() }
                            val dashboardUrl = "$serverUrl/#/stage"
                            SyncLog.add("已登录但页面在登录页，跳转到仪表盘")
                            view.loadUrl(dashboardUrl)
                            return
                        }

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

    // 双向同步：优先使用 localStorage 中的值（前端可能已刷新 token）
    // 只有 localStorage 为空时才注入保存的旧值
    private fun syncLocalStorageWithBackend(webView: WebView) {
        val savedAuth = sync.session.authData
        val js = """
            (function() {
                try {
                    var keys = ['__AURORA__authorization', 'authorization', 'auth_data'];
                    var existing = '';
                    for (var i = 0; i < keys.length; i++) {
                        var val = localStorage.getItem(keys[i]);
                        if (val) {
                            // vue-ls 格式: {"value":"xxx"}
                            try {
                                var parsed = JSON.parse(val);
                                if (parsed && typeof parsed === 'object' && parsed.value) {
                                    existing = parsed.value;
                                } else if (typeof parsed === 'string') {
                                    existing = parsed;
                                } else {
                                    existing = val;
                                }
                            } catch(e) { existing = val; }
                            if (existing) break;
                        }
                    }
                    if (existing && typeof existing === 'string' && existing.length > 10) {
                        // localStorage 有值，回传给 native 保存
                        AndroidBridge.onLocalStorageAuth(existing);
                    } else if ('${savedAuth.replace("'", "\\'")}'.length > 10) {
                        // localStorage 为空，注入保存的值
                        var key = '__AURORA__authorization';
                        var value = JSON.stringify({value: '${savedAuth.replace("'", "\\'")}'});
                        localStorage.setItem(key, value);
                    }
                } catch(e) { AndroidBridge.log('syncAuth error: ' + e.message); }
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
                // vue-ls 用 JSON.stringify({value: v}) 存储
                var origSetItem = localStorage.setItem;
                localStorage.setItem = function(key, value) {
                    origSetItem.call(localStorage, key, value);
                    if ((key === 'authorization' || key === '__AURORA__authorization') && value) {
                        // vue-ls 格式: {"value":"xxx"}，需要提取 .value
                        var cleanValue = value;
                        try {
                            var parsed = JSON.parse(value);
                            if (parsed && typeof parsed === 'object' && parsed.value) {
                                cleanValue = parsed.value;
                            } else if (typeof parsed === 'string') {
                                cleanValue = parsed;
                            }
                        } catch(e) {}
                        if (cleanValue && typeof cleanValue === 'string') {
                            handleAuthData(cleanValue, '');
                        }
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
                            // vue-ls 格式: {"value":"xxx"}，需要提取 .value
                            try {
                                var parsed = JSON.parse(auth);
                                if (parsed && typeof parsed === 'object' && parsed.value) {
                                    auth = parsed.value;
                                } else if (typeof parsed === 'string') {
                                    auth = parsed;
                                }
                            } catch(e) {}
                            if (auth && typeof auth === 'string') {
                                handleAuthData(auth, '');
                            }
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

            // 清理 auth_data：处理 vue-ls 的 {"value":"JWT"} 格式
            var cleanAuth = authData.trim().removeSurrounding("\"").removeSurrounding("'")
            // 如果仍然是 JSON 对象格式，尝试提取 value 字段
            if (cleanAuth.startsWith("{") && cleanAuth.contains("\"value\"")) {
                try {
                    val json = org.json.JSONObject(cleanAuth)
                    cleanAuth = json.optString("value", cleanAuth)
                } catch (_: Exception) {}
            }
            if (cleanAuth.isBlank()) return

            // 如果已登录且 auth_data 相同，不重复触发同步
            val existingAuth = activity.sync.session.authData
            if (existingAuth.isNotBlank() && existingAuth == cleanAuth && activity.loginDetected) {
                activity.loginDetected = true
                SyncLog.add("已登录，跳过重复同步")
                return
            }

            activity.loginDetected = true
            activity.sync.session.save(cleanAuth, token, "")

            Log.d("V2Board: onAuthData called")
            SyncLog.add("登录成功，获取到认证信息")
            SyncLog.add("后端地址: ${SyncLog.maskUrl(serverUrl)}")
            SyncLog.add("auth_data: ${cleanAuth.take(30)}...")

            // 保存后端API地址
            if (serverUrl.isNotBlank()) {
                activity.sync.config.serverUrl = serverUrl
                activity.sync.resetApi()
                Log.d("V2Board: Saved backend URL")
            }

            activity.launch {
                withContext(Dispatchers.Main) {
                    activity.design?.showToast(
                        "登录成功，3秒后自动同步订阅...",
                        ToastDuration.Short
                    )
                }
                SyncLog.add("等待3秒后获取订阅...")

                // 延迟3秒，确保前端 localStorage 已写入 auth_data
                kotlinx.coroutines.delay(3000)

                withContext(Dispatchers.Main) {
                    // 用 JavaScript 调用前端的 API，自动带正确的 authorization header
                    SyncLog.add("通过JS获取订阅URL...")
                    activity.fetchSubscribeViaJs()
                }
            }
        }

        @JavascriptInterface
        fun onSubscribeUrl(subscribeUrl: String) {
            if (subscribeUrl.isBlank()) return
            Log.d("V2Board: onSubscribeUrl called")
            SyncLog.add("获取到订阅URL: ${SyncLog.maskUrl(subscribeUrl)}")

            activity.launch {
                Log.d("V2Board: Starting sync")
                val syncResult = V2BoardAutoSync.sync(activity, subscribeUrl)

                withContext(Dispatchers.Main) {
                    if (syncResult.isSuccess) {
                        Log.d("V2Board: Sync succeeded: ${syncResult.getOrNull()}")
                        SyncLog.add("同步成功: ${syncResult.getOrNull()}")
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
                        Log.w("V2Board: Sync failed: $errorMsg")
                        SyncLog.add("同步失败: $errorMsg")
                        activity.design?.showToast(
                            "同步失败: $errorMsg",
                            ToastDuration.Long
                        )
                    }
                }
            }
        }

        @JavascriptInterface
        fun log(message: String) {
            Log.d("V2Board JS: $message")
            SyncLog.add("JS: $message")
        }

        @JavascriptInterface
        fun onLocalStorageAuth(authData: String) {
            if (authData.isBlank() || authData.length < 10) return
            val existing = activity.sync.session.authData
            // 只在值不同时更新，避免不必要的写入
            if (existing != authData) {
                activity.sync.session.save(authData, "", "")
                Log.d("V2Board: Saved refreshed auth_data from localStorage")
                SyncLog.add("检测到前端刷新了认证，已同步保存")
            }
            activity.loginDetected = true
        }

        @JavascriptInterface
        fun onSubscribeError(error: String) {
            Log.w("V2Board: onSubscribeError: $error")
            SyncLog.add("获取订阅失败: $error")
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
    // 完全模拟前端 AuroraForV2board 的逻辑
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
                    // vue-ls 用 JSON.stringify({value: v}) 存储，需要解析提取 .value
                    try {
                        var parsed = JSON.parse(auth);
                        if (parsed && typeof parsed === 'object' && parsed.value) {
                            auth = parsed.value;
                        } else if (typeof parsed === 'string') {
                            auth = parsed;
                        }
                    } catch(e) {}
                    if (!auth || typeof auth !== 'string') {
                        AndroidBridge.onSubscribeError('登录凭证格式错误');
                        return;
                    }
                    // API 使用后端地址（与前端 n["l"] 一致）
                    var baseUrl = '${serverUrl.replace("'", "\\'")}' || window.location.origin;
                    // 后端 User middleware: request->input('auth_data') ?? request->header('authorization')
                    // 用 query 参数 auth_data 比 header 更可靠（避免 CDN/代理剥离 header）
                    var apiUrl = baseUrl + '/api/v1/user/getSubscribe?auth_data=' + encodeURIComponent(auth);
                    fetch(apiUrl, {
                        method: 'GET',
                        headers: {
                            'Accept': 'application/json'
                        }
                    }).then(function(r) { return r.json(); })
                    .then(function(json) {
                        if (json && json.data) {
                            var subscribeUrl = json.data.subscribe_url || '';
                            var token = json.data.token || '';
                            // 使用 baseUrl 而不是 location.origin 来构造订阅 URL
                            var finalUrl = subscribeUrl || (baseUrl + '/api/v1/client/subscribe?token=' + token);
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
