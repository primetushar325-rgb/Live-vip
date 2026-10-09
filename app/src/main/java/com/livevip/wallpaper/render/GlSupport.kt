package com.livevip.wallpaper.render

import android.app.ActivityManager
import android.content.Context

/** Device GL capability queries used to choose the preview context version. */
object GlSupport {
    /** Returns 3 when the driver advertises OpenGL ES 3.0+, otherwise 2. */
    fun preferredClientVersion(context: Context): Int {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val reqVersion = am.deviceConfigurationInfo.reqGlEsVersion
        return if (reqVersion >= 0x30000) 3 else 2
    }

    /** Text such as "OpenGL ES 3.2" from the platform configuration, or a fallback label. */
    fun describe(context: Context): String {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val v = am.deviceConfigurationInfo.reqGlEsVersion
        if (v <= 0) return "OpenGL ES version not reported"
        return "OpenGL ES ${v shr 16}.${v and 0xFFFF}"
    }
}
