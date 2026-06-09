package com.github.kr328.clash

import android.app.AlertDialog
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

        // 初始化应用 UID（最可靠的方式）
        RootHelper.initAppUid(applicationInfo.uid)

        // 检测 root 权限
        var rootAvailable = withContext(Dispatchers.IO) {
            RootHelper.isRootAvailable()
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
                            // 重新申请 root 权限（带重试机制）
                            design.showToast(DesignR.string.root_requesting,
                                com.github.kr328.clash.design.ui.ToastDuration.Short)

                            rootAvailable = withContext(Dispatchers.IO) {
                                RootHelper.requestRootWithRetry()
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
                            } else {
                                design.showToast(DesignR.string.root_request_failed,
                                    com.github.kr328.clash.design.ui.ToastDuration.Long)
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
                            showWarningAndApply {
                                withContext(Dispatchers.IO) {
                                    RootHelper.applyLockBackground()
                                }
                            }
                        }
                        RootSettingsDesign.Request.ApplyDnsHijack -> {
                            showWarningAndApply {
                                withContext(Dispatchers.IO) {
                                    RootHelper.applyDnsHijack()
                                }
                            }
                        }
                        RootSettingsDesign.Request.ClearTransparentProxy -> {
                            withContext(Dispatchers.IO) {
                                RootHelper.clearTransparentProxyRules()
                            }
                        }
                        RootSettingsDesign.Request.ClearLockBackground -> {
                            withContext(Dispatchers.IO) {
                                RootHelper.clearLockBackgroundRules()
                            }
                        }
                        RootSettingsDesign.Request.ClearDnsHijack -> {
                            withContext(Dispatchers.IO) {
                                RootHelper.clearDnsHijackRules()
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

        val (success, message) = apply()
        val design = this.design ?: return
        if (success) {
            if (message.isNotBlank()) {
                // 成功但有附加信息（如 REDIRECT 模式提示）
                design.showToast(message,
                    com.github.kr328.clash.design.ui.ToastDuration.Long)
            } else {
                design.showToast(DesignR.string.root_apply_success,
                    com.github.kr328.clash.design.ui.ToastDuration.Short)
            }
        } else {
            design.showToast(getString(DesignR.string.root_apply_failed, message),
                com.github.kr328.clash.design.ui.ToastDuration.Long)
        }
    }

    private suspend fun applyEnabledRules(srvStore: ServiceStore) {
        val design = this.design ?: return
        withContext(Dispatchers.IO) {
            if (srvStore.rootTransparentProxy) {
                val (success, msg) = RootHelper.applyTransparentProxy()
                if (!success) {
                    withContext(Dispatchers.Main) {
                        design.showToast(getString(DesignR.string.root_apply_failed, msg),
                            com.github.kr328.clash.design.ui.ToastDuration.Long)
                    }
                }
            }
            if (srvStore.rootLockBackground) {
                val (success, msg) = RootHelper.applyLockBackground()
                if (!success) {
                    withContext(Dispatchers.Main) {
                        design.showToast(getString(DesignR.string.root_apply_failed, msg),
                            com.github.kr328.clash.design.ui.ToastDuration.Long)
                    }
                }
            }
            if (srvStore.rootDnsHijack) {
                val (success, msg) = RootHelper.applyDnsHijack()
                if (!success) {
                    withContext(Dispatchers.Main) {
                        design.showToast(getString(DesignR.string.root_apply_failed, msg),
                            com.github.kr328.clash.design.ui.ToastDuration.Long)
                    }
                }
            }
        }
    }
}
