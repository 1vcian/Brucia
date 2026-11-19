package app.brucia.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Surface
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.brucia.wardrive.CmdStatus
import app.brucia.wardrive.CommandLogEntry
import app.brucia.ui.TextButton

@Composable
internal fun CommandLogDialog(
    entries: List<CommandLogEntry>,
    onClear: () -> Unit,
    onStopRunning: () -> Unit,
    onDismiss: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val maxH = windowHeightDp() * 0.6f
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .heightIn(max = maxH),
            shape = RoundedCornerShape(14.dp),
            color = cs.surface,
            tonalElevation = 4.dp,
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "Command log",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = cs.primary,
                    )
                    Spacer(Modifier.weight(1f))
                    if (entries.any { it.status == CmdStatus.RUNNING }) {
                        TextButton(onClick = onStopRunning) { Text("Stop", color = cs.error) }
                    }
                    if (entries.isNotEmpty()) {
                        TextButton(onClick = onClear) { Text("Clear") }
                    }
                    TextButton(onClick = onDismiss) { Text("Close") }
                }
                Box(Modifier.fillMaxWidth().height(1.dp).background(cs.surfaceVariant))
                if (entries.isEmpty()) {
                    Text(
                        "No commands yet.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = cs.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp),
                    )
                } else {
                    LazyColumn(modifier = Modifier.fillMaxWidth()) {
                        items(entries, key = { it.id }) { e -> CommandLogRow(e) }
                    }
                }
            }
        }
    }
}

@Composable
private fun CommandLogRow(e: CommandLogEntry) {
    val cs = MaterialTheme.colorScheme
    val chipColor = when (e.status) {
        CmdStatus.RUNNING -> Amber
        CmdStatus.DONE -> Color(0xFF4CAF50)
        CmdStatus.FAILED -> cs.error
    }
    // Elapsed while running; true duration once finished.
    val timeLabel = when (e.status) {
        CmdStatus.RUNNING -> {
            val secs = ((System.currentTimeMillis() - e.sentAtMs) / 1000L).coerceAtLeast(0)
            "running ${secs}s"
        }
        else -> {
            val ms = ((e.doneAtMs ?: e.sentAtMs) - e.sentAtMs).coerceAtLeast(0)
            if (ms < 1000) "${ms}ms" else String.format(java.util.Locale.ROOT, "%.1fs", ms / 1000f)
        }
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                e.text,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = cs.onSurface,
            )
            Spacer(Modifier.size(8.dp))
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(chipColor.copy(alpha = 0.18f))
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            ) {
                Text(
                    e.status.name,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = chipColor,
                )
            }
            Spacer(Modifier.size(8.dp))
            Text(
                timeLabel,
                style = MaterialTheme.typography.labelSmall,
                color = cs.onSurfaceVariant,
            )
        }
        val result = e.result
        if (e.status != CmdStatus.RUNNING && !result.isNullOrBlank()) {
            Text(
                result,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = cs.onSurfaceVariant,
                maxLines = 8,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        Box(Modifier.fillMaxWidth().padding(top = 8.dp).height(1.dp).background(cs.surfaceVariant.copy(alpha = 0.5f)))
    }
}
