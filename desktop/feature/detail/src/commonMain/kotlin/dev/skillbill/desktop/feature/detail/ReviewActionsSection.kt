package dev.skillbill.desktop.feature.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.text.BasicText
import androidx.compose.ui.unit.dp
import dev.skillbill.desktop.core.designsystem.SkillBillTheme
import dev.skillbill.desktop.core.designsystem.component.SbButton
import dev.skillbill.desktop.core.designsystem.component.SbButtonVariant
import dev.skillbill.desktop.core.domain.model.Detail
import dev.skillbill.desktop.core.domain.model.ReviewFindingStatus
import dev.skillbill.desktop.core.domain.model.ReviewFindingsAction
import dev.skillbill.desktop.core.domain.model.WorkItem

internal fun reviewActionsAvailable(item: WorkItem, detail: Detail): Boolean {
  if (!item.standaloneExecution || item.control.phaseId != "review" || item.running) return false
  if (item.control.invocationId == null || item.control.databaseFile == null) return false
  val run = currentExecution(item, detail) ?: return false
  return run.finishedAt != null && run.reviewReport?.findings?.isNotEmpty() == true
}

internal fun reviewActionsEnabled(item: WorkItem, detail: Detail): Boolean =
  reviewActionsAvailable(item, detail) && currentExecution(item, detail)?.reviewReport?.findings.orEmpty().any { it.status != ReviewFindingStatus.Fixed }

internal fun DetailSections.reviewActionsSection(item: WorkItem, detail: Detail, onAction: (DetailAction) -> Unit) {
  if (!reviewActionsAvailable(item, detail)) return
  section("actions", "Actions") {
    item(key = "review-actions") {
      Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
          ReviewFindingsAction.entries.forEach { action ->
            SbButton(action.label, { onAction(DetailAction.ReviewFindings(action)) }, variant = SbButtonVariant.Secondary, enabled = reviewActionsEnabled(item, detail))
          }
        }
        BasicText(
          "Run in agent chat. Fix findings edits the code. Verify findings checks the code and records confirmed fixes without changing code.",
          style = SkillBillTheme.type.tSm.copy(color = SkillBillTheme.colors.fgFaint),
        )
      }
    }
  }
}
