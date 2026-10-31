package dev.skillbill.desktop.feature.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.skillbill.desktop.core.designsystem.component.SbButton
import dev.skillbill.desktop.core.designsystem.component.SbControlSize
import dev.skillbill.desktop.core.domain.model.ChatPermissionMode
import dev.skillbill.desktop.core.domain.model.ChatSuggestedAction

@Composable
internal fun ChatActionButtons(
  actions: List<ChatSuggestedAction>,
  enabled: Boolean,
  mode: ChatPermissionMode,
  onAction: (ChatSuggestedAction) -> Unit,
) {
  FlowRow(
    Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 4.dp),
    horizontalArrangement = Arrangement.spacedBy(6.dp),
    verticalArrangement = Arrangement.spacedBy(6.dp),
  ) {
    actions.forEach { action ->
      val pause = mode == ChatPermissionMode.ReadOnly && action.mode == ChatPermissionMode.Act
      SbButton(
        label = if (pause) "Pause and ${action.label.replaceFirstChar { it.lowercase() }}" else action.label,
        onClick = { onAction(action) },
        enabled = enabled,
        size = SbControlSize.Small,
        tooltip = action.instruction,
      )
    }
  }
}
