package com.livevip.wallpaper.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            LiveVipTheme {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    AppRoot()
                }
            }
        }
    }
}

/** Screen identifiers. Kept as strings so they survive configuration changes via rememberSaveable. */
private object Route {
    const val LIBRARY = "library"
    const val IMPORT = "import"
    const val PREVIEW = "preview"
    const val EFFECTS = "effects"
    const val MOTION = "motion"
    const val APPLY = "apply"
    const val PERFORMANCE = "performance"
}

@Composable
private fun AppRoot(vm: AppViewModel = viewModel()) {
    var route by rememberSaveable { mutableStateOf(Route.LIBRARY) }
    var projectId by rememberSaveable { mutableStateOf<String?>(null) }

    fun go(next: String, id: String? = projectId) {
        projectId = id
        route = next
    }

    BackHandler(enabled = route != Route.LIBRARY) {
        when (route) {
            Route.IMPORT, Route.PERFORMANCE -> go(Route.LIBRARY)
            Route.EFFECTS, Route.MOTION, Route.PREVIEW -> go(Route.LIBRARY)
            Route.APPLY -> go(Route.PREVIEW)
            else -> go(Route.LIBRARY)
        }
    }

    val id = projectId
    when {
        route == Route.IMPORT -> ImportScreen(vm, onDone = { go(Route.LIBRARY) }, onBack = { go(Route.LIBRARY) })
        route == Route.PERFORMANCE -> PerformanceScreen(vm, onBack = { go(Route.LIBRARY) })
        route == Route.PREVIEW && id != null -> PreviewScreen(
            vm = vm,
            projectId = id,
            onBack = { go(Route.LIBRARY) },
            onEffects = { go(Route.EFFECTS) },
            onMotion = { go(Route.MOTION) },
            onApply = { go(Route.APPLY) },
        )
        route == Route.EFFECTS && id != null -> EffectsScreen(vm, id, onBack = { go(Route.PREVIEW) }, onPreview = { go(Route.PREVIEW) })
        route == Route.MOTION && id != null -> MotionScreen(vm, id, onBack = { go(Route.PREVIEW) }, onPreview = { go(Route.PREVIEW) })
        route == Route.APPLY && id != null -> ApplyScreen(vm, id, onBack = { go(Route.PREVIEW) })
        else -> LibraryScreen(
            vm = vm,
            onOpenPreview = { go(Route.PREVIEW, it) },
            onOpenEffects = { go(Route.EFFECTS, it) },
            onImport = { go(Route.IMPORT) },
            onPerformance = { go(Route.PERFORMANCE) },
        )
    }
}
