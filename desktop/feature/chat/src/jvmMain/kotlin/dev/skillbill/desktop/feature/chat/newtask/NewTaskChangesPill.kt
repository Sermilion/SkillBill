package dev.skillbill.desktop.feature.chat.newtask

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import dev.skillbill.desktop.core.designsystem.SkillBillTheme
import dev.skillbill.desktop.core.designsystem.component.LocalUiTicker
import dev.skillbill.desktop.core.designsystem.component.SbPickerButton
import dev.skillbill.desktop.core.designsystem.component.SbPopover
import dev.skillbill.desktop.core.designsystem.component.SbPopoverPlacement
import dev.skillbill.desktop.core.designsystem.component.SbTooltip
import dev.skillbill.desktop.core.designsystem.icon.LucideIcons
import dev.skillbill.desktop.core.domain.model.ChatChanges
import dev.skillbill.desktop.core.domain.model.chatScopeTarget
import dev.skillbill.desktop.core.ui.changes.ChangesSource
import dev.skillbill.desktop.core.ui.input.ShellLayer
import java.time.Instant as JavaInstant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.time.Instant

internal fun chatChangesSince(takenAt: Instant, now: Instant, zone: ZoneId = ZoneId.systemDefault()): String {
  val taken = JavaInstant.ofEpochSecond(takenAt.epochSeconds, takenAt.nanosecondsOfSecond.toLong()).atZone(zone)
  val current = JavaInstant.ofEpochSecond(now.epochSeconds, now.nanosecondsOfSecond.toLong()).atZone(zone)
  val pattern = if (taken.toLocalDate() == current.toLocalDate()) "HH:mm" else "MMM d, HH:mm"
  return DateTimeFormatter.ofPattern(pattern, Locale.ENGLISH).format(taken)
}

@Composable
internal fun NewTaskChangesPill(changes: ChatChanges, since: String, expanded: Boolean, focusRequester: FocusRequester, onClick: () -> Unit) {
  val colors = SkillBillTheme.colors
  val type = SkillBillTheme.type
  SbTooltip("Files this chat changed since $since") {
    SbPickerButton(
      onClick = onClick,
      modifier = Modifier.focusRequester(focusRequester).semantics { stateDescription = if (expanded) "Expanded" else "Collapsed" },
      icon = LucideIcons.diff,
      open = expanded,
      onClickLabel = "Show files this chat changed",
      chevronDescription = "Show files this chat changed",
      chevron = LucideIcons.chevronsUpDown,
    ) {
      Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        BasicText("${changes.files.size} ${if (changes.files.size == 1) "file" else "files"}", style = type.monoSm.copy(color = colors.fgMuted), maxLines = 1, softWrap = false)
        BasicText("+${changes.insertions}", style = type.monoSm.copy(color = colors.stDone), maxLines = 1, softWrap = false)
        BasicText("−${changes.deletions}", style = type.monoSm.copy(color = colors.stFailed), maxLines = 1, softWrap = false)
      }
    }
  }
}

@Composable
internal fun ChatChangesAnchor(chat: NewTaskChat?, state: ChatChangesState, composerFocus: FocusRequester, now: () -> Instant, onOpenChanges: (ChangesSource.Chat, String?) -> Unit = { _, _ -> }) {
  LaunchedEffect(chat) { chat?.observeChanges() }
  val pillFocus = remember { FocusRequester() }
  var open by remember(chat) { mutableStateOf(false) }
  val shown = state as? ChatChangesState.Shown
  LaunchedEffect(chat, shown == null) { if (chat == null || shown == null) open = false }
  val expanded = open && shown != null
  val ticker = LocalUiTicker.current
  if (shown != null) {
    DisposableEffect(ticker) { ticker.retain(); onDispose { ticker.release() } }
  }
  val tick by ticker.now
  val since = shown?.let { remember(it.baseline.takenAt, tick) { chatChangesSince(it.baseline.takenAt, now()) } } ?: ""

  val density = LocalDensity.current
  val windowWidth = with(density) { LocalWindowInfo.current.containerSize.width.toDp() }
  val returnFocus = if (shown != null) pillFocus else composerFocus
  Box {
    if (shown != null) NewTaskChangesPill(shown.changes, since, expanded, pillFocus) { open = !open }
    if (expanded) ShellLayer { open = false }
    SbPopover(
    expanded = expanded,
    onDismissRequest = { open = false },
    width = minOf(440.dp, (windowWidth - 32.dp).coerceAtLeast(0.dp)),
    maxWidth = 440.dp,
    placement = SbPopoverPlacement.AboveEnd,
    returnFocusTo = returnFocus,
    arrowKeysMoveFocus = false,
    title = "Changes",
    ) {
      if (shown != null) NewTaskChangesPanel(
        shown,
        since,
        onOpenDiff = { openChatChanges(chat, onOpenChanges, null) { open = false } },
        onOpenFile = { path -> openChatChanges(chat, onOpenChanges, path) { open = false } },
        onOpenWorktree = { worktree, path -> openChatChanges(chat, onOpenChanges, path, worktree.repositoryRoot) { open = false } },
      )
    }
  }
}

private fun openChatChanges(chat: NewTaskChat?, dispatch: (ChangesSource.Chat, String?) -> Unit, path: String?, worktreeRoot: String? = null, close: () -> Unit) {
  close()
  val currentChat = chat ?: return
  val current = currentChat.changes.value as? ChatChangesState.Shown ?: return
  val worktree = current.changes.worktrees.firstOrNull { it.repositoryRoot == worktreeRoot }
  if (worktreeRoot != null && worktree == null) return
  val root = worktree?.repositoryRoot ?: currentChat.repositoryRoot
  val baseline = worktree?.baseline ?: current.baseline
  val changes = worktree?.changes ?: current.changes
  val paths = worktree?.paths ?: current.paths
  val sessionId = currentChat.sessionId
  val source = ChangesSource.Chat(
    sessionId,
    chatChangesWindowTitle(currentChat.thread.transcript.value),
    chatScopeTarget(sessionId, root, baseline, changes.timeline, paths, changes.comparisonBaseSha),
  )
  dispatch(source, path)
}
