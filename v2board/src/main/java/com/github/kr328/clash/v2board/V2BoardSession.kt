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

    val isLoggedIn: Boolean
        get() = authData.isNotBlank()

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
