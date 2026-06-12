package com.github.kr328.clash.design

import android.content.Context
import android.content.res.ColorStateList
import android.view.View
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

    suspend fun setProfileFlowInfo(info: String?) {
        withContext(Dispatchers.Main) {
            binding.profileFlowInfo = info
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
            binding.cardStatus.trailingText = name
            binding.cardStatus.setTrailingTextSize(14f)
        }
    }

    suspend fun setProfileExpiryInfo(text: String?, color: Int? = null) {
        withContext(Dispatchers.Main) {
            binding.cardStatus.trailingText2 = text
            binding.cardStatus.setTrailingText2Size(8f)
            if (color != null) {
                binding.cardStatus.setTrailingText2Color(color)
            }
        }
    }

    suspend fun setProfileBalance(balance: String?) {
        withContext(Dispatchers.Main) {
            binding.cardV2Board.trailingText = balance
        }
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
