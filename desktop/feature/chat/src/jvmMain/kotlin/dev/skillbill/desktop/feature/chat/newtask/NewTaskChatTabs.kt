package dev.skillbill.desktop.feature.chat.newtask

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import dev.skillbill.desktop.core.designsystem.LocalReducedMotion
import dev.skillbill.desktop.core.designsystem.SkillBillRadius
import dev.skillbill.desktop.core.designsystem.SkillBillTheme
import dev.skillbill.desktop.core.designsystem.component.SbControlSize
import dev.skillbill.desktop.core.designsystem.component.SbIconButton
import dev.skillbill.desktop.core.designsystem.component.SbSpinner
import dev.skillbill.desktop.core.designsystem.component.SbTooltip
import dev.skillbill.desktop.core.designsystem.component.sbFocusRing
import dev.skillbill.desktop.core.designsystem.icon.LucideIcons
import dev.skillbill.desktop.core.designsystem.icon.SbIcon
import dev.skillbill.desktop.core.designsystem.icon.SbIconSize
import dev.skillbill.desktop.core.designsystem.navigation.LocalSidebarToggle
import dev.skillbill.desktop.core.ui.pane.MOD_KEY
import dev.skillbill.desktop.feature.chat.ChatMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private const val EMPTY_CHAT_TITLE = "New chat"
private const val WHEEL_STEP_DP = 48f

internal fun newTaskChatTitle(transcript: List<ChatMessage>): String =
  transcript.firstNotNullOfOrNull { it as? ChatMessage.Operator }?.text?.lineSequence()?.firstOrNull()?.trim()
    ?.takeIf(String::isNotBlank) ?: EMPTY_CHAT_TITLE

internal data class TabStripMetrics(val tabWidth: Float, val overflow: Boolean)

internal fun tabStripMetrics(available: Float, count: Int, minTab: Float, maxTab: Float, gap: Float): TabStripMetrics {
  val minPx = minTab.roundToInt().toFloat()
  val maxPx = maxTab.roundToInt().toFloat()
  if (count == 0) return TabStripMetrics(minPx, false)
  val natural = ((available.roundToInt() - gap.roundToInt() * (count - 1)).coerceAtLeast(0)) / count
  return TabStripMetrics(natural.toFloat().coerceIn(minPx, maxPx), natural < minPx)
}

internal fun tabOffset(index: Int, metrics: TabStripMetrics, gap: Float): Float =
  index * (metrics.tabWidth.roundToInt() + gap.roundToInt()).toFloat()

internal fun scrollTargetFor(index: Int, metrics: TabStripMetrics, gap: Float, scroll: Int, viewport: Int): Int? {
  val left = tabOffset(index, metrics, gap)
  val right = left + metrics.tabWidth.roundToInt()
  return when {
    left < scroll -> left.toInt()
    right > scroll + viewport -> (right - viewport).toInt()
    else -> null
  }
}

internal fun hiddenActivity(statuses: List<NewTaskChatStatus>, metrics: TabStripMetrics, gap: Float, scroll: Int, viewport: Int): Boolean =
  statuses.withIndex().any { (index, status) ->
    val left = tabOffset(index, metrics, gap)
    val right = left + metrics.tabWidth.roundToInt()
    (left < scroll || right > scroll + viewport) && status != NewTaskChatStatus.Idle
  }

/** The shared reply marker used by both chat layouts. */
@Composable
internal fun NewTaskChatMarker(status: NewTaskChatStatus, modifier: Modifier = Modifier) {
  Box(modifier.size(14.dp), contentAlignment = Alignment.Center) {
    when (status) {
      NewTaskChatStatus.Replying -> SbSpinner(size = 11.dp, contentDescription = "Replying")
      NewTaskChatStatus.NewReply -> Box(Modifier.size(7.dp).background(SkillBillTheme.colors.accent, CircleShape).semantics { contentDescription = "New reply" })
      NewTaskChatStatus.Idle -> Unit
    }
  }
}

