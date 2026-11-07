package dev.skillbill.desktop.feature.chat

import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.skillbill.desktop.core.designsystem.LocalContentColor
import dev.skillbill.desktop.core.designsystem.SkillBillTheme
import dev.skillbill.desktop.core.designsystem.list.sbRowSurface
import dev.skillbill.desktop.core.designsystem.composite.Edge
import dev.skillbill.desktop.core.designsystem.composite.hairline
import dev.skillbill.desktop.core.designsystem.component.Kbd
import dev.skillbill.desktop.core.designsystem.component.SbButton
import dev.skillbill.desktop.core.designsystem.component.SbButtonVariant
import dev.skillbill.desktop.core.designsystem.component.SbControlSize
import dev.skillbill.desktop.core.designsystem.component.SbElevation
import dev.skillbill.desktop.core.designsystem.component.sbOverlaySurface
import dev.skillbill.desktop.core.designsystem.component.SbLabel
import dev.skillbill.desktop.core.designsystem.component.SbLabelStyle
import dev.skillbill.desktop.core.designsystem.icon.LucideIcons
import dev.skillbill.desktop.core.designsystem.icon.SbIcon
import dev.skillbill.desktop.core.designsystem.icon.SbIconSize
import dev.skillbill.desktop.core.domain.model.ChatHistoryEntry
import dev.skillbill.desktop.core.domain.model.ChatHistoryRole
import dev.skillbill.desktop.core.ui.input.ShellLayer
import java.time.ZoneId
import kotlin.time.Instant

private const val PREVIEW_LINES = 4

/** Where the palette's chats come from: a repository's name in New Task, or a run's worktree label when [worktree]. */
internal data class ResumeScope(val label: String?, val worktree: Boolean)

/**
 * The `/resume` palette: a filterable list of [choices] grouped by day on the left and the active chat's latest messages on
 * the right. Up and Down move, Enter resumes, Esc closes; focus returns to [returnFocusTo] when it closes.
 */
@Composable
internal fun ResumePalette(
  choices: List<ChatHistoryEntry>, now: Instant, scope: ResumeScope, branchOf: (ChatHistoryEntry) -> String?,
  returnFocusTo: FocusRequester?, onResume: (ChatHistoryEntry) -> Unit, onDismiss: () -> Unit,
  zone: ZoneId = ZoneId.systemDefault(),
) {
  ShellLayer(onDismiss)
  val colors = SkillBillTheme.colors
  Dialog(onDismiss, DialogProperties(dismissOnBackPress = true, dismissOnClickOutside = true, usePlatformDefaultWidth = false, scrimColor = colors.scrim)) {
    DisposableEffect(Unit) { onDispose { returnFocusTo?.requestFocus() } }
    var query by remember { mutableStateOf("") }
    var active by remember { mutableIntStateOf(0) }
    var showAll by remember { mutableStateOf(false) }
    val listing = remember(choices, query, showAll) { resumeListing(choices, query, showAll, branchOf) }
    val selected = active.coerceAtMost(listing.shown.lastIndex).coerceAtLeast(0)
    val current = listing.shown.getOrNull(selected)
    val resume: () -> Unit = { current?.let(onResume) }
    BoxWithConstraints(Modifier.fillMaxSize().clickable(onClick = onDismiss)) {
      Column(
        Modifier.align(Alignment.TopCenter).padding(top = maxHeight * .12f)
          .width(min(860.dp, (maxWidth - 48.dp).coerceAtLeast(0.dp))).height(min(520.dp, maxHeight * .76f))
          .sbOverlaySurface(SbElevation.Modal)
          .focusProperties { canFocus = false }
          .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
          .padding(1.dp)
          .onPreviewKeyEvent { if (it.type == KeyEventType.KeyDown && it.key == Key.Escape) { onDismiss(); true } else false },
      ) {
        FilterBar(query, scope, onQuery = { query = it; active = 0 }, onMove = { step ->
          active = (selected + step).coerceAtMost(listing.shown.lastIndex).coerceAtLeast(0)
        }, onSubmit = resume)
        Row(Modifier.weight(1f).fillMaxWidth()) {
          Box(Modifier.weight(1f).fillMaxHeight().hairline(Edge.End, colors.borderSubtle)) {
            ResumeList(choices.isEmpty(), listing, query, showAll, selected, now, zone, branchOf, onShowAll = { showAll = true }, onActivate = { active = it }, onResume = onResume)
          }
          Box(Modifier.weight(1f).fillMaxHeight()) {
            ResumePreview(current, now, branchOf, onResume = resume, onDismiss = onDismiss)
          }
        }
        PaletteFooter(resumeCount(listing))
      }
    }
  }
}

