package dev.skillbill.desktop.feature.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import dev.skillbill.desktop.core.designsystem.detail.SbBox
import dev.skillbill.desktop.core.domain.settings.CriterionMark

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun VerifyFixItemControls(item: FixItem, items: List<FixItem>, state: DetailUiState, allowed: Boolean, onAction: (DetailAction) -> Unit) {
  if (!allowed) return
  Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
      VerifyFixCheckbox(item, state, onAction)
      VerifyFixRoute(item, state, onAction)
    }
    VerifyFixItemDetails(item, items, onAction)
  }
}

@Composable
internal fun VerifyFixCheckbox(item: FixItem, state: DetailUiState, onAction: (DetailAction) -> Unit) {
  val marked = item is FixItem.Criterion && item.mark != null
  SbCheckbox(item.key in state.fixSelection, { onAction(DetailAction.ToggleFix(item.key)) }, "Select ${item.label}", enabled = !marked)
}

@Composable
internal fun VerifyFixRoute(item: FixItem, state: DetailUiState, onAction: (DetailAction) -> Unit) {
  if (item.key in state.fixSelection) Segmented(
    FixRoute.entries.map { route -> SegmentedOption(route, route.name) }, selected = state.fixSelection.getValue(item.key),
    onSelect = { onAction(DetailAction.SetFixRoute(item.key, it)) },
  )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun VerifyFixItemDetails(item: FixItem, items: List<FixItem>, onAction: (DetailAction) -> Unit) {
  Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
    if (item is FixItem.Criterion) FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
      if (item.mark != null) {
        BasicText(if (item.mark == CriterionMark.NotInRepo) "Left out of fixes: the work lives in another repository." else "Left out of fixes until the spec is settled.", style = SkillBillTheme.type.tXs.copy(color = SkillBillTheme.colors.fgFaint))
        SbButton("Clear mark", { onAction(DetailAction.MarkCriterion(item.grade.number, null)) }, variant = SbButtonVariant.Ghost, size = dev.skillbill.desktop.core.designsystem.component.SbControlSize.Small)
      } else {
        BasicText("Not a code gap here?", style = SkillBillTheme.type.tXs.copy(color = SkillBillTheme.colors.fgFaint))
        SbButton("Not in this repo", { onAction(DetailAction.MarkCriterion(item.grade.number, CriterionMark.NotInRepo)) }, variant = SbButtonVariant.Ghost, size = dev.skillbill.desktop.core.designsystem.component.SbControlSize.Small)
        SbButton("Spec question", { onAction(DetailAction.MarkCriterion(item.grade.number, CriterionMark.SpecQuestion)) }, variant = SbButtonVariant.Ghost, size = dev.skillbill.desktop.core.designsystem.component.SbControlSize.Small)
      }
    }
    sharedHint(item, items)?.let { BasicText(it, style = SkillBillTheme.type.tXs.copy(color = SkillBillTheme.colors.fgMuted)) }
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

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun VerifyFixBar(items: List<FixItem>, state: DetailUiState, allowed: Boolean, onAction: (DetailAction) -> Unit) {
  if (!allowed || state.fixSelection.isEmpty()) return
  val selected = orderedSelectedItems(items, state.fixSelection)
  val chatCount = state.fixSelection.values.count { it == FixRoute.Chat }
  val goalCount = state.fixSelection.values.count { it == FixRoute.Goal }
  SbBox(Modifier.padding(top = 24.dp), fill = SkillBillTheme.colors.bgRaised) {
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
      BasicText("Selected for fixing", style = SkillBillTheme.type.tXs.copy(color = SkillBillTheme.colors.fgMuted))
      BasicText("${selected.size} selected", style = SkillBillTheme.type.tMd.copy(color = SkillBillTheme.colors.fg))
      BasicText(selected.joinToString(", ") { it.label }, style = SkillBillTheme.type.tSm.copy(color = SkillBillTheme.colors.fgMuted))
      sharedLocationNote(selected)?.let { BasicText(it, style = SkillBillTheme.type.tSm.copy(color = SkillBillTheme.colors.fgMuted)) }
      FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (chatCount > 0) SbButton("Fix $chatCount in chat", { onAction(DetailAction.FixInChat) }, variant = SbButtonVariant.Primary)
        if (goalCount > 0) SbButton("Start goal with $goalCount", { onAction(DetailAction.StartGoalWithFixes) })
        SbButton("Clear", { onAction(DetailAction.ClearFixSelection) }, variant = SbButtonVariant.Secondary)
      }
    }
  }
}