/**
 * The top of the New task screen: one tab per open chat in tab order, followed by the new-chat and overflow controls. It leads
 * with the sidebar toggle while the sidebar is a drawer, since the screen has no view header.
 */
@Composable
internal fun NewTaskChatTabs(
  items: List<OpenChatItem>,
  selected: NewTaskDraft,
  onSelect: (NewTaskDraft) -> Unit,
  onClose: (NewTaskDraft) -> Unit,
  onDiscard: (NewTaskDraft) -> Unit,
  onNew: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val colors = SkillBillTheme.colors
  val density = LocalDensity.current
  val reducedMotion = LocalReducedMotion.current
  val scroll = rememberScrollState()
  val scope = rememberCoroutineScope()
  val focusRequester = remember { FocusRequester() }
  val sidebarToggle = LocalSidebarToggle.current
  BoxWithConstraints(
    modifier.fillMaxWidth().height(36.dp).background(colors.bgFrame)
      .drawBehind { drawLine(colors.borderSubtle, Offset(0f, size.height - .5f), Offset(size.width, size.height - .5f), 1.dp.toPx()) }
      .padding(horizontal = 8.dp),
  ) {
    val gapPx = with(density) { 2.dp.toPx().roundToInt() }
    val plusSlotPx = with(density) { 24.dp.toPx().roundToInt() } + gapPx
    val moreSlotPx = with(density) { 48.dp.toPx().roundToInt() } + gapPx
    val togglePx = if (sidebarToggle != null) with(density) { 24.dp.toPx().roundToInt() } + gapPx else 0
    val availablePx = with(density) { maxWidth.roundToPx() } - togglePx
    val minTabPx = with(density) { 112.dp.toPx().roundToInt() }
    val maxTabPx = with(density) { 196.dp.toPx().roundToInt() }
    val initial = tabStripMetrics((availablePx - plusSlotPx).toFloat(), items.size, minTabPx.toFloat(), maxTabPx.toFloat(), gapPx.toFloat())
    val metrics = if (initial.overflow) {
      tabStripMetrics((availablePx - plusSlotPx - moreSlotPx).toFloat(), items.size, minTabPx.toFloat(), maxTabPx.toFloat(), gapPx.toFloat())
    } else {
      initial
    }
    var expanded by remember(metrics.overflow) { mutableStateOf(false) }
    val viewportPx = scroll.viewportSize
    val scrollRange = scroll.maxValue
    val contentWidthPx = (items.size * metrics.tabWidth.roundToInt() + (items.size - 1).coerceAtLeast(0) * gapPx).coerceAtLeast(0)
    val measuredRange = (contentWidthPx - viewportPx).coerceAtLeast(0)
    // The overflow Row spaces four children (tabs, "+", spacer, chevron), so it spends one gap more than the slots count.
    val reservedPx = plusSlotPx + if (metrics.overflow) moreSlotPx + gapPx else 0
    val containerWidth = with(density) { contentWidthPx.coerceAtMost((availablePx - reservedPx).coerceAtLeast(0)).toDp() }
    val selectedIndex = items.indexOfFirst { it.draft === selected }
    LaunchedEffect(selected, selectedIndex, items.size, metrics, gapPx, viewportPx, scrollRange) {
      if (selectedIndex >= 0 && viewportPx > 0 && scrollRange != Int.MAX_VALUE && scrollRange == measuredRange) {
        scrollTargetFor(selectedIndex, metrics, gapPx.toFloat(), scroll.value, viewportPx)?.let {
          val target = it.coerceIn(0, scrollRange)
          if (reducedMotion) scroll.scrollTo(target) else scroll.animateScrollTo(target)
        }
      }
    }
    val statuses = items.map { it.status }
    val hasHiddenActivity by remember(statuses, metrics, gapPx, viewportPx) {
      derivedStateOf { hiddenActivity(statuses, metrics, gapPx.toFloat(), scroll.value, viewportPx) }
    }
    val gapDp = with(density) { gapPx.toDp() }
    Row(Modifier.fillMaxHeight(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(gapDp)) {
      sidebarToggle?.let { SbIconButton(LucideIcons.panelLeft, "Toggle sidebar", it, size = SbControlSize.Small, shortcut = "[") }
      Box(
        Modifier.width(containerWidth).fillMaxHeight().padding(top = 4.dp).verticalWheelScrollsHorizontally(scroll, scope, with(density) { WHEEL_STEP_DP.dp.toPx() })
          .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)
          .drawWithContent {
            drawContent()
            val fade = 32.dp.toPx().coerceAtMost(size.width)
            // The gradients clamp to opaque black past the fade, so a full-size DstIn rect leaves the rest untouched.
            if (scroll.canScrollBackward) drawRect(Brush.horizontalGradient(listOf(Color.Transparent, Color.Black), startX = 0f, endX = fade), blendMode = BlendMode.DstIn)
            if (scroll.canScrollForward) drawRect(Brush.horizontalGradient(listOf(Color.Black, Color.Transparent), startX = size.width - fade, endX = size.width), blendMode = BlendMode.DstIn)
          }
      ) {
        Box(Modifier.fillMaxWidth().fillMaxHeight().horizontalScroll(scroll)) {
          Row(horizontalArrangement = Arrangement.spacedBy(gapDp)) {
            items.forEach { item ->
              key(item.draft) {
                NewTaskChatTab(item, item.draft === selected, onClick = { onSelect(item.draft) }, onClose = { onClose(item.draft) }, onDiscard = { onDiscard(item.draft) }, modifier = Modifier.width(with(density) { metrics.tabWidth.toDp() }))
              }
            }
          }
        }
      }
      SbIconButton(LucideIcons.plus, EMPTY_CHAT_TITLE, onNew, size = SbControlSize.Small, shortcut = "$MOD_KEY T")
      if (metrics.overflow) {
        Spacer(Modifier.weight(1f))
        Box {
          SbTooltip("All open chats") {
            OverflowButton(items.size, hasHiddenActivity, expanded, focusRequester) { expanded = !expanded }
          }
          NewTaskChatOverflowMenu(expanded, items, selected, onPick = { onSelect(it); expanded = false }, onDismiss = { expanded = false }, returnFocusTo = focusRequester)
        }
      }
    }
  }
}

