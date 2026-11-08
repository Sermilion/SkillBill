package dev.skillbill.desktop.feature.changes

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import dev.skillbill.desktop.core.designsystem.SkillBillTheme
import dev.skillbill.desktop.core.designsystem.composite.Edge
import dev.skillbill.desktop.core.designsystem.composite.hairline
import dev.skillbill.desktop.core.designsystem.component.SbInput
import dev.skillbill.desktop.core.designsystem.component.Segmented
import dev.skillbill.desktop.core.designsystem.component.SegmentedOption
import dev.skillbill.desktop.core.designsystem.component.SbPickerButton
import dev.skillbill.desktop.core.domain.model.ScopeSubtaskState
import dev.skillbill.desktop.core.domain.model.ScopeKind
import dev.skillbill.desktop.core.domain.model.availableScopes
import dev.skillbill.desktop.core.ui.changes.ScopeStateDot
import dev.skillbill.desktop.core.ui.changes.changesMono
import dev.skillbill.desktop.core.ui.changes.changesSans

/** The Scope picker (only with Subtasks), the joined quick scope buttons and the filter box. */
@Composable
internal fun ScopeToolbar(
  state: ChangesWindowUiState,
  onAction: (ChangesWindowAction) -> Unit,
  pickerSearch: FocusRequester,
  filterFocus: FocusRequester,
  onFilterFocused: (Boolean) -> Unit,
) {
  val colors = SkillBillTheme.colors
  Row(
    Modifier.fillMaxWidth().background(colors.bgPanel)
      .then(if (state.subtasksAvailable) Modifier else Modifier.hairline(Edge.Bottom, colors.border))
      .padding(horizontal = 14.dp, vertical = 10.dp),
    horizontalArrangement = Arrangement.spacedBy(8.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    val chatCommitPicker = state.chatScopes && state.view == ChangesView.Commits &&
      ScopeKind.Commits in (state.target?.let(::availableScopes).orEmpty())
    if (state.subtasksAvailable || chatCommitPicker) {
      Box {
        SbPickerButton(
          { onAction(ChangesWindowAction.TogglePicker) },
          Modifier.widthIn(min = 260.dp, max = 420.dp).semantics { selected = state.pickerOpen },
          open = state.pickerOpen,
        ) {
          Row(Modifier.weight(1f, fill = false), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            BasicText("SCOPE", style = changesMono(10f, FontWeight.Medium, SkillBillTheme.colors.fgFaint).copy(letterSpacing = 0.08.em), maxLines = 1, softWrap = false)
            BasicText(scopeLabel(state.scope, state.subtasks), Modifier.weight(1f, fill = false), style = changesSans(12f, FontWeight.SemiBold, SkillBillTheme.colors.fg), maxLines = 1, overflow = TextOverflow.Ellipsis)
          }
        }
        if (state.pickerOpen) ScopePicker(state, onAction, pickerSearch)
      }
      if (state.subtasksAvailable && ScopeKind.Commits in (state.target?.let(::availableScopes).orEmpty())) {
        Segmented(ChangesView.entries.map { SegmentedOption(it, it.name) }, state.view) { onAction(ChangesWindowAction.SetView(it)) }
      }
    }
    Segmented(
      options = state.quick.map { button ->
        SegmentedOption<QuickScope?>(
          button.kind,
          button.label,
          enabled = button.enabled,
          count = button.count,
          lead = if (button.kind == QuickScope.Running && button.running) ({ ScopeStateDot(ScopeSubtaskState.RUNNING) }) else null,
        )
      },
      selected = state.quick.firstOrNull { it.pressed }?.kind,
      onSelect = { kind -> kind?.let { onAction(ChangesWindowAction.SelectQuickScope(it)) } },
    )
    Spacer(Modifier.weight(1f))
    SbInput(
      state.filter,
      { onAction(ChangesWindowAction.SetFilter(it)) },
      Modifier.width(220.dp).focusRequester(filterFocus).onFocusChanged { onFilterFocused(it.hasFocus) },
      placeholder = "Filter files  /",
      mono = true,
    )
  }
}
