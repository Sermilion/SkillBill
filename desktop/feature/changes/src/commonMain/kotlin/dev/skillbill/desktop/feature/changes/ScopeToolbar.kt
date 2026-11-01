package dev.skillbill.desktop.feature.changes

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import dev.skillbill.desktop.core.designsystem.LocalContentColor
import dev.skillbill.desktop.core.designsystem.SkillBillTheme
import dev.skillbill.desktop.core.designsystem.component.sbFocusRing
import dev.skillbill.desktop.core.designsystem.icon.LucideIcons
import dev.skillbill.desktop.core.designsystem.icon.SbIcon
import dev.skillbill.desktop.core.domain.model.ScopeSubtaskState
import dev.skillbill.desktop.core.ui.changes.ScopeStateDot
import dev.skillbill.desktop.core.ui.changes.changesMono
import dev.skillbill.desktop.core.ui.changes.changesSans
import dev.skillbill.desktop.core.ui.changes.hairlineBottom

private val CONTROL_SHAPE = RoundedCornerShape(7.dp)

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
      .then(if (state.subtasksAvailable) Modifier else Modifier.hairlineBottom(colors.border))
      .padding(horizontal = 14.dp, vertical = 10.dp),
    horizontalArrangement = Arrangement.spacedBy(8.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    if (state.subtasksAvailable) {
      Box {
        PickerButton(scopeLabel(state.scope, state.subtasks), state.pickerOpen) { onAction(ChangesWindowAction.TogglePicker) }
        if (state.pickerOpen) ScopePicker(state, onAction, pickerSearch)
      }
    }
    QuickScopes(state.quick, onAction)
    Spacer(Modifier.weight(1f))
    FilterBox(state.filter, { onAction(ChangesWindowAction.SetFilter(it)) }, filterFocus, onFilterFocused)
  }
}

@Composable
private fun PickerButton(label: String, open: Boolean, onClick: () -> Unit) {
  val colors = SkillBillTheme.colors
  val interaction = remember { MutableInteractionSource() }
  Row(
    Modifier.widthIn(min = 260.dp, max = 420.dp)
      .sbFocusRing(interaction, CONTROL_SHAPE)
      .height(30.dp)
      .background(colors.bgOverlay, CONTROL_SHAPE)
      .border(1.dp, colors.borderStrong, CONTROL_SHAPE)
      .pointerHoverIcon(PointerIcon.Hand)
      .clickable(interaction, indication = null, role = Role.DropdownList, onClick = onClick)
      .semantics { selected = open }
      .padding(start = 12.dp, end = 10.dp),
    horizontalArrangement = Arrangement.SpaceBetween,
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Row(Modifier.weight(1f, fill = false), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
      BasicText("SCOPE", style = changesMono(10f, FontWeight.Medium, colors.fgFaint).copy(letterSpacing = 0.08.em), maxLines = 1, softWrap = false)
      BasicText(label, Modifier.weight(1f, fill = false), style = changesSans(12f, FontWeight.SemiBold, colors.fg), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
    CompositionLocalProvider(LocalContentColor provides colors.fg) { SbIcon(LucideIcons.chevronDown, 12.dp, strokeWidth = 2.4f, modifier = Modifier.padding(start = 8.dp)) }
  }
}

@Composable
private fun QuickScopes(buttons: List<QuickButtonUi>, onAction: (ChangesWindowAction) -> Unit) {
  val colors = SkillBillTheme.colors
  Row(Modifier.clip(CONTROL_SHAPE).border(1.dp, colors.borderStrong, CONTROL_SHAPE).padding(1.dp)) {
    buttons.forEachIndexed { index, button ->
      QuickScopeButton(button, showDivider = index < buttons.lastIndex) { onAction(ChangesWindowAction.SelectQuickScope(button.kind)) }
    }
  }
}

@Composable
private fun QuickScopeButton(button: QuickButtonUi, showDivider: Boolean, onClick: () -> Unit) {
  val colors = SkillBillTheme.colors
  val interaction = remember { MutableInteractionSource() }
  val hovered by interaction.collectIsHoveredAsState()
  val hot = button.enabled && hovered
  val background = when {
    button.pressed -> colors.bgOverlay
    hot -> colors.bgRaised
    else -> Color.Transparent
  }
  val content = when {
    button.pressed -> colors.fg
    hot -> colors.fg2
    else -> colors.fgMuted
  }
  val divider = colors.border
  val underline = colors.accent
  Row(
    Modifier.alpha(if (button.enabled) 1f else 0.4f)
      .height(28.dp)
      .background(background)
      .drawBehind {
        val stroke = 1.dp.toPx()
        if (showDivider) drawLine(divider, Offset(size.width - stroke / 2, 0f), Offset(size.width - stroke / 2, size.height), stroke)
        if (button.pressed) drawRect(underline, Offset(0f, size.height - 2.dp.toPx()), Size(size.width, 2.dp.toPx()))
      }
      .then(if (button.enabled) Modifier.pointerHoverIcon(PointerIcon.Hand) else Modifier)
      .clickable(interaction, indication = null, enabled = button.enabled, role = Role.Button, onClick = onClick)
      .semantics { selected = button.pressed }
      .padding(horizontal = 10.dp),
    horizontalArrangement = Arrangement.spacedBy(6.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    if (button.kind == QuickScope.Running && button.running) ScopeStateDot(ScopeSubtaskState.RUNNING)
    BasicText(button.label, style = changesSans(12f, FontWeight.Medium, content), maxLines = 1, softWrap = false)
    button.count?.let { BasicText("$it", style = changesMono(10.5f, FontWeight.Medium, colors.fgFaint), maxLines = 1, softWrap = false) }
  }
}

@Composable
private fun FilterBox(value: String, onChange: (String) -> Unit, focus: FocusRequester, onFocused: (Boolean) -> Unit) {
  val colors = SkillBillTheme.colors
  var focused by remember { mutableStateOf(false) }
  val outline = colors.accent
  val style = changesMono(12f, FontWeight.Normal, colors.fg)
  BasicTextField(
    value,
    onChange,
    Modifier.width(220.dp).height(30.dp)
      .focusRequester(focus)
      .onFocusChanged {
        focused = it.hasFocus
        onFocused(it.hasFocus)
      }
      .drawBehind {
        if (focused) {
          val outset = 2.dp.toPx()
          drawRoundRect(
            outline,
            Offset(-outset, -outset),
            Size(size.width + outset * 2, size.height + outset * 2),
            CornerRadius(7.dp.toPx() + outset),
            style = Stroke(2.dp.toPx()),
          )
        }
      },
    singleLine = true,
    textStyle = style,
    cursorBrush = SolidColor(colors.fg),
    decorationBox = { field ->
      Box(
        Modifier.background(colors.bgInset, CONTROL_SHAPE).border(1.dp, colors.borderStrong, CONTROL_SHAPE).padding(horizontal = 10.dp),
        contentAlignment = Alignment.CenterStart,
      ) {
        if (value.isEmpty()) BasicText("Filter files  /", style = style.copy(color = colors.fgFaint), maxLines = 1, softWrap = false)
        field()
      }
    },
  )
}