@Composable
private fun OverflowButton(count: Int, hiddenActivity: Boolean, selected: Boolean, focusRequester: FocusRequester, onClick: () -> Unit) {
  val colors = SkillBillTheme.colors
  val type = SkillBillTheme.type
  val interaction = remember { MutableInteractionSource() }
  val hovered by interaction.collectIsHoveredAsState()
  val shape = RoundedCornerShape(SkillBillRadius.sm)
  Row(
    Modifier.width(48.dp).height(24.dp).focusRequester(focusRequester).background(if (hovered || selected) colors.bgHover else Color.Transparent, shape)
      .hoverable(interaction).clickable(interaction, indication = null, onClick = onClick)
      .sbFocusRing(interaction, shape).semantics { contentDescription = "All $count open chats"; role = Role.Button },
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally),
  ) {
    Box(Modifier.size(8.dp), contentAlignment = Alignment.Center) { if (hiddenActivity) Box(Modifier.size(5.dp).background(colors.accent, CircleShape)) }
    BasicText(count.toString(), style = type.tXs.copy(color = colors.fgMuted), maxLines = 1)
    SbIcon(LucideIcons.chevronDown, SbIconSize.Xs)
  }
}

@Composable
private fun NewTaskChatTab(item: OpenChatItem, selected: Boolean, onClick: () -> Unit, onClose: () -> Unit, onDiscard: () -> Unit, modifier: Modifier) {
  val colors = SkillBillTheme.colors
  val type = SkillBillTheme.type
  val unread = item.status == NewTaskChatStatus.NewReply
  val shape = RoundedCornerShape(topStart = SkillBillRadius.sm, topEnd = SkillBillRadius.sm, bottomStart = 0.dp, bottomEnd = 0.dp)
  val interaction = remember { MutableInteractionSource() }
  val hovered by interaction.collectIsHoveredAsState()
  val menu = remember { ChatMenuAnchor() }
  val focus = remember { FocusRequester() }
  val titleColor = when {
    selected || unread -> colors.fg
    hovered -> colors.fg2
    else -> colors.fgMuted
  }
  Box(modifier.fillMaxHeight()) {
    Row(
      Modifier.fillMaxSize()
        .drawBehind {
          when {
            selected -> {
              drawOutline(shape.createOutline(size, layoutDirection, this), colors.bgPanel)
              drawTopEdges(colors.borderSubtle, SkillBillRadius.sm.toPx(), 1.dp.toPx())
            }
            // Only the selected tab joins the content below, so the hover fill stops above the strip's 1dp hairline.
            hovered -> drawOutline(shape.createOutline(size.copy(height = size.height - 1.dp.toPx()), layoutDirection, this), colors.bgHover)
          }
        }
        .focusRequester(focus).chatMenuTrigger(menu)
        .hoverable(interaction).clickable(interaction, indication = null, onClick = onClick).sbFocusRing(interaction, shape)
        .semantics { role = Role.Tab; this.selected = selected }.padding(start = 10.dp, end = 4.dp),
      horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically,
    ) {
      NewTaskChatMarker(item.status)
      BasicText(item.title, Modifier.weight(1f), style = type.tSm.copy(color = titleColor, fontWeight = if (unread) FontWeight.Medium else type.tSm.fontWeight), maxLines = 1, overflow = TextOverflow.Ellipsis)
      Box(Modifier.size(20.dp), contentAlignment = Alignment.Center) { if (hovered || selected) TabCloseButton(item.title, onClose) }
    }
    NewTaskChatMenu(menu, item, onClose, onDiscard, focus)
  }
}

