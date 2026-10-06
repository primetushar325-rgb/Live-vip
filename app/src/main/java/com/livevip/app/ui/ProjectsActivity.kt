package com.livevip.app.ui

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.PopupMenu
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.livevip.app.R
import com.livevip.app.data.Project
import com.livevip.app.data.ProjectRepository
import com.livevip.app.data.SettingsRepository
import com.livevip.app.databinding.ActivityProjectsBinding
import com.livevip.app.databinding.ItemProjectBinding
import com.livevip.app.data.ThumbnailCache
import com.livevip.app.media.VideoRepository
import java.util.Locale

/**
 * MY LIVE PROJECTS — the project hub.
 *
 * Every live configuration is a Project and stays saved after the live ends
 * (PROJECT HISTORY); a project can be reopened and reused at any time.
 */
class ProjectsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityProjectsBinding
    private lateinit var repo: ProjectRepository
    private lateinit var settings: SettingsRepository
    private lateinit var videos: VideoRepository
    private lateinit var thumbs: ThumbnailCache
    private var adapter: Adapter? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityProjectsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        repo = ProjectRepository.get(this)
        settings = SettingsRepository.get(this)
        videos = VideoRepository.get(this)
        thumbs = ThumbnailCache.get(this)

        binding.btnBack.setOnClickListener { finish() }
        binding.btnNewProject.setOnClickListener { newProject() }
        binding.projectsList.layoutManager = LinearLayoutManager(this)
        refresh()
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        repo.async({ it.allProjects() }) { projects ->
            if (isFinishing || isDestroyed) return@async
            binding.emptyProjects.visibility =
                if (projects.isEmpty()) View.VISIBLE else View.GONE
            adapter = Adapter(projects)
            binding.projectsList.adapter = adapter
        }
    }

    private fun newProject() {
        startActivity(
            Intent(this, ProjectEditorActivity::class.java)
                .putExtra(ProjectEditorActivity.EXTRA_PROJECT_ID, 0L)
        )
    }

    private fun openProject(project: Project) {
        settings.currentProjectId = project.id
        finish()
    }

    private fun editProject(project: Project) {
        startActivity(
            Intent(this, ProjectEditorActivity::class.java)
                .putExtra(ProjectEditorActivity.EXTRA_PROJECT_ID, project.id)
        )
    }

    private fun confirmDelete(project: Project) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.delete)
            .setMessage(getString(R.string.delete_project_confirm, project.name))
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.delete) { _, _ ->
                repo.async({ r -> r.deleteProject(project); true }) { refresh() }
            }
            .show()
    }

    private fun duplicate(project: Project) {
        repo.async({ r ->
            val bundle = r.projectBundle(project.id)
            if (bundle != null) {
                val copy = bundle.project.copy(
                    id = 0,
                    name = "${project.name} (copy)",
                    lastStreamedAt = 0,
                    totalStreamCount = 0
                )
                r.saveProject(copy, bundle.destinations.map { it.copy(id = 0) }, emptyMap(), bundle.playlist)
            }
            true
        }) { refresh() }
    }

    // ------------------------------------------------------------------
    // Adapter
    // ------------------------------------------------------------------

    private inner class Adapter(private val items: List<Project>) :
        androidx.recyclerview.widget.RecyclerView.Adapter<Adapter.Holder>() {

        inner class Holder(val b: ItemProjectBinding) :
            androidx.recyclerview.widget.RecyclerView.ViewHolder(b.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
            Holder(ItemProjectBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun getItemCount(): Int = items.size

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val project = items[position]
            val b = holder.b
            b.projectName.text = project.name
            b.projectSummary.text = summaryFor(project)
            b.projectThumb.setImageDrawable(null)

            repo.async({ r -> r.projectBundle(project.id) }) { bundle ->
                if (isFinishing || isDestroyed || bundle == null) return@async
                val firstVideo = bundle.playlist.firstOrNull()?.let { videos.byId(it.videoId) }
                if (firstVideo != null) {
                    thumbs.load(firstVideo.id, firstVideo.uri) { bmp ->
                        if (!isFinishing && bmp != null) b.projectThumb.setImageBitmap(bmp)
                    }
                }
            }

            b.root.setOnClickListener { openProject(project) }
            b.btnProjectMore.setOnClickListener { anchor ->
                val menu = PopupMenu(this@ProjectsActivity, anchor)
                menu.menu.add(getString(R.string.edit_live)).setOnMenuItemClickListener {
                    editProject(project); true
                }
                menu.menu.add(getString(R.string.duplicate_project)).setOnMenuItemClickListener {
                    duplicate(project); true
                }
                menu.menu.add(getString(R.string.delete)).setOnMenuItemClickListener {
                    confirmDelete(project); true
                }
                menu.show()
            }
        }
    }

    private fun summaryFor(project: Project): String {
        val duration = if (project.lastDurationSec > 0) {
            formatDuration(project.lastDurationSec)
        } else {
            getString(R.string.never_streamed)
        }
        val history = if (project.totalStreamCount > 0) {
            " • ${getString(R.string.streamed_count, project.totalStreamCount)}"
        } else ""
        return "$duration • ${project.height}p ${project.fps}fps" +
            " • ${project.broadcastMode.label}$history"
    }

    private fun formatDuration(seconds: Long): String {
        val h = seconds / 3600
        val m = (seconds % 3600) / 60
        return if (h > 0) String.format(Locale.US, "%dh %02dm", h, m)
        else String.format(Locale.US, "%dm", m)
    }
}
