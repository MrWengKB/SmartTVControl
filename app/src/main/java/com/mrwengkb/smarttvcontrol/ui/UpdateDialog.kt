package com.mrwengkb.smarttvcontrol.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mrwengkb.smarttvcontrol.BuildConfig
import com.mrwengkb.smarttvcontrol.UpdateInfo

/** "Update available" prompt: Update downloads the new APK in the browser, Ignore skips this version. */
@Composable
fun UpdateDialog(update: UpdateInfo, onIgnore: () -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Update available") },
        text = {
            Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                Text("Installed: v${BuildConfig.VERSION_NAME}\nLatest: v${update.version}")
                if (update.notes.isNotBlank()) {
                    Text(
                        "\nWhat's new",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(formatNotes(update.notes), style = MaterialTheme.typography.bodyMedium)
                }
                Text(
                    "\nUpdate downloads the new APK. Open it when the download finishes to install over this version.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                try {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(update.downloadUrl)))
                } catch (_: ActivityNotFoundException) {
                    // No browser; nothing else we can do.
                }
                onDismiss()
            }) { Text("Update") }
        },
        dismissButton = { TextButton(onClick = onIgnore) { Text("Ignore") } },
    )
}

/** Release notes are versions.md bullets ("- item"); show them as "• item". */
private fun formatNotes(notes: String): String =
    notes.lines()
        .map { it.trim() }
        .filter { it.isNotEmpty() && !it.startsWith("#") }
        .joinToString("\n") { if (it.startsWith("- ") || it.startsWith("* ")) "• " + it.drop(2) else it }
