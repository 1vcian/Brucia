package app.brucia.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.brucia.R
import app.brucia.device.CliCatalog
import app.brucia.ui.Button
import app.brucia.ui.OutlinedButton

/** Console presentation; the application shell owns the link and log export. */
@Composable
internal fun ConsoleScreen(
    connected: Boolean,
    log: List<String>,
    onConnect: () -> Unit,
    onSend: (String) -> Unit,
    onClear: () -> Unit,
    onExportLog: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    var cmd by rememberSaveable { mutableStateOf("") }
    val listState = rememberLazyListState()
    LaunchedEffect(log.size) { if (log.isNotEmpty()) listState.animateScrollToItem(log.size - 1) }

    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Image(
                painter = painterResource(R.drawable.ic_flame), colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(cs.primary),
                contentDescription = null,
                modifier = Modifier.size(26.dp),
            )
            Spacer(Modifier.size(8.dp))
            Text(
                "Brucia",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.ExtraBold,
                color = cs.primary,
            )
            Spacer(Modifier.weight(1f))
            StatusPill(connected)
        }
        Text(
            "Console - run any Bruce CLI command, output on the phone",
            color = cs.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
        )
        Spacer(Modifier.height(8.dp))
        if (!connected) {
            Button(onClick = onConnect, modifier = Modifier.fillMaxWidth()) { Text("Connect over USB") }
            Spacer(Modifier.height(8.dp))
        }
        // Command palette: every device CLI group, so all the device's choices are here.
        var group by rememberSaveable { mutableIntStateOf(0) }
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            CliCatalog.groups.forEachIndexed { i, g ->
                SegButton(g.name, group == i, Modifier) { group = i }
            }
        }
        Spacer(Modifier.height(6.dp))
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            CliCatalog.groups[group].commands.forEach { c ->
                OutlinedButton(
                    enabled = connected,
                    onClick = {
                        if (c.template.contains("<")) cmd = c.template // needs args: fill the input
                        else onSend(c.template)               // no args: run now
                    },
                ) { Text(c.template.substringBefore(" <").let { if (it.length > 22) it.take(22) else it }) }
            }
        }
        CliCatalog.groups[group].commands.getOrNull(0)?.let {
            Text(
                "Tap a command to run it or fill the console; <...> are arguments to replace.",
                style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(8.dp))
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth()
        ) {
            items(log) { line ->
                Text(
                    line,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = if (line.startsWith("> ")) cs.primary else cs.onSurface,
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = cmd,
                onValueChange = { cmd = it },
                modifier = Modifier.weight(1f),
                label = { Text("command") },
                singleLine = true,
            )
            Button(
                enabled = connected && cmd.isNotBlank(),
                onClick = { onSend(cmd.trim()); cmd = "" }
            ) { Text("Run") }
        }
        Spacer(Modifier.height(4.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onClear, modifier = Modifier.weight(1f)) { Text("Clear") }
            OutlinedButton(
                onClick = onExportLog,
                modifier = Modifier.weight(1f),
            ) { Text("Export log") }
        }
    }
}
