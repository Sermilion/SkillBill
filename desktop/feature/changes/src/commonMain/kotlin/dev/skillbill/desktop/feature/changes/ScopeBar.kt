package dev.skillbill.desktop.feature.changes

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.skillbill.desktop.core.designsystem.SkillBillTheme
import dev.skillbill.desktop.core.designsystem.component.SbButton
import dev.skillbill.desktop.core.designsystem.component.SbButtonVariant
import dev.skillbill.desktop.core.designsystem.component.SbControlSize
import dev.skillbill.desktop.core.domain.model.ChangeScope

/** The scope label and commit range; for a subtask range the previous, next and Show all links; the visible files' totals. */
@Composable
internal fun ScopeBar(state: ChangesWindowUiState, onAction: (ChangesWindowAction) -> Unit) {
  val target = state.target ?: return
  val colors = SkillBillTheme.colors
  val type = SkillBillTheme.type
  val visible = state.visibleFiles
  Row(
    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
    horizontalArrangement = Arrangement.spacedBy(8.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    BasicText(scopeLabel(state.scope, state.subtasks), style = type.tSmMedium.copy(color = colors.fg))
    BasicText(
      commitRangeText(target, state.scope, state.outsideCommits),
      Modifier.weight(1f, fill = false),
      style = type.monoSm.copy(color = colors.fgMuted),
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
    )
    if (state.scope is ChangeScope.Subtasks) {
      stepTarget(state.subtasks, state.scope, forward = false)?.let { id ->
        SbButton("← $id", { onAction(ChangesWindowAction.Step(forward = false)) }, variant = SbButtonVariant.Ghost, size = SbControlSize.Small, shortcut = "[")
      }
      stepTarget(state.subtasks, state.scope, forward = true)?.let { id ->
        SbButton("$id →", { onAction(ChangesWindowAction.Step(forward = true)) }, variant = SbButtonVariant.Ghost, size = SbControlSize.Small, shortcut = "]")
      }
      SbButton("Show all", { onAction(ChangesWindowAction.PickAll) }, variant = SbButtonVariant.Ghost, size = SbControlSize.Small, shortcut = "A")
    }
    Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
      BasicText(if (visible.size == 1) "1 file" else "${visible.size} files", style = type.tSm.copy(color = colors.fgMuted))
      BasicText("+${visible.sumOf { it.insertions ?: 0 }}", style = type.monoSm.copy(color = colors.stDone))
      BasicText("−${visible.sumOf { it.deletions ?: 0 }}", style = type.monoSm.copy(color = colors.stFailed))
    }
  }
}
