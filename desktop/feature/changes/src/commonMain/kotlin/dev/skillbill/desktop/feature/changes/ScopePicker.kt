package dev.skillbill.desktop.feature.changes

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.isShiftPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import dev.skillbill.desktop.core.designsystem.SkillBillRadius
import dev.skillbill.desktop.core.designsystem.SkillBillTheme
import dev.skillbill.desktop.core.designsystem.component.Hint
import dev.skillbill.desktop.core.designsystem.component.SbInput
import dev.skillbill.desktop.core.designsystem.layeredShadow
import dev.skillbill.desktop.core.ui.changes.ScopeStateDot

private val PICKER_WIDTH = 360.dp
private val PICKER_LIST_MAX = 320.dp

/**
 * The Scope picker, anchored under its button. It is its own focusable popup rather than SbPopover, whose vertical scroll cannot
 * host a lazy list. Esc and a click outside close it; the search takes focus on open.
 */
@Composable
internal fun ScopePicker(state: ChangesWindowUiState, onAction: (ChangesWindowAction) -> Unit, search: FocusRequester) {
  val colors = SkillBillTheme.colors
  val shape = RoundedCornerShape(SkillBillRadius.md)
  val below = with(LocalDensity.current) { 32.dp.roundToPx() }
  val close = { onAction(ChangesWindowAction.ClosePicker) }
  Popup(
    alignment = Alignment.TopStart,
    offset = IntOffset(0, below),
    onDismissRequest = close,
    properties = PopupProperties(focusable = true, dismissOnClickOutside = true, dismissOnBackPress = true),
  ) {
    LaunchedEffect(Unit) { search.requestFocus() }
    Column(
      Modifier.width(PICKER_WIDTH)
        .layeredShadow(SkillBillTheme.shadows.popover, shape)
        .background(colors.bgOverlay, shape)
        .border(1.dp, colors.borderStrong, shape)
        .padding(6.dp)
        .onPreviewKeyEvent { if (it.type == KeyEventType.KeyDown && it.key == Key.Escape) { close(); true } else false },
    ) {
      SbInput(
        state.pickerQuery,
        { onAction(ChangesWindowAction.PickerQuery(it)) },
        Modifier.fillMaxWidth().focusRequester(search),
        placeholder = "Subtask number or name",
      )
      PickerRow(enabled = true, onPick = { onAction(ChangesWindowAction.PickAll) }) {
        BasicText("All changes", style = SkillBillTheme.type.tSm.copy(color = colors.fg))
      }
      val rows = filterSubtasks(state.subtasks, state.pickerQuery)
      if (rows.isEmpty()) {
        Row(Modifier.padding(8.dp)) { Hint("No subtask matches.") }
      } else {
        LazyColumn(Modifier.heightIn(max = PICKER_LIST_MAX)) {
          items(rows, key = { it.id }) { subtask ->
            val stats = state.stats[subtask.id]
            PickerRow(subtask.selectable, { extend -> onAction(ChangesWindowAction.PickSubtask(subtask.id, extend, fromPicker = true)) }) {
              BasicText("${subtask.id}", Modifier.width(28.dp), style = SkillBillTheme.type.monoSm.copy(color = colors.fgMuted))
              ScopeStateDot(subtask.state)
              BasicText(subtask.name, Modifier.weight(1f), style = SkillBillTheme.type.tSm.copy(color = colors.fg), maxLines = 1, overflow = TextOverflow.Ellipsis)
              val detail = pickerRowDetail(subtask, stats)
              BasicText(detail, style = SkillBillTheme.type.monoSm.copy(color = colors.fgMuted))
            }
          }
        }
      }
    }
  }
}

/** A picker row: dimmed and inert when not [enabled]; a click reports whether Shift was held. */
@Composable
private fun PickerRow(enabled: Boolean, onPick: (extend: Boolean) -> Unit, content: @Composable RowScope.() -> Unit) {
  val pick by rememberUpdatedState(onPick)
  Row(
    Modifier.fillMaxWidth()
      .alpha(if (enabled) 1f else 0.45f)
      .semantics(mergeDescendants = true) {
        role = Role.Button
        if (enabled) onClick { pick(false); true } else disabled()
      }
      .pointerInput(enabled) {
        if (!enabled) return@pointerInput
        awaitEachGesture {
          awaitFirstDown()
          val extend = currentEvent.keyboardModifiers.isShiftPressed
          if (waitForUpOrCancellation() != null) pick(extend)
        }
      }
      .padding(horizontal = 8.dp, vertical = 5.dp),
    horizontalArrangement = Arrangement.spacedBy(8.dp),
    verticalAlignment = Alignment.CenterVertically,
    content = content,
  )
}
