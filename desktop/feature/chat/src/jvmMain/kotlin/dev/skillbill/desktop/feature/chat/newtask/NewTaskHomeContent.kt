package dev.skillbill.desktop.feature.chat.newtask

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.skillbill.desktop.core.designsystem.LocalContentColor
import dev.skillbill.desktop.core.designsystem.SkillBillRadius
import dev.skillbill.desktop.core.designsystem.SkillBillSize
import dev.skillbill.desktop.core.designsystem.SkillBillTheme
import dev.skillbill.desktop.core.designsystem.component.LocalUiTicker
import dev.skillbill.desktop.core.designsystem.component.SbButton
import dev.skillbill.desktop.core.designsystem.component.SbButtonVariant
import dev.skillbill.desktop.core.designsystem.component.SbControlSize
import dev.skillbill.desktop.core.designsystem.component.SbIconButton
import dev.skillbill.desktop.core.designsystem.component.SbLabel
import dev.skillbill.desktop.core.designsystem.component.SbLabelStyle
import dev.skillbill.desktop.core.designsystem.component.dataColorFor
import dev.skillbill.desktop.core.designsystem.component.rememberRuntimeText
import dev.skillbill.desktop.core.designsystem.component.sbFocusRing
import dev.skillbill.desktop.core.designsystem.icon.LucideIcons
import dev.skillbill.desktop.core.designsystem.icon.SbIcon
import dev.skillbill.desktop.core.designsystem.icon.SbIconSize
import dev.skillbill.desktop.core.designsystem.status.StatusIcon
import dev.skillbill.desktop.core.domain.model.ChatHistoryEntry
import dev.skillbill.desktop.core.domain.model.WorkItem
import dev.skillbill.desktop.core.ui.status.statusKind
import kotlin.time.Instant

private val CHIP_SHAPE = RoundedCornerShape(13.dp)
private val SECTION_SPACING = 8.dp

internal const val NEW_TASK_HISTORY_ROWS = 6

private data class ExampleTask(val label: String, val kind: String, val fill: String)

private val EXAMPLE_TASKS = listOf(
  ExampleTask("Add CSV export to the history screen", "Goal run", "Add CSV export to the history screen. Done when a user can download the rows they see."),
  ExampleTask("Review my current branch", "Standalone review", "Review my current branch"),
  ExampleTask("APP-123", "From an issue", "APP-123"),
)

private val START_CHIPS = listOf(
  "Review my branch" to "review",
  "Run the checks" to "validation",
  "QA my branch" to "qa",
  "Open a PR" to "pr",
)

