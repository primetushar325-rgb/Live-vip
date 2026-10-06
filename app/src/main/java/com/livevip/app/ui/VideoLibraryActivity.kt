package com.livevip.app.ui

import android.content.Intent
import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.PopupMenu
import android.widget.VideoView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.textfield.TextInputEditText
import com.livevip.app.R
import com.livevip.app.data.ProjectRepository
import com.livevip.app.data.SettingsRepository
import com.livevip.app.databinding.ActivityVideoLibraryBinding
import com.livevip.app.databinding.ItemVideoBinding
import com.livevip.app.media.MediaAnalyzer
import com.livevip.app.data.ThumbnailCache
import com.livevip.app.media.VideoItem
import com.livevip.app.media.VideoRepository
import com.livevip.app.streaming.LiveStreamingManager
import java.util.concurrent.Executors

/**
 * VIDEO LIBRARY — reference-based management of imported videos.
 *
 * Every item shows: thumbnail, filename, duration, resolution, FPS, video
 * codec, audio codec, file size. Actions: Preview, Select, Rename, Delete,
 * Details, Add to Playlist. Videos are NEVER duplicated — only content-URI
 * references with persisted permissions are stored.
 */
class VideoLibraryActivity : AppCompatActivity() {

    private lateinit var binding: ActivityVideoLibraryBinding
    private lateinit var repo: VideoRepository
    private lateinit var settings: SettingsRepository
    private lateinit var projects: ProjectRepository
    private lateinit var thumbs: ThumbnailCache
    private val bgExecutor = Executors.newSingleThreadExecutor()

