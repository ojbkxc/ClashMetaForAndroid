package com.github.kr328.clash

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.contract.ActivityResultContracts.RequestPermission
import androidx.core.content.ContextCompat
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import androidx.lifecycle.lifecycleScope
import com.github.kr328.clash.common.constants.Intents
import com.github.kr328.clash.common.log.Log
import com.github.kr328.clash.common.util.intent
import com.github.kr328.clash.common.util.ticker
import com.github.kr328.clash.design.MainDesign
import com.github.kr328.clash.design.ui.ToastDuration
import com.github.kr328.clash.util.startClashService
import com.github.kr328.clash.util.stopClashService
import com.github.kr328.clash.util.withClash
import com.github.kr328.clash.util.withProfile
import com.github.kr328.clash.v2board.SyncLog
import com.github.kr328.clash.v2board.V2BoardSync
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit
import com.github.kr328.clash.design.R as DesignR

class MainActivity : BaseActivity<MainDesign>() {
    override suspend fun main() {
        val design = MainDesign(this)

        setContentDesign(design)

        // 更新登录状态和日志
        fun updateSyncUI() {
            val sync = V2BoardSync.getInstance(this@MainActivity)
            launch {
                design.setLoginStatus(sync.session.isLoggedIn)
                // 登录成功后顶部显示email，未登录显示默认标题
                if (sync.session.isLoggedIn && sync.session.email.isNotBlank()) {
                    design.setTitleText(sync.session.email)
                } else {
                    design.setTitleText(null)
                }
            }
        }

        // 初始更新UI
        updateSyncUI()

        design.fetch()

        // 启动后延迟检查更新（非阻塞，不干扰主页加载）
        launch {
            kotlinx.coroutines.delay(3000)
            try {
                val currentVersion = packageManager.getPackageInfo(packageName, 0).versionName
                    ?: return@launch
                val release = withContext(Dispatchers.IO) {
                    UpdateChecker.checkForUpdate(this@MainActivity)
                } ?: return@launch
                if (UpdateChecker.isSkipped(this@MainActivity, release.tagName)) return@launch
                if (UpdateChecker.compareVersions(currentVersion, release.tagName) < 0) {
                    withContext(Dispatchers.Main) {
                        UpdateChecker.showUpdateDialog(this@MainActivity, currentVersion, release)
                    }
                }
            } catch (_: Exception) {
                // 更新检查失败不影响正常使用
            }
        }

        val ticker = ticker(TimeUnit.SECONDS.toMillis(1))

        while (isActive) {
            select<Unit> {
                events.onReceive {
                    when (it) {
                        Event.ActivityStart -> {
                            design.fetch()
                            updateSyncUI()
                        }
                        Event.ServiceRecreated,
                        Event.ClashStop, Event.ClashStart,
                        Event.ProfileLoaded, Event.ProfileChanged -> design.fetch()
                        Event.V2BoardLoginChanged -> updateSyncUI()
                        else -> Unit
                    }
                }
                design.requests.onReceive {
                    when (it) {
                        MainDesign.Request.ToggleStatus -> {
                            if (clashRunning)
                                stopClashService()
                            else
                                design.startClash()
                        }
                        MainDesign.Request.OpenProxy ->
                            startActivity(ProxyActivity::class.intent)
                        MainDesign.Request.OpenProfiles ->
                            startActivity(ProfilesActivity::class.intent)
                        MainDesign.Request.OpenProviders ->
                            startActivity(ProvidersActivity::class.intent)
                        MainDesign.Request.OpenSettings ->
                            startActivity(SettingsActivity::class.intent)
                        MainDesign.Request.OpenHelp ->
                            startActivity(V2BoardActivity.openKnowledge(this@MainActivity))
                        MainDesign.Request.OpenAbout ->
                            startActivity(V2BoardActivity.openAbout(this@MainActivity))
                        MainDesign.Request.OpenV2BoardLogin -> {
                            // 始终打开 WebView（已登录时显示仪表盘，未登录时显示登录页）
                            startActivity(V2BoardActivity.openLogin(this@MainActivity))
                        }
                        MainDesign.Request.ViewSyncLog -> {
                            startActivity(SyncLogActivity::class.intent)
                        }
                    }
                }
                if (clashRunning) {
                    ticker.onReceive {
                        design.fetchTraffic()
                    }
                }
            }
        }
    }

