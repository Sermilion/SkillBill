package dev.skillbill.desktop.feature.changes

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.skillbill.desktop.core.designsystem.SkillBillTheme
import dev.skillbill.desktop.core.designsystem.composite.Edge
import dev.skillbill.desktop.core.designsystem.composite.hairline
import dev.skillbill.desktop.core.domain.model.ChangeScope
import dev.skillbill.desktop.core.ui.changes.AddCount
import dev.skillbill.desktop.core.ui.changes.DelCount
import dev.skillbill.desktop.core.ui.changes.changesMono
import dev.skillbill.desktop.core.ui.changes.changesSans
import dev.skillbill.desktop.core.ui.changes.filesLabel
import dev.skillbill.desktop.core.ui.changes.timelineProblemText

/** The scope label and commit range; for a subtask range the previous, next and Show all links; the visible files' totals. */
@Composable
internal fun ScopeBar(state: ChangesWindowUiState, onAction: (ChangesWindowAction) -> Unit) {
  val target = state.target ?: return
  val colors = SkillBillTheme.colors
  val visible = state.visibleFiles
  val mono = changesMono(11.5f, FontWeight.Normal, colors.fgMuted)
  Row(
    Modifier.fillMaxWidth().height(29.dp).hairline(Edge.Bottom, colors.border).padding(horizontal = 14.dp),
    horizontalArrangement = Arrangement.spacedBy(10.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
      BasicText(scopeLabel(state.scope, state.subtasks), style = changesSans(12f, FontWeight.SemiBold, colors.fg2), maxLines = 1, softWrap = false)
      BasicText(
        state.target?.timelineProblem?.let(::timelineProblemText) ?: commitRangeText(target, state.scope),
        Modifier.weight(1f, fill = false),
        style = mono,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
      if (state.view == ChangesView.Commits) {
        val previous = commitStepTarget(state.commits, state.scope, forward = false)
        val next = commitStepTarget(state.commits, state.scope, forward = true)
        ChangesLink("← ${previous?.take(7).orEmpty()}", { onAction(ChangesWindowAction.Step(forward = false)) })
        ChangesLink("${next?.take(7).orEmpty()} →", { onAction(ChangesWindowAction.Step(forward = true)) })
        if (state.scope is ChangeScope.Commits) ChangesLink("All commits", { onAction(ChangesWindowAction.PickAll) })
      } else if (state.scope is ChangeScope.Subtasks) {
        val previous = stepTarget(state.subtasks, state.scope, forward = false)
        val next = stepTarget(state.subtasks, state.scope, forward = true)
        ChangesLink("← ${previous ?: ""}", { onAction(ChangesWindowAction.Step(forward = false)) })
        ChangesLink("${next ?: ""} →", { onAction(ChangesWindowAction.Step(forward = true)) })
        ChangesLink("Show all", { onAction(ChangesWindowAction.PickAll) })
      }
    }
    BasicText(filesLabel(visible.size), style = mono, maxLines = 1, softWrap = false)
    AddCount(visible.sumOf { it.insertions ?: 0 })
    DelCount(visible.sumOf { it.deletions ?: 0 })
  }
}
