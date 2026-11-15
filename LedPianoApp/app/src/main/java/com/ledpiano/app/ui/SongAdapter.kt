package com.ledpiano.app.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.ledpiano.app.databinding.ItemSongBinding
import com.ledpiano.app.util.MidiIo

/**
 * Recent-songs list. Tap opens the song; long-press asks to remove it.
 * Entries are "uri|displayName" strings as stored in
 * [com.ledpiano.app.model.AppSettings.recentSongs].
 */
class SongAdapter(
    private val items: MutableList<String>,
    private val onClick: (entry: String) -> Unit,
    private val onLongPress: (entry: String) -> Unit,
) : RecyclerView.Adapter<SongAdapter.Holder>() {

    class Holder(val binding: ItemSongBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val binding = ItemSongBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return Holder(binding)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val entry = items[position]
        val sep = entry.indexOf('|')
        val uri = if (sep >= 0) entry.take(sep) else entry
        val name = if (sep >= 0) entry.substring(sep + 1) else entry

        holder.binding.txtSongName.text = name
        holder.binding.txtSongInfo.text = if (uri.startsWith(MidiIo.ASSET_SCHEME)) {
            "Bài tập cơ bản"
        } else {
            "Tệp MIDI"
        }
        holder.binding.root.setOnClickListener { onClick(entry) }
        holder.binding.root.setOnLongClickListener {
            onLongPress(entry)
            true
        }
    }

    fun replace(newItems: List<String>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }
}
