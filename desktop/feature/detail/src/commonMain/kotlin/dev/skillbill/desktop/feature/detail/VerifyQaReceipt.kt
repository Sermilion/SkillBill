package dev.skillbill.desktop.feature.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import dev.skillbill.desktop.core.designsystem.detail.SectionCollapse
import dev.skillbill.desktop.core.domain.model.VerifyRunDetails

/**
 * A Verify run's QA step: the verdict above the same QA results a standalone QA run shows. Once the run allows fixes, the
 * open findings take Select all, a checkbox, the Chat or Goal choice and the shared-code hint.
 */
@Composable
internal fun VerifyQaReceipt(run: VerifyRunDetails, view: QaResultsView, collapse: SectionCollapse, state: DetailUiState, items: List<FixItem>, onAction: (DetailAction) -> Unit) {
  val fixes = items.filterIsInstance<FixItem.QaFinding>().associateBy { it.finding.id }
  val selection = if (verifySelectionAllowed(run) && fixes.isNotEmpty()) QaFindingSelection(
    head = { VerifyFixSelectAll(items, FixKind.QaFinding, state, allowed = true, onAction) },
    leading = { row -> fixes[row.id]?.let { VerifyFixCheckbox(it, state, onAction) } },
    trailing = { row -> fixes[row.id]?.let { VerifyFixRoute(it, state, onAction) } },
    details = { row -> fixes[row.id]?.let { VerifyFixItemDetails(it, items, onAction) } },
  ) else null
  Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) { QaVerdictMark(view) }
    QaResultsContent(view, collapse, onAction, selection = selection)
  }
}
