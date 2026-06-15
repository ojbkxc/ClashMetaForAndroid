package com.github.kr328.clash.design

import android.animation.ObjectAnimator
import android.content.Context
import android.content.res.ColorStateList
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import com.github.kr328.clash.core.model.TunnelState
import com.github.kr328.clash.core.util.trafficCompactUpload
import com.github.kr328.clash.core.util.trafficCompactDownload
import com.github.kr328.clash.core.util.trafficTotal
import com.github.kr328.clash.design.databinding.DesignMainBinding
import com.github.kr328.clash.design.util.layoutInflater
import com.github.kr328.clash.design.util.resolveThemedColor
import com.github.kr328.clash.design.util.root
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainDesign(context: Context) : Design<MainDesign.Request>(context) {
    enum class Request {
        ToggleStatus,
        OpenProxy,
        OpenProfiles,
        OpenProviders,
        OpenSettings,
        OpenHelp,
        OpenAbout,
        OpenV2BoardLogin,
        ViewSyncLog,
    }

    private val binding = DesignMainBinding
        .inflate(context.layoutInflater, context.root, false)

    override val root: View
        get() = binding.root

    // 动画相关
    private val animationScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var switchJob: Job? = null
    private var isShowingBalance = false

    // 设置顶部标题文本（登录后显示email）
    suspend fun setTitleText(text: String?) {
        withContext(Dispatchers.Main) {
            binding.titleText.text = text ?: context.getString(R.string.application_name)
        }
    }

    suspend fun setProfileName(name: String?) {
        withContext(Dispatchers.Main) {
            binding.profileName = name
        }
    }

    suspend fun setClashRunning(running: Boolean) {
        withContext(Dispatchers.Main) {
            binding.clashRunning = running
            if (!running) {
                binding.cardStatus.text = context.getString(R.string.stopped)
                binding.cardStatus.subtext = ""
                binding.cardStatus.clearTraffic()  // 清除流量显示
                binding.cardStatus.clearTrailingInfo()  // 清除套餐信息和日期
            }
        }
    }

    suspend fun setForwarded(value: Long) {
        withContext(Dispatchers.Main) {
            binding.forwarded = value.trafficTotal()
            if (binding.clashRunning) {
                // 主文字只显示"运行中"
                binding.cardStatus.text = context.getString(R.string.running)
                // 流量数据分两行显示：上行上传，下行下载
                binding.cardStatus.setTraffic(
                    upload = value.trafficCompactUpload(),
                    download = value.trafficCompactDownload()
                )
            }
        }
    }

    suspend fun setMode(mode: TunnelState.Mode) {
        withContext(Dispatchers.Main) {
            binding.mode = when (mode) {
                TunnelState.Mode.Direct -> context.getString(R.string.direct_mode)
                TunnelState.Mode.Global -> context.getString(R.string.global_mode)
                TunnelState.Mode.Rule -> context.getString(R.string.rule_mode)
                else -> context.getString(R.string.rule_mode)
            }
        }
    }

    suspend fun setHasProviders(has: Boolean) {
        withContext(Dispatchers.Main) {
            binding.hasProviders = has
        }
    }

    suspend fun setLoginStatus(loggedIn: Boolean) {
        withContext(Dispatchers.Main) {
            binding.isLoggedIn = loggedIn
        }
    }

<<<<<<< Updated upstream
    suspend fun setProfileFlowInfo(info: String?) {
        withContext(Dispatchers.Main) {
            binding.profileFlowInfo = info
        }
    }

    suspend fun showLogoGuideHighlight() {
        withContext(Dispatchers.Main) {
            // Logo 呼吸动画：缩放脉冲 1.0 → 1.15 → 1.0 循环
            val logoParent = binding.root.findViewById<View>(R.id.logoContainer)
            logoParent?.let { logo ->
                val scaleXAnim = ObjectAnimator.ofFloat(logo, "scaleX", 1.0f, 1.15f, 1.0f)
                val scaleYAnim = ObjectAnimator.ofFloat(logo, "scaleY", 1.0f, 1.15f, 1.0f)
                scaleXAnim.apply {
                    duration = 1000
                    repeatCount = ObjectAnimator.INFINITE
                    repeatMode = ObjectAnimator.RESTART
                    interpolator = AccelerateDecelerateInterpolator()
                    start()
                }
                scaleYAnim.apply {
                    duration = 1000
                    repeatCount = ObjectAnimator.INFINITE
                    repeatMode = ObjectAnimator.RESTART
                    interpolator = AccelerateDecelerateInterpolator()
                    start()
                }
                // 保存引用以便后续停止动画
                logo.setTag(R.id.logo_guide_anim, scaleXAnim)
            }
        }
    }

    suspend fun hideLogoGuideHighlight() {
        withContext(Dispatchers.Main) {
            val logoParent = binding.root.findViewById<View>(R.id.logoContainer)
            logoParent?.let { logo ->
                val anim = logo.getTag(R.id.logo_guide_anim) as? ObjectAnimator
                anim?.cancel()
                logo.animate().scaleX(1.0f).scaleY(1.0f).setDuration(200).start()
            }
        }
    }

    suspend fun setProfileFlowProgress(progress: Int) {
        withContext(Dispatchers.Main) {
            binding.profileFlowProgress = progress
            val color = when {
                progress >= 800 -> 0xFFF44336.toInt() // 红色 >80%
                progress >= 500 -> 0xFFFF9800.toInt() // 橙色 50-80%
                progress > 0    -> 0xFF4CAF50.toInt() // 绿色 <50%
                else -> return@withContext
            }
            binding.profileFlowProgressBar.progressTintList =
                ColorStateList.valueOf(color)
        }
    }

    suspend fun setProfilePlanName(name: String?) {
        withContext(Dispatchers.Main) {
            // 套餐名称靠右显示（在 cardStatus 卡片右侧）
            // 只有在运行状态时才显示，未运行状态在 setClashRunning 中已清除
            if (binding.clashRunning) {
                binding.cardStatus.setTrailingInfo(name, null)
            }
        }
    }

    suspend fun setProfileExpiryInfo(text: String?, color: Int? = null) {
        withContext(Dispatchers.Main) {
            // 到期日期靠右显示（在 cardStatus 卡片右侧，套餐名称下方）
            // 只有在运行状态时才显示
            if (binding.clashRunning) {
                val currentPlanName = binding.cardStatus.trailingText2?.toString()
                binding.cardStatus.setTrailingInfo(currentPlanName, text)
                // 设置日期颜色
                if (color != null) {
                    binding.cardStatus.setTrailingDateColor(color)
                }
            }
        }
    }

    // 设置余额并启动交替动画（登录成功后调用）
    suspend fun setAccountBalance(balance: Int?) {
        withContext(Dispatchers.Main) {
            val shouldShowBalance = balance != null && balance in 1..2000

            if (shouldShowBalance) {
                // 显示余额
                val yuan = balance!! / 100.0
                binding.accountBalance.text = String.format("¥%.2f", yuan)
                startBalanceAnimation()
            } else {
                // 停止动画，只显示设置图标
                stopBalanceAnimation()
                binding.settingsContainer.visibility = View.VISIBLE
                binding.settingsContainer.alpha = 1f
                binding.balanceContainer.visibility = View.GONE
            }
        }
    }

    // 启动余额和设置图标交替动画
    private fun startBalanceAnimation() {
        // 停止之前的动画
        switchJob?.cancel()

        // 初始状态：显示设置图标
        binding.settingsContainer.visibility = View.VISIBLE
        binding.settingsContainer.alpha = 1f
        binding.balanceContainer.visibility = View.GONE
        isShowingBalance = false

        switchJob = animationScope.launch {
            while (true) {
                // 等待5秒
                delay(5000)

                // 渐变消失设置图标
                fadeOut(binding.settingsContainer)

                // 等待渐变完成
                delay(300)

                // 显示余额
                binding.balanceContainer.visibility = View.VISIBLE
                isShowingBalance = true

                // 渐变显示余额
                fadeIn(binding.balanceContainer)

                // 等待3秒
                delay(3000)

                // 渐变消失余额
                fadeOut(binding.balanceContainer)

                // 等待渐变完成
                delay(300)

                // 显示设置图标
                binding.settingsContainer.visibility = View.VISIBLE
                isShowingBalance = false

                // 渐变显示设置图标
                fadeIn(binding.settingsContainer)
            }
        }
    }

    // 停止交替动画
    private fun stopBalanceAnimation() {
        switchJob?.cancel()
        switchJob = null
    }

    // 渐变消失
    private suspend fun fadeOut(view: View) {
        withContext(Dispatchers.Main) {
            ObjectAnimator.ofFloat(view, "alpha", 1f, 0f).apply {
                duration = 300
                interpolator = AccelerateDecelerateInterpolator()
                start()
            }
            delay(300)
        }
    }

    // 渐变显示
    private suspend fun fadeIn(view: View) {
        withContext(Dispatchers.Main) {
            view.alpha = 0f
            ObjectAnimator.ofFloat(view, "alpha", 0f, 1f).apply {
                duration = 300
                interpolator = AccelerateDecelerateInterpolator()
                start()
            }
            delay(300)
        }
    }

    // 页面销毁时停止动画
    fun onPageDestroy() {
        stopBalanceAnimation()
        animationScope.cancel()
    }

=======
>>>>>>> Stashed changes
    init {
        binding.self = this

        binding.colorClashStarted = context.resolveThemedColor(com.google.android.material.R.attr.colorPrimary)
        binding.colorClashStopped = context.resolveThemedColor(R.attr.colorClashStopped)
    }

    fun request(request: Request) {
        requests.trySend(request)
    }
}
