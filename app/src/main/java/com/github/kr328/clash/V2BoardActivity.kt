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
    private var destroyed = false
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
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                return false
            }
            
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                pageLoaded = false
                // 在页面开始加载时注入 localStorage
                // 确保前端 router guard 检查时能读取到登录凭证
                if (view != null && url != null && !url.startsWith("file://")) {
                    // 强制注入保存的 auth_data，不检查 localStorage 是否已有值
                    // 这确保了第二次进入时不需要重新登录
                    forceInjectLocalStorage(view)
                }
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                if (!pageLoaded && view != null) {
                    pageLoaded = true
                    if (url != null && !url.startsWith("file://")) {
                        // localStorage 已在 onPageStarted 中注入，这里只注入登录检测
                        injectAuthDetector(view)
                        
                        // 页面加载完成后再次注入 localStorage（防止 onPageStarted 的注入时机太早）
                        // 这是一个额外的保险措施
                        if (sync.session.isLoggedIn) {
                            forceInjectLocalStorage(view)
                        }

                        // 如果已登录但页面停在登录页，强制跳转到仪表盘
                        if (sync.session.isLoggedIn && url.contains("/login")) {
                            val serverUrl = sync.config.serverUrl.ifBlank { sync.getActiveUrl() }
                            val dashboardUrl = "$serverUrl/#/stage"
                            SyncLog.add("已登录但页面在登录页，跳转到仪表盘")
                            view.loadUrl(dashboardUrl)
                            return
                        }

                        // 如果已登录但还没有触发过登录检测，立即获取订阅信息
                        if (!loginDetected && sync.session.isLoggedIn) {
                            loginDetected = true
                            launch {
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
                                        "正在获取订阅信息...",
                                        ToastDuration.Short
                                    )
                                    fetchSubscribeViaJs()
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

    // 强制注入 localStorage，确保第二次进入时不需要重新登录
    // 在页面开始加载前注入，确保 Vue router guard 能读取到登录凭证
    private fun forceInjectLocalStorage(webView: WebView) {
        val savedAuth = sync.session.authData
        if (savedAuth.isBlank()) {
            // 没有保存的 auth_data，不需要注入
            return
        }
        
        // 强制注入到多个 localStorage key，确保前端能读取到
        // 使用 loadUrl 而不是 evaluateJavascript，确保同步执行
        val escapedAuth = savedAuth.replace("'", "\\'").replace("\"", "\\\"").replace("\n", "\\n")
        val js = """
            javascript:(function() {
                try {
                    var auth = '${escapedAuth}';
                    // vue-ls 格式: {"value":"xxx"}
                    var value = JSON.stringify({value: auth});
                    
                    // 注入到多个 key，确保前端能读取到
                    localStorage.setItem('__AURORA__authorization', value);
                    localStorage.setItem('authorization', value);
                    localStorage.setItem('auth_data', value);
                    
                    // 同时设置 Cookie（某些前端可能使用 Cookie）
                    document.cookie = 'auth_data=' + auth + '; path=/; max-age=31536000';
                    
                    console.log('V2Board: Injected auth_data to localStorage');
                } catch(e) { 
                    console.log('V2Board inject error: ' + e.message);
                }
            })();
        """.trimIndent()
        // 使用 loadUrl 确保同步执行，evaluateJavascript 是异步的
        webView.loadUrl(js)
    }

    // 将已保存的 auth_data 注入到 WebView 的 localStorage
    // 确保前端 router guard 能读取到登录状态
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
            if (activity.destroyed || activity.design == null) return
            if (authData.isBlank()) return
            if (activity.loginDetected) return

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
            // 注意：必须同时检查 loginDetected，防止新 Activity 实例中 auth_data 相同但未触发同步
            val existingAuth = activity.sync.session.authData
            if (existingAuth.isNotBlank() && existingAuth == cleanAuth && activity.loginDetected) {
                activity.loginDetected = true
                SyncLog.add("已登录，跳过重复同步")
                return
            }

            // 如果 auth_data 变化了，说明切换了账户，清除旧账号缓存信息
            if (existingAuth.isNotBlank() && existingAuth != cleanAuth) {
                SyncLog.add("检测到账户切换，清除旧缓存")
                activity.sync.session.planName = ""
                activity.sync.session.resetDay = 0
                activity.sync.session.balance = 0
                activity.sync.session.expiredAt = 0L
            }

            activity.loginDetected = true
            activity.sync.session.save(cleanAuth, token, "")

            Log.d("V2Board: onAuthData called")
            SyncLog.add("登录成功，获取到认证信息")
            SyncLog.add("后端地址: ${SyncLog.maskUrl(serverUrl)}")
            SyncLog.add("auth_data: ${cleanAuth.take(30)}...")

            // 强制持久化 Cookie，确保下次不丢失登录状态
            try {
                android.webkit.CookieManager.getInstance().flush()
            } catch (_: Exception) {}

            // 保存后端API地址
            if (serverUrl.isNotBlank()) {
                activity.sync.config.serverUrl = serverUrl
                activity.sync.resetApi()
                Log.d("V2Board: Saved backend URL")
            }

            // 通知 MainActivity 更新 UI
            activity.events.trySend(BaseActivity.Event.V2BoardLoginChanged)

            // 登录成功后，刷新当前页面
            // 页面重新加载时，onPageStarted 会自动注入 localStorage
            // onPageFinished 检测到已登录会自动跳转到仪表盘
            // 这种方式比直接跳转更可靠，确保 localStorage 已正确注入
            activity.design?.reload()
            SyncLog.add("登录成功，刷新页面以应用登录状态")

            activity.launch {
                withContext(Dispatchers.Main) {
                    activity.design?.showToast(
                        "登录成功，正在同步订阅...",
                        ToastDuration.Short
                    )
                    // 登录成功后立即获取订阅，不再固定等待
                    SyncLog.add("登录成功，立即获取订阅...")
                    activity.fetchSubscribeViaJs()
                }
            }
        }

        @JavascriptInterface
        fun onSubscribeUrl(subscribeUrl: String, email: String) {
            if (activity.destroyed || activity.design == null) return
            if (subscribeUrl.isBlank()) return
            
            Log.d("V2Board: onSubscribeUrl called, email=$email, url=${SyncLog.maskUrl(subscribeUrl)}")
            SyncLog.add("获取到订阅URL: ${SyncLog.maskUrl(subscribeUrl)}")
            
            // 如果邮箱变了，说明切换了账号，清除旧账号缓存
            val oldEmail = activity.sync.session.email
            if (oldEmail.isNotBlank() && email != oldEmail) {
                activity.sync.session.planName = ""
                activity.sync.session.resetDay = 0
                activity.sync.session.balance = 0
                activity.sync.session.expiredAt = 0L
                SyncLog.add("检测到账号切换: $oldEmail → $email")
            }
            
            // 保存邮箱（无论是否为空）
            if (email.isNotBlank()) {
                activity.sync.session.email = email
                SyncLog.add("用户邮箱: $email")
            }

            activity.launch {
                Log.d("V2Board: Starting sync")
                val syncResult = V2BoardAutoSync.sync(activity, subscribeUrl, email)

                // 同步订阅后，重新获取用户信息
                // fetchSubscribeUrl 会更新 email、planName、expiredAt
                // fetchUserInfo 会更新 balance
                // 必须在这里等待获取完成后再通知 UI 更新
                try {
                    if (syncResult.isSuccess) {
                        activity.sync.fetchSubscribeUrl()
                        activity.sync.fetchUserInfo()
                        SyncLog.add("已重新获取用户信息")
                        // 刷新 session 缓存，确保 MainActivity 读取到最新数据
                        Log.d("V2Board: User info updated, plan=${activity.sync.session.planName}, balance=${activity.sync.session.balance}")
                    }
                } catch (_: Exception) {
                    Log.w("V2Board: Failed to fetch user info after sync")
                }

                withContext(Dispatchers.Main) {
                    if (syncResult.isSuccess) {
                        Log.d("V2Board: Sync succeeded: ${syncResult.getOrNull()}")
                        SyncLog.add("同步成功: ${syncResult.getOrNull()}")
                        // 通知 MainActivity 更新 UI（此时数据已就绪）
                        activity.events.trySend(BaseActivity.Event.V2BoardLoginChanged)
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
        fun onSubscribeError(error: String) {
            if (activity.destroyed || activity.design == null) return
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
        fun onLocalStorageAuth(authData: String) {
            if (activity.destroyed) return
            if (authData.isBlank() || authData.length < 10) return
            val existing = activity.sync.session.authData
            
            // 检查 auth_data 是否变化
            if (existing != authData) {
                activity.sync.session.save(authData, "", "")
                Log.d("V2Board: Saved refreshed auth_data from localStorage")
                SyncLog.add("检测到认证信息，已同步保存")
                // 强制持久化 Cookie
                try {
                    android.webkit.CookieManager.getInstance().flush()
                } catch (_: Exception) {}
            }
            
            // 设置 loginDetected，确保后续流程正确执行
            activity.loginDetected = true
            
            // 如果还没有获取过订阅，立即获取
            // 这解决了第二次进入时不需要重新登录的问题
            if (activity.sync.session.planName.isBlank() && activity.sync.session.expiredAt == 0L) {
                activity.launch {
                    SyncLog.add("检测到已登录，获取订阅信息...")
                    activity.fetchSubscribeViaJs()
                }
            }
        }

        @JavascriptInterface
        fun onLogout() {
            activity.sync.session.clear()
            activity.loginDetected = false
            // 通知 MainActivity 更新 UI
            activity.events.trySend(BaseActivity.Event.V2BoardLoginChanged)
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
                    // 兼容多种 localStorage key：AuroraForV2board 的 __AURORA__authorization，
                    // 以及标准的 authorization 和 auth_data
                    var keys = ['__AURORA__authorization', 'authorization', 'auth_data'];
                    var auth = '';
                    for (var i = 0; i < keys.length; i++) {
                        var val = localStorage.getItem(keys[i]);
                        if (val) {
                            // vue-ls 格式: {"value":"xxx"}，需要解析提取 .value
                            try {
                                var parsed = JSON.parse(val);
                                if (parsed && typeof parsed === 'object' && parsed.value) {
                                    auth = parsed.value;
                                } else if (typeof parsed === 'string') {
                                    auth = parsed;
                                } else {
                                    auth = val;
                                }
                            } catch(e) { auth = val; }
                            if (auth && typeof auth === 'string' && auth.length > 10) {
                                break;
                            }
                        }
                    }
                    if (!auth || typeof auth !== 'string' || auth.length < 10) {
                        AndroidBridge.onSubscribeError('未找到登录凭证');
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
                        console.log('V2Board API response:', JSON.stringify(json));
                        if (json && json.data) {
                            var subscribeUrl = json.data.subscribe_url || '';
                            var token = json.data.token || '';
                            var email = json.data.email || '';
                            console.log('V2Board email from API:', email);
                            // 使用 baseUrl 而不是 location.origin 来构造订阅 URL
                            var finalUrl = subscribeUrl || (baseUrl + '/api/v1/client/subscribe?token=' + token);
                            if (finalUrl) {
                                console.log('V2Board calling onSubscribeUrl with email:', email);
                                // 同时保存 email 到 localStorage，方便后续使用
                                localStorage.setItem('_last_email', email);
                                AndroidBridge.onSubscribeUrl(finalUrl, email);
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
        destroyed = true
        // 移除 JS 接口防止回调访问已销毁的 Activity
        // 不销毁 WebView，保留 cookie 和缓存状态避免重复登录
        try {
            design?.removeJavascriptInterface("AndroidBridge")
        } catch (_: Exception) {}
        // 强制持久化 Cookie，确保下次打开 WebView 时登录状态不丢失
        try {
            android.webkit.CookieManager.getInstance().flush()
        } catch (_: Exception) {}
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