@Composable
private fun FilterBar(query: String, scope: ResumeScope, onQuery: (String) -> Unit, onMove: (Int) -> Unit, onSubmit: () -> Unit) {
  val colors = SkillBillTheme.colors
  val type = SkillBillTheme.type
  val focus = remember { FocusRequester() }
  LaunchedEffect(Unit) { focus.requestFocus() }
  val placeholder = if (scope.worktree) "Filter chats in this worktree" else "Filter by title, branch or agent"
  Row(
    Modifier.fillMaxWidth().height(44.dp).hairline(Edge.Bottom, colors.borderSubtle).padding(horizontal = 14.dp),
    horizontalArrangement = Arrangement.spacedBy(10.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    CompositionLocalProvider(LocalContentColor provides colors.fgFaint) { SbIcon(LucideIcons.search, SbIconSize.Sm) }
    BasicTextField(
      value = query,
      onValueChange = onQuery,
      singleLine = true,
      textStyle = type.tBase.copy(color = colors.fg),
      cursorBrush = SolidColor(colors.fg),
      modifier = Modifier.weight(1f).focusRequester(focus).onPreviewKeyEvent { event ->
        if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
        when (event.key) {
          Key.DirectionDown -> { onMove(1); true }
          Key.DirectionUp -> { onMove(-1); true }
          Key.Enter, Key.NumPadEnter -> { onSubmit(); true }
          else -> false
        }
      },
      decorationBox = { inner ->
        Box(contentAlignment = Alignment.CenterStart) {
          if (query.isEmpty()) BasicText(placeholder, style = type.tBase.copy(color = colors.fgFaint), maxLines = 1)
          inner()
        }
      },
    )
    scope.label?.let {
      SbLabel(
        it,
        style = if (scope.worktree) SbLabelStyle.Mono else SbLabelStyle.Plain,
        icon = if (scope.worktree) LucideIcons.branch else LucideIcons.folder,
      )
    }
  }
}

@Composable
private fun ResumeList(
  noChats: Boolean, listing: ResumeListing, query: String, showAll: Boolean, active: Int, now: Instant, zone: ZoneId,
  branchOf: (ChatHistoryEntry) -> String?, onShowAll: () -> Unit, onActivate: (Int) -> Unit, onResume: (ChatHistoryEntry) -> Unit,
) {
  val q = query.trim()
  if (noChats) return PaneNote("No saved chats yet", "Chats you start here show up in this list.")
  if (listing.shown.isEmpty()) return PaneNote("No chat matches \"$q\"", "The filter checks titles, branches and agents.")
  val groups = remember(listing, now, zone) { listing.shown.map { resumeGroup(it.updatedAt, now, zone) } }
  val headed = remember(groups) { groups.indices.filter { it == 0 || groups[it] != groups[it - 1] }.toSet() }
  val rowIndex = remember(groups) {
    var headers = 0
    groups.indices.map { index -> if (index in headed) headers++; index + headers }
  }
  val listState = rememberLazyListState()
  LaunchedEffect(active, listing) {
    val target = rowIndex.getOrNull(active) ?: return@LaunchedEffect
    val top = if (active in headed) target - 1 else target
    val info = listState.layoutInfo
    val item = info.visibleItemsInfo.firstOrNull { it.index == target }
    val first = info.visibleItemsInfo.firstOrNull { it.index == top }
    when {
      item == null || first == null -> listState.scrollToItem(top)
      first.offset < info.viewportStartOffset -> listState.scrollBy((first.offset - info.viewportStartOffset).toFloat())
      item.offset + item.size > info.viewportEndOffset -> listState.scrollBy((item.offset + item.size - info.viewportEndOffset).toFloat())
    }
  }
  val colors = SkillBillTheme.colors
  val type = SkillBillTheme.type
  LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = PaddingValues(vertical = 6.dp)) {
    listing.shown.forEachIndexed { index, entry ->
      if (index in headed) {
        item {
          BasicText(groups[index].label, Modifier.padding(start = 14.dp, end = 14.dp, top = 8.dp, bottom = 4.dp), style = type.t2xs.copy(color = colors.fgFaint))
        }
      }
      item {
        ResumeRow(entry, index == active, q, now, branchOf, onClick = { onActivate(index) }, onDoubleClick = { onResume(entry) })
      }
    }
    if (q.isEmpty() && !showAll && listing.total > RESUME_LIMIT) {
      item {
        Box(Modifier.fillMaxWidth().padding(8.dp).focusProperties { canFocus = false }, contentAlignment = Alignment.Center) {
          SbButton("Show all ${listing.total} chats", onShowAll, variant = SbButtonVariant.Ghost, size = SbControlSize.Small)
        }
      }
    }
  }
}

