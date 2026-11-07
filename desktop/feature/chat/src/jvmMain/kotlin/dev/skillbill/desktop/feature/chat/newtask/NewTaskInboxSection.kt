package dev.skillbill.desktop.feature.chat.newtask

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.CollectionInfo
import androidx.compose.ui.semantics.collectionInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import dev.skillbill.desktop.core.designsystem.LocalReducedMotion
import dev.skillbill.desktop.core.designsystem.SkillBillRadius
import dev.skillbill.desktop.core.designsystem.SkillBillTheme
import dev.skillbill.desktop.core.designsystem.component.LocalUiTicker
import dev.skillbill.desktop.core.designsystem.component.sbFocusRing
import dev.skillbill.desktop.core.domain.model.ChatHistoryEntry
import dev.skillbill.desktop.core.domain.model.WorkItem
import kotlin.time.Instant

internal fun nearestScrollTarget(start: Int, end: Int, scroll: Int, viewport: Int): Int? = when {
  start < scroll -> start
  end > scroll + viewport -> end - viewport
  else -> null
}

@Composable
internal fun RecentChatsSection(
  chats: List<ChatHistoryEntry>,
  selectedRoot: String?,
  openSessionIds: Set<String>,
  now: () -> Instant,
  onResume: (ChatHistoryEntry) -> Unit,
  onDiscard: (ChatHistoryEntry) -> Unit,
  onSearch: () -> Unit,
  modifier: Modifier = Modifier,
  nextCardFocusRequester: FocusRequester = FocusRequester.Default,
) {
  HomeSection("Recent chats", chats.size, { SearchChatsButton(onSearch) }, modifier) {
    val current = rememberHomeNow(now)
    HomeCard(
      rows = chats,
      description = "Recent chats",
      keyOf = { it.sessionId },
      titleOf = { chatTitle(it.title).title },
      onOpen = onResume,
      onDelete = { entry ->
        if (entry.sessionId in openSessionIds) false else {
          onDiscard(entry)
          true
        }
      },
      nextFocusRequester = nextCardFocusRequester,
    ) { entry, selected, divider ->
      InboxChatRow(entry, selectedRoot, current, divider, entry.sessionId in openSessionIds, selected, onResume, onDiscard)
    }
  }
}

@Composable
internal fun NeedsYouSection(
  home: NewTaskHome.Returning,
  now: () -> Instant,
  onOpenRun: (WorkItem) -> Unit,
  onShowAllWork: () -> Unit,
  modifier: Modifier = Modifier,
  cardFocusRequester: FocusRequester? = null,
) {
  val title = if (home.needsYou.isEmpty()) "All clear" else "Needs you"
  val count = home.needsYou.size.takeIf { it > 0 }
  HomeSection(title, count, { WorkSummaryLink(home.running, home.finished, onShowAllWork) }, modifier) {
    if (home.needsYou.isNotEmpty()) {
      val current = rememberHomeNow(now)
      HomeCard(
        rows = home.needsYou,
        description = "Needs you",
        keyOf = { it.id },
        titleOf = { it.title },
        onOpen = onOpenRun,
        onDelete = { false },
        focusRequester = cardFocusRequester,
      ) { item, selected, divider ->
        InboxRunRow(item, current, divider, selected, onOpenRun)
      }
    }
  }
}

@Composable
private fun HomeSection(title: String, count: Int?, action: @Composable () -> Unit, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
  Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
    HomeSectionHeader(title, count, action)
    content()
  }
}

