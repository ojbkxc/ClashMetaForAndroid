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
        ClearAllRules,
    }

    private val binding = DesignSettingsCommonBinding
        .inflate(context.layoutInflater, context.root, false)

    override val root: View
        get() = binding.root

    init {
        binding.surface = surface

        binding.activityBarLayout.applyFrom(context)

        binding.scrollRoot.bindAppBarElevation(binding.activityBarLayout)

        if (!rootAvailable) {
            launch {
                showToast(R.string.root_not_available, ToastDuration.Long)
            }
        }

        val screen = preferenceScreen(context) {
            val rootDependencies: MutableList<Preference> = mutableListOf()

            category(R.string.root_settings)

            switch(
                value = srvStore::rootTransparentProxy,
                icon = R.drawable.ic_baseline_vpn_lock,
                title = R.string.root_transparent_proxy,
                summary = R.string.root_transparent_proxy_summary,
                configure = rootDependencies::add,
            ) {
                setEnabled(rootAvailable)
                setListener(OnChangedListener {
                    if (srvStore.rootTransparentProxy) {
                        requests.trySend(Request.ApplyTransparentProxy)
                    } else {
                        requests.trySend(Request.ClearAllRules)
                    }
                })
            }

            switch(
                value = srvStore::rootLockBackground,
                icon = R.drawable.ic_baseline_restore,
                title = R.string.root_lock_background,
                summary = R.string.root_lock_background_summary,
                configure = rootDependencies::add,
            ) {
                enabled = rootAvailable
                listener = OnChangedListener {
                    if (srvStore.rootLockBackground) {
                        requests.trySend(Request.ApplyLockBackground)
                    }
                }
            }

            switch(
                value = srvStore::rootDnsHijack,
                icon = R.drawable.ic_baseline_dns,
                title = R.string.root_dns_hijack,
                summary = R.string.root_dns_hijack_summary,
                configure = rootDependencies::add,
            ) {
                enabled = rootAvailable
                listener = OnChangedListener {
                    if (srvStore.rootDnsHijack) {
                        requests.trySend(Request.ApplyDnsHijack)
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
