package dev.skillbill.desktop.feature.changes

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.isShiftPressed
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import dev.skillbill.desktop.core.designsystem.SkillBillTheme
import dev.skillbill.desktop.core.designsystem.layeredShadow
import dev.skillbill.desktop.core.domain.model.ChangeScope
import dev.skillbill.desktop.core.domain.model.ScopeSubtask
import dev.skillbill.desktop.core.domain.model.SubtaskStats
import dev.skillbill.desktop.core.ui.changes.ScopeStateDot
import dev.skillbill.desktop.core.ui.changes.changesMono
import dev.skillbill.desktop.core.ui.changes.changesSans
import dev.skillbill.desktop.core.ui.changes.filesLabel
import dev.skillbill.desktop.core.ui.changes.hairlineBottom
import dev.skillbill.desktop.core.ui.changes.hairlineTop

private val PICKER_WIDTH = 520.dp
private val PICKER_LIST_MAX = 380.dp
private val PICKER_BELOW_BUTTON = 36.dp

/**
 * The Scope picker, anchored under its button. It is its own focusable popup rather than SbPopover, whose vertical scroll cannot
 * host a lazy list. Esc and a click outside close it; the search takes focus on open; arrows and Enter pick from the keyboard.
 */
@Composable
internal fun ScopePicker(state: ChangesWindowUiState, onAction: (ChangesWindowAction) -> Unit, search: FocusRequester) {
  val colors = SkillBillTheme.colors
  val shape = RoundedCornerShape(10.dp)
  val below = with(LocalDensity.current) { PICKER_BELOW_BUTTON.roundToPx() }
  val close = { onAction(ChangesWindowAction.ClosePicker) }
  val rows = filterSubtasks(state.subtasks, state.pickerQuery)
  val selectable = rows.filter { it.selectable }
  var keyboardId by remember(state.pickerQuery) { mutableStateOf<Int?>(null) }
  val listState = rememberLazyListState()
  Popup(
    alignment = Alignment.TopStart,
    offset = IntOffset(0, below),
    onDismissRequest = close,
    properties = PopupProperties(focusable = true, dismissOnClickOutside = true, dismissOnBackPress = true),
  ) {
    LaunchedEffect(Unit) { search.requestFocus() }
    LaunchedEffect(keyboardId) {
      val index = rows.indexOfFirst { it.id == keyboardId }
      if (index < 0) return@LaunchedEffect
      val info = listState.layoutInfo
      if (info.visibleItemsInfo.none { it.index == index && it.offset >= 0 && it.offset + it.size <= info.viewportEndOffset }) listState.scrollToItem(index)
    }
    Column(
      Modifier.width(PICKER_WIDTH)
        .layeredShadow(SkillBillTheme.shadows.popover, shape)
        .clip(shape)
        .background(colors.bgOverlay, shape)
        .border(1.dp, colors.borderStrong, shape)
        .onPreviewKeyEvent { event ->
          if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
          when (event.key) {
            Key.Escape -> close()
            Key.DirectionDown, Key.DirectionUp -> {
              val current = selectable.indexOfFirst { it.id == keyboardId }
              val moved = current + if (event.key == Key.DirectionDown) 1 else -1
              keyboardId = selectable.getOrNull(moved.coerceIn(0, (selectable.size - 1).coerceAtLeast(0)))?.id
            }
            Key.Enter, Key.NumPadEnter -> {
              val picked = selectable.firstOrNull { it.id == keyboardId } ?: selectable.firstOrNull()
              if (picked != null) onAction(ChangesWindowAction.PickSubtask(picked.id, extend = false, fromPicker = true)) else close()
            }
            else -> return@onPreviewKeyEvent false
          }
          true
        },
    ) {
      PickerSearch(state.pickerQuery, { onAction(ChangesWindowAction.PickerQuery(it)) }, search)
      if (rows.isEmpty()) {
        PaneMessage("No subtask matches.")
      } else {
        val range = (state.scope as? ChangeScope.Subtasks)?.let { it.from..it.to }
        LazyColumn(Modifier.heightIn(max = PICKER_LIST_MAX), state = listState, contentPadding = PaddingValues(4.dp)) {
          items(rows, key = { it.id }) { subtask ->
            PickerRow(
              subtask,
              state.stats[subtask.id],
              inScope = range != null && subtask.id in range,
              keyboard = keyboardId == subtask.id,
            ) { extend -> onAction(ChangesWindowAction.PickSubtask(subtask.id, extend, fromPicker = true)) }
          }
        }
      }
      PickerFooter { onAction(ChangesWindowAction.PickAll) }
    }
  }
}

