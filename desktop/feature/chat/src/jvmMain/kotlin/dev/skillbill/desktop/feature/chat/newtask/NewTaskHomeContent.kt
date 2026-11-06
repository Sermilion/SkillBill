package dev.skillbill.desktop.feature.chat.newtask

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.skillbill.desktop.core.designsystem.SkillBillRadius
import dev.skillbill.desktop.core.designsystem.SkillBillSize
import dev.skillbill.desktop.core.designsystem.SkillBillTheme
import dev.skillbill.desktop.core.designsystem.component.LocalUiTicker
import dev.skillbill.desktop.core.designsystem.component.SbButton
import dev.skillbill.desktop.core.designsystem.component.SbButtonVariant
import dev.skillbill.desktop.core.designsystem.component.SbControlSize
import dev.skillbill.desktop.core.designsystem.component.rememberRuntimeText
import dev.skillbill.desktop.core.designsystem.component.sbFocusRing
import dev.skillbill.desktop.core.designsystem.status.StatusIcon
import dev.skillbill.desktop.core.domain.model.ChatHistoryEntry
import dev.skillbill.desktop.core.domain.model.WorkItem
import dev.skillbill.desktop.core.ui.status.statusKind
import dev.skillbill.desktop.feature.chat.savedAgo
import java.io.File
import kotlin.time.Instant

private val CHIP_SHAPE = RoundedCornerShape(13.dp)
private val TITLE_MAX_WIDTH = 240.dp
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

/** Lists recent saved drafts with resume, discard and picker actions. */
@Composable
internal fun RecentChatsSection(
  saved: List<ChatHistoryEntry>,
  now: Instant,
  openSessionIds: Set<String>,
  onResume: (ChatHistoryEntry) -> Unit,
  onDiscard: (ChatHistoryEntry) -> Unit,
  onShowAll: () -> Unit,
  modifier: Modifier = Modifier,
) {
  Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(SECTION_SPACING)) {
    SectionLabel("Recent chats")
    saved.take(NEW_TASK_HISTORY_ROWS).forEach { entry ->
      key(entry.sessionId) {
        RecentChatRow(entry, now, entry.sessionId in openSessionIds, { onResume(entry) }, { onDiscard(entry) })
      }
    }
    if (saved.size > NEW_TASK_HISTORY_ROWS) {
      SbButton("Show all", onShowAll, variant = SbButtonVariant.Ghost, size = SbControlSize.Small)
    }
  }
}

@Composable
private fun RecentChatRow(entry: ChatHistoryEntry, now: Instant, open: Boolean, onClick: () -> Unit, onDiscard: () -> Unit) {
  val colors = SkillBillTheme.colors
  val type = SkillBillTheme.type
  HomeRow(onClick, Modifier.height(SkillBillSize.row)) {
    Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
      BasicText(rememberRuntimeText(entry.title.lineSequence().first()), Modifier.weight(1f, fill = false), style = type.tBase.copy(color = colors.fg), maxLines = 1, overflow = TextOverflow.Ellipsis)
      if (open) OpenBadge()
    }
    entry.newTask?.let {
      BasicText(File(it.repositoryRoot).name, Modifier.weight(0.5f, fill = false), style = type.tSm.copy(color = colors.fgMuted), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
    BasicText(savedAgo(entry.updatedAt, now), style = type.tSm.copy(color = colors.fgFaint), maxLines = 1)
    SbButton("Discard", onDiscard, variant = SbButtonVariant.Ghost, size = SbControlSize.Small)
  }
}

/** `.home-row .open`: marks a saved chat that is open in a tab. */
@Composable
private fun OpenBadge() {
  val colors = SkillBillTheme.colors
  BasicText(
    "open",
    Modifier.border(1.dp, colors.accentRing, RoundedCornerShape(percent = 50)).padding(horizontal = 6.dp),
    style = SkillBillTheme.type.tXs.copy(color = colors.accentText),
    maxLines = 1,
  )
}

@Composable
internal fun NeedsYouSection(home: NewTaskHome.Returning, now: () -> Instant, onOpenRun: (WorkItem) -> Unit, onShowAllWork: () -> Unit, modifier: Modifier = Modifier) {
  Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(SECTION_SPACING)) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
      Box(Modifier.weight(1f)) { if (home.needsYou.isNotEmpty()) SectionLabel("Needs you") }
      SbButton("${home.running} running · ${home.finished} finished today", onShowAllWork, variant = SbButtonVariant.Ghost, size = SbControlSize.Small)
    }
    if (home.needsYou.isNotEmpty()) NeedsYouRows(home.needsYou, now, onOpenRun)
  }
}

@Composable
private fun NeedsYouRows(items: List<WorkItem>, now: () -> Instant, onOpenRun: (WorkItem) -> Unit) {
  val ticker = LocalUiTicker.current
  DisposableEffect(ticker) {
    ticker.retain()
    onDispose { ticker.release() }
  }
  val tick by ticker.now
  val current = tick.let { now() }
  Column {
    items.forEach { item -> key(item.id) { NeedsYouRow(item, current) { onOpenRun(item) } } }
  }
}

@Composable
private fun NeedsYouRow(item: WorkItem, now: Instant, onClick: () -> Unit) {
  val colors = SkillBillTheme.colors
  val type = SkillBillTheme.type
  HomeRow(onClick, Modifier.height(SkillBillSize.row)) {
    StatusIcon(item.statusKind(), null)
    BasicText(item.issueKey ?: item.id, style = type.monoSm.copy(color = colors.fgMuted), maxLines = 1)
    BasicText(rememberRuntimeText(item.title), Modifier.widthIn(max = TITLE_MAX_WIDTH), style = type.tBaseMedium.copy(color = colors.fg), maxLines = 1, overflow = TextOverflow.Ellipsis)
    BasicText(rememberRuntimeText((item.exactPauseReason ?: item.reason).orEmpty()), Modifier.weight(1f), style = type.tSm.copy(color = colors.fgMuted), maxLines = 1, overflow = TextOverflow.Ellipsis)
    BasicText(savedAgo(item.control.updatedAt, now), style = type.tSm.copy(color = colors.fgFaint), maxLines = 1)
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