@Composable
private fun <T> HomeCard(
  rows: List<T>,
  description: String,
  keyOf: (T) -> Any,
  titleOf: (T) -> String,
  onOpen: (T) -> Unit,
  onDelete: (T) -> Boolean,
  focusRequester: FocusRequester? = null,
  nextFocusRequester: FocusRequester? = null,
  row: @Composable (item: T, selected: Boolean, divider: Boolean) -> Unit,
) {
  val colors = SkillBillTheme.colors
  val shape = RoundedCornerShape(SkillBillRadius.md)
  val interaction = remember { MutableInteractionSource() }
  val focused by interaction.collectIsFocusedAsState()
  val scroll = rememberScrollState()
  var selectedIndex by remember { mutableIntStateOf(0) }
  val lastIndex = rows.lastIndex
  val selected = selectedIndex.coerceIn(0, lastIndex.coerceAtLeast(0))
  val rowPx = with(LocalDensity.current) { 34.dp.roundToPx() }
  val reducedMotion = LocalReducedMotion.current

  LaunchedEffect(rows) { selectedIndex = selected }
  LaunchedEffect(selected, focused) {
    if (!focused) return@LaunchedEffect
    nearestScrollTarget(selected * rowPx, (selected + 1) * rowPx, scroll.value, scroll.viewportSize)
      ?.let { target ->
        val clamped = target.coerceIn(0, scroll.maxValue)
        if (reducedMotion) scroll.scrollTo(clamped) else scroll.animateScrollTo(clamped)
      }
  }

  Column(
    Modifier.fillMaxWidth().heightIn(max = 172.dp)
      .background(Color.White.copy(alpha = .012f), shape).clip(shape)
      .border(1.dp, colors.borderSubtle, shape)
      .hoverable(interaction)
      .onFocusChanged { if (it.isFocused) selectedIndex = 0 }
      .onPreviewKeyEvent { event ->
        if (event.type != KeyEventType.KeyDown || event.isCtrlPressed || event.isMetaPressed || event.isAltPressed) return@onPreviewKeyEvent false
        when (event.key) {
          Key.DirectionDown -> {
            if (lastIndex >= 0) selectedIndex = (selected + 1).coerceAtMost(lastIndex)
            true
          }
          Key.DirectionUp -> {
            if (lastIndex >= 0) selectedIndex = (selected - 1).coerceAtLeast(0)
            true
          }
          Key.Enter, Key.NumPadEnter -> rows.getOrNull(selected)?.let { onOpen(it); true } ?: false
          Key.Delete -> rows.getOrNull(selected)?.let(onDelete) ?: false
          else -> false
        }
      }
      .then(focusRequester?.let { Modifier.focusRequester(it) } ?: Modifier)
      .then(nextFocusRequester?.let { requester -> Modifier.focusProperties { next = requester } } ?: Modifier)
      .sbFocusRing(interaction, shape)
      .semantics {
        contentDescription = description
        collectionInfo = CollectionInfo(rowCount = rows.size, columnCount = 1)
        if (focused) rows.getOrNull(selected)?.let { stateDescription = "${selected + 1} of ${rows.size}, ${titleOf(it)}" }
      }
      .focusable(interactionSource = interaction)
      .padding(1.dp),
  ) {
    Column(Modifier.fillMaxWidth().verticalEdgeFade(scroll, hasOverflowRows = rows.size > 5).verticalScroll(scroll)) {
      rows.forEachIndexed { index, item -> key(keyOf(item)) { row(item, focused && index == selected, index > 0) } }
    }
  }
}

private fun Modifier.verticalEdgeFade(scroll: androidx.compose.foundation.ScrollState, hasOverflowRows: Boolean): Modifier =
  graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
    .drawWithContent {
      drawContent()
      val edgePx = 24.dp.toPx().coerceAtMost(size.height)
      if (hasOverflowRows && scroll.canScrollBackward) {
        drawRect(
          Brush.verticalGradient(listOf(Color.Transparent, Color.Black), startY = 0f, endY = edgePx),
          size = Size(size.width, edgePx),
          blendMode = BlendMode.DstIn,
        )
      }
      if (hasOverflowRows && scroll.canScrollForward) {
        drawRect(
          Brush.verticalGradient(listOf(Color.Black, Color.Transparent), startY = size.height - edgePx, endY = size.height),
          topLeft = Offset(0f, size.height - edgePx),
          size = Size(size.width, edgePx),
          blendMode = BlendMode.DstIn,
        )
      }
    }

@Composable
private fun rememberHomeNow(now: () -> Instant): Instant {
  val ticker = LocalUiTicker.current
  DisposableEffect(ticker) {
    ticker.retain()
    onDispose { ticker.release() }
  }
  val tick by ticker.now
  return tick.let { now() }
}
