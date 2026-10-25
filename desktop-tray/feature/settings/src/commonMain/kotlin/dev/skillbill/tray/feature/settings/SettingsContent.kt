package dev.skillbill.tray.feature.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.skillbill.tray.core.designsystem.amber
import dev.skillbill.tray.core.designsystem.canvas
import dev.skillbill.tray.core.designsystem.green
import dev.skillbill.tray.core.designsystem.ink
import dev.skillbill.tray.core.designsystem.muted
import dev.skillbill.tray.core.domain.model.Retention
import dev.skillbill.tray.core.domain.model.allowedTransitions
import dev.skillbill.tray.core.domain.model.defaultNotifications
import kotlinx.coroutines.delay

private const val SAVED_NOTICE_MILLIS = 2_000L

/** The settings window; it shows the store the board is reading right now from its own state. */
@Composable
fun SettingsScreen(viewModel: SettingsViewModel, onClose: () -> Unit, modifier: Modifier = Modifier) {
  val state by viewModel.state.collectAsState()
  var saved by remember { mutableStateOf(false) }
  LaunchedEffect(viewModel) {
    viewModel.effects.collect { effect ->
      if (effect == SettingsEffect.Saved) {
        saved = true
        delay(SAVED_NOTICE_MILLIS)
        saved = false
      }
    }
  }
  SettingsContent(state, saved, viewModel::onAction, onClose, modifier)
}

@Composable
fun SettingsContent(
  state: SettingsUiState,
  saved: Boolean,
  onAction: (SettingsAction) -> Unit,
  onClose: () -> Unit,
  modifier: Modifier = Modifier,
) {
  Column(modifier.width(460.dp).heightIn(max = 680.dp).background(canvas).padding(20.dp)) {
    Row(verticalAlignment = Alignment.CenterVertically) {
      Text("Settings", Modifier.weight(1f), color = ink, style = MaterialTheme.typography.titleLarge)
      TextButton(onClick = onClose) { Text("Close") }
    }
    state.pendingFeedback.forEach { feedback ->
      Text(feedback.message, Modifier.fillMaxWidth().clickable { onAction(SettingsAction.AcknowledgeFeedback(feedback.id)) }, color = amber, style = MaterialTheme.typography.bodySmall)
    }
    when (val readiness = state.readiness) {
      SettingsReadiness.Loading -> Text("Loading settings...", color = muted, style = MaterialTheme.typography.bodySmall)
      is SettingsReadiness.Unreadable -> Text(readiness.problem.summary, color = amber, style = MaterialTheme.typography.bodySmall)
      SettingsReadiness.Ready -> ReadySettings(state, saved, onAction)
    }
  }
}

@Composable
private fun ReadySettings(state: SettingsUiState, saved: Boolean, onAction: (SettingsAction) -> Unit) {
  val settings = state.settings
  OutlinedTextField(
    state.draftPath,
    { onAction(SettingsAction.PathChanged(it)) },
    Modifier.fillMaxWidth(),
    label = { Text("Database path, blank uses runtime default") },
    isError = state.pathError != null,
    supportingText = { if (state.pathError == PathError.PathBlank) Text("The path is only spaces. Clear it to use the default.") },
    singleLine = true,
  )
  state.effectiveDatabasePath?.let { Text("Current database: $it", color = muted, style = MaterialTheme.typography.bodySmall) }
  ClaudeConfigDirPicker(settings.claudeConfigDir, state.claudeConfigDirectories, onAction)
  Text("History retention", color = ink)
  Row { Retention.entries.forEach { value -> FilterChip(settings.retention == value, { onAction(SettingsAction.RetentionChosen(value)) }, label = { Text(value.cliValue) }) } }
  Text("Notifications", color = ink)
  allowedTransitions.forEach { key ->
    Row(verticalAlignment = Alignment.CenterVertically) {
      Text(key.replace('_', ' '), Modifier.weight(1f), color = muted)
      Switch(settings.notifications[key] ?: defaultNotifications.getValue(key), { enabled -> onAction(SettingsAction.NotificationToggled(key, enabled)) })
    }
  }
  Row(verticalAlignment = Alignment.CenterVertically) {
    Text("Start at login", Modifier.weight(1f), color = ink)
    Switch(settings.startAtLogin, { onAction(SettingsAction.StartAtLoginChanged(it)) })
  }
  Row(verticalAlignment = Alignment.CenterVertically) {
    Button(onClick = { onAction(SettingsAction.SavePath) }, enabled = !state.savingPath) { Text("Save settings") }
    if (saved) Text("Saved", Modifier.padding(start = 12.dp), color = green, style = MaterialTheme.typography.bodySmall)
  }
}

/**
 * Runs started from the app use the chosen directory as CLAUDE_CONFIG_DIR; a saved one no longer found stays listed. With
 * nothing saved, the runtime's first directory, the default `~/.claude`, shows as selected.
 */
@Composable
private fun ClaudeConfigDirPicker(chosen: String?, directories: List<String>, onAction: (SettingsAction) -> Unit) {
  var open by remember { mutableStateOf(false) }
  val choices = (directories + listOfNotNull(chosen)).distinct()
  val shown = chosen ?: directories.firstOrNull()
  Text("Claude account", color = ink)
  Box {
    OutlinedButton(onClick = { open = true }, Modifier.fillMaxWidth(), enabled = choices.isNotEmpty()) {
      Text(shown ?: "No Claude directory found", Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
      Text("▾", color = muted)
    }
    DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
      choices.forEach { directory ->
        DropdownMenuItem(text = { Text(directory) }, onClick = {
          open = false
          onAction(SettingsAction.ClaudeConfigDirChosen(directory))
        })
      }
    }
  }
  Text("Runs started here use it as CLAUDE_CONFIG_DIR.", color = muted, style = MaterialTheme.typography.bodySmall)
}
