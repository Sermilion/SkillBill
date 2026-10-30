package dev.skillbill.desktop.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.skillbill.desktop.core.designsystem.SkillBillTheme
import dev.skillbill.desktop.core.designsystem.component.FeedbackTone
import dev.skillbill.desktop.core.designsystem.component.Hint
import dev.skillbill.desktop.core.designsystem.component.SbButton
import dev.skillbill.desktop.core.designsystem.component.SbButtonVariant
import dev.skillbill.desktop.core.designsystem.component.SbField
import dev.skillbill.desktop.core.designsystem.component.SbInput
import dev.skillbill.desktop.core.designsystem.component.SbMenu
import dev.skillbill.desktop.core.designsystem.component.SbMenuEntry
import dev.skillbill.desktop.core.designsystem.component.SbPopover
import dev.skillbill.desktop.core.designsystem.component.SbToggle
import dev.skillbill.desktop.core.designsystem.component.Segmented
import dev.skillbill.desktop.core.designsystem.component.SegmentedOption
import dev.skillbill.desktop.core.designsystem.icon.LucideIcons
import dev.skillbill.desktop.core.designsystem.settings.SettingPick
import dev.skillbill.desktop.core.designsystem.settings.SettingRow
import dev.skillbill.desktop.core.designsystem.settings.SettingsGroup
import dev.skillbill.desktop.core.designsystem.settings.SettingsHeader
import dev.skillbill.desktop.core.domain.session.DatabaseSessionState
import dev.skillbill.desktop.core.domain.settings.HistoryWindow
import dev.skillbill.desktop.core.ui.input.gatesShortcutsWhileFocused

@Composable
fun GeneralPage(state: SettingsUiState, sessionState: DatabaseSessionState, onAction: (SettingsAction) -> Unit) {
  SettingsHeader("General", body = "Where the app reads Skill Bill's work, which Claude account runs use, and how long finished work stays listed.")
  SettingsGroup(
    title = "Database",
    rows = listOf {
      SettingRow(stacked = true, control = {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
          SbField("Database path, blank uses runtime default") {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
              SbInput(
                state.draftPath,
                { onAction(SettingsAction.PathChanged(it)) },
                fieldModifier.weight(1f).gatesShortcutsWhileFocused(),
                placeholder = "Runtime default",
                mono = true,
                enabled = state.ready,
                isError = state.pathError != null,
                onSubmit = { onAction(SettingsAction.SavePath) },
              )
              SbButton("Save settings", { onAction(SettingsAction.SavePath) }, variant = SbButtonVariant.Primary, enabled = state.ready && state.pathEdited && !state.savingPath)
            }
          }
          state.pathError?.let { Hint(it.message(), FeedbackTone.Error) }
          Hint("Current database: ${state.currentDatabase ?: "runtime default"}")
        }
      })
    },
  )
  SettingsGroup(
    title = "Claude account",
    rows = listOf {
      ClaudeDirectoryRow(state, sessionState, onAction)
    },
  )
  SettingsGroup(
    title = "History retention",
    rows = listOf {
      SettingRow(label = "Keep finished work for", description = "How long History and the tray list finished work.", control = {
        Segmented(HistoryWindow.entries.map { SegmentedOption(it, it.label(), enabled = state.ready) }, state.settings.historyWindow) {
          onAction(SettingsAction.RetentionChosen(it))
        }
      })
    },
  )
  SettingsGroup(
    rows = listOf {
      SettingRow(label = "Start at login", description = "Keeps the tray icon running in the background.", control = {
        SbToggle(state.settings.startAtLogin, { onAction(SettingsAction.StartAtLoginChanged(it)) }, "Start at login", enabled = state.ready && !state.loginPending)
      })
    },
  )
  state.loginError?.let { Hint(it, FeedbackTone.Error) }
  state.writeError?.let { Hint(it, FeedbackTone.Error) }
}

@Composable
private fun ClaudeDirectoryRow(state: SettingsUiState, sessionState: DatabaseSessionState, onAction: (SettingsAction) -> Unit) {
  var expanded by remember { mutableStateOf(false) }
  val chosen = state.settings.claudeConfigDir
  val openSessionId = (sessionState as? DatabaseSessionState.Open)?.sessionId
  val discovery = when (val current = state.claudeDiscovery) {
    is ClaudeDirectoryDiscovery.Pending -> if (openSessionId == current.sessionId) current else ClaudeDirectoryDiscovery.Unavailable
    is ClaudeDirectoryDiscovery.Success -> if (openSessionId == current.sessionId) current else ClaudeDirectoryDiscovery.Unavailable
    is ClaudeDirectoryDiscovery.Failed -> if (openSessionId == current.sessionId) current else ClaudeDirectoryDiscovery.Unavailable
    else -> current
  }
  val dirs = (discovery as? ClaudeDirectoryDiscovery.Success)?.directories.orEmpty()
  val canChoose = state.ready && dirs.isNotEmpty()
  SettingRow(
    label = "Claude config directory",
    description = "Runs started here use it as CLAUDE_CONFIG_DIR.",
    control = {
      if (discovery is ClaudeDirectoryDiscovery.Success && dirs.isEmpty() && chosen == null) {
        BasicText("No Claude directory found", style = SkillBillTheme.type.tSm.copy(color = SkillBillTheme.colors.fgMuted))
      } else {
        BoxWithClaudeMenu(chosen ?: "Runtime default", chosen, expanded, canChoose, { expanded = it }, dirs) { onAction(SettingsAction.ClaudeDirectoryChosen(it)) }
      }
    },
  )
  when (val current = discovery) {
    is ClaudeDirectoryDiscovery.Failed -> androidx.compose.foundation.layout.Box(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp)) {
      Hint(current.problem.displayMessage(), FeedbackTone.Error)
    }
    is ClaudeDirectoryDiscovery.Pending -> Unit
    ClaudeDirectoryDiscovery.Unavailable -> Unit
    is ClaudeDirectoryDiscovery.Success -> Unit
  }
}

@Composable
private fun BoxWithClaudeMenu(value: String, chosen: String?, expanded: Boolean, enabled: Boolean, setExpanded: (Boolean) -> Unit, directories: List<String>, onChoose: (String) -> Unit) {
  androidx.compose.foundation.layout.Box {
    SettingPick(value, { setExpanded(true) }, icon = LucideIcons.folder, mono = true, enabled = enabled, contentDescription = "Claude config directory: $value")
    if (expanded) {
      dev.skillbill.desktop.core.ui.input.ShellLayer { setExpanded(false) }
      SbPopover(true, { setExpanded(false) }) {
        SbMenu(
          listOf(SbMenuEntry.Head("Claude config directories found")) +
            directories.map { directory ->
              SbMenuEntry.Item(directory, { onChoose(directory); setExpanded(false) }, icon = LucideIcons.folder, checked = directory == chosen)
            },
        )
      }
    }
  }
}

private fun dev.skillbill.desktop.core.domain.model.ReadProblem.displayMessage() = when (this) {
  is dev.skillbill.desktop.core.domain.model.ReadProblem.Runtime -> message
  is dev.skillbill.desktop.core.domain.model.ReadProblem.Storage -> "Claude directories could not be read: $failure."
}

private fun HistoryWindow.label() = when (this) {
  HistoryWindow.SixHours -> "6h"
  HistoryWindow.Day -> "24h"
  HistoryWindow.Week -> "7d"
}

private fun PathError.message() = when (this) {
  PathError.OnlySpaces -> "The path is only spaces. Clear it to use the default."
  PathError.WriteFailed -> "The settings file could not be written."
}