@Composable
private fun TabCloseButton(title: String, onClose: () -> Unit) {
  val colors = SkillBillTheme.colors
  val interaction = remember { MutableInteractionSource() }
  val hovered by interaction.collectIsHoveredAsState()
  val shape = RoundedCornerShape(SkillBillRadius.sm)
  SbTooltip("Close", "$MOD_KEY W") {
    Box(Modifier.size(20.dp).background(if (hovered) colors.bgHover else Color.Transparent, shape).hoverable(interaction)
      .clickable(interaction, indication = null, onClick = onClose).sbFocusRing(interaction, shape)
      .semantics { contentDescription = "Close $title"; role = Role.Button }, contentAlignment = Alignment.Center) {
      SbIcon(LucideIcons.x, SbIconSize.Xs)
    }
  }
}

/** Strokes the left, top and right edges along the rounded top corners, leaving the bottom open. */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawTopEdges(color: Color, radius: Float, stroke: Float) {
  val inset = stroke / 2
  val r = radius.coerceAtMost(size.width / 2)
  val path = Path().apply {
    moveTo(inset, size.height)
    lineTo(inset, r)
    arcTo(Rect(inset, inset, 2 * r - inset, 2 * r - inset), 180f, 90f, false)
    lineTo(size.width - r, inset)
    arcTo(Rect(size.width - 2 * r + inset, inset, size.width - inset, 2 * r - inset), 270f, 90f, false)
    lineTo(size.width - inset, size.height)
  }
  drawPath(path, color, style = Stroke(stroke))
}

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
private fun Modifier.verticalWheelScrollsHorizontally(state: androidx.compose.foundation.ScrollState, scope: CoroutineScope, stepPx: Float): Modifier =
  onPointerEvent(PointerEventType.Scroll) { event ->
    val change = event.changes.firstOrNull() ?: return@onPointerEvent
    val delta = change.scrollDelta
    if (delta.y != 0f && delta.x == 0f) {
      scope.launch { state.scrollBy(delta.y * stepPx) }
      event.changes.forEach { it.consume() }
    }
  }
