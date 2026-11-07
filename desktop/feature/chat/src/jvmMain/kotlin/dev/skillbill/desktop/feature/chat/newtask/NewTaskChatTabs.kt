package dev.skillbill.desktop.feature.chat.newtask

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
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
import dev.skillbill.desktop.core.designsystem.status.StatusDot
import dev.skillbill.desktop.core.designsystem.component.SbControlSize
import dev.skillbill.desktop.core.designsystem.component.SbIconButton
import dev.skillbill.desktop.core.designsystem.component.SbSpinner
import dev.skillbill.desktop.core.designsystem.component.SbTooltip
import dev.skillbill.desktop.core.designsystem.component.sbFocusRing
import dev.skillbill.desktop.core.designsystem.icon.LucideIcons
import dev.skillbill.desktop.core.designsystem.component.SbPickerButton
import dev.skillbill.desktop.core.ui.pane.MOD_KEY
import dev.skillbill.desktop.feature.chat.ChatMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private const val EMPTY_CHAT_TITLE = "New chat"
private const val WHEEL_STEP_DP = 48f
private val OVERFLOW_BUTTON_WIDTH = 68.dp

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
      NewTaskChatStatus.NewReply -> StatusDot(SkillBillTheme.colors.accent, Modifier.semantics { contentDescription = "New reply" })
      NewTaskChatStatus.Idle -> Unit
    }
  }
}

/** One tab per open chat in tab order, followed by the new-chat and overflow controls. */
@Composable
internal fun NewTaskChatTabs(
  items: List<OpenChatItem>,
  selected: NewTaskDraft,
  onSelect: (NewTaskDraft) -> Unit,
  onClose: (NewTaskDraft) -> Unit,
  onNew: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val colors = SkillBillTheme.colors
  val density = LocalDensity.current
  val reducedMotion = LocalReducedMotion.current
  val scroll = rememberScrollState()
  val scope = rememberCoroutineScope()
  val focusRequester = remember { FocusRequester() }
  BoxWithConstraints(
    modifier.fillMaxWidth().height(36.dp).background(colors.bgFrame)
      .drawBehind { drawLine(colors.borderSubtle, Offset(0f, size.height - .5f), Offset(size.width, size.height - .5f), 1.dp.toPx()) }
      .padding(horizontal = 8.dp, vertical = 4.dp),
  ) {
    val gapPx = with(density) { 2.dp.toPx().roundToInt() }
    val plusSlotPx = with(density) { 24.dp.toPx().roundToInt() } + gapPx
    val moreSlotPx = with(density) { OVERFLOW_BUTTON_WIDTH.toPx().roundToInt() } + gapPx
    val availablePx = with(density) { maxWidth.roundToPx() }
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
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(gapDp)) {
      Box(
        Modifier.weight(1f).fillMaxHeight().verticalWheelScrollsHorizontally(scroll, scope, with(density) { WHEEL_STEP_DP.dp.toPx() })
          .drawWithContent {
            drawContent()
            val fade = 32.dp.toPx().coerceAtMost(size.width)
            if (scroll.canScrollBackward) drawRect(Brush.horizontalGradient(listOf(colors.bgFrame, Color.Transparent), startX = 0f, endX = fade))
            if (scroll.canScrollForward) drawRect(Brush.horizontalGradient(listOf(Color.Transparent, colors.bgFrame), startX = size.width - fade, endX = size.width))
          }
      ) {
        Box(Modifier.fillMaxWidth().fillMaxHeight().horizontalScroll(scroll)) {
          Row(horizontalArrangement = Arrangement.spacedBy(gapDp)) {
            items.forEach { item ->
              key(item.draft) {
                NewTaskChatTab(item, item.draft === selected, onClick = { onSelect(item.draft) }, onClose = { onClose(item.draft) }, modifier = Modifier.width(with(density) { metrics.tabWidth.toDp() }))
              }
            }
          }
        }
      }
      SbIconButton(LucideIcons.plus, EMPTY_CHAT_TITLE, onNew, size = SbControlSize.Small, shortcut = "$MOD_KEY T")
      if (metrics.overflow) {
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
  SbPickerButton(
    onClick,
    Modifier.width(OVERFLOW_BUTTON_WIDTH).focusRequester(focusRequester).semantics { contentDescription = "All $count open chats" },
    open = selected,
  ) {
    Box(Modifier.size(8.dp), contentAlignment = Alignment.Center) { if (hiddenActivity) Box(Modifier.size(5.dp).background(colors.accent, CircleShape)) }
    BasicText(count.toString(), style = type.tXs.copy(color = colors.fgMuted), maxLines = 1)
  }
}

@Composable
private fun NewTaskChatTab(item: OpenChatItem, selected: Boolean, onClick: () -> Unit, onClose: () -> Unit, modifier: Modifier) {
  val colors = SkillBillTheme.colors
  val type = SkillBillTheme.type
  val unread = item.status == NewTaskChatStatus.NewReply
  val shape = RoundedCornerShape(SkillBillRadius.sm)
  val interaction = remember { MutableInteractionSource() }
  val hovered by interaction.collectIsHoveredAsState()
  Row(
    modifier.fillMaxHeight().background(if (selected) colors.bgPanel else Color.Transparent, shape)
      .border(1.dp, if (selected) colors.borderSubtle else Color.Transparent, shape)
      .hoverable(interaction).clickable(interaction, indication = null, onClick = onClick).sbFocusRing(interaction, shape)
      .semantics { role = Role.Tab; this.selected = selected }.padding(horizontal = 8.dp),
    horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically,
  ) {
    NewTaskChatMarker(item.status)
    BasicText(item.title, Modifier.weight(1f, fill = false), style = if (unread) type.tSm.copy(color = colors.fg, fontWeight = FontWeight.Medium) else type.tSm.copy(color = if (selected) colors.fg else colors.fg2), maxLines = 1, overflow = TextOverflow.Ellipsis)
    Box(Modifier.size(16.dp), contentAlignment = Alignment.Center) {
      if (hovered || selected) SbIconButton(LucideIcons.x, "Close ${item.title}", onClose, size = SbControlSize.XSmall, shortcut = "$MOD_KEY W")
    }
  }
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