@Composable
internal fun InboxSection(
  needsYou: List<WorkItem>,
  running: List<WorkItem>,
  chats: List<ChatHistoryEntry>,
  savedCount: Int,
  selectedRoot: String?,
  home: NewTaskHome.Returning?,
  showAll: Boolean,
  now: () -> Instant,
  onOpenRun: (WorkItem) -> Unit,
  onResume: (ChatHistoryEntry) -> Unit,
  onDiscard: (ChatHistoryEntry) -> Unit,
  onShowAll: () -> Unit,
  onShowAllWork: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val colors = SkillBillTheme.colors
  val shape = RoundedCornerShape(SkillBillRadius.md)
  val ticker = LocalUiTicker.current
  var filter by remember { mutableStateOf(InboxFilter.All) }
  var selectedIndex by remember(filter) { mutableIntStateOf(0) }
  val cardInteraction = remember { MutableInteractionSource() }
  val cardFocused by cardInteraction.collectIsFocusedAsState()
  DisposableEffect(ticker) {
    ticker.retain()
    onDispose { ticker.release() }
  }
  val tick by ticker.now
  val current = tick.let { now() }
  val activeFilter = if (home != null) filter else InboxFilter.All
  val items = remember(needsYou, running, chats, selectedRoot, current, activeFilter) {
    inboxItems(needsYou, running, chats, selectedRoot, current, filter = activeFilter)
  }
  val visibleItems = remember(items) { items.filter { it !is InboxItem.Group } }
  val selected = selectedIndex.coerceIn(0, (visibleItems.size - 1).coerceAtLeast(0))
  LaunchedEffect(visibleItems) { selectedIndex = selected }
  Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(SECTION_SPACING)) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
      SectionLabel("Pick up where you left off")
      if (home != null) InboxFilterControl(filter, needsYou.size, savedCount) { filter = it }
    }
    Column(
      Modifier.fillMaxWidth().sbFocusRing(cardInteraction, shape).background(colors.bgPanel, shape).clip(shape)
        .border(1.dp, colors.borderSubtle, shape)
        .semantics {
          contentDescription = "Pick up where you left off"
          val row = visibleItems.getOrNull(selected)
          val title = when (row) {
            is InboxItem.Run -> row.item.title
            is InboxItem.Chat -> row.title.title
            else -> null
          }
          stateDescription = if (title == null) "No items" else "${selected + 1} of ${visibleItems.size}, $title"
        }
        .onFocusChanged { if (it.isFocused) selectedIndex = 0 }
        .onPreviewKeyEvent { event ->
          if (event.type != KeyEventType.KeyDown || event.isCtrlPressed || event.isMetaPressed || event.isAltPressed) return@onPreviewKeyEvent false
          val currentSelection = selectedIndex.coerceIn(0, visibleItems.lastIndex.coerceAtLeast(0))
          val row = visibleItems.getOrNull(currentSelection)
          when (event.key) {
            Key.DirectionDown -> { selectedIndex = (currentSelection + 1).coerceAtMost(visibleItems.lastIndex.coerceAtLeast(0)); true }
            Key.DirectionUp -> { selectedIndex = (currentSelection - 1).coerceAtLeast(0); true }
            Key.Enter, Key.NumPadEnter -> { row?.let { openInboxItem(it, onOpenRun, onResume) }; row != null }
            Key.Delete -> if (row is InboxItem.Chat) { onDiscard(row.entry); true } else false
            Key.One, Key.Two, Key.Three, Key.Four, Key.Five, Key.Six, Key.Seven, Key.Eight, Key.Nine -> {
              val index = listOf(Key.One, Key.Two, Key.Three, Key.Four, Key.Five, Key.Six, Key.Seven, Key.Eight, Key.Nine).indexOf(event.key)
              val target = visibleItems.getOrNull(index) ?: return@onPreviewKeyEvent false
              selectedIndex = index
              openInboxItem(target, onOpenRun, onResume)
              true
            }
            else -> false
          }
        }
        .focusable(interactionSource = cardInteraction),
    ) {
      if (items.isEmpty()) {
        BasicText("Nothing here. Start something below.", Modifier.padding(horizontal = 12.dp, vertical = 10.dp), style = SkillBillTheme.type.tSm.copy(color = colors.fgFaint))
      } else {
        items.forEachIndexed { index, item ->
          val previous = items.getOrNull(index - 1)
          if (index > 0 && item !is InboxItem.Group && previous !is InboxItem.Group) {
            Box(Modifier.fillMaxWidth().height(1.dp).background(colors.borderSubtle))
          }
          when (item) {
            is InboxItem.Group -> key(item.day) { InboxGroupHeader(item.day) }
            is InboxItem.Run -> key(item.item.id) { InboxRunRow(item, current, cardFocused && selected == visibleItems.indexOf(item), onOpenRun) }
            is InboxItem.Chat -> key(item.entry.sessionId) { InboxChatRow(item, current, cardFocused && selected == visibleItems.indexOf(item), onResume, onDiscard) }
          }
        }
      }
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(SECTION_SPACING), verticalAlignment = Alignment.CenterVertically) {
      if (home != null) {
        SbButton("${home.running} running · ${home.finished} finished today", onShowAllWork, variant = SbButtonVariant.Ghost, size = SbControlSize.Small)
      }
      if (showAll) SbButton("Show all", onShowAll, variant = SbButtonVariant.Ghost, size = SbControlSize.Small)
    }
  }
}

