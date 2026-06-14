package com.github.kr328.clash.design

import android.animation.Animator
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.content.Context
import android.content.res.ColorStateList
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import com.github.kr328.clash.core.model.TunnelState
import com.github.kr328.clash.core.util.trafficTotal
import com.github.kr328.clash.design.databinding.DesignMainBinding
import com.github.kr328.clash.design.util.layoutInflater
import com.github.kr328.clash.design.util.resolveThemedColor
import com.github.kr328.clash.design.util.root
import kotlinx.coroutines.Dispatchers
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

    suspend fun setProfileName(name: String?) {
        withContext(Dispatchers.Main) {
            binding.profileName = name
        }
    }

    suspend fun setClashRunning(running: Boolean) {
        withContext(Dispatchers.Main) {
            binding.clashRunning = running
        }
    }

    suspend fun setForwarded(value: Long) {
        withContext(Dispatchers.Main) {
            binding.forwarded = value.trafficTotal()
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

    suspend fun setEmail(email: String?) {
        withContext(Dispatchers.Main) {
            binding.email = email
        }
    }

    suspend fun setPlanName(name: String?) {
        withContext(Dispatchers.Main) {
            binding.cardProfile.trailingText = name
        }
    }

    suspend fun setBalance(balance: String?) {
        withContext(Dispatchers.Main) {
            binding.cardProfile.trailingText2 = balance
        }
    }

    suspend fun setProfileFlowInfo(info: String?) {
        withContext(Dispatchers.Main) {
            binding.profileFlowInfo = info
        }
    }

    suspend fun setProfileFlowProgress(progress: Int) {
        withContext(Dispatchers.Main) {
            binding.profileFlowProgress = progress
            val color = when {
                progress >= 800 -> 0xFFF44336.toInt()
                progress >= 500 -> 0xFFFF9800.toInt()
                progress > 0    -> 0xFF4CAF50.toInt()
                else -> return@withContext
            }
            binding.profileFlowProgressBar.progressTintList =
                ColorStateList.valueOf(color)
        }
    }

    private var breathingAnimation: AnimatorSet? = null

    fun startLogoBreathingAnimation() {
        breathingAnimation?.cancel()
        val logoView = binding.logoView
        val scaleUpX = ObjectAnimator.ofFloat(logoView, "scaleX", 1.0f, 1.15f)
        val scaleUpY = ObjectAnimator.ofFloat(logoView, "scaleY", 1.0f, 1.15f)
        val scaleDownX = ObjectAnimator.ofFloat(logoView, "scaleX", 1.15f, 1.0f)
        val scaleDownY = ObjectAnimator.ofFloat(logoView, "scaleY", 1.15f, 1.0f)

        scaleUpX.duration = 800
        scaleUpY.duration = 800
        scaleDownX.duration = 800
        scaleDownY.duration = 800

        val breatheIn = AnimatorSet().apply { playTogether(scaleUpX, scaleUpY) }
        val breatheOut = AnimatorSet().apply { playTogether(scaleDownX, scaleDownY) }

        breathingAnimation = AnimatorSet().apply {
            playSequentially(breatheIn, breatheOut)
            interpolator = AccelerateDecelerateInterpolator()
            addListener(object : Animator.AnimatorListener {
                override fun onAnimationRepeat(animation: Animator) {}
                override fun onAnimationEnd(animation: Animator) {
                    startLogoBreathingAnimation()
                }
                override fun onAnimationCancel(animation: Animator) {}
                override fun onAnimationStart(animation: Animator) {}
            })
            start()
        }
    }

    fun stopLogoBreathingAnimation() {
        breathingAnimation?.cancel()
        breathingAnimation = null
        binding.logoView.scaleX = 1.0f
        binding.logoView.scaleY = 1.0f
    }

    init {
        binding.self = this

        binding.colorClashStarted = context.resolveThemedColor(com.google.android.material.R.attr.colorPrimary)
        binding.colorClashStopped = context.resolveThemedColor(R.attr.colorClashStopped)
    }

    fun request(request: Request) {
        requests.trySend(request)
    }
}