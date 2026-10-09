package com.livevip.wallpaper.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

val NeonCyan = Color(0xFF00E5FF)
val NeonMagenta = Color(0xFFFF2BD6)
val NeonViolet = Color(0xFF9B5DE5)
val NightBackground = Color(0xFF0B0B12)
val NightSurface = Color(0xFF151524)
val NightSurfaceHigh = Color(0xFF1E1E33)

private val DarkScheme: ColorScheme = darkColorScheme(
    primary = NeonCyan,
    onPrimary = Color(0xFF002329),
    secondary = NeonMagenta,
    onSecondary = Color(0xFF2B0022),
    tertiary = NeonViolet,
    background = NightBackground,
    onBackground = Color(0xFFEDEDF7),
    surface = NightSurface,
    onSurface = Color(0xFFEDEDF7),
    surfaceVariant = NightSurfaceHigh,
    onSurfaceVariant = Color(0xFFB8B8CC),
    error = Color(0xFFFF6B8B),
)

@Composable
fun LiveVipTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = DarkScheme, content = content)
}

/** Dark rounded panel used for every grouped block of controls. */
@Composable
fun Panel(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = NightSurface),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            content()
        }
    }
}

/** Color swatches used for glow and tint choices. */
@Composable
fun ColorSwatches(selected: Int, options: List<Pair<String, Int>>, onSelect: (Int) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        options.forEach { (label, argb) ->
            val isSelected = selected == argb
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.clickable { onSelect(argb) },
            ) {
                Spacer(
                    Modifier
                        .size(34.dp)
                        .background(Color(argb), CircleShape)
                        .border(if (isSelected) 3.dp else 1.dp, if (isSelected) NeonCyan else Color(0x33FFFFFF), CircleShape),
                )
                Text(
                    text = if (isSelected) "$label ✓" else label,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (isSelected) NeonCyan else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, color = NeonCyan)
}

@Composable
fun Hint(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
fun VerticalSpace(height: Int) {
    Spacer(Modifier.height(height.dp))
}
