package com.livevip.app.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.PopupMenu
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.textfield.TextInputEditText
import com.livevip.app.R
import com.livevip.app.data.SettingsRepository
import com.livevip.app.databinding.ActivityVideoLibraryBinding
import com.livevip.app.databinding.ItemVideoBinding
import com.livevip.app.media.MediaAnalyzer
import com.livevip.app.media.VideoItem
import com.livevip.app.media.VideoRepository
import com.livevip.app.streaming.LiveStreamingManager
import java.util.concurrent.Executors

/**
 * Video Library — reference-based management of imported videos.
 * Select for live, rename, delete, info. No file duplication ever.
 */
class VideoLibraryActivity : AppCompatActivity() {

    private lateinit var binding: ActivityVideoLibraryBinding
    private lateinit var repo: VideoRepository
    private lateinit var settings: SettingsRepository
    private val bgExecutor = Executors.newSingleThreadExecutor()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityVideoLibraryBinding.inflate(layoutInflater)
        setContentView(binding.root)

        repo = VideoRepository.get(this)
        settings = SettingsRepository.get(this)

        binding.toolbar.setNavigationOnClickListener { finish() }
        binding.recycler.layoutManager = LinearLayoutManager(this)
        refresh()
    }

    private fun refresh() {
        val items = repo.all().sortedByDescending { it.id }
        binding.emptyText.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        binding.recycler.adapter = Adapter(items)
    }

    private inner class Adapter(private val items: List<VideoItem>) :
        RecyclerView.Adapter<Adapter.Holder>() {

        inner class Holder(val b: ItemVideoBinding) : RecyclerView.ViewHolder(b.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
            Holder(ItemVideoBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun getItemCount(): Int = items.size

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val item = items[position]
            val b = holder.b
            b.videoName.text = item.name
            b.videoMeta.text = item.metaLabel()
            b.selectedBadge.visibility =
                if (item.id == settings.selectedVideoId) View.VISIBLE else View.GONE
            b.videoThumb.setImageDrawable(null)
            bgExecutor.execute {
                val thumb = MediaAnalyzer.thumbnail(this@VideoLibraryActivity, item.uriParsed())
                runOnUiThread {
                    if (!isFinishing && thumb != null) b.videoThumb.setImageBitmap(thumb)
                }
            }

            b.btnUse.setOnClickListener { selectForLive(item) }
            b.root.setOnClickListener { selectForLive(item) }
            b.btnMore.setOnClickListener { view -> showMenu(view, item) }
        }
    }

    private fun selectForLive(item: VideoItem) {
        if (LiveStreamingManager.isStreaming) {
            Snackbar.make(binding.root, R.string.video_locked_while_live, Snackbar.LENGTH_LONG)
                .show()
            return
        }
        settings.selectedVideoId = item.id
        LiveStreamingManager.invalidateVideoSource()
        Snackbar.make(
            binding.root,
            getString(R.string.video_selected_format, item.name),
            Snackbar.LENGTH_SHORT
        ).show()
        refresh()
    }

    private fun showMenu(anchor: View, item: VideoItem) {
        val menu = PopupMenu(this, anchor)
        menu.menu.add(0, 1, 0, getString(R.string.select_for_live))
        menu.menu.add(0, 2, 1, getString(R.string.rename))
        menu.menu.add(0, 3, 2, getString(R.string.video_info))
        menu.menu.add(0, 4, 3, getString(R.string.delete))
        menu.setOnMenuItemClickListener { mi ->
            when (mi.itemId) {
                1 -> selectForLive(item)
                2 -> promptRename(item)
                3 -> showInfo(item)
                4 -> confirmDelete(item)
            }
            true
        }
        menu.show()
    }

    private fun promptRename(item: VideoItem) {
        val input = TextInputEditText(this).apply {
            setText(item.name)
            setPadding(48, 48, 48, 24)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.rename)
            .setView(input)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.save) { _, _ ->
                val name = input.text?.toString()?.trim().orEmpty()
                if (name.isNotEmpty()) {
                    repo.rename(item.id, name)
                    refresh()
                }
            }
            .show()
    }

    private fun showInfo(item: VideoItem) {
        val mb = item.sizeBytes / (1024.0 * 1024.0)
        val info = StringBuilder()
            .append(getString(R.string.info_name, item.name)).append('\n')
            .append(getString(R.string.info_resolution, item.width, item.height)).append('\n')
            .append(getString(R.string.info_fps, item.fps)).append('\n')
            .append(getString(R.string.info_duration, item.durationLabel())).append('\n')
            .append(
                getString(
                    R.string.info_audio,
                    if (item.hasAudio) "${item.sampleRate} Hz, ${item.channels} ch"
                    else getString(R.string.no_audio)
                )
            ).append('\n')
            .append(getString(R.string.info_size, String.format("%.1f MB", mb)))
            .toString()
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.video_info)
            .setMessage(info)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun confirmDelete(item: VideoItem) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.delete)
            .setMessage(getString(R.string.delete_video_confirm, item.name))
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.delete) { _, _ ->
                repo.remove(item.id)
                if (settings.selectedVideoId == item.id) {
                    settings.selectedVideoId = 0L
                    LiveStreamingManager.invalidateVideoSource()
                }
                refresh()
            }
            .show()
    }
}
