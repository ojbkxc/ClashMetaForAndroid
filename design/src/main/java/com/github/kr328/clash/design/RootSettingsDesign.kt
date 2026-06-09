package com.github.kr328.clash.design

import android.content.Context
import android.view.View
import com.github.kr328.clash.design.databinding.DesignSettingsCommonBinding
import com.github.kr328.clash.design.preference.*
import com.github.kr328.clash.design.ui.ToastDuration
import com.github.kr328.clash.design.util.applyFrom
import com.github.kr328.clash.design.util.bindAppBarElevation
import com.github.kr328.clash.design.util.layoutInflater
import com.github.kr328.clash.design.util.root
import com.github.kr328.clash.design.R
import com.github.kr328.clash.service.store.ServiceStore
import kotlinx.coroutines.launch

class RootSettingsDesign(
    context: Context,
    srvStore: ServiceStore,
    rootAvailable: Boolean,
) : Design<RootSettingsDesign.Request>(context) {
    enum class Request {
        ApplyTransparentProxy,
        ApplyLockBackground,
        ApplyDnsHijack,
        ClearTransparentProxy,
        ClearLockBackground,
        ClearDnsHijack,
        ClearAllRules,
        RequestRoot,
    }

    private val binding = DesignSettingsCommonBinding
        .inflate(context.layoutInflater, context.root, false)

    override val root: View
        get() = binding.root

    init {
        binding.surface = surface

        binding.activityBarLayout.applyFrom(context)

        binding.scrollRoot.bindAppBarElevation(binding.activityBarLayout)

        val screen = preferenceScreen(context) {
            val rootDependencies: MutableList<Preference> = mutableListOf()

            category(R.string.root_settings)

            // Root 状态提示
            if (!rootAvailable) {
                clickable(
                    icon = R.drawable.ic_baseline_sync,
                    title = R.string.root_request_permission,
                    summary = R.string.root_request_permission_summary,
                ) {
                    requests.trySend(Request.RequestRoot)
                }
            }

            switch(
                value = srvStore::rootTransparentProxy,
                icon = R.drawable.ic_baseline_vpn_lock,
                title = R.string.root_transparent_proxy,
                summary = R.string.root_transparent_proxy_summary,
            ) {
                rootDependencies.add(this)
                enabled = rootAvailable
                listener = OnChangedListener {
                    if (srvStore.rootTransparentProxy) {
                        requests.trySend(Request.ApplyTransparentProxy)
                    } else {
                        requests.trySend(Request.ClearTransparentProxy)
                    }
                }
            }

            switch(
                value = srvStore::rootLockBackground,
                icon = R.drawable.ic_baseline_restore,
                title = R.string.root_lock_background,
                summary = R.string.root_lock_background_summary,
            ) {
                rootDependencies.add(this)
                enabled = rootAvailable
                listener = OnChangedListener {
                    if (srvStore.rootLockBackground) {
                        requests.trySend(Request.ApplyLockBackground)
                    } else {
                        requests.trySend(Request.ClearLockBackground)
                    }
                }
            }

            switch(
                value = srvStore::rootDnsHijack,
                icon = R.drawable.ic_baseline_dns,
                title = R.string.root_dns_hijack,
                summary = R.string.root_dns_hijack_summary,
            ) {
                rootDependencies.add(this)
                enabled = rootAvailable
                listener = OnChangedListener {
                    if (srvStore.rootDnsHijack) {
                        requests.trySend(Request.ApplyDnsHijack)
                    } else {
                        requests.trySend(Request.ClearDnsHijack)
                    }
                }
            }
        }

        binding.content.addView(screen.root)
    }

    fun request(request: Request) {
        requests.trySend(request)
    }
}
