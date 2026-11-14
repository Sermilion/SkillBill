package dev.skillbill.desktop.feature.chat.newtask

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
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
import dev.skillbill.desktop.core.designsystem.component.SbPickerButton
import dev.skillbill.desktop.core.designsystem.component.SbPopover
import dev.skillbill.desktop.core.designsystem.component.SbPopoverPlacement
import dev.skillbill.desktop.core.designsystem.component.SbTooltip
import dev.skillbill.desktop.core.designsystem.icon.LucideIcons
import dev.skillbill.desktop.core.domain.model.ChatChanges
import dev.skillbill.desktop.core.domain.model.chatScopeTarget
import dev.skillbill.desktop.core.ui.changes.ChangesSource
import dev.skillbill.desktop.core.ui.input.ShellLayer

@Composable
internal fun NewTaskChangesPill(changes: ChatChanges, expanded: Boolean, focusRequester: FocusRequester, onClick: () -> Unit) {
  val colors = SkillBillTheme.colors
  val type = SkillBillTheme.type
  SbTooltip("Uncommitted changes against the latest HEAD") {
    SbPickerButton(
      onClick = onClick,
      modifier = Modifier.focusRequester(focusRequester).semantics { stateDescription = if (expanded) "Expanded" else "Collapsed" },
      icon = LucideIcons.diff,
      open = expanded,
      onClickLabel = "Show chat changes",
      chevronDescription = "Show chat changes",
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
internal fun ChatChangesAnchor(chat: NewTaskChat?, state: ChatChangesState, composerFocus: FocusRequester, onOpenChanges: (ChangesSource.Chat, String?) -> Unit = { _, _ -> }, repositoryRoot: String? = chat?.selectedRepositoryRoot) {
  LaunchedEffect(chat) { chat?.observeChanges() }
  key(chat, repositoryRoot) {
    ChangesAnchor(state, composerFocus) { worktreeRoot, path ->
      val shown = state as? ChatChangesState.Shown ?: return@ChangesAnchor
      openChatChanges(chat, shown, repositoryRoot, onOpenChanges, path, worktreeRoot)
    }
  }
}

@Composable
internal fun ChangesAnchor(
  state: ChatChangesState,
  composerFocus: FocusRequester,
  onOpen: (worktreeRoot: String?, path: String?) -> Unit,
) {
  val pillFocus = remember { FocusRequester() }
  var open by remember { mutableStateOf(false) }
  val shown = state as? ChatChangesState.Shown
  LaunchedEffect(shown == null) { if (shown == null) open = false }
  val expanded = open && shown != null
  val density = LocalDensity.current
  val windowWidth = with(density) { LocalWindowInfo.current.containerSize.width.toDp() }
  val returnFocus = if (shown != null) pillFocus else composerFocus
  fun openChanges(root: String?, path: String?) {
    open = false
    onOpen(root, path)
  }
  Box {
    if (shown != null) NewTaskChangesPill(shown.changes, expanded, pillFocus) { open = !open }
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
        onOpenDiff = { openChanges(null, null) },
        onOpenFile = { path -> openChanges(null, path) },
        onOpenWorktree = { worktree, path -> openChanges(worktree.repositoryRoot, path) },
      )
    }
  }
}

private fun openChatChanges(chat: NewTaskChat?, current: ChatChangesState.Shown, repositoryRoot: String?, dispatch: (ChangesSource.Chat, String?) -> Unit, path: String?, worktreeRoot: String? = null) {
  val worktree = current.changes.worktrees.firstOrNull { it.repositoryRoot == worktreeRoot }
  if (worktreeRoot != null && worktree == null) return
  val root = worktree?.repositoryRoot ?: repositoryRoot ?: return
  val baseline = worktree?.baseline ?: current.baseline
  val changes = worktree?.changes ?: current.changes
  val paths = worktree?.paths ?: current.paths
  val sessionId = chat?.sessionId ?: "preview:$root"
  val source = ChangesSource.Chat(
    sessionId,
    chatChangesWindowTitle(chat?.thread?.transcript?.value.orEmpty()),
    chatScopeTarget(sessionId, root, baseline, changes.timeline, paths, changes.comparisonBaseSha),
  )
  dispatch(source, path)
}