@Composable
private fun ResumeRow(
  entry: ChatHistoryEntry, active: Boolean, query: String, now: Instant, branchOf: (ChatHistoryEntry) -> String?,
  onClick: () -> Unit, onDoubleClick: () -> Unit,
) {
  val colors = SkillBillTheme.colors
  val type = SkillBillTheme.type
  val interaction = remember { MutableInteractionSource() }
  val title = resumeTitle(entry)
  Column(
    Modifier.fillMaxWidth().padding(horizontal = 6.dp).sbRowSurface(interaction, selected = active)
      .focusProperties { canFocus = false }.combinedClickable(interactionSource = interaction, indication = null, onClick = onClick, onDoubleClick = onDoubleClick)
      .padding(horizontal = 8.dp, vertical = 6.dp),
    verticalArrangement = Arrangement.spacedBy(2.dp),
  ) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
      CompositionLocalProvider(LocalContentColor provides colors.fgFaint) {
        if (entry.lines.isEmpty()) SbIcon(LucideIcons.terminal, SbIconSize.Xs)
        if (title.images.isNotEmpty()) SbIcon(LucideIcons.image, SbIconSize.Xs)
      }
      val styled = buildAnnotatedString {
        append(title.text)
        val at = if (query.isEmpty()) -1 else title.text.indexOf(query, ignoreCase = true)
        if (at >= 0) addStyle(SpanStyle(color = colors.accentText), at, at + query.length)
      }
      BasicText(styled, Modifier.weight(1f), style = type.tSm.copy(color = colors.fg), maxLines = 1, overflow = TextOverflow.Ellipsis)
      if (title.images.size > 1) BasicText("+${title.images.size - 1}", style = type.t2xs.copy(color = colors.fgFaint))
      BasicText(savedAgo(entry.updatedAt, now).removeSuffix(" ago"), style = type.t2xs.copy(color = colors.fgFaint))
    }
    val meta = buildAnnotatedString {
      branchOf(entry)?.let { branch ->
        val start = length
        append(branch)
        addStyle(type.monoSm.toSpanStyle().copy(color = colors.fgFaint), start, length)
        append(" · ")
      }
      append(agentName(entry.agent))
    }
    BasicText(meta, style = type.tXs.copy(color = colors.fgFaint), maxLines = 1, overflow = TextOverflow.Ellipsis)
  }
}

