package com.livevip.wallpaper.wallpaper

import android.app.WallpaperManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import com.livevip.wallpaper.service.LiveWallpaperService

/**
 * Answers "is our live wallpaper the active one?" from the system, so the UI only reports success
 * after Android has actually applied it.
 */
object WallpaperStatus {
    fun component(context: Context): ComponentName = ComponentName(context, LiveWallpaperService::class.java)

    fun isActive(context: Context): Boolean {
        val info = WallpaperManager.getInstance(context).wallpaperInfo ?: return false
        return info.component == component(context)
    }

    /** Opens the system live-wallpaper preview with this app's wallpaper preselected (user must confirm). */
    fun previewIntent(context: Context): Intent =
        Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER)
            .putExtra(WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT, component(context))

    /** Generic live-wallpaper chooser, used when the direct preview is not handled. */
    fun chooserIntent(): Intent = Intent(WallpaperManager.ACTION_LIVE_WALLPAPER_CHOOSER)
}
