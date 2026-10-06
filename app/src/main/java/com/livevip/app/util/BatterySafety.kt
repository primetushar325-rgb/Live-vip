package com.livevip.app.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings

/**
 * BACKGROUND SAFETY — honest assessment, never a false promise.
 *
 * Android/OEM battery optimization CAN kill background services. We detect
 * the risk, explain it, and guide the user to the right settings. We never
 * claim Android cannot kill us.
 */
object BatterySafety {

    data class Assessment(
        /** True when the app is exempt from Doze-style battery optimization. */
        val ignoringOptimizations: Boolean,
        /** Manufacturer-specific guidance needed (Xiaomi/OPPO/Vivo/Huawei…). */
        val oemRisk: Boolean,
        val manufacturer: String,
        /** Human-readable guidance shown with the FIX NOW flow. */
        val guidance: String
    )

    fun assess(context: Context): Assessment {
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        val ignoring = pm?.isIgnoringBatteryOptimizations(context.packageName) ?: false
        val manufacturer = Build.MANUFACTURER ?: ""
        val oem = listOf(
            "xiaomi", "redmi", "oppo", "vivo", "realme", "oneplus",
            "huawei", "honor", "tecno", "infinix", "itel"
        ).any { manufacturer.lowercase().contains(it) }

        val guidance = buildString {
            append("Long background live streams depend on Android allowing ")
            append("Live VIP to run without battery restrictions.\n\n")
            append("Recommended:\n")
            append("• Battery optimization: Unrestricted (Don't optimize)\n")
            if (oem) {
                append("• ${manufacturer} security app: allow Autostart for Live VIP\n")
                append("• Lock Live VIP in recents / enable background activity\n")
            }
            append("\nAndroid may still stop any app in extreme cases (low memory, ")
            append("thermal shutdown). The persistent notification keeps the ")
            append("stream's foreground priority as high as the platform allows.")
        }

        return Assessment(
            ignoringOptimizations = ignoring,
            oemRisk = oem,
            manufacturer = manufacturer,
            guidance = guidance
        )
    }

    /** True when the risk banner should be shown. */
    fun shouldWarn(context: Context): Boolean = !assess(context).ignoringOptimizations

    /** Intent that opens the battery-optimization exemption dialog for this app. */
    fun exemptionIntent(context: Context): Intent =
        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = Uri.parse("package:${context.packageName}")
        }

    /** Fallback: general battery optimization list. */
    fun optimizationListIntent(): Intent =
        Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
}
