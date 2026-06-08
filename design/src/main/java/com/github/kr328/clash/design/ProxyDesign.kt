package com.github.kr328.clash.design

import android.content.Context
import android.content.res.ColorStateList
import android.view.View
import android.widget.Toast
import androidx.viewpager2.widget.ViewPager2
import com.github.kr328.clash.core.model.Proxy
import com.github.kr328.clash.core.model.TunnelState
import com.github.kr328.clash.design.adapter.ProxyAdapter
import com.github.kr328.clash.design.adapter.ProxyPageAdapter
import com.github.kr328.clash.design.component.ProxyMenu
import com.github.kr328.clash.design.component.ProxyViewConfig
import com.github.kr328.clash.design.databinding.DesignProxyBinding
import com.github.kr328.clash.design.model.ProxyState
import com.github.kr328.clash.design.store.UiStore
import com.github.kr328.clash.design.util.applyFrom
import com.github.kr328.clash.design.util.layoutInflater
import com.github.kr328.clash.design.util.resolveThemedColor
import com.github.kr328.clash.design.util.root
import com.google.android.material.tabs.TabLayoutMediator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class ProxyDesign(
    context: Context,
    overrideMode: TunnelState.Mode?,
    groupNames: List<String>,
    private val uiStore: UiStore,
) : Design<ProxyDesign.Request>(context) {
    sealed class Request {
        object ReloadAll : Request()
        object ReLaunch : Request()

        data class PatchMode(val mode: TunnelState.Mode?) : Request()
        data class Reload(val index: Int) : Request()
        data class Select(val index: Int, val name: String) : Request()
        data class UrlTest(val index: Int) : Request()
    }

    private val binding = DesignProxyBinding
        .inflate(context.layoutInflater, context.root, false)

    private var config = ProxyViewConfig(context, uiStore.proxyLine)

    private val menu: ProxyMenu by lazy {
        ProxyMenu(context, binding.menuView, overrideMode, uiStore, requests) {
            config.proxyLine = uiStore.proxyLine
        }
    }

    private val adapter: ProxyPageAdapter
        get() = binding.pagesView.adapter!! as ProxyPageAdapter

    private var horizontalScrolling = false
    private val verticalBottomScrolled: Boolean
        get() = adapter.states[binding.pagesView.currentItem].bottom
    private var urlTesting: Boolean
        get() = adapter.states[binding.pagesView.currentItem].urlTesting
        set(value) {
            adapter.states[binding.pagesView.currentItem].urlTesting = value
        }

    override val root: View = binding.root

    suspend fun updateGroup(
        position: Int,
        proxies: List<Proxy>,
        selectable: Boolean,
        parent: ProxyState,
        links: Map<String, ProxyState>
    ) {
        // 应用区域过滤器
        val filteredProxies = if (uiStore.proxyRegionFilter.isNotEmpty()) {
            try {
                val pattern = Regex(uiStore.proxyRegionFilter, RegexOption.IGNORE_CASE)
                proxies.filter { proxy ->
                    pattern.containsMatchIn(proxy.title) || pattern.containsMatchIn(proxy.subtitle)
                }
            } catch (e: Exception) {
                // 正则表达式无效时不过滤
                proxies
            }
        } else {
            proxies
        }

        adapter.updateAdapter(position, filteredProxies, selectable, parent, links)

        adapter.states[position].urlTesting = false

        updateUrlTestButtonStatus()
    }

    suspend fun requestRedrawVisible() {
        withContext(Dispatchers.Main) {
            adapter.requestRedrawVisible()
        }
    }

    suspend fun showModeSwitchTips() {
        withContext(Dispatchers.Main) {
            Toast.makeText(context, R.string.mode_switch_tips, Toast.LENGTH_LONG).show()
        }
    }

    init {
        binding.self = this

        binding.activityBarLayout.applyFrom(context)

        binding.menuView.setOnClickListener {
            menu.show()
        }

        if (groupNames.isEmpty()) {
            binding.emptyView.visibility = View.VISIBLE

            binding.urlTestView.visibility = View.GONE
            binding.tabLayoutView.visibility = View.GONE
            binding.elevationView.visibility = View.GONE
            binding.pagesView.visibility = View.GONE
            binding.urlTestFloatView.visibility = View.GONE
        } else {
            binding.urlTestFloatView.supportImageTintList = ColorStateList.valueOf(
                context.resolveThemedColor(com.google.android.material.R.attr.colorOnPrimary)
            )

            binding.pagesView.apply {
                adapter = ProxyPageAdapter(
                    surface,
                    config,
                    List(groupNames.size) { index ->
                        ProxyAdapter(config) { name ->
                            requests.trySend(Request.Select(index, name))
                        }
                    }
                ) {
                    if (it == currentItem)
                        updateUrlTestButtonStatus()
                }

                registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
                    override fun onPageScrollStateChanged(state: Int) {
                        horizontalScrolling = state != ViewPager2.SCROLL_STATE_IDLE

                        updateUrlTestButtonStatus()
                    }

                    override fun onPageSelected(position: Int) {
                        uiStore.proxyLastGroup = groupNames[position]
                    }
                })
            }

            TabLayoutMediator(binding.tabLayoutView, binding.pagesView) { tab, index ->
                tab.text = groupNames[index]
                val iconRes = getGroupIconRes(groupNames[index])
                if (iconRes != null) {
                    tab.icon = androidx.core.content.ContextCompat.getDrawable(context, iconRes)
                }
            }.attach()

            val initialPosition = groupNames.indexOf(uiStore.proxyLastGroup)

            binding.pagesView.post {
                if (initialPosition > 0)
                    binding.pagesView.setCurrentItem(initialPosition, false)
            }
        }
    }

    fun requestUrlTesting() {
        urlTesting = true

        requests.trySend(Request.UrlTest(binding.pagesView.currentItem))

        updateUrlTestButtonStatus()
    }

    private fun updateUrlTestButtonStatus() {
        if (verticalBottomScrolled || horizontalScrolling || urlTesting) {
            binding.urlTestFloatView.hide()
        } else {
            binding.urlTestFloatView.show()
        }

        if (urlTesting) {
            binding.urlTestView.visibility = View.GONE
            binding.urlTestProgressView.visibility = View.VISIBLE
        } else {
            binding.urlTestView.visibility = View.VISIBLE
            binding.urlTestProgressView.visibility = View.GONE
        }
    }

    companion object {
        // 根据代理组名称返回对应的图标资源 ID
        fun getGroupIconRes(groupName: String): Int? {
            return when {
                groupName.contains("香港") || groupName.contains("HK") -> R.drawable.ic_region_hk
                groupName.contains("日本") || groupName.contains("JP") -> R.drawable.ic_region_jp
                groupName.contains("美国") || groupName.contains("US") -> R.drawable.ic_region_us
                groupName.contains("新加坡") || groupName.contains("SG") -> R.drawable.ic_region_sg
                groupName.contains("台湾") || groupName.contains("TW") -> R.drawable.ic_region_cn
                groupName.contains("韩国") || groupName.contains("KR") -> R.drawable.ic_region_cn
                groupName.contains("中国") || groupName.contains("CN") -> R.drawable.ic_region_cn
                groupName.contains("全球") || groupName.contains("直连") -> R.drawable.ic_globe
                groupName.contains("拦截") || groupName.contains("广告") -> R.drawable.ic_no_ads
                groupName.contains("选择") || groupName.contains("节点") -> R.drawable.ic_region_all
                groupName.contains("自动") -> R.drawable.ic_region_all
                groupName.contains("Google") -> R.drawable.ic_google
                groupName.contains("YouTube") -> R.drawable.ic_youtube
                groupName.contains("Telegram") -> R.drawable.ic_telegram
                groupName.contains("Twitter") -> R.drawable.ic_twitter
                groupName.contains("Netflix") -> R.drawable.ic_netflix
                groupName.contains("OpenAI") || groupName.contains("AI") -> R.drawable.ic_openai
                groupName.contains("Steam") || groupName.contains("游戏") -> R.drawable.ic_steam
                groupName.contains("Discord") -> R.drawable.ic_discord
                groupName.contains("GitHub") -> R.drawable.ic_github
                groupName.contains("微软") || groupName.contains("Microsoft") -> R.drawable.ic_microsoft
                groupName.contains("Apple") || groupName.contains("苹果") -> R.drawable.ic_apple
                groupName.contains("TikTok") -> R.drawable.ic_tiktok
                groupName.contains("Spotify") -> R.drawable.ic_spotify
                groupName.contains("Facebook") -> R.drawable.ic_facebook
                groupName.contains("BiliBili") || groupName.contains("B站") -> R.drawable.ic_bilibili
                groupName.contains("抖音") -> R.drawable.ic_douyin
                groupName.contains("小红书") -> R.drawable.ic_xiaohongshu
                groupName.contains("DNS") -> R.drawable.ic_dns_custom
                groupName.contains("流媒体") || groupName.contains("解锁") -> R.drawable.ic_globe
                else -> null
            }
        }
    }
}