package com.github.kr328.clash.design.adapter

import android.content.Context
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.github.kr328.clash.design.databinding.AdapterAppBinding
import com.github.kr328.clash.design.model.AppInfo
import com.github.kr328.clash.design.util.layoutInflater
import com.github.kr328.clash.design.util.root

class AppAdapter(
    private val context: Context,
    private val selected: MutableSet<String>,
) : ListAdapter<AppInfo, AppAdapter.Holder>(AppDiffCallback()) {
    
    class Holder(val binding: AdapterAppBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        return Holder(
            AdapterAppBinding
                .inflate(context.layoutInflater, context.root, false)
        )
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val current = getItem(position)

        holder.binding.app = current
        holder.binding.selected = current.packageName in selected
        holder.binding.root.setOnClickListener {
            if (holder.binding.selected) {
                selected.remove(current.packageName)
                holder.binding.selected = false
            } else {
                selected.add(current.packageName)
                holder.binding.selected = true
            }
        }
    }

    override fun getItemId(position: Int): Long {
        return getItem(position).packageName.hashCode().toLong()
    }

    fun updateApps(newApps: List<AppInfo>) {
        submitList(newApps)
    }

    fun getCurrentApps(): List<AppInfo> {
        return currentList
    }

    private class AppDiffCallback : DiffUtil.ItemCallback<AppInfo>() {
        override fun areItemsTheSame(oldItem: AppInfo, newItem: AppInfo): Boolean {
            return oldItem.packageName == newItem.packageName
        }

        override fun areContentsTheSame(oldItem: AppInfo, newItem: AppInfo): Boolean {
            return oldItem.label == newItem.label &&
                   oldItem.packageName == newItem.packageName
        }
    }
}