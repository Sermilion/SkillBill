package dev.skillbill.desktop.core.designsystem.overlay

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.skillbill.desktop.core.designsystem.LocalContentColor
import dev.skillbill.desktop.core.designsystem.SkillBillRadius
import dev.skillbill.desktop.core.designsystem.SkillBillSize
import dev.skillbill.desktop.core.designsystem.SkillBillTheme
import dev.skillbill.desktop.core.designsystem.component.Kbd
import dev.skillbill.desktop.core.designsystem.component.rememberHoverColor
import dev.skillbill.desktop.core.designsystem.composite.Edge
import dev.skillbill.desktop.core.designsystem.composite.hairline
import dev.skillbill.desktop.core.designsystem.composite.runAfterCurrentEvent
import dev.skillbill.desktop.core.designsystem.icon.SbIcon
import dev.skillbill.desktop.core.designsystem.layeredShadow

private const val paletteTopFraction = 0.14f
private val paletteInputHeight = 52.dp
private val paletteWindowMargin = 32.dp
private val commandListMaxHeight = 420.dp
private const val commandListWindowFraction = 0.56f

@Immutable
class CommandItem(
  val id: String,
  val label: String,
  val icon: ImageVector? = null,
  val shortcut: String? = null,
  val danger: Boolean = false,
  val enabled: Boolean = true,
)

@Immutable
class CommandGroup(val head: String, val items: List<CommandItem>)

/**
 * The caller filters [groups] from [query]; a new [groups] list resets the highlight to the first item.
 * [returnFocusTo] receives focus after the palette closes, once its popup has left the window.
 */
@Composable
fun CommandPalette(
  query: String,
  onQueryChange: (String) -> Unit,
  groups: List<CommandGroup>,
  onRun: (id: String) -> Unit,
  onDismiss: () -> Unit,
  context: (@Composable RowScope.() -> Unit)? = null,
  placeholder: String = "Type a command or search…",
  returnFocusTo: FocusRequester? = null,
) {
  val colors = SkillBillTheme.colors
  val type = SkillBillTheme.type
  val flat = remember(groups) { groups.flatMap { it.items } }
  var highlight by remember(groups) { mutableIntStateOf(0) }
  val input = remember { FocusRequester() }
  var inputFocused by remember { mutableStateOf(false) }
  val shape = RoundedCornerShape(SkillBillRadius.lg)
  val windowWidth = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.width.toDp() }
  val focusTarget by rememberUpdatedState(returnFocusTo)
  LaunchedEffect(Unit) { input.requestFocus() }
  DisposableEffect(Unit) {
    onDispose { focusTarget?.let { requester -> runAfterCurrentEvent { runCatching { requester.requestFocus() } } } }
  }
  ModalLayer(onDismiss, paletteTopFraction) {
    Column(
      Modifier.width(SkillBillSize.commandMenu.coerceAtMost((windowWidth - paletteWindowMargin).coerceAtLeast(0.dp)))
        .layeredShadow(SkillBillTheme.shadows.modal, shape).background(colors.bgOverlay, shape)
        .border(1.dp, colors.borderStrong, shape).clip(shape)
        .semantics { paneTitle = "Command palette" }
        .onPreviewKeyEvent {
          if (it.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
          val n = flat.size
          when (it.key) {
            Key.DirectionDown -> { highlight = if (n == 0) 0 else (highlight + 1) % n; true }
            Key.DirectionUp -> { highlight = if (n == 0) 0 else (highlight - 1 + n) % n; true }
            Key.Enter, Key.NumPadEnter -> if (!inputFocused) false else { flat.getOrNull(highlight)?.takeIf { item -> item.enabled }?.let { item -> onRun(item.id) }; true }
            Key.Escape -> { onDismiss(); true }
            else -> false
          }
        },
    ) {
      if (context != null) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically, content = context)
      }
      Box(Modifier.fillMaxWidth().height(paletteInputHeight).padding(horizontal = 16.dp), contentAlignment = Alignment.CenterStart) {
        if (query.isEmpty()) BasicText(placeholder, style = type.tLg.copy(color = colors.fgFaint), maxLines = 1)
        BasicTextField(
          query, onQueryChange,
          Modifier.fillMaxWidth().focusRequester(input).onFocusChanged { inputFocused = it.isFocused }.semantics { contentDescription = placeholder },
          textStyle = type.tLg.copy(color = colors.fg),
          singleLine = true,
          cursorBrush = SolidColor(colors.fg),
        )
      }
      CommandList(groups, highlight, onRun, onHover = { highlight = it })
      Row(
        Modifier.fillMaxWidth().hairline(Edge.Top, colors.borderSubtle).padding(horizontal = 14.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        FooterHint("↑ ↓", "Move")
        FooterHint("↵", "Run")
        FooterHint("esc", "Close")
      }
    }
  }
}

