package dev.skillbill.desktop.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.skillbill.desktop.core.designsystem.component.FeedbackTone
import dev.skillbill.desktop.core.designsystem.component.Hint
import dev.skillbill.desktop.core.designsystem.component.SbToggle
import dev.skillbill.desktop.core.designsystem.component.Toast
import dev.skillbill.desktop.core.designsystem.component.ToastData
import dev.skillbill.desktop.core.designsystem.component.ToastLead
import dev.skillbill.desktop.core.designsystem.settings.SettingRow
import dev.skillbill.desktop.core.designsystem.settings.SettingsGroup
import dev.skillbill.desktop.core.designsystem.settings.SettingsHeader
import dev.skillbill.desktop.core.domain.settings.NotificationKind

@Composable
fun NotificationsPage(state: SettingsUiState, onAction: (SettingsAction) -> Unit) {
  SettingsHeader("Notifications", body = "A notification fires when work changes lifecycle state. Clicking one opens that item.")
  SettingsGroup(
    title = "Notify me when",
    rows = NotificationKind.entries.map { kind ->
      {
        SettingRow(label = kind.label(), control = {
          SbToggle(
            checked = state.settings.notifications[kind] ?: kind.defaultEnabled,
            onCheckedChange = { onAction(SettingsAction.NotificationToggled(kind, it)) },
            label = kind.label(),
            enabled = state.ready,
          )
        })
      }
    },
  )
  SettingsGroup(
    title = "Preview",
    rows = listOf {
      Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Toast(ToastData("INF-7 is blocked", "Review left 2 Blocker findings on subtask 3 · infra-terraform", ToastLead.System, "Skill Bill", "now")) {}
      }
    },
  )
  state.writeError?.let { Hint(it, FeedbackTone.Error) }
}
