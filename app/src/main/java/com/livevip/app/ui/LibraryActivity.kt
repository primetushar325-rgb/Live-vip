package com.livevip.app.ui

import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.livevip.app.R
import com.livevip.app.core.LiveEngine
import com.livevip.app.databinding.ActivityLibraryBinding
import com.livevip.app.store.LibraryStore
import com.livevip.app.store.LibraryVideo
import com.livevip.app.store.SettingsStore
import java.io.File

/**
 * VIDEO LIBRARY — the picked-video reference list. Rows show the cached
 * thumbnail, filename, duration and resolution. SELECT makes a video the
 * current source; DELETE removes the REFERENCE only (the user's actual
 * media file is never touched).
 */
class LibraryActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLibraryBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLibraryBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or
                    WindowInsetsCompat.Type.displayCutout()
            )
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            WindowInsetsCompat.CONSUMED
        }

        binding.btnBack.setOnClickListener { finish() }
        render()
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    private fun render() {
        val container = binding.libraryList
        container.removeAllViews()
        val videos = LibraryStore.all(this).sortedByDescending { it.id }
        if (videos.isEmpty()) {
            container.addView(
                TextView(this).apply {
                    text = getString(R.string.library_empty)
                    setTextColor(getColor(R.color.text_secondary))
                    textSize = 13f
                    gravity = Gravity.CENTER
                    setPadding(32, 48, 32, 48)
                }
            )
            return
        }
        val currentUri = SettingsStore.get(this).currentVideoUri
        videos.forEach { video -> container.addView(rowFor(video, video.uri == currentUri)) }
    }

    private fun rowFor(video: LibraryVideo, selected: Boolean): View {
        val density = resources.displayMetrics.density
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(
                (12 * density).toInt(), (10 * density).toInt(),
                (12 * density).toInt(), (10 * density).toInt()
            )
            background = getDrawable(R.drawable.card_bg)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = (8 * density).toInt() }
        }

        val thumb = ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                (52 * density).toInt(), (52 * density).toInt()
            )
            scaleType = ImageView.ScaleType.CENTER_CROP
            video.thumbPath?.let { path ->
                val file = File(path)
                if (file.exists()) {
                    // Small cached thumbnail only — no per-frame bitmap decode.
                    setImageBitmap(BitmapFactory.decodeFile(path))
                }
            }
            if (drawable == null) setImageResource(R.drawable.ic_folder_video)
            clipToOutline = false
        }

        val label = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setPadding((12 * density).toInt(), 0, (8 * density).toInt(), 0)
        }
        label.addView(
            TextView(this).apply {
                text = if (selected) "● ${video.name}" else video.name
                setTextColor(getColor(R.color.text_primary))
                textSize = 14f
                isSingleLine = true
                ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
            }
        )
        label.addView(
            TextView(this).apply {
                text = "${video.durationLabel()} • ${video.resolutionLabel()} • ${video.fps} FPS"
                setTextColor(getColor(R.color.text_secondary))
                textSize = 12f
            }
        )

        val select = Button(this).apply {
            text = getString(R.string.action_select)
            minHeight = 44
            textSize = 12f
            backgroundTintList = android.content.res.ColorStateList.valueOf(
                getColor(if (selected) R.color.primary_purple_dark else R.color.card_graphite_high)
            )
            setOnClickListener { selectVideo(video) }
        }
        val delete = Button(this).apply {
            text = getString(R.string.action_delete)
            minHeight = 44
            textSize = 12f
            backgroundTintList = android.content.res.ColorStateList.valueOf(
                getColor(R.color.card_graphite_high)
            )
            setOnClickListener {
                // Removes the REFERENCE (and our thumbnail). The user's
                // media file is never deleted.
                LibraryStore.remove(this@LibraryActivity, video.id)
                if (SettingsStore.get(this@LibraryActivity).currentVideoUri == video.uri) {
                    SettingsStore.get(this@LibraryActivity).currentVideoUri = ""
                    SettingsStore.get(this@LibraryActivity).currentVideoJson = ""
                }
                Toast.makeText(
                    this@LibraryActivity,
                    getString(R.string.library_reference_removed, video.name),
                    Toast.LENGTH_SHORT
                ).show()
                render()
            }
        }

        row.addView(thumb)
        row.addView(label)
        row.addView(select)
        row.addView(delete)
        return row
    }

    private fun selectVideo(video: LibraryVideo) {
        if (LiveEngine.isBroadcasting) {
            Toast.makeText(this, R.string.settings_locked_while_live, Toast.LENGTH_SHORT).show()
            return
        }
        val settings = SettingsStore.get(this)
        settings.currentVideoUri = video.uri
        settings.currentVideoJson = video.toJson().toString()
        if (LiveEngine.mode != LiveEngine.Mode.VIDEO) {
            LiveEngine.setMode(LiveEngine.Mode.VIDEO)
        }
        Toast.makeText(this, getString(R.string.video_selected_toast, video.name), Toast.LENGTH_SHORT)
            .show()
        finish()
    }
}
