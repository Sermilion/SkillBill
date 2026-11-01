package dev.skillbill.desktop.feature.changes

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.unit.dp
import dev.skillbill.desktop.core.designsystem.component.SbButton
import dev.skillbill.desktop.core.designsystem.component.SbButtonVariant
import dev.skillbill.desktop.core.designsystem.component.SbControlSize

/** The Scope picker button (only with Subtasks) and the quick scope buttons with their file counts. */
@Composable
internal fun ScopeToolbar(state: ChangesWindowUiState, onAction: (ChangesWindowAction) -> Unit, pickerSearch: FocusRequester) {
  Row(
    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
    horizontalArrangement = Arrangement.spacedBy(6.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    if (state.subtasksAvailable) {
      Box {
        SbButton(scopeLabel(state.scope, state.subtasks), { onAction(ChangesWindowAction.TogglePicker) }, size = SbControlSize.Small, shortcut = "S")
        if (state.pickerOpen) ScopePicker(state, onAction, pickerSearch)
      }
    }
    Spacer(Modifier.weight(1f))
    state.quick.forEach { button ->
      SbButton(
        button.count?.let { "${button.label} $it" } ?: button.label,
        { onAction(ChangesWindowAction.SelectQuickScope(button.kind)) },
        enabled = button.enabled,
        variant = if (button.pressed) SbButtonVariant.Primary else SbButtonVariant.Secondary,
        size = SbControlSize.Small,
      )
    }
  }
}