@Composable
private fun ResumePreview(entry: ChatHistoryEntry?, now: Instant, branchOf: (ChatHistoryEntry) -> String?, onResume: () -> Unit, onDismiss: () -> Unit) {
  val colors = SkillBillTheme.colors
  val type = SkillBillTheme.type
  Column(Modifier.fillMaxSize()) {
    Column(Modifier.weight(1f).fillMaxWidth()) {
      if (entry == null) {
        Box(Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.Center) {
          BasicText("Pick a chat to see its latest messages.", style = type.tSm.copy(color = colors.fgFaint))
        }
      } else {
        Column(Modifier.fillMaxWidth().hairline(Edge.Bottom, colors.borderSubtle).padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
          BasicText(resumeTitle(entry).text, style = type.tMd.copy(color = colors.fg, fontWeight = FontWeight.Medium), maxLines = 2, overflow = TextOverflow.Ellipsis)
          branchOf(entry)?.let { PreviewFact("Branch") { BasicText(it, style = type.monoSm.copy(color = colors.fgMuted), maxLines = 1, overflow = TextOverflow.Ellipsis) } }
          PreviewFact("Agent") { BasicText("${agentName(entry.agent)} · ${savedAgo(entry.updatedAt, now)}", style = type.tXs.copy(color = colors.fgMuted)) }
        }
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
          if (entry.lines.isEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
              CompositionLocalProvider(LocalContentColor provides colors.fgFaint) { SbIcon(LucideIcons.terminal, SbIconSize.Sm) }
              BasicText("This session lives in the ${agentName(entry.agent)} CLI. Its messages load when you resume it.", style = type.tSm.copy(color = colors.fgMuted))
            }
          } else {
            entry.lines.filter { it.role == ChatHistoryRole.Operator || it.role == ChatHistoryRole.Agent }.takeLast(PREVIEW_LINES).forEach { line ->
              val (paths, rest) = splitLeadingImages(line.text)
              Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                BasicText(if (line.role == ChatHistoryRole.Operator) "You" else agentName(entry.agent), style = type.tXs.copy(color = colors.fgMuted, fontWeight = FontWeight.Medium))
                paths.forEach { SbLabel(it.substringAfterLast('/'), icon = LucideIcons.image) }
                if (rest.isNotBlank()) BasicText(rest.trim(), style = type.tSm.copy(color = colors.fg), maxLines = 4, overflow = TextOverflow.Ellipsis)
              }
            }
          }
        }
      }
    }
    Row(
      Modifier.fillMaxWidth().hairline(Edge.Top, colors.borderSubtle).padding(horizontal = 12.dp, vertical = 10.dp),
      horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
    ) {
      SbButton("Cancel", onDismiss, size = SbControlSize.Small)
      SbButton("Resume", onResume, variant = SbButtonVariant.Primary, size = SbControlSize.Small, enabled = entry != null)
    }
  }
}

@Composable
private fun PreviewFact(label: String, value: @Composable () -> Unit) {
  Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
    BasicText(label, Modifier.width(52.dp), style = SkillBillTheme.type.tXs.copy(color = SkillBillTheme.colors.fgFaint))
    value()
  }
}

@Composable
private fun PaneNote(title: String, hint: String) {
  val colors = SkillBillTheme.colors
  val type = SkillBillTheme.type
  Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically), horizontalAlignment = Alignment.CenterHorizontally) {
    BasicText(title, style = type.tSm.copy(color = colors.fg))
    BasicText(hint, style = type.tXs.copy(color = colors.fgFaint))
  }
}

@Composable
private fun PaletteFooter(count: String) {
  val colors = SkillBillTheme.colors
  val hint = SkillBillTheme.type.t2xs.copy(color = colors.fgFaint)
  Row(
    Modifier.fillMaxWidth().height(30.dp).hairline(Edge.Top, colors.borderSubtle).padding(horizontal = 12.dp),
    horizontalArrangement = Arrangement.spacedBy(6.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    listOf("↑↓" to "move", "↵" to "resume", "esc" to "close").forEach { (keys, action) ->
      Kbd(keys)
      BasicText(action, Modifier.padding(end = 6.dp), style = hint)
    }
    Spacer(Modifier.weight(1f))
    BasicText(count, style = hint)
  }
}