@Composable
private fun InboxGroupHeader(day: InboxDay) {
  Box(
    Modifier.fillMaxWidth().height(26.dp).background(SkillBillTheme.colors.bgPanel).padding(horizontal = 12.dp),
    contentAlignment = Alignment.BottomStart,
  ) {
    BasicText(day.label.uppercase(), Modifier.padding(bottom = 3.dp), style = SkillBillTheme.type.tXs.copy(color = SkillBillTheme.colors.fgFaint))
  }
}

@Composable
private fun InboxFilterControl(selected: InboxFilter, needsCount: Int, savedCount: Int, onSelect: (InboxFilter) -> Unit) {
  val colors = SkillBillTheme.colors
  Row(
    Modifier.background(colors.bgRaised, RoundedCornerShape(SkillBillRadius.sm)).border(1.dp, colors.border, RoundedCornerShape(SkillBillRadius.sm)).padding(2.dp),
    horizontalArrangement = Arrangement.spacedBy(2.dp),
  ) {
    listOf(Triple(InboxFilter.All, "All", null), Triple(InboxFilter.NeedsYou, "Needs you", needsCount), Triple(InboxFilter.Chats, "Chats", savedCount)).forEach { (filter, label, count) ->
      val active = selected == filter
      val interaction = remember(filter) { MutableInteractionSource() }
      val hovered by interaction.collectIsHoveredAsState()
      val foreground = if (active || hovered) colors.fg else colors.fgMuted
      Row(
        Modifier.height(22.dp)
          .background(if (active) colors.bgActive else Color.Transparent, RoundedCornerShape(SkillBillRadius.xs))
          .clickable(interaction, indication = null, role = Role.Tab, onClick = { onSelect(filter) })
          .sbFocusRing(interaction, RoundedCornerShape(SkillBillRadius.xs))
          .semantics { this.selected = active; contentDescription = label }
          .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
      ) {
        BasicText(label, style = SkillBillTheme.type.tSmMedium.copy(color = foreground), maxLines = 1)
        count?.let {
          val countColor = if (filter == InboxFilter.NeedsYou && it > 0) colors.stBlocked else colors.fgFaint
          BasicText(it.toString(), style = SkillBillTheme.type.tSmMedium.copy(color = countColor), maxLines = 1)
        }
      }
    }
  }
}

private fun openInboxItem(item: InboxItem, onOpenRun: (WorkItem) -> Unit, onResume: (ChatHistoryEntry) -> Unit) {
  when (item) {
    is InboxItem.Run -> onOpenRun(item.item)
    is InboxItem.Chat -> onResume(item.entry)
    is InboxItem.Group -> Unit
  }
}

