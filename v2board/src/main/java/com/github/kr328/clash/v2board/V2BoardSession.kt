package com.github.kr328.clash.v2board

import android.content.Context
import com.github.kr328.clash.common.store.Store
import com.github.kr328.clash.common.store.asStoreProvider

class V2BoardSession(context: Context) {
    private val store = Store(
        context
            .getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)
            .asStoreProvider()
    )

    var authData: String by store.string(
        key = "auth_data",
        defaultValue = "",
    )

    var userToken: String by store.string(
        key = "user_token",
        defaultValue = "",
    )

    var email: String by store.string(
        key = "email",
        defaultValue = "",
    )

    var hasEverLoggedIn: Boolean by store.boolean(
        key = "has_ever_logged_in",
        defaultValue = false,
    )

    var lastValidated: Long by store.long(
        key = "last_validated",
        defaultValue = 0L,
    )

    val isLoggedIn: Boolean
        get() = authData.isNotBlank()

    // 检查 JWT 是否可能已过期（基于 exp 字段）
    fun isTokenLikelyExpired(): Boolean {
        if (authData.isBlank()) return true
        return try {
            val parts = authData.split(".")
            if (parts.size != 3) return false // 非标准 JWT，无法判断
            val payload = android.util.Base64.decode(parts[1], android.util.Base64.URL_SAFE)
            val json = org.json.JSONObject(String(payload))
            val exp = json.optLong("exp", 0)
            if (exp <= 0) return false // 无 exp 字段
            System.currentTimeMillis() / 1000 >= exp
        } catch (_: Exception) {
            false // 解析失败，保守认为未过期
        }
    }

    // token 是否需要验证（超过 30 分钟未验证）
    fun needsValidation(): Boolean {
        if (authData.isBlank()) return false
        return System.currentTimeMillis() - lastValidated > 30 * 60 * 1000
    }

    fun markValidated() {
        lastValidated = System.currentTimeMillis()
    }

    fun save(authData: String, userToken: String, email: String = "") {
        this.authData = authData
        this.userToken = userToken
        if (email.isNotBlank()) {
            this.email = email
        }
        this.hasEverLoggedIn = true
    }

    fun clear() {
        authData = ""
        userToken = ""
        email = ""
        // hasEverLoggedIn remains true - user can re-login from within the app
    }

    companion object {
        private const val FILE_NAME = "v2board_session"
    }
}
