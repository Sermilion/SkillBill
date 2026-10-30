package dev.skillbill.desktop.core.designsystem.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.skillbill.desktop.core.designsystem.LocalContentColor
import dev.skillbill.desktop.core.designsystem.SkillBillRadius
import dev.skillbill.desktop.core.designsystem.SkillBillSize
import dev.skillbill.desktop.core.designsystem.SkillBillTheme
import dev.skillbill.desktop.core.designsystem.component.SbControlSize
import dev.skillbill.desktop.core.designsystem.component.SbIconButton
import dev.skillbill.desktop.core.designsystem.composite.Edge
import dev.skillbill.desktop.core.designsystem.composite.hairline
import dev.skillbill.desktop.core.designsystem.composite.verticalResizePointerIcon
import dev.skillbill.desktop.core.designsystem.icon.LucideIcons
import dev.skillbill.desktop.core.designsystem.icon.SbIcon
import dev.skillbill.desktop.core.designsystem.icon.SbIconSize
import kotlin.time.TimeSource

private val terminalBarHeight = 34.dp
private val terminalGripHeight = 6.dp
private val terminalStep = 80.dp

@Immutable
class TerminalTabChrome(val id: String, val title: String)

/**
 * The caller owns [height], clamping and persistence. [onResizeBy] reports a height delta: positive grows the pane
 * (dragging the grip up or the taller button), negative shrinks it. [renameFieldGate] is called inside the open rename
 * field, for exactly as long as the field is composed, with the field's own cancel and commit. It returns the modifier
 * that gates the window's shortcuts while the field has focus.
 */