@Composable
private fun InboxRunRow(row: InboxItem.Run, now: Instant, selected: Boolean, onOpenRun: (WorkItem) -> Unit) {
  val item = row.item
  val colors = SkillBillTheme.colors
  val type = SkillBillTheme.type
  InboxRow(
    onClick = { onOpenRun(item) },
    blocked = row.blocked,
    selected = selected,
    leading = { StatusIcon(item.statusKind(), null, modifier = Modifier.size(16.dp)) },
    middle = { _, _ ->
      BasicText(
        item.issueKey ?: item.id,
        Modifier.weight(1f, fill = false),
        style = type.monoSm.copy(color = colors.fgMuted),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
      BasicText(
        rememberRuntimeText(item.title),
        Modifier.weight(2f, fill = false),
        style = type.tBaseMedium.copy(color = colors.fg),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
      (item.exactPauseReason ?: item.reason)?.takeIf(String::isNotEmpty)?.let {
        BasicText(
          it,
          Modifier.weight(1f, fill = false),
          style = type.tSm.copy(color = colors.fgFaint),
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
      }
      Box(Modifier.weight(1f, fill = false).clipToBounds(), contentAlignment = Alignment.CenterStart) {
        SbLabel(item.stepLabel, SbLabelStyle.Mono)
      }
    },
    trailing = { _, _ -> InboxTime(shortAgo(item.control.updatedAt, now)) },
  )
}

@Composable
private fun InboxChatRow(row: InboxItem.Chat, now: Instant, selected: Boolean, onResume: (ChatHistoryEntry) -> Unit, onDiscard: (ChatHistoryEntry) -> Unit) {
  val colors = SkillBillTheme.colors
  val type = SkillBillTheme.type
  InboxRow(
    onClick = { onResume(row.entry) },
    blocked = false,
    selected = selected,
    leading = {
      val icon = if (row.title.imageName != null) LucideIcons.image else LucideIcons.messageSquare
      val tint = if (row.title.imageName != null) colors.data2 else colors.fgFaint
      CompositionLocalProvider(LocalContentColor provides tint) {
        SbIcon(icon, SbIconSize.Md)
      }
    },
    middle = { hovered, focused ->
      BasicText(
        row.title.title,
        Modifier.weight(2f, fill = false),
        style = type.tSm.copy(color = if (hovered || focused || selected) colors.fg else colors.fg2),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
      row.title.imageName?.let {
        BasicText(
          it,
          Modifier.weight(1f, fill = false),
          style = type.monoSm.copy(color = colors.fgFaint),
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
      }
      row.repoLabel?.let {
        Box(Modifier.weight(1f, fill = false).clipToBounds(), contentAlignment = Alignment.CenterStart) {
          SbLabel(it, SbLabelStyle.Dot(colors.dataColorFor(it)))
        }
      }
    },
    trailing = { hovered, focused ->
      if (hovered || focused || selected) {
        SbIconButton(
          LucideIcons.x,
          "Discard ${row.title.title}",
          { onDiscard(row.entry) },
          modifier = Modifier.focusProperties { canFocus = false },
          size = SbControlSize.Small,
        )
      } else {
        InboxTime(shortAgo(row.entry.updatedAt, now))
      }
    },
  )
}

@Composable
private fun InboxTime(text: String) {
  BasicText(text, style = SkillBillTheme.type.tXs.copy(color = SkillBillTheme.colors.fgFaint, fontFeatureSettings = "tnum"), maxLines = 1)
}

@Composable
private fun InboxRow(
  onClick: () -> Unit,
  blocked: Boolean,
  selected: Boolean,
  leading: @Composable () -> Unit,
  middle: @Composable RowScope.(hovered: Boolean, focused: Boolean) -> Unit,
  trailing: @Composable (hovered: Boolean, focused: Boolean) -> Unit,
) {
  val colors = SkillBillTheme.colors
  val interaction = remember { MutableInteractionSource() }
  val hovered by interaction.collectIsHoveredAsState()
  val focused by interaction.collectIsFocusedAsState()
  Row(
    Modifier.fillMaxWidth().height(34.dp).focusProperties { canFocus = false }
      .background(if (hovered || focused || selected) colors.bgHover else Color.Transparent)
      .drawBehind {
        if (blocked) drawRect(colors.stBlocked, size = Size(2.dp.toPx(), size.height))
        else if (selected) drawRect(colors.accent, topLeft = androidx.compose.ui.geometry.Offset(0f, 7.dp.toPx()), size = Size(2.dp.toPx(), (size.height - 14.dp.toPx()).coerceAtLeast(0f)))
      }
      .pointerHoverIcon(PointerIcon.Hand)
      .clickable(interaction, indication = null, role = Role.Button, onClick = onClick)
      .padding(start = 12.dp, end = 10.dp),
    horizontalArrangement = Arrangement.spacedBy(10.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Box(Modifier.size(16.dp), contentAlignment = Alignment.Center) { leading() }
    Row(
      Modifier.weight(1f).fillMaxWidth(),
      horizontalArrangement = Arrangement.spacedBy(10.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) { middle(hovered, focused) }
    Box(Modifier.width(52.dp), contentAlignment = Alignment.CenterEnd) { trailing(hovered, focused) }
  }
}

@Composable
internal fun ExamplesSection(readiness: ReadinessLine?, onExample: (String) -> Unit, modifier: Modifier = Modifier) {
  val colors = SkillBillTheme.colors
  val type = SkillBillTheme.type
  Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(SECTION_SPACING)) {
    SectionLabel("Try one of these")
    Column {
      EXAMPLE_TASKS.forEach { example ->
        HomeRow({ onExample(example.fill) }, Modifier.heightIn(min = SkillBillSize.row)) {
          BasicText(example.label, Modifier.weight(1f), style = type.tBase.copy(color = colors.fg), maxLines = 1, overflow = TextOverflow.Ellipsis)
          BasicText(example.kind, style = type.tSm.copy(color = colors.fgMuted), maxLines = 1)
        }
      }
    }
    readiness?.let { ReadinessRow(it) }
  }
}

@Composable
private fun ReadinessRow(line: ReadinessLine) {
  val colors = SkillBillTheme.colors
  val dot = if (line.ready) colors.stDone else colors.accent
  Row(horizontalArrangement = Arrangement.spacedBy(SECTION_SPACING), verticalAlignment = Alignment.CenterVertically) {
    Canvas(Modifier.size(7.dp)) { drawCircle(dot) }
    BasicText(line.text, style = SkillBillTheme.type.tSm.copy(color = colors.fgMuted), maxLines = 1, overflow = TextOverflow.Ellipsis)
  }
}

/** Pressing a chip reports its start kind and leaves the input text alone. A null [onStart] renders every chip disabled. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun NewTaskChips(onStart: ((String, String?) -> Unit)?, modifier: Modifier = Modifier) {
  FlowRow(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
    START_CHIPS.forEach { (label, kind) -> StartChip(label, enabled = onStart != null) { onStart?.invoke(kind, null) } }
  }
}

@Composable
private fun StartChip(label: String, enabled: Boolean, onClick: () -> Unit) {
  val colors = SkillBillTheme.colors
  val interaction = remember { MutableInteractionSource() }
  val hovered by interaction.collectIsHoveredAsState()
  Box(
    Modifier.height(26.dp).sbFocusRing(interaction, CHIP_SHAPE).clip(CHIP_SHAPE)
      .background(if (hovered) colors.bgHover else Color.Transparent)
      .border(1.dp, colors.border, CHIP_SHAPE)
      .then(if (enabled) Modifier.pointerHoverIcon(PointerIcon.Hand) else Modifier)
      .clickable(interaction, indication = null, enabled = enabled, role = Role.Button, onClick = onClick)
      .padding(horizontal = 10.dp),
    contentAlignment = Alignment.Center,
  ) {
    BasicText(label, style = SkillBillTheme.type.tSm.copy(color = if (enabled) colors.fgMuted else colors.fgFaint), maxLines = 1)
  }
}

@Composable
private fun SectionLabel(text: String) {
  BasicText(text, Modifier.semantics { heading() }, style = SkillBillTheme.type.tSmMedium.copy(color = SkillBillTheme.colors.fgMuted))
}

@Composable
private fun HomeRow(onClick: () -> Unit, modifier: Modifier, content: @Composable RowScope.() -> Unit) {
  val interaction = remember { MutableInteractionSource() }
  val hovered by interaction.collectIsHoveredAsState()
  val focused by interaction.collectIsFocusedAsState()
  Row(
    modifier.fillMaxWidth()
      .background(if (hovered || focused) SkillBillTheme.colors.bgHover else Color.Transparent, RoundedCornerShape(SkillBillRadius.sm))
      .pointerHoverIcon(PointerIcon.Hand)
      .clickable(interaction, indication = null, role = Role.Button, onClick = onClick)
      .padding(horizontal = 8.dp),
    horizontalArrangement = Arrangement.spacedBy(10.dp),
    verticalAlignment = Alignment.CenterVertically,
    content = content,
  )
}
