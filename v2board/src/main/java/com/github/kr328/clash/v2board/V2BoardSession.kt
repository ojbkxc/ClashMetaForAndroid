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

    var planName: String by store.string(
        key = "plan_name",
        defaultValue = "",
    )

    var resetDay: Int by store.int(
        key = "reset_day",
        defaultValue = 0,
    )

    var expiredAt: Long by store.long(
        key = "expired_at",
        defaultValue = 0L,
    )

    var balance: Int by store.int(
        key = "balance",
        defaultValue = 0,
    )

    var hasEverLoggedIn: Boolean by store.boolean(
        key = "has_ever_logged_in",
        defaultValue = false,
    )

    // 存储当前登录用户对应的订阅UUID，用于多账号场景下精确匹配订阅
    var v2boardProfileUuid: String by store.string(
        key = "v2board_profile_uuid",
        defaultValue = "",
    )

    // email → profile UUID 映射（JSON格式），用于多账号切换时找到各自的订阅
    private var profileUuidByEmail: String by store.string(
        key = "profile_uuid_by_email",
        defaultValue = "{}",
    )

    /**
     * 根据 email 查找对应的订阅 UUID
     */
    fun getProfileUuidForEmail(email: String): String? {
        if (email.isBlank()) return null
        return try {
            val json = org.json.JSONObject(profileUuidByEmail)
            json.optString(email, "").takeIf { it.isNotBlank() }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * 保存 email → 订阅 UUID 的映射
     */
    fun setProfileUuidForEmail(email: String, uuid: String) {
        if (email.isBlank() || uuid.isBlank()) return
        try {
            val json = org.json.JSONObject(profileUuidByEmail)
            json.put(email, uuid)
            profileUuidByEmail = json.toString()
        } catch (_: Exception) {}
    }

    val isLoggedIn: Boolean
        get() = authData.isNotBlank()

    fun save(authData: String, userToken: String, email: String = "") {
        this.authData = authData
        this.userToken = userToken
        if (email.isNotBlank()) {
            // 如果邮箱变了，说明切换了账号，清除旧账号缓存信息
            if (this.email.isNotBlank() && email != this.email) {
                planName = ""
                resetDay = 0
                balance = 0
                expiredAt = 0L
            }
            this.email = email
        }
        this.hasEverLoggedIn = true
    }

    fun clear() {
        authData = ""
        userToken = ""
        email = ""
        // hasEverLoggedIn remains true - user can re-login from within the app
        // v2boardProfileUuid 也保留，用于下次登录时找到对应的订阅
    }

    companion object {
        private const val FILE_NAME = "v2board_session"
    }
}
