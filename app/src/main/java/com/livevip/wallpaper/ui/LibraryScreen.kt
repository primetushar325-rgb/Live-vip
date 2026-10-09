package com.livevip.wallpaper.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.livevip.wallpaper.project.ProjectSummary

/** Home / Project Library: list of imported projects with preview, effects and management actions. */
@Composable
fun LibraryScreen(
    vm: AppViewModel,
    onOpenPreview: (String) -> Unit,
    onOpenEffects: (String) -> Unit,
    onImport: () -> Unit,
    onPerformance: () -> Unit,
) {
    val projects by vm.projects.collectAsState()
    val message by vm.message.collectAsState()
    val activeId = vm.prefs.activeProjectId
    var renaming by remember { mutableStateOf<ProjectSummary?>(null) }
    var deleting by remember { mutableStateOf<ProjectSummary?>(null) }

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Column {
                Text("Live VIP", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.primary)
                Text("Offline 3D wallpaper projects", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            OutlinedButton(onClick = onPerformance) { Text("Performance") }
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(onClick = onImport) { Text("Import project") }
        }
        message?.let {
            Spacer(Modifier.height(8.dp))
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(it, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    TextButton(onClick = { vm.clearMessage() }) { Text("OK") }
                }
            }
        }
        Spacer(Modifier.height(12.dp))

        if (projects.isEmpty()) {
            Panel {
                SectionTitle("No projects yet")
                Hint("Import a .mwproj project file prepared outside the app, or import the bundled neon warrior sample to see the effects right away.")
                OutlinedButton(onClick = { vm.importBundledSample() }) { Text("Import bundled sample") }
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
                items(projects, key = { it.id }) { project ->
                    ProjectCard(
                        project = project,
                        isActive = project.id == activeId,
                        onPreview = { onOpenPreview(project.id) },
                        onEffects = { onOpenEffects(project.id) },
                        onSetActive = { vm.setActive(project.id) },
                        onRename = { renaming = project },
                        onDuplicate = { vm.duplicate(project.id) },
                        onDelete = { deleting = project },
                    )
                }
            }
        }
    }

    renaming?.let { project ->
        var text by remember(project.id) { mutableStateOf(project.name) }
        AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text("Rename project") },
            text = {
                OutlinedTextField(value = text, onValueChange = { text = it.take(80) }, singleLine = true, label = { Text("Name") })
            },
            confirmButton = {
                TextButton(onClick = { vm.rename(project.id, text); renaming = null }, enabled = text.isNotBlank()) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { renaming = null }) { Text("Cancel") } },
        )
    }

    deleting?.let { project ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Delete \"${project.name}\"?") },
            text = { Text("This removes the imported project and its saved effect settings from this device. The original .mwproj file is not affected.") },
            confirmButton = {
                TextButton(onClick = { vm.delete(project.id); deleting = null }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun ProjectCard(
    project: ProjectSummary,
    isActive: Boolean,
    onPreview: () -> Unit,
    onEffects: () -> Unit,
    onSetActive: () -> Unit,
    onRename: () -> Unit,
    onDuplicate: () -> Unit,
    onDelete: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = androidx.compose.foundation.shape.RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            SquareThumb(project)
            Spacer(Modifier.size(14.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(project.name, style = MaterialTheme.typography.titleMedium, maxLines = 1)
                ProjectSummaryLine(project)
                if (isActive) {
                    Text("Selected for live wallpaper", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.secondary)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onPreview) { Text("Preview") }
                    OutlinedButton(onClick = onEffects) { Text("Effects") }
                }
                Row {
                    TextButton(onClick = { menuOpen = true }) { Text("More") }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(text = { Text("Use for live wallpaper") }, onClick = { menuOpen = false; onSetActive() })
                        DropdownMenuItem(text = { Text("Rename") }, onClick = { menuOpen = false; onRename() })
                        DropdownMenuItem(text = { Text("Duplicate") }, onClick = { menuOpen = false; onDuplicate() })
                        DropdownMenuItem(text = { Text("Delete") }, onClick = { menuOpen = false; onDelete() })
                    }
                }
            }
        }
    }
}

/** Import Project screen: system document picker, validation feedback and the format summary. */
@Composable
fun ImportScreen(vm: AppViewModel, onDone: () -> Unit, onBack: () -> Unit) {
    val state by vm.importState.collectAsState()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.importUri(uri)
    }

    Column(
        Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("Import project", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.primary)
        Panel {
            SectionTitle("Project file (.mwproj)")
            Hint("A ZIP archive prepared outside this app. It must contain manifest.json, background.png and any layer PNGs it references. No AI processing happens on the phone; the app only renders the files you provide.")
            Hint("Limits: archive ≤ 150 MB, unpacked ≤ 300 MB, canvas 128–4096 px, GPU image memory ≤ 256 MB. The file picker copies the file locally; nothing is uploaded.")
        }
        Panel {
            // "*/*" on purpose: .mwproj has no registered MIME type, and providers report it as
            // application/zip, application/octet-stream, or with no type at all. Filtering by MIME
            // hides valid files in some providers. The importer checks the contents instead.
            Button(onClick = { picker.launch(arrayOf("*/*")) }, enabled = !state.busy) { Text("Choose .mwproj file") }
            OutlinedButton(onClick = { vm.importBundledSample() }, enabled = !state.busy) { Text("Import bundled sample") }
            if (state.busy) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                    Text("Checking and unpacking…")
                }
            }
        }
        state.importedName?.let { name ->
            Panel {
                SectionTitle("Imported: $name")
                Hint("The project is in your library. Preview it, adjust effects, then set it as the live wallpaper.")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { vm.clearImportState(); onDone() }) { Text("Go to library") }
                }
            }
        }
        if (state.errors.isNotEmpty()) {
            Panel {
                SectionTitle("Import rejected")
                state.errors.forEach { Text("• $it", color = MaterialTheme.colorScheme.error) }
                Hint("Fix the project file and try again. Nothing was added to your library.")
            }
        }
        Spacer(Modifier.weight(1f))
        TextButton(onClick = { vm.clearImportState(); onBack() }) { Text("Back to library") }
    }
}
