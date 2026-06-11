package com.github.kr328.clash.design.adapter

import android.content.Context
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.github.kr328.clash.design.databinding.AdapterFileBinding
import com.github.kr328.clash.design.model.File
import com.github.kr328.clash.design.ui.ObservableCurrentTime

class FileAdapter(
    private val context: Context,
    private val open: (File) -> Unit,
    private val more: (File) -> Unit,
) : ListAdapter<File, FileAdapter.Holder>(FileDiff) {
    class Holder(val binding: AdapterFileBinding) : RecyclerView.ViewHolder(binding.root)

    private val currentTime = ObservableCurrentTime()

    fun updateElapsed() {
        currentTime.update()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        return Holder(
            AdapterFileBinding
                .inflate(LayoutInflater.from(parent.context), parent, false)
                .also { it.currentTime = currentTime }
        )
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val current = getItem(position)

        holder.binding.apply {
            file = current

            setOpen {
                open(current)
            }

            setMore {
                more(current)
            }
        }
    }

    companion object {
        val FileDiff = object : DiffUtil.ItemCallback<File>() {
            override fun areItemsTheSame(oldItem: File, newItem: File): Boolean =
                oldItem.id == newItem.id

            override fun areContentsTheSame(oldItem: File, newItem: File): Boolean =
                oldItem == newItem
        }
    }
}