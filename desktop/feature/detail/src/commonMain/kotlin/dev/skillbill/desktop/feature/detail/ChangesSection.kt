package dev.skillbill.desktop.feature.detail

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.skillbill.desktop.core.designsystem.SkillBillTheme
import dev.skillbill.desktop.core.designsystem.list.sbRowSurface
import dev.skillbill.desktop.core.designsystem.component.Hint
import dev.skillbill.desktop.core.designsystem.component.SbButton
import dev.skillbill.desktop.core.designsystem.component.SbButtonVariant
import dev.skillbill.desktop.core.designsystem.icon.LucideIcons
import dev.skillbill.desktop.core.domain.model.ChangeScope
import dev.skillbill.desktop.core.domain.model.ChangesState
import dev.skillbill.desktop.core.domain.model.ScopeSubtask
import dev.skillbill.desktop.core.domain.model.ScopeSubtaskState
import dev.skillbill.desktop.core.domain.model.SubtaskScopesState
import dev.skillbill.desktop.core.domain.model.SubtaskStats
import dev.skillbill.desktop.core.ui.changes.AddCount
import dev.skillbill.desktop.core.ui.changes.DelCount
import dev.skillbill.desktop.core.ui.changes.ScopeStateDot
import dev.skillbill.desktop.core.ui.changes.SubtaskStrip
import dev.skillbill.desktop.core.ui.changes.timelineProblemText
import dev.skillbill.desktop.core.ui.changes.changesMono
import dev.skillbill.desktop.core.ui.changes.changesSans
import dev.skillbill.desktop.core.ui.changes.filesLabel
import dev.skillbill.desktop.core.ui.feedback.readProblemText

/**
 * The open item's changes. A goal shows its per-subtask strip and the running subtask; a click opens the Changes window on that
 * subtask. Any other item shows its change summary. Every item has the "Open changes" button, which opens All.
 */
internal fun DetailSections.changesSection(changes: ChangesUi, onAction: (DetailAction) -> Unit) {
  val subtasks = changes.subtasks
  val count = when {
    subtasks is SubtaskScopesState.Listed -> goalCount(subtasks)
    subtasks == null -> (changes.state as? ChangesState.Listed)?.changes?.files?.size?.toString()
    else -> null
  }
  section(
    "changes",
    "Changes",
    count = count,
    countStyle = if (subtasks is SubtaskScopesState.Listed) goalCountStyle else null,
    actions = { SbButton("Open changes", { onAction(DetailAction.OpenChangesWindow()) }, variant = SbButtonVariant.Primary, icon = LucideIcons.openWindow) },
  ) {
    if (subtasks != null) goalChanges(subtasks, onAction) else executionChanges(changes.state)
  }
}

private val goalCountStyle: @Composable () -> TextStyle = { changesMono(11f, FontWeight.Normal, SkillBillTheme.colors.fgMuted) }

/** `N files · +A −D · M subtasks`: files by path across the subtasks' lists, counts summed over them. */
private fun goalCount(state: SubtaskScopesState.Listed): String {
  val stats = state.stats.values
  val files = stats.flatMap { it.files }.map { it.path }.distinct().size
  return "${filesLabel(files)} · +${stats.sumOf { it.insertions }} −${stats.sumOf { it.deletions }} · ${state.subtasks.size} subtasks"
}

private fun LazyListScope.goalChanges(state: SubtaskScopesState, onAction: (DetailAction) -> Unit) {
  when (state) {
    SubtaskScopesState.Loading -> notice("changes-loading") { Hint("Loading changes") }
    SubtaskScopesState.NotAGoal -> notice("changes-none") { Hint("No subtasks recorded") }
    is SubtaskScopesState.Unavailable -> notice("changes-problem") { Hint(readProblemText(state.problem)) }
    is SubtaskScopesState.Listed -> {
      state.timelineProblem?.let { problem -> notice("changes-timeline-problem") { Hint(timelineProblemText(problem)) } }
      val open = { id: Int -> onAction(DetailAction.OpenChangesWindow(ChangeScope.Subtasks(id, id))) }
      // Shift-clicks and drags do nothing in the detail strip; only a plain click opens the pressed subtask.
      item(key = "changes-strip") {
        val running = state.subtasks.firstOrNull { it.state == ScopeSubtaskState.RUNNING }
        SubtaskStrip(
          state.subtasks,
          state.stats,
          selected = null,
          onPick = { id, extend -> if (!extend) open(id) },
          modifier = Modifier.padding(top = 4.dp),
          barsFooter = running?.let { { RunningRow(it, state.stats[it.id], open) } },
        )
      }
    }
  }
}

private fun LazyListScope.executionChanges(state: ChangesState) {
  when (state) {
    ChangesState.Loading -> notice("changes-loading") { Hint("Loading changes") }
    ChangesState.NoSubtask -> notice("changes-none") { Hint("No base branch to diff against yet") }
    is ChangesState.BaseUnavailable -> notice("changes-base") { Hint("Base commit unavailable: ${state.reason}") }
    is ChangesState.NotARepository -> notice("changes-repo") { Hint("${state.worktreeRoot} is not a git repository") }
    is ChangesState.Unavailable -> notice("changes-problem") { Hint(readProblemText(state.problem)) }
    is ChangesState.Listed -> if (state.changes.files.isEmpty()) {
      notice("changes-empty") { Hint("No changes since the base commit") }
    } else {
      val set = state.changes
      item(key = "changes-totals") {
        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          val colors = SkillBillTheme.colors
          val type = SkillBillTheme.type
          BasicText(if (set.files.size == 1) "1 file ·" else "${set.files.size} files ·", style = type.tSm.copy(color = colors.fgMuted))
          BasicText("+${set.insertions}", style = type.monoSm.copy(color = colors.stDone))
          BasicText("−${set.deletions}", style = type.monoSm.copy(color = colors.stFailed))
        }
      }
    }
  }
}

private fun LazyListScope.notice(key: String, content: @Composable () -> Unit) {
  item(key = key) { Row(Modifier.padding(vertical = 4.dp)) { content() } }
}

@Composable
private fun RunningRow(subtask: ScopeSubtask, stats: SubtaskStats?, onOpen: (Int) -> Unit) {
  val colors = SkillBillTheme.colors
  val interaction = remember { MutableInteractionSource() }
  val shape = RoundedCornerShape(7.dp)
  val clickable = if (subtask.selectable) {
    Modifier.pointerHoverIcon(PointerIcon.Hand).clickable(interaction, indication = null) { onOpen(subtask.id) }
  } else {
    Modifier
  }
  Row(
    Modifier.padding(top = 14.dp).fillMaxWidth()
      .sbRowSurface(interaction, shape = shape)
      .then(clickable)
      .padding(horizontal = 10.dp, vertical = 9.dp),
    horizontalArrangement = Arrangement.spacedBy(10.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    ScopeStateDot(subtask.state)
    BasicText("${subtask.id}", style = changesMono(11f, FontWeight.SemiBold, colors.accentText), maxLines = 1, softWrap = false)
    BasicText(subtask.name, Modifier.weight(1f), style = changesSans(12.5f, FontWeight.Normal, colors.fg2, lineHeight = 16.25f), maxLines = 1, overflow = TextOverflow.Ellipsis)
    BasicText(filesLabel(stats?.files?.size ?: 0), style = changesMono(11f, FontWeight.Normal, colors.fgMuted), maxLines = 1, softWrap = false)
    AddCount(stats?.insertions ?: 0)
    DelCount(stats?.deletions ?: 0)
  }
}
