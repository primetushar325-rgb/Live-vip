package com.livevip.wallpaper.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.livevip.wallpaper.project.Finding
import com.livevip.wallpaper.project.FormatRequirements
import com.livevip.wallpaper.project.ReportText
import com.livevip.wallpaper.project.Severity
import com.livevip.wallpaper.project.ValidationReport

/**
 * Import Project screen.
 *
 * 1. The .mwproj requirements checklist, visible before any file is chosen.
 * 2. The system document picker, plus the bundled sample for a real example report.
 * 3. The validation report for the last file: result, missing items, unsupported files, optional
 *    files, and passed checks. Copy buttons put the missing list or the full report on the clipboard.
 *
 * Checks run on a background thread (see [AppViewModel]). Nothing is uploaded.
 */
@Composable
fun ImportScreen(vm: AppViewModel, onDone: () -> Unit, onBack: () -> Unit) {
    val state by vm.importState.collectAsState()
    val context = LocalContext.current
    var copyNote by remember { mutableStateOf<String?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            copyNote = null
            vm.importUri(uri)
        }
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(
            "Import project",
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.primary,
        )

        RequirementsPanel(
            onCopy = {
                copyToClipboard(context, "Live VIP .mwproj checklist", FormatRequirements.checklistText())
                copyNote = "Checklist copied to the clipboard."
            },
        )

        Panel {
            SectionTitle("2. Choose a project file")
            Hint("The file is checked on this phone and nothing is uploaded. A project is never changed: the original file stays as it is.")
            // "*/*" on purpose: .mwproj has no registered MIME type, and providers report it as
            // application/zip, application/octet-stream, or with no type at all. The importer checks the contents.
            Button(onClick = { picker.launch(arrayOf("*/*")) }, enabled = !state.busy) {
                Text("Choose .mwproj file")
            }
            OutlinedButton(
                onClick = {
                    copyNote = null
                    vm.importBundledSample()
                },
                enabled = !state.busy,
            ) {
                Text("Check bundled sample")
            }
            if (state.busy) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                    Text("Checking the project…")
                }
            }
        }

        val report = state.report
        if (report != null && !state.busy) {
            ReportPanel(
                report = report,
                imported = state.importedName != null,
                copyNote = copyNote,
                onCopyMissing = {
                    copyToClipboard(context, "Missing items", ReportText.missingItems(report))
                    copyNote = "Missing items copied to the clipboard."
                },
                onCopyFull = {
                    copyToClipboard(context, "Validation report", ReportText.fullReport(report))
                    copyNote = "Full validation report copied to the clipboard."
                },
                onChooseAnother = {
                    copyNote = null
                    picker.launch(arrayOf("*/*"))
                },
            )
        }

        state.importedName?.let { name ->
            if (!state.busy) {
                Panel {
                    SectionTitle("Added to your library: $name")
                    Hint("Preview it, adjust effects, then set it as the live wallpaper.")
                    Button(onClick = { vm.clearImportState(); onDone() }) { Text("Go to library") }
                }
            }
        }

        TextButton(onClick = { vm.clearImportState(); onBack() }) { Text("Back to library") }
    }
}

/** The requirements checklist. Always visible, so users know the format before they pick a file. */
@Composable
private fun RequirementsPanel(onCopy: () -> Unit) {
    Panel {
        SectionTitle("1. What a .mwproj project must contain")
        Hint("Check this list before choosing a file. Each problem found is reported by its exact file path or field name.")
        FormatRequirements.CHECKLIST.forEachIndexed { i, item ->
            Hint("${i + 1}. $item")
        }
        SectionTitle("Example manifest.json")
        Text(
            FormatRequirements.TEMPLATE,
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedButton(onClick = onCopy) { Text("Copy checklist") }
    }
}

/** The validation report for the last file. */
@Composable
private fun ReportPanel(
    report: ValidationReport,
    imported: Boolean,
    copyNote: String?,
    onCopyMissing: () -> Unit,
    onCopyFull: () -> Unit,
    onChooseAnother: () -> Unit,
) {
    Panel {
        SectionTitle("3. Validation report: ${report.fileName}")
        Text(
            ReportText.headline(report),
            style = MaterialTheme.typography.titleMedium,
            color = if (report.isImportable) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
        )
        report.stats?.let { Hint(ReportText.statsLine(it)) }
        if (imported && report.isImportable) Hint("Added to your library.")

        FindingGroup("Missing items (required)", report.requiredMissing(), emptyText = "None. Every required field and file is present.")
        FindingGroup("Unsupported files", report.unsupportedFiles())
        FindingGroup("Optional files not included (ignored)", report.optionalMissing())
        FindingGroup("Other problems", report.otherErrors())
        FindingGroup("Notes and warnings", report.notes())
        FindingGroup("Passed checks", report.passed)

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onCopyMissing) { Text("Copy missing items") }
            OutlinedButton(onClick = onCopyFull) { Text("Copy full report") }
            OutlinedButton(onClick = onChooseAnother) { Text("Check another file") }
        }
        copyNote?.let { Hint(it) }
    }
}

@Composable
private fun FindingGroup(title: String, items: List<Finding>, emptyText: String? = null) {
    if (items.isEmpty()) {
        if (emptyText != null) {
            SectionTitle(title)
            Hint(emptyText)
        }
        return
    }
    SectionTitle("$title (${items.size})")
    items.forEach { FindingRow(it) }
}

@Composable
private fun FindingRow(f: Finding) {
    val color = when (f.severity) {
        Severity.ERROR -> MaterialTheme.colorScheme.error
        Severity.WARNING -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            "${f.severity.label}: ${f.path ?: f.section.title}",
            style = MaterialTheme.typography.bodyMedium,
            color = color,
            fontWeight = FontWeight.SemiBold,
        )
        Text(f.message, style = MaterialTheme.typography.bodySmall)
        f.fix?.let { Hint("Fix: $it") }
    }
}

private fun copyToClipboard(context: Context, label: String, text: String) {
    val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
    manager.setPrimaryClip(ClipData.newPlainText(label, text))
}
