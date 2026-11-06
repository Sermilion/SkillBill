package dev.skillbill.desktop.feature.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.skillbill.desktop.core.designsystem.SkillBillTheme
import dev.skillbill.desktop.core.designsystem.component.SbButton
import dev.skillbill.desktop.core.designsystem.component.SbButtonVariant
import dev.skillbill.desktop.core.designsystem.component.SbCheckbox
import dev.skillbill.desktop.core.designsystem.component.Segmented
import dev.skillbill.desktop.core.designsystem.component.SegmentedOption
import dev.skillbill.desktop.core.domain.settings.CriterionMark

@Composable
internal fun VerifyFixItemControls(item: FixItem, items: List<FixItem>, state: DetailUiState, allowed: Boolean, onAction: (DetailAction) -> Unit) {
  if (!allowed) return
  val selected = item.key in state.fixSelection
  val marked = item is FixItem.Criterion && item.mark != null
  Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
    SbCheckbox(selected, { onAction(DetailAction.ToggleFix(item.key)) }, "Select ${item.label}", enabled = !marked)
    if (marked && item is FixItem.Criterion) {
      Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        BasicText(if (item.mark == CriterionMark.NotInRepo) "Not in this repo" else "Spec question", style = SkillBillTheme.type.tXs.copy(color = SkillBillTheme.colors.fgMuted))
        SbButton("Clear", { onAction(DetailAction.MarkCriterion(item.grade.number, null)) }, variant = SbButtonVariant.Ghost, size = dev.skillbill.desktop.core.designsystem.component.SbControlSize.Small)
      }
    } else if (item is FixItem.Criterion) {
      Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        SbButton("Not in this repo", { onAction(DetailAction.MarkCriterion(item.grade.number, CriterionMark.NotInRepo)) }, variant = SbButtonVariant.Ghost, size = dev.skillbill.desktop.core.designsystem.component.SbControlSize.Small)
        SbButton("Spec question", { onAction(DetailAction.MarkCriterion(item.grade.number, CriterionMark.SpecQuestion)) }, variant = SbButtonVariant.Ghost, size = dev.skillbill.desktop.core.designsystem.component.SbControlSize.Small)
      }
    }
    if (selected) Segmented(
      FixRoute.entries.map { route -> SegmentedOption(route, route.name) },
      selected = state.fixSelection.getValue(item.key),
      onSelect = { onAction(DetailAction.SetFixRoute(item.key, it)) },
    )
    sharedHint(item, items)?.let { BasicText(it, style = SkillBillTheme.type.tXs.copy(color = SkillBillTheme.colors.fgMuted), modifier = Modifier.padding(start = 4.dp)) }
  }
}

@Composable
internal fun VerifyFixSelectAll(items: List<FixItem>, kind: FixKind, state: DetailUiState, allowed: Boolean, onAction: (DetailAction) -> Unit) {
  if (!allowed) return
  val matching = items.filter { it.kind == kind }
  val selectState = selectAllState(matching, state.fixSelection) ?: return
  val available = matching.count { it !is FixItem.Criterion || it.mark == null }
  Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 4.dp)) {
    SbCheckbox(selectState == SelectAllState.On, { onAction(DetailAction.ToggleSelectAllFixes(kind)) }, "Select all $available", indeterminate = selectState == SelectAllState.Mixed)
    BasicText("Select all $available", style = SkillBillTheme.type.tSm.copy(color = SkillBillTheme.colors.fgMuted))
  }
}

@Composable
internal fun VerifyFixBar(items: List<FixItem>, state: DetailUiState, allowed: Boolean, onAction: (DetailAction) -> Unit) {
  if (!allowed || state.fixSelection.isEmpty()) return
  val selected = orderedSelectedItems(items, state.fixSelection)
  val chatCount = state.fixSelection.values.count { it == FixRoute.Chat }
  val goalCount = state.fixSelection.values.count { it == FixRoute.Goal }
  Column(Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
    BasicText("${selected.size} selected · ${selected.joinToString(", ") { it.label }}", style = SkillBillTheme.type.tSmMedium.copy(color = SkillBillTheme.colors.fg))
    sharedLocationNote(selected)?.let { BasicText(it, style = SkillBillTheme.type.tSm.copy(color = SkillBillTheme.colors.fgMuted)) }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      if (chatCount > 0) SbButton("Fix $chatCount in chat", { onAction(DetailAction.FixInChat) })
      if (goalCount > 0) SbButton("Start goal with $goalCount", { onAction(DetailAction.StartGoalWithFixes) })
      SbButton("Clear", { onAction(DetailAction.ClearFixSelection) }, variant = SbButtonVariant.Secondary)
    }
  }
}
