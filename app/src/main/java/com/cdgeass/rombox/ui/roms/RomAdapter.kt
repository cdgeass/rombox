package com.cdgeass.rombox.ui.roms

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.cdgeass.rombox.core.rom.RomFile
import com.cdgeass.rombox.databinding.ItemRomBinding

class RomAdapter(
    private val onClick: (RomFile) -> Unit,
    private val onAction: (RomFile) -> Unit,
) : ListAdapter<RomFile, RomAdapter.Holder>(DIFF) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val binding = ItemRomBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return Holder(binding)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) = holder.bind(getItem(position))

    inner class Holder(private val binding: ItemRomBinding) : RecyclerView.ViewHolder(binding.root) {

        fun bind(item: RomFile) {
            binding.badge.text = item.region?.take(3)?.uppercase() ?: "GBA"
            binding.title.text = item.title
            binding.subtitle.text = if (item.title == item.file.nameWithoutExtension) {
                item.subtitle
            } else {
                "${item.subtitle} · ${item.name}"
            }
            binding.root.setOnClickListener { onClick(item) }
            binding.btnMore.setOnClickListener { onAction(item) }
        }
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<RomFile>() {
            override fun areItemsTheSame(oldItem: RomFile, newItem: RomFile): Boolean =
                oldItem.path == newItem.path

            override fun areContentsTheSame(oldItem: RomFile, newItem: RomFile): Boolean =
                oldItem == newItem
        }
    }
}