    private val videoPickerLauncher =
        registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) importVideo(uri)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityVideoLibraryBinding.inflate(layoutInflater)
        setContentView(binding.root)

        repo = VideoRepository.get(this)
        settings = SettingsRepository.get(this)
        projects = ProjectRepository.get(this)
        thumbs = ThumbnailCache.get(this)

        binding.btnBack.setOnClickListener { finish() }
        binding.btnImport.setOnClickListener { openPicker() }
        binding.libraryList.layoutManager = LinearLayoutManager(this)
        refresh()
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun openPicker() {
        try {
            videoPickerLauncher.launch(arrayOf("video/*"))
        } catch (t: Throwable) {
            Snackbar.make(binding.root, R.string.picker_unavailable, Snackbar.LENGTH_LONG).show()
        }
    }

    private fun importVideo(uri: Uri) {
        Snackbar.make(binding.root, R.string.analyzing_video, Snackbar.LENGTH_SHORT).show()
        bgExecutor.execute {
            val info = MediaAnalyzer.analyze(this, uri)
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (info == null) {
                    MaterialAlertDialogBuilder(this)
                        .setTitle(R.string.unsupported_video_title)
                        .setMessage(R.string.unsupported_video_message)
                        .setPositiveButton(android.R.string.ok, null)
                        .show()
                    return@runOnUiThread
                }
                repo.add(uri, info)
                refresh()
            }
        }
    }

    private fun refresh() {
        val items = repo.all().sortedByDescending { it.id }
        binding.emptyLibrary.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        binding.libraryList.adapter = Adapter(items)
    }

    // ------------------------------------------------------------------
    // Item actions
    // ------------------------------------------------------------------

    private fun selectForLive(item: VideoItem) {
        settings.selectedVideoId = item.id
        LiveStreamingManager.invalidateVideoSource()
        refresh()
        Snackbar.make(
            binding.root,
            getString(R.string.selected_video_format, item.name),
            Snackbar.LENGTH_SHORT
        ).show()
    }

    private fun preview(item: VideoItem) {
        val video = VideoView(this)
        video.setVideoURI(item.uriParsed())
        video.setOnPreparedListener { player: MediaPlayer -> player.isLooping = true }
        video.start()
        MaterialAlertDialogBuilder(this)
            .setTitle(item.name)
            .setView(video)
            .setOnDismissListener { video.stopPlayback() }
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun details(item: VideoItem) {
        MaterialAlertDialogBuilder(this)
            .setTitle(item.name)
            .setMessage(
                getString(R.string.info_resolution, item.displayWidth, item.displayHeight) + "\n" +
                    getString(R.string.info_fps, item.fps) + "\n" +
                    getString(R.string.info_duration, item.durationLabel()) + "\n" +
                    getString(R.string.info_codec, item.codecLabel()) + "\n" +
                    getString(R.string.info_audio, if (item.hasAudio)
                        "${item.sampleRate} Hz • ${if (item.channels >= 2) "stereo" else "mono"}"
                    else getString(R.string.no_audio)) + "\n" +
                    getString(R.string.info_size, item.sizeLabel())
            )
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun rename(item: VideoItem) {
        val input = TextInputEditText(this).apply {
            setText(item.name)
            setPadding(48, 48, 48, 24)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.rename_video)
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

    private fun confirmDelete(item: VideoItem) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.delete)
            .setMessage(getString(R.string.delete_video_confirm, item.name))
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.delete) { _, _ ->
                repo.remove(item.id)
                thumbs.invalidate(item.id)
                if (settings.selectedVideoId == item.id) settings.selectedVideoId = 0
                refresh()
            }
            .show()
    }

    private fun addToPlaylist(item: VideoItem) {
        projects.async({ it.allProjects() }) { list ->
            if (isFinishing || isDestroyed) return@async
            if (list.isEmpty()) {
                Snackbar.make(
                    binding.root,
                    R.string.no_project_hint,
                    Snackbar.LENGTH_LONG
                ).show()
                return@async
            }
            val names = list.map { it.name }.toTypedArray()
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.choose_project)
                .setItems(names) { _, which ->
                    val project = list[which]
                    projects.async({ r ->
                        val bundle = r.projectBundle(project.id)
                        if (bundle != null) {
                            r.savePlaylist(
                                project.id,
                                bundle.playlist + com.livevip.app.data.PlaylistItem(videoId = item.id)
                            )
                        }
                        true
                    }) {
                        Snackbar.make(
                            binding.root,
                            getString(R.string.added_to_playlist, project.name),
                            Snackbar.LENGTH_SHORT
                        ).show()
                    }
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
    }

    // ------------------------------------------------------------------
    // Adapter
    // ------------------------------------------------------------------

    private inner class Adapter(private val items: List<VideoItem>) :
        androidx.recyclerview.widget.RecyclerView.Adapter<Adapter.Holder>() {

        inner class Holder(val b: ItemVideoBinding) :
            androidx.recyclerview.widget.RecyclerView.ViewHolder(b.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
            Holder(ItemVideoBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun getItemCount(): Int = items.size

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val item = items[position]
            val b = holder.b
            b.videoName.text = item.name
            b.videoMeta.text = "${item.resolutionLabel()} • ${item.fps} FPS • " +
                "${item.codecLabel()} • ${item.sizeLabel()}"
            b.videoDuration.text = item.durationLabel()
            b.selectedCheck.visibility =
                if (item.id == settings.selectedVideoId) View.VISIBLE else View.GONE

            b.videoThumb.setImageDrawable(null)
            thumbs.load(item.id, item.uri) { bmp ->
                if (!isFinishing && bmp != null) b.videoThumb.setImageBitmap(bmp)
            }

            b.chipSelect.setOnClickListener { selectForLive(item) }
            b.chipPlaylist.setOnClickListener { addToPlaylist(item) }
            b.btnVideoMore.setOnClickListener { anchor ->
                val menu = PopupMenu(this@VideoLibraryActivity, anchor)
                menu.menu.add(getString(R.string.preview_video)).setOnMenuItemClickListener {
                    preview(item); true
                }
                menu.menu.add(getString(R.string.select)).setOnMenuItemClickListener {
                    selectForLive(item); true
                }
                menu.menu.add(getString(R.string.rename)).setOnMenuItemClickListener {
                    rename(item); true
                }
                menu.menu.add(getString(R.string.details)).setOnMenuItemClickListener {
                    details(item); true
                }
                menu.menu.add(getString(R.string.add_to_playlist)).setOnMenuItemClickListener {
                    addToPlaylist(item); true
                }
                menu.menu.add(getString(R.string.delete)).setOnMenuItemClickListener {
                    confirmDelete(item); true
                }
                menu.show()
            }
        }
    }
}
