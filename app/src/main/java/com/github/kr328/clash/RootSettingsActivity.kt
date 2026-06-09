package com.github.kr328.clash

import android.app.AlertDialog
import com.github.kr328.clash.common.RootChecker
import com.github.kr328.clash.design.RootSettingsDesign
import com.github.kr328.clash.design.R as DesignR
import com.github.kr328.clash.service.store.ServiceStore
import com.github.kr328.clash.service.root.RootHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

class RootSettingsActivity : BaseActivity<RootSettingsDesign>() {
    override suspend fun main() {
        val srvStore = ServiceStore(this)

        // 主动申请 root 权限（会触发 superuser 弹窗）
        var rootAvailable = withContext(Dispatchers.IO) {
            RootChecker.requestRoot()
        }

        var design = RootSettingsDesign(
            this,
            srvStore,
            rootAvailable,
        )

        setContentDesign(design)

        while (isActive) {
            select<Unit> {
                events.onReceive {
                    when (it) {
                        Event.ClashStart, Event.ClashStop -> {
                            // 服务状态变化时重新应用规则
                            if (rootAvailable) {
                                applyEnabledRules(srvStore)
                            }
                        }
                        else -> Unit
                    }
                }
                design.requests.onReceive {
                    when (it) {
                        RootSettingsDesign.Request.RequestRoot -> {
                            // 重新申请 root 权限
                            rootAvailable = withContext(Dispatchers.IO) {
                                RootChecker.requestRoot()
                            }
                            // 重新创建设计以更新 UI 状态
                            design = RootSettingsDesign(
                                this@RootSettingsActivity,
                                srvStore,
                                rootAvailable,
                            )
                            setContentDesign(design)
                            if (rootAvailable) {
                                design.showToast(DesignR.string.root_apply_success,
                                    com.github.kr328.clash.design.ui.ToastDuration.Short)
                            }
                        }
                        RootSettingsDesign.Request.ApplyTransparentProxy -> {
                            showWarningAndApply {
                                withContext(Dispatchers.IO) {
                                    RootHelper.applyTransparentProxy()
                                }
                            }
                        }
                        RootSettingsDesign.Request.ApplyLockBackground -> {
                            withContext(Dispatchers.IO) {
                                RootHelper.applyLockBackground()
                            }
                            design.showToast(DesignR.string.root_apply_success,
                                com.github.kr328.clash.design.ui.ToastDuration.Short)
                        }
                        RootSettingsDesign.Request.ApplyDnsHijack -> {
                            showWarningAndApply {
                                withContext(Dispatchers.IO) {
                                    RootHelper.applyDnsHijack()
                                }
                            }
                        }
                        RootSettingsDesign.Request.ClearAllRules -> {
                            withContext(Dispatchers.IO) {
                                RootHelper.clearAllRules()
                            }
                            design.showToast(DesignR.string.root_rules_cleared,
                                com.github.kr328.clash.design.ui.ToastDuration.Short)
                        }
                    }
                }
            }
        }
    }

    private suspend fun showWarningAndApply(
        apply: suspend () -> Pair<Boolean, String>
    ) {
        val confirmed = suspendCancellableCoroutine<Boolean> { cont ->
            val dialog = AlertDialog.Builder(this@RootSettingsActivity)
                .setTitle(DesignR.string.root_warning_title)
                .setMessage(DesignR.string.root_warning_message)
                .setPositiveButton(DesignR.string.btn_ok) { _, _ -> if (cont.isActive) cont.resume(true) }
                .setNegativeButton(DesignR.string.btn_cancel) { _, _ -> if (cont.isActive) cont.resume(false) }
                .setCancelable(false)
                .create()

            cont.invokeOnCancellation { dialog.dismiss() }
            dialog.show()
        }

        if (!confirmed) return

        val (success, error) = apply()
        val design = this.design ?: return
        if (success) {
            design.showToast(DesignR.string.root_apply_success,
                com.github.kr328.clash.design.ui.ToastDuration.Short)
        } else {
            design.showToast(getString(DesignR.string.root_apply_failed, error),
                com.github.kr328.clash.design.ui.ToastDuration.Long)
        }
    }

    private suspend fun applyEnabledRules(srvStore: ServiceStore) {
        withContext(Dispatchers.IO) {
            if (srvStore.rootTransparentProxy) {
                RootHelper.applyTransparentProxy()
            }
            if (srvStore.rootLockBackground) {
                RootHelper.applyLockBackground()
            }
            if (srvStore.rootDnsHijack) {
                RootHelper.applyDnsHijack()
            }
        }
    }
}
