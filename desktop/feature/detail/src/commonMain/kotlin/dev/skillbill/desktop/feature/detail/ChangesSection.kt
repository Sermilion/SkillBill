package dev.skillbill.desktop.feature.detail

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.skillbill.desktop.core.designsystem.SkillBillTheme
import dev.skillbill.desktop.core.designsystem.component.Hint
import dev.skillbill.desktop.core.designsystem.component.SbButton
import dev.skillbill.desktop.core.domain.model.ChangeScope
import dev.skillbill.desktop.core.domain.model.ChangesState
import dev.skillbill.desktop.core.domain.model.ScopeSubtask
import dev.skillbill.desktop.core.domain.model.ScopeSubtaskState
import dev.skillbill.desktop.core.domain.model.SubtaskScopesState
import dev.skillbill.desktop.core.domain.model.SubtaskStats
import dev.skillbill.desktop.core.ui.changes.ScopeStateDot
import dev.skillbill.desktop.core.ui.changes.SubtaskStrip
import dev.skillbill.desktop.core.ui.feedback.readProblemText

/**
 * The open item's changes. A goal shows its per-subtask strip and the running subtask; a click opens the Changes window on that
 * subtask. Any other item shows its change summary. Every item has the "Open changes" button, which opens All.
 */
internal fun DetailSections.changesSection(changes: ChangesUi, onAction: (DetailAction) -> Unit) {
  val subtasks = changes.subtasks
  val count = if (subtasks == null) (changes.state as? ChangesState.Listed)?.changes?.files?.size?.toString() else null
  section("changes", "Changes", count = count) {
    item(key = "changes-open") {
      SbButton("Open changes", { onAction(DetailAction.OpenChangesWindow()) })
    }
    if (subtasks != null) goalChanges(subtasks, onAction) else executionChanges(changes.state)
  }
}

private fun LazyListScope.goalChanges(state: SubtaskScopesState, onAction: (DetailAction) -> Unit) {
  when (state) {
    SubtaskScopesState.Loading -> notice("changes-loading") { Hint("Loading changes") }
    SubtaskScopesState.NotAGoal -> notice("changes-none") { Hint("No subtasks recorded") }
    is SubtaskScopesState.Unavailable -> notice("changes-problem") { Hint(readProblemText(state.problem)) }
    is SubtaskScopesState.Listed -> {
      val open = { id: Int -> onAction(DetailAction.OpenChangesWindow(ChangeScope.Subtasks(id, id))) }
      item(key = "changes-summary") {
        val done = state.subtasks.count { it.state == ScopeSubtaskState.DONE }
        BasicText(
          "${state.subtasks.size} subtasks · $done done",
          Modifier.padding(vertical = 4.dp),
          style = SkillBillTheme.type.tSm.copy(color = SkillBillTheme.colors.fgMuted),
        )
      }
      // Shift-clicks and drags do nothing in the detail strip; only a plain click opens the pressed subtask.
      item(key = "changes-strip") { SubtaskStrip(state.subtasks, state.stats, selected = null, onPick = { id, extend -> if (!extend) open(id) }) }
      state.subtasks.firstOrNull { it.state == ScopeSubtaskState.RUNNING }?.let { running ->
        item(key = "changes-running") { RunningRow(running, state.stats[running.id], open) }
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
  val type = SkillBillTheme.type
  val clickable = if (subtask.selectable) Modifier.pointerHoverIcon(PointerIcon.Hand).clickable { onOpen(subtask.id) } else Modifier
  Row(
    Modifier.fillMaxWidth().then(clickable).padding(vertical = 4.dp),
    horizontalArrangement = Arrangement.spacedBy(8.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    ScopeStateDot(subtask.state)
    BasicText("${subtask.id}", style = type.monoSm.copy(color = colors.fgMuted))
    BasicText(subtask.name, Modifier.weight(1f), style = type.tSm.copy(color = colors.fg2), maxLines = 1, overflow = TextOverflow.Ellipsis)
    BasicText("${stats?.files?.size ?: 0} files", style = type.tSm.copy(color = colors.fgMuted))
    BasicText("+${stats?.insertions ?: 0}", style = type.monoSm.copy(color = colors.stDone))
    BasicText("−${stats?.deletions ?: 0}", style = type.monoSm.copy(color = colors.stFailed))
  }
}