@Composable
fun TerminalPaneChrome(
  height: Dp,
  tabs: List<TerminalTabChrome>,
  activeId: String?,
  renamingId: String?,
  onSelectTab: (String) -> Unit,
  onStartRename: (String) -> Unit,
  onRename: (id: String, name: String) -> Unit,
  onCancelRename: () -> Unit,
  renameFieldGate: @Composable (cancel: () -> Unit, commit: () -> Unit) -> Modifier,
  onCloseTab: (String) -> Unit,
  onNewTab: () -> Unit,
  onResizeBy: (Dp) -> Unit,
  onClose: () -> Unit,
  modifier: Modifier = Modifier,
  content: @Composable BoxScope.() -> Unit,
) {
  val colors = SkillBillTheme.colors
  val density = LocalDensity.current
  val drag = rememberDraggableState { delta -> onResizeBy(with(density) { (-delta).toDp() }) }
  Box(modifier.fillMaxWidth().heightIn(min = SkillBillSize.terminalMin).height(height).background(colors.bgInset).hairline(Edge.Top, colors.border)) {
    // `.sb-term`'s 1 px top border sits outside its content, so the bar starts below the hairline.
    Column(Modifier.padding(top = 1.dp)) {
      // `.sb-term-bar` is 34 px including its 1 px bottom border, so its items center in the 33 px above the border.
      Row(
        Modifier.fillMaxWidth().height(terminalBarHeight).hairline(Edge.Bottom, colors.borderSubtle).padding(start = 12.dp, end = 6.dp, bottom = 1.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        CompositionLocalProvider(LocalContentColor provides colors.fgFaint) {
          // `.sb-term-tabs` keeps its content width and only shrinks (then scrolls) when the bar runs out of room; `.l-grow`
          // takes the rest. A second weight would cap the strip at half the free space, so the left group fills the row
          // instead, and its 6 px end padding stands for the gap on the far side of the empty `.l-grow`.
          Row(Modifier.weight(1f).padding(end = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            SbIcon(LucideIcons.terminal, SbIconSize.Sm)
            Row(Modifier.weight(1f, fill = false).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically) {
              tabs.forEach { tab ->
                TerminalTab(
                  tab, active = tab.id == activeId, renaming = tab.id == renamingId,
                  onSelect = { onSelectTab(tab.id) }, onStartRename = { onStartRename(tab.id) },
                  onRename = { onRename(tab.id, it) }, onCancelRename = onCancelRename, renameFieldGate = renameFieldGate, onClose = { onCloseTab(tab.id) },
                )
              }
            }
            SbIconButton(LucideIcons.plus, "New terminal tab", onNewTab, size = SbControlSize.Small, shortcut = "⌃ ⇧ `")
          }
          SbIconButton(LucideIcons.chevronUp, "Make terminal taller", { onResizeBy(terminalStep) }, size = SbControlSize.Small)
          SbIconButton(LucideIcons.chevronDown, "Make terminal shorter", { onResizeBy(-terminalStep) }, size = SbControlSize.Small)
          SbIconButton(LucideIcons.x, "Close terminal", onClose, size = SbControlSize.Small, shortcut = "⌃ `")
        }
      }
      Box(Modifier.fillMaxWidth().weight(1f), content = content)
    }
    Box(
      // `top: -3px` is measured from the padding box, which starts below the 1 px top border.
      Modifier.fillMaxWidth().height(terminalGripHeight).offset(y = 1.dp - terminalGripHeight / 2)
        .pointerHoverIcon(verticalResizePointerIcon)
        .draggable(drag, Orientation.Vertical)
        .semantics { contentDescription = "Resize terminal" },
    )
  }
}

@Composable
private fun TerminalTab(
  tab: TerminalTabChrome,
  active: Boolean,
  renaming: Boolean,
  onSelect: () -> Unit,
  onStartRename: () -> Unit,
  onRename: (String) -> Unit,
  onCancelRename: () -> Unit,
  renameFieldGate: @Composable (cancel: () -> Unit, commit: () -> Unit) -> Modifier,
  onClose: () -> Unit,
) {
  val colors = SkillBillTheme.colors
  val interaction = remember { MutableInteractionSource() }
  val hovered = interaction.collectIsHoveredAsState().value
  val shape = RoundedCornerShape(SkillBillRadius.sm)
  // `.sb-ttab` has no transition, so the hover fill switches at once.
  val fill = if (active) colors.bgActive else if (hovered) colors.bgHover else Color.Transparent
  val textColor = if (active || hovered) colors.fg else colors.fgMuted
  // main.js handles `click` and `dblclick` separately: the first click selects the tab at once and the second starts the
  // rename. combinedClickable would hold every single click back for the double-click timeout.
  val doubleTapMillis = LocalViewConfiguration.current.doubleTapTimeoutMillis
  var lastClick by remember { mutableStateOf<TimeSource.Monotonic.ValueTimeMark?>(null) }
  Row(
    Modifier.height(24.dp).background(fill, shape).hoverable(interaction)
      .clickable(interaction, indication = null, role = Role.Button) {
        onSelect()
        val previous = lastClick
        if (previous != null && previous.elapsedNow().inWholeMilliseconds <= doubleTapMillis) {
          lastClick = null
          onStartRename()
        } else {
          lastClick = TimeSource.Monotonic.markNow()
        }
      }
      .pointerHoverIcon(PointerIcon.Hand)
      .semantics { selected = active }
      .padding(start = 9.dp, end = 2.dp),
    horizontalArrangement = Arrangement.spacedBy(2.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    if (renaming) {
      RenameField(tab.title, onRename, onCancelRename, renameFieldGate)
    } else {
      BasicText(tab.title, style = SkillBillTheme.type.tSmMedium.copy(color = textColor), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
    SbIconButton(
      LucideIcons.x, "Close ${tab.title} tab", onClose,
      modifier = Modifier.size(20.dp).alpha(if (hovered || active) 1f else 0f),
      size = SbControlSize.Small,
    )
  }
}

@Composable
private fun RenameField(
  initial: String,
  onRename: (String) -> Unit,
  onCancel: () -> Unit,
  gate: @Composable (cancel: () -> Unit, commit: () -> Unit) -> Modifier,
) {
  val colors = SkillBillTheme.colors
  val shape = RoundedCornerShape(SkillBillRadius.xs)
  val focus = remember { FocusRequester() }
  var value by remember { mutableStateOf(TextFieldValue(initial, TextRange(0, initial.length))) }
  var hadFocus by remember { mutableStateOf(false) }
  var settled by remember { mutableStateOf(false) }
  fun commit() { if (!settled) { settled = true; onRename(value.text) } }
  fun cancel() { if (!settled) { settled = true; onCancel() } }
  val gateModifier = gate(::cancel, ::commit)
  LaunchedEffect(Unit) { focus.requestFocus() }
  BasicTextField(
    value, { value = it },
    gateModifier.width(120.dp).height(20.dp).background(colors.bgPanel, shape).border(1.dp, colors.accentRing, shape)
      .focusRequester(focus)
      .onFocusChanged { if (it.isFocused) hadFocus = true else if (hadFocus) commit() }
      .onPreviewKeyEvent {
        if (it.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
        when (it.key) {
          Key.Enter, Key.NumPadEnter -> { commit(); true }
          Key.Escape -> { cancel(); true }
          else -> false
        }
      }
      .semantics { contentDescription = "Tab name" }
      // `.sb-ttab-in` pads 6 px inside its 1 px border.
      .padding(horizontal = 7.dp).wrapContentHeight(Alignment.CenterVertically),
    textStyle = SkillBillTheme.type.tSmMedium.copy(color = colors.fg),
    singleLine = true,
    cursorBrush = SolidColor(colors.fg),
  )
}