@Composable
private fun PickerSearch(value: String, onChange: (String) -> Unit, focus: FocusRequester) {
  val colors = SkillBillTheme.colors
  val style = changesSans(12.5f, FontWeight.Normal, colors.fg)
  BasicTextField(
    value,
    onChange,
    Modifier.fillMaxWidth().height(38.dp).focusRequester(focus),
    singleLine = true,
    textStyle = style,
    cursorBrush = SolidColor(colors.fg),
    decorationBox = { field ->
      Box(Modifier.fillMaxWidth().hairlineBottom(colors.border).padding(horizontal = 12.dp), contentAlignment = Alignment.CenterStart) {
        if (value.isEmpty()) BasicText("Filter subtasks by name or number", style = style.copy(color = colors.fgFaint), maxLines = 1, softWrap = false)
        field()
      }
    },
  )
}

/** A picker row: faint and inert when the subtask has not started; a click reports whether Shift was held. */
@Composable
private fun PickerRow(subtask: ScopeSubtask, stats: SubtaskStats?, inScope: Boolean, keyboard: Boolean, onPick: (extend: Boolean) -> Unit) {
  val colors = SkillBillTheme.colors
  val enabled = subtask.selectable
  val pick by rememberUpdatedState(onPick)
  val interaction = remember { MutableInteractionSource() }
  val hovered by interaction.collectIsHoveredAsState()
  val shape = RoundedCornerShape(6.dp)
  val background = when {
    inScope -> colors.accent.copy(alpha = 0.10f)
    hovered || keyboard -> colors.bgRaised
    else -> androidx.compose.ui.graphics.Color.Transparent
  }
  val nameColor = when {
    !enabled -> colors.fgFaint
    inScope -> colors.fg
    else -> colors.fg2
  }
  Row(
    Modifier.fillMaxWidth()
      .background(background, shape)
      .hoverable(interaction)
      .then(if (enabled) Modifier.pointerHoverIcon(PointerIcon.Hand) else Modifier)
      .semantics(mergeDescendants = true) {
        role = Role.Button
        selected = inScope
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
      .padding(horizontal = 8.dp, vertical = 7.dp),
    horizontalArrangement = Arrangement.spacedBy(8.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    val end = TextAlign.End
    BasicText("${subtask.id}", Modifier.width(26.dp), style = changesMono(11f, FontWeight.SemiBold, if (inScope) colors.accentText else colors.fgFaint).copy(textAlign = end), maxLines = 1)
    Box(Modifier.width(12.dp), contentAlignment = Alignment.CenterStart) { ScopeStateDot(subtask.state) }
    BasicText(subtask.name, Modifier.weight(1f), style = changesSans(12.5f, FontWeight.Normal, nameColor, lineHeight = 15.6f), maxLines = 1, overflow = TextOverflow.Ellipsis)
    val detail = changesMono(11f, FontWeight.Normal, colors.fgMuted).copy(textAlign = end)
    if (!enabled) {
      BasicText("not started", Modifier.width(168.dp), style = detail, maxLines = 1)
    } else {
      BasicText(filesLabel(stats?.files?.size ?: 0), Modifier.width(58.dp), style = detail, maxLines = 1, softWrap = false)
      BasicText("+${stats?.insertions ?: 0}", Modifier.width(50.dp), style = changesMono(11f, FontWeight.Medium, colors.stDone).copy(textAlign = end), maxLines = 1, softWrap = false)
      BasicText("−${stats?.deletions ?: 0}", Modifier.width(44.dp), style = changesMono(11f, FontWeight.Medium, colors.stFailed).copy(textAlign = end), maxLines = 1, softWrap = false)
    }
  }
}

@Composable
private fun PickerFooter(onShowAll: () -> Unit) {
  val colors = SkillBillTheme.colors
  val text = changesSans(11f, FontWeight.Normal, colors.fgFaint)
  Row(
    Modifier.fillMaxWidth().height(28.5.dp).hairlineTop(colors.border).padding(horizontal = 12.dp),
    horizontalArrangement = Arrangement.spacedBy(12.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    BasicText("Click to pick one", style = text, maxLines = 1, softWrap = false)
    Row(verticalAlignment = Alignment.CenterVertically) {
      KeyCap("Shift")
      BasicText("-click for a range", style = text, maxLines = 1, softWrap = false)
    }
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
      KeyCap("↑")
      KeyCap("↓")
      KeyCap("Enter")
    }
    Spacer(Modifier.weight(1f))
    ChangesLink("All subtasks", onShowAll)
  }
}