    private suspend fun MainDesign.fetch() {
        setClashRunning(clashRunning)

        val state = withClash {
            queryTunnelState()
        }
        val providers = withClash {
            queryProviders()
        }

        setMode(state.mode)
        setHasProviders(providers.isNotEmpty())

        withProfile {
            val active = queryActive()
            setProfileName(active?.name)

            // 显示订阅流量信息
            val flowInfo = if (active != null && active.total > 0) {
                val usedBytes = active.upload + active.download
                val usedStr = formatBytes(usedBytes)
                val totalStr = formatBytes(active.total)
                "$usedStr / $totalStr"
            } else null

            val flowProgress = if (active != null && active.total > 1) {
                ((active.upload + active.download) / (active.total / 1000)).toInt().coerceIn(0, 1000)
            } else 0

            setProfileFlowInfo(flowInfo)
            setProfileFlowProgress(flowProgress)

            // 显示套餐名、到期时间、余额到各卡片右侧（从 V2BoardSession 缓存读取）
            val sync = V2BoardSync.getInstance(this@MainActivity)
            val session = sync.session
            if (session.isLoggedIn) {
                // 如果缓存为空，后台拉取数据（仅首次）
                if (session.planName.isBlank() && session.expiredAt <= 0L && session.balance <= 0) {
                    launch {
                        try {
                            sync.fetchSubscribeUrl()
                            sync.fetchUserInfo()
                            // 数据就绪后刷新 UI
                            withContext(Dispatchers.Main) {
                                fetch()
                            }
                        } catch (_: Exception) {}
                    }
                }

                // 运行中卡片右侧：套餐名
                setProfilePlanName(session.planName.ifBlank { null })

                // 运行中卡片右侧：上=套餐名，下=到期信息
                // 到期信息：>20天显示日期，≤20天显示"还剩X天"并着色
                if (session.expiredAt > 0) {
                    val sdf = java.text.SimpleDateFormat("yy-MM-dd", java.util.Locale.getDefault())
                    val daysLeft = ((session.expiredAt - System.currentTimeMillis()) / (24 * 60 * 60 * 1000)).toInt()
                    when {
                        daysLeft > 90 -> setProfileExpiryInfo(null)  // 超过3个月不显示
                        daysLeft > 20 -> setProfileExpiryInfo(sdf.format(java.util.Date(session.expiredAt)))
                        else -> {
                            val (text, color) = when {
                                daysLeft <= 0  -> "已过期"  to 0xFFFF4444.toInt()
                                daysLeft <= 7  -> "还剩${daysLeft}天" to 0xFFFF4444.toInt()
                                else           -> "还剩${daysLeft}天" to 0xFFFF9800.toInt()
                            }
                            setProfileExpiryInfo(text, color)
                        }
                    }
                } else {
                    setProfileExpiryInfo(null)
                }
            } else {
                setProfilePlanName(null)
                setProfileExpiryInfo(null)
            }

            // 设置余额并启动交替动画
            setAccountBalance(session.balance)

            // 订阅到期提醒
            if (active != null && active.expire > 0) {
                val daysLeft = ((active.expire - System.currentTimeMillis()) / (24 * 60 * 60 * 1000)).toInt()
                if (daysLeft <= 0) {
                    this@MainActivity.design?.showToast(
                        DesignR.string.subscription_expired,
                        ToastDuration.Long
                    )
                } else if (daysLeft <= 3) {
                    this@MainActivity.design?.showToast(
                        getString(DesignR.string.subscription_expiring_soon, daysLeft),
                        ToastDuration.Long
                    )
                }
            }

            // 流量超 90% 提醒
            if (active != null && active.total > 1) {
                val usedBytes = active.upload + active.download
                val usagePercent = (usedBytes * 100.0 / active.total).toInt()
                if (usagePercent >= 90) {
                    this@MainActivity.design?.showToast(
                        DesignR.string.subscription_traffic_warning,
                        ToastDuration.Long
                    )
                }
            }
        }
    }

    private suspend fun MainDesign.fetchTraffic() {
        withClash {
            setForwarded(queryTrafficTotal())
        }
    }

    private fun formatBytes(bytes: Long): String {
        return when {
            bytes < 1024 -> "${bytes}B"
            bytes < 1024 * 1024 -> "%.1fKB".format(bytes / 1024.0)
            bytes < 1024 * 1024 * 1024 -> "%.1fMB".format(bytes / (1024.0 * 1024))
            else -> "%.2fGB".format(bytes / (1024.0 * 1024 * 1024))
        }
    }

    private suspend fun MainDesign.startClash() {
        val active = withProfile { queryActive() }

        if (active == null || !active.imported) {
            showToast(DesignR.string.no_profile_selected, ToastDuration.Long) {
                setAction(DesignR.string.profiles) {
                    startActivity(ProfilesActivity::class.intent)
                }
            }

            return
        }

        val vpnRequest = startClashService()

        try {
            if (vpnRequest != null) {
                val result = startActivityForResult(
                    ActivityResultContracts.StartActivityForResult(),
                    vpnRequest
                )

                if (result.resultCode == RESULT_OK)
                    startClashService()
            }
        } catch (e: Exception) {
            design?.showToast(DesignR.string.unable_to_start_vpn, ToastDuration.Long)
        }
    }