@Composable
private fun FooterHint(keys: String, label: String) {
  Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
    Kbd(keys)
    BasicText(label, style = SkillBillTheme.type.tXs.copy(color = SkillBillTheme.colors.fgFaint))
  }
}

/** [highlight] indexes the flattened items of [groups]; [onHover] reports the hovered item's flattened index. */
@Composable
fun CommandList(groups: List<CommandGroup>, highlight: Int, onRun: (String) -> Unit, onHover: (Int) -> Unit, modifier: Modifier = Modifier) {
  val colors = SkillBillTheme.colors
  val visible = groups.filter { it.items.isNotEmpty() }
  val windowHeight = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.height.toDp() }
  val maxHeight = minOf(commandListMaxHeight, windowHeight * commandListWindowFraction)
  val state = rememberLazyListState()
  // Scrolling slides rows under a still pointer; hover may move the highlight again only after real pointer movement.
  var pointerLive by remember { mutableStateOf(true) }
  val itemLazyIndex = remember(visible) {
    var lazy = 0
    buildList { visible.forEach { group -> lazy++; repeat(group.items.size) { add(lazy); lazy++ } } }
  }
  LaunchedEffect(highlight, itemLazyIndex) {
    val target = itemLazyIndex.getOrNull(highlight) ?: return@LaunchedEffect
    val shown = state.layoutInfo.visibleItemsInfo
    val end = state.layoutInfo.viewportEndOffset
    if (shown.isEmpty() || shown.any { it.index == target && it.offset >= 0 && it.offset + it.size <= end }) return@LaunchedEffect
    pointerLive = false
    if (target <= shown.first().index) state.scrollToItem(target)
    else state.scrollToItem(target, -(end - (shown.firstOrNull { it.index == target } ?: shown.last()).size))
  }
  Box(
    modifier.fillMaxWidth().hairline(Edge.Top, colors.borderSubtle).pointerInput(Unit) {
      awaitPointerEventScope {
        while (true) {
          val event = awaitPointerEvent(PointerEventPass.Initial)
          if (event.type == PointerEventType.Move && event.changes.any { it.position != it.previousPosition }) pointerLive = true
        }
      }
    },
  ) {
    if (visible.isEmpty()) {
      BasicText("No commands match", Modifier.align(Alignment.Center).padding(horizontal = 16.dp, vertical = 28.dp), style = SkillBillTheme.type.tBase.copy(color = colors.fgFaint))
      return@Box
    }
    LazyColumn(Modifier.fillMaxWidth().heightIn(max = maxHeight), state = state, contentPadding = PaddingValues(start = 8.dp, end = 8.dp, top = 4.dp, bottom = 8.dp)) {
      var flatIndex = 0
      visible.forEachIndexed { groupIndex, group ->
        item(key = "head:$groupIndex") {
          BasicText(group.head, Modifier.padding(start = 10.dp, end = 10.dp, top = 10.dp, bottom = 6.dp), style = SkillBillTheme.type.tXs.copy(color = colors.fgFaint, fontWeight = FontWeight.Medium))
        }
        group.items.forEach { command ->
          val index = flatIndex++
          item(key = "item:$index") { CommandRow(command, index == highlight, onRun = { onRun(command.id) }, pointerLive = pointerLive, onHover = { onHover(index) }) }
        }
      }
    }
  }
}

@Composable
private fun CommandRow(item: CommandItem, highlighted: Boolean, onRun: () -> Unit, pointerLive: Boolean, onHover: () -> Unit) {
  val colors = SkillBillTheme.colors
  val interaction = remember { MutableInteractionSource() }
  val hovered = interaction.collectIsHoveredAsState().value
  LaunchedEffect(hovered, pointerLive) { if (hovered && pointerLive) onHover() }
  val shape = RoundedCornerShape(SkillBillRadius.sm)
  val fill = rememberHoverColor(highlighted, Color.Transparent, colors.bgActive).value
  val fg = when { item.danger -> colors.stFailed; highlighted -> colors.fg; else -> colors.fg2 }
  val iconColor = if (item.danger) colors.stFailed else colors.fgMuted
  Row(
    Modifier.fillMaxWidth().height(40.dp).background(fill, shape).hoverable(interaction)
      .then(if (item.enabled) Modifier.clickable(interaction, indication = null, role = Role.Button, onClick = onRun) else Modifier.alpha(0.4f))
      .semantics(mergeDescendants = true) { selected = highlighted }
      .padding(horizontal = 10.dp),
    horizontalArrangement = Arrangement.spacedBy(8.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    item.icon?.let { CompositionLocalProvider(LocalContentColor provides iconColor) { SbIcon(it) } }
    BasicText(item.label, Modifier.weight(1f), style = SkillBillTheme.type.tBase.copy(color = fg), maxLines = 1, overflow = TextOverflow.Ellipsis)
    item.shortcut?.let { Kbd(it) }
  }
}
