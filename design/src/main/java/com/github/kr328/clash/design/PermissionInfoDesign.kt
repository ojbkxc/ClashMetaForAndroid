package com.github.kr328.clash.design

import android.content.Context
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.github.kr328.clash.design.databinding.ActivityPermissionInfoBinding
import com.github.kr328.clash.design.model.PermissionFeature

class PermissionInfoDesign(context: Context) : Design<Nothing>(context) {
    private val binding = ActivityPermissionInfoBinding.inflate(LayoutInflater.from(context))
    private val adapter = PermissionFeatureAdapter()

    init {
        binding.recyclerView.layoutManager = LinearLayoutManager(context)
        binding.recyclerView.adapter = adapter
        adapter.submitList(getPermissionFeatures())
    }

    override fun onCreateView(parent: ViewGroup): androidx.viewbinding.ViewBinding {
        return binding
    }

    private fun getPermissionFeatures(): List<PermissionFeature> {
        return listOf(
            PermissionFeature("对象池优化", true, true, "减少内存分配开销"),
            PermissionFeature("并发优化", true, true, "提升初始化速度"),
            PermissionFeature("Hysteria2/QUIC", true, true, "协议层面支持"),
            PermissionFeature("UDP 缓冲区优化", false, true, "需 sysctl 调整内核参数"),
            PermissionFeature("连接跟踪优化", false, true, "需调整 nf_conntrack 参数"),
            PermissionFeature("队列优化", false, true, "需调整 netdev_max_backlog"),
            PermissionFeature("BBR 算法", false, true, "需调整 TCP 拥塞控制"),
            PermissionFeature("DNS 劫持", false, true, "需 iptables 规则"),
            PermissionFeature("透明代理", false, true, "需 TPROXY/REDIRECT 规则"),
            PermissionFeature("锁定后台", false, true, "需 iptables 标记")
        )
    }

    private class PermissionFeatureAdapter : RecyclerView.Adapter<PermissionFeatureAdapter.ViewHolder>() {
        private var items = emptyList<PermissionFeature>()

        fun submitList(list: List<PermissionFeature>) {
            items = list
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val binding = com.github.kr328.clash.design.databinding.ItemPermissionFeatureBinding
                .inflate(LayoutInflater.from(parent.context), parent, false)
            return ViewHolder(binding)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            holder.bind(items[position])
        }

        override fun getItemCount(): Int = items.size

        class ViewHolder(private val binding: com.github.kr328.clash.design.databinding.ItemPermissionFeatureBinding) :
            RecyclerView.ViewHolder(binding.root) {

            fun bind(feature: PermissionFeature) {
                binding.featureName.text = feature.name
                binding.description.text = feature.description
                binding.vpnMode.text = if (feature.vpnMode) "✓" else "✗"
                binding.rootMode.text = if (feature.rootMode) "✓" else "✗"
            }
        }
    }
}