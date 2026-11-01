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
import dev.skillbill.desktop.core.domain.model.ChangeScope
import dev.skillbill.desktop.core.ui.changes.AddCount
import dev.skillbill.desktop.core.ui.changes.DelCount
import dev.skillbill.desktop.core.ui.changes.changesMono
import dev.skillbill.desktop.core.ui.changes.changesSans
import dev.skillbill.desktop.core.ui.changes.filesLabel
import dev.skillbill.desktop.core.ui.changes.hairlineBottom

/** The scope label and commit range; for a subtask range the previous, next and Show all links; the visible files' totals. */
@Composable
internal fun ScopeBar(state: ChangesWindowUiState, onAction: (ChangesWindowAction) -> Unit) {
  val target = state.target ?: return
  val colors = SkillBillTheme.colors
  val visible = state.visibleFiles
  val mono = changesMono(11.5f, FontWeight.Normal, colors.fgMuted)
  Row(
    Modifier.fillMaxWidth().height(29.dp).hairlineBottom(colors.border).padding(horizontal = 14.dp),
    horizontalArrangement = Arrangement.spacedBy(10.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
      BasicText(scopeLabel(state.scope, state.subtasks), style = changesSans(12f, FontWeight.SemiBold, colors.fg2), maxLines = 1, softWrap = false)
      BasicText(
        commitRangeText(target, state.scope, state.outsideCommits),
        Modifier.weight(1f, fill = false),
        style = mono,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
      if (state.scope is ChangeScope.Subtasks) {
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
