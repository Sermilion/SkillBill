package dev.skillbill.desktop.feature.detail

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import dev.skillbill.desktop.core.designsystem.detail.DocBlockGap
import dev.skillbill.desktop.core.domain.model.Detail
import dev.skillbill.desktop.core.domain.model.DetailList
import dev.skillbill.desktop.core.domain.model.WorkItem
import dev.skillbill.desktop.core.domain.model.WorkKind

internal fun isStandaloneValidation(item: WorkItem) = item.kind == WorkKind.Phase && item.control.phaseId == "validation"

internal fun DetailSections.validationDocument(
  state: DetailUiState,
  view: RunActionsView,
  detail: Detail,
  page: ValidationPageState,
  onAction: (DetailAction) -> Unit,
) {
  val item = state.header
  incident(state, view, onAction)
  item(key = "validation-card") {
    Column(Modifier.padding(top = DocBlockGap)) {
      ValidationOutcomeCard(item, detail, page.shape)
    }
  }
  validationFlagStrip(page)
  validationChecksSection(item, detail, state, onAction)
  validationReportSection(page)
  validationRunsSection(detail, detail.invocationId)
  activitySection(item, detail, state, onAction)
  pageFooter(DetailList.Validation, state, onAction)
}