    private suspend fun autoSyncSubscription(design: MainDesign) {
        val sync = V2BoardSync.getInstance(this)
        val session = sync.session

        if (!session.isLoggedIn) {
            SyncLog.add("未登录，请先登录")
            return
        }

        SyncLog.add("已登录，开始通过API获取订阅...")

        // 方式一：通过 Kotlin API 获取订阅（推荐，不需要 WebView）
        val result = sync.fetchSubscribeUrl()

        if (result.isSuccess) {
            val subscribeUrl = result.getOrNull()!!
            SyncLog.add("API获取订阅URL成功")

            // 顺便获取余额
            sync.fetchUserInfo()

            val syncResult = V2BoardAutoSync.sync(this, subscribeUrl)
            if (syncResult.isSuccess) {
                design.showToast(syncResult.getOrNull() ?: "订阅同步成功", ToastDuration.Short)
                design.fetch()
            } else {
                val errorMsg = syncResult.exceptionOrNull()?.message ?: "未知错误"
                design.showToast("同步失败: $errorMsg", ToastDuration.Long)
            }
        } else {
            val errorMsg = result.exceptionOrNull()?.message ?: "未知错误"
            SyncLog.add("API获取订阅失败: $errorMsg")
            design.showToast("同步失败: $errorMsg", ToastDuration.Long)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val requestPermissionLauncher =
                registerForActivityResult(RequestPermission()
                ) { isGranted: Boolean ->
                }
            if (ContextCompat.checkSelfPermission(
                    this,
                    android.Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED) {
                requestPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        setupShortcuts()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == R.id.action_check_update) {
            checkForUpdate()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    private fun checkForUpdate() {
        lifecycleScope.launch {
            val currentVersion = try {
                packageManager.getPackageInfo(packageName, 0).versionName ?: "unknown"
            } catch (e: Exception) {
                "unknown"
            }

            // Show a dialog indicating check in progress
            val progressDialog = androidx.appcompat.app.AlertDialog.Builder(this@MainActivity)
                .setTitle("检查更新")
                .setMessage("正在检查...")
                .setCancelable(false)
                .create()
            progressDialog.show()

            val release = withContext(Dispatchers.IO) {
                UpdateChecker.checkForUpdate(this@MainActivity)
            }
            progressDialog.dismiss()

            if (release == null) {
                androidx.appcompat.app.AlertDialog.Builder(this@MainActivity)
                    .setTitle("检查更新")
                    .setMessage("检查更新失败，请稍后重试。")
                    .setPositiveButton("确定", null)
                    .show()
                return@launch
            }

            UpdateChecker.showUpdateDialog(this@MainActivity, currentVersion, release)
        }
    }

    private fun setupShortcuts() {
        if (uiStore.hideAppIcon) return

        val flags = Intent.FLAG_ACTIVITY_NEW_TASK or
            Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS or
            Intent.FLAG_ACTIVITY_NO_ANIMATION

        val toggle = ShortcutInfoCompat.Builder(this, "toggle_clash")
            .setShortLabel(getString(DesignR.string.shortcut_toggle_short))
            .setLongLabel(getString(DesignR.string.shortcut_toggle_long))
            .setIcon(IconCompat.createWithResource(this, R.drawable.ic_toggle_all))
            .setIntent(
                Intent(Intents.ACTION_TOGGLE_CLASH)
                    .setClassName(this, ExternalControlActivity::class.java.name)
                    .addFlags(flags)
            )
            .setRank(0)
            .build()

        val start = ShortcutInfoCompat.Builder(this, "start_clash")
            .setShortLabel(getString(DesignR.string.shortcut_start_short))
            .setLongLabel(getString(DesignR.string.shortcut_start_long))
            .setIcon(IconCompat.createWithResource(this, R.drawable.ic_toggle_on))
            .setIntent(
                Intent(Intents.ACTION_START_CLASH)
                    .setClassName(this, ExternalControlActivity::class.java.name)
                    .addFlags(flags)
            )
            .setRank(1)
            .build()

        val stop = ShortcutInfoCompat.Builder(this, "stop_clash")
            .setShortLabel(getString(DesignR.string.shortcut_stop_short))
            .setLongLabel(getString(DesignR.string.shortcut_stop_long))
            .setIcon(IconCompat.createWithResource(this, R.drawable.ic_toggle_off))
            .setIntent(
                Intent(Intents.ACTION_STOP_CLASH)
                    .setClassName(this, ExternalControlActivity::class.java.name)
                    .addFlags(flags)
            )
            .setRank(2)
            .build()

        ShortcutManagerCompat.setDynamicShortcuts(this, listOf(toggle, start, stop))
    }

    override fun onDestroy() {
        design?.onPageDestroy()
        super.onDestroy()
    }
}
