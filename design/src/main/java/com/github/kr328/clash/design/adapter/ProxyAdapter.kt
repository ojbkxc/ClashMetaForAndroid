package com.github.kr328.clash.design.adapter

import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.github.kr328.clash.design.component.ProxyView
import com.github.kr328.clash.design.component.ProxyViewConfig
import com.github.kr328.clash.design.component.ProxyViewState

class ProxyAdapter(
    private val config: ProxyViewConfig,
    private val clicked: (String) -> Unit,
) : ListAdapter<ProxyViewState, ProxyAdapter.Holder>(ProxyDiffCallback()) {
    
    class Holder(val view: ProxyView) : RecyclerView.ViewHolder(view)
    
    var selectable: Boolean = false
    
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        return Holder(ProxyView(config.context, config))
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val current = getItem(position)

        holder.view.apply {
            state = current

            setOnClickListener {
                clicked(current.proxy.name)
            }

            val isSelector = selectable

            isFocusable = isSelector
            isClickable = isSelector

            updateState(true)
        }
    }

    override fun getItemId(position: Int): Long {
        return getItem(position).proxy.name.hashCode().toLong()
    }

    fun updateStates(newStates: List<ProxyViewState>) {
        submitList(newStates)
    }

    private class ProxyDiffCallback : DiffUtil.ItemCallback<ProxyViewState>() {
        override fun areItemsTheSame(oldItem: ProxyViewState, newItem: ProxyViewState): Boolean {
            return oldItem.proxy.name == newItem.proxy.name
        }

        override fun areContentsTheSame(oldItem: ProxyViewState, newItem: ProxyViewState): Boolean {
            return oldItem.proxy.delay == newItem.proxy.delay &&
                   oldItem.proxy.name == newItem.proxy.name &&
                   oldItem.proxy.title == newItem.proxy.title &&
                   oldItem.proxy.subtitle == newItem.proxy.subtitle
        }
    }
}
