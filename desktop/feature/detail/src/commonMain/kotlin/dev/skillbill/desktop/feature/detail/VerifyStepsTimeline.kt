package dev.skillbill.desktop.feature.detail

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.skillbill.desktop.core.designsystem.LocalContentColor
import dev.skillbill.desktop.core.designsystem.SkillBillTheme
import dev.skillbill.desktop.core.designsystem.component.SbButton
import dev.skillbill.desktop.core.designsystem.component.SbButtonVariant
import dev.skillbill.desktop.core.designsystem.component.rememberRuntimeText
import dev.skillbill.desktop.core.designsystem.detail.BarSegment
import dev.skillbill.desktop.core.designsystem.detail.SegmentBar
import dev.skillbill.desktop.core.designsystem.icon.LucideIcons
import dev.skillbill.desktop.core.designsystem.icon.SbIcon
import dev.skillbill.desktop.core.domain.model.QaRunDetails
import dev.skillbill.desktop.core.domain.model.QaVerdict
import dev.skillbill.desktop.core.domain.model.VerifyRunDetails
import dev.skillbill.desktop.core.domain.model.VerifyRunStep

internal fun verifyQaInput(run: VerifyRunDetails): QaResultsInput? {
  val qa = run.qa ?: if (run.qaVerdict == "skipped" && !run.qaReportText.isNullOrBlank()) {
    QaRunDetails()
  } else return null
  return QaResultsInput(
    runKey = "verify:${run.workflowId}",
    outcome = when (run.qaVerdict) {
      "passed" -> QaVerdict.Passed
      "findings_open" -> QaVerdict.Findings
      "skipped" -> QaVerdict.Skipped
      "incomplete" -> QaVerdict.Incomplete
      null -> null
      else -> QaVerdict.Attempted
    },
    reason = qa.skipReason,
    reportText = run.qaReportText,
    findings = qa.findings.map { finding ->
      QaResultsFinding(finding.id, finding.title, observed = finding.observed, expected = finding.expected, steps = finding.steps, evidencePaths = finding.evidencePaths)
    },
    details = qa,
    evidenceMode = QaEvidenceMode.VerifyNamesOnly,
  )
}

internal fun DetailSections.verifyStepsTimeline(run: VerifyRunDetails, state: DetailUiState, items: List<FixItem>, onAction: (DetailAction) -> Unit, onScrollToSection: (String) -> Unit) {
  val collapse = collapse
  val steps = auditedSteps(run)
  verifySection(VERIFY_STEPS_KEY, "Steps", "${steps.count { it.status in setOf("completed", "skipped") }} of ${steps.size}") {
    auditedSteps(run).forEach { step ->
      val targetKey = if (step.stepId == "qa") VERIFY_QA_KEY else verifyStepKey(step.stepId)
      val receipt = verifyStepHasReceipt(run, step)
      val stateKey = verifyStepCollapseKey(run.workflowId, step.stepId)
      val open = receipt && !collapse.isCollapsed(stateKey, default = true)
      item(key = targetKey) {
        VerifyStepHeader(run, step, open, receipt) { collapse.toggle(stateKey, default = true) }
      }
      if (open) item(key = "$targetKey:receipt") {
        VerifyReceiptBody(run, step, collapse, state, items, onAction, onScrollToSection)
      }
    }
  }
}

internal fun verifyStepHasReceipt(run: VerifyRunDetails, step: VerifyRunStep): Boolean = when (step.stepId) {
  "collect_inputs" -> step.status == "completed"
  "extract_criteria" -> run.criteria.isNotEmpty() || run.criteriaRaw != null
  "feature_flag_audit" -> run.flagAudit != null
  "code_review" -> run.reviewFindings != null
  "unit_test_value_check" -> run.unitTestValue != null
  "completeness_audit" -> run.completenessAudit != null
  "qa" -> run.qa != null || !run.qaVerdict.isNullOrBlank() || step.status.lowercase() in setOf("completed", "skipped")
  "verdict" -> run.verdict != null
  "gather_diff" -> run.changedFiles.isNotEmpty()
  else -> false
}

@Composable
private fun VerifyStepHeader(run: VerifyRunDetails, step: VerifyRunStep, open: Boolean, expandable: Boolean, onToggle: () -> Unit) {
  val status = step.status.lowercase()
  val colors = SkillBillTheme.colors
  val label = when (verifyRunState(run)) {
    VerifyRunState.Parked -> if (step.stepId == "extract_criteria") "Awaiting confirmation" else step.status
    VerifyRunState.Stopped -> if (step.status == "running") "Stopped" else step.status
    else -> step.status
  }
  val color = verifyTone(verifyStepTone(run, step))
  val rowModifier = Modifier.fillMaxWidth().padding(vertical = 8.dp).then(
    if (expandable) Modifier.clickable(role = Role.Button, onClick = onToggle).semantics { stateDescription = "$label, ${if (open) "expanded" else "collapsed"}" }
    else Modifier.semantics { stateDescription = label },
  )
  Row(rowModifier.verifyRail(), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
    when (status) {
      "completed" -> CompositionLocalProvider(LocalContentColor provides color) { SbIcon(LucideIcons.check, 16.dp, contentDescription = label) }
      "running" -> CompositionLocalProvider(LocalContentColor provides color) { SbIcon(LucideIcons.clock, 16.dp, contentDescription = label) }
      "failed" -> CompositionLocalProvider(LocalContentColor provides color) { SbIcon(LucideIcons.x, 16.dp, contentDescription = label) }
      "blocked" -> CompositionLocalProvider(LocalContentColor provides color) { SbIcon(LucideIcons.info, 16.dp, contentDescription = label) }
      "paused" -> CompositionLocalProvider(LocalContentColor provides color) { SbIcon(LucideIcons.clock, 16.dp, contentDescription = label) }
      "skipped" -> BasicText("–", style = SkillBillTheme.type.tMd.copy(color = color))
      else -> BasicText("○", style = SkillBillTheme.type.tMd.copy(color = color), modifier = Modifier.semantics { stateDescription = label })
    }
    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
      BasicText(verifyStepName(step.stepId), style = SkillBillTheme.type.tBaseMedium.copy(color = colors.fg))
      BasicText(verifyStepSummary(run, step), style = SkillBillTheme.type.tSm.copy(color = colors.fgMuted), maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
    if (step.attemptCount > 1) BasicText("attempt ${step.attemptCount}", style = SkillBillTheme.type.tXs.copy(color = colors.fgMuted))
    if (expandable) BasicText(if (open) "⌄" else "›", style = SkillBillTheme.type.tSm.copy(color = colors.fgFaint))
  }
}

@Composable
private fun VerifyReceiptBody(run: VerifyRunDetails, step: VerifyRunStep, collapse: dev.skillbill.desktop.core.designsystem.detail.SectionCollapse, state: DetailUiState, items: List<FixItem>, onAction: (DetailAction) -> Unit, onScrollToSection: (String) -> Unit) {
  Column(Modifier.fillMaxWidth().verifyRail().padding(start = 26.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
    when (step.stepId) {
      "collect_inputs" -> {
        listOf("Intake" to run.intake, "Repository" to run.repoRoot, "Target" to run.target, "Range" to "${run.baseRevision.orEmpty()}..${run.headRevision.orEmpty()}", "Review mode" to run.reviewMode, "QA" to run.qaSelection).forEach { (label, value) ->
          if (value != null) {
            BasicText(label, style = SkillBillTheme.type.tXs.copy(color = SkillBillTheme.colors.fgFaint))
            BasicText(rememberRuntimeText(value), style = SkillBillTheme.type.tSm.copy(color = SkillBillTheme.colors.fg2))
          }
        }
      }
      "extract_criteria" -> SbButton("Show the criteria", { onScrollToSection(VERIFY_CRITERIA_KEY) }, variant = SbButtonVariant.Ghost)
      "feature_flag_audit" -> run.flagAudit?.let { VerifyFlagAuditReceipt(it) }
      "code_review" -> run.reviewFindings?.let { receipt ->
        VerifyFixSelectAll(items, FixKind.Finding, state, verifySelectionAllowed(run), onAction)
        VerifyReviewReceipt(receipt) { finding ->
          items.filterIsInstance<FixItem.Finding>().firstOrNull { it.finding.ref == finding.ref }?.let { VerifyFixItemControls(it, items, state, verifySelectionAllowed(run), onAction) }
        }
      }
      "unit_test_value_check" -> run.unitTestValue?.let { receipt ->
        VerifyFixSelectAll(items, FixKind.TestRow, state, verifySelectionAllowed(run), onAction)
        VerifyTestValueReceipt(receipt) { index, _ ->
          items.filterIsInstance<FixItem.TestRow>().firstOrNull { it.key == "t:$index" }?.let { VerifyFixItemControls(it, items, state, verifySelectionAllowed(run), onAction) }
        }
      }
      "completeness_audit" -> run.completenessAudit?.let { VerifyCompletenessReceipt(it, onScrollToSection) }
      "qa" -> {
        val input = verifyQaInput(run)
        if (input != null) QaResultsContent(
          qaResultsView(input), collapse, onAction, showVerdictMark = true,
          beforeFindings = { VerifyFixSelectAll(items, FixKind.QaFinding, state, verifySelectionAllowed(run), onAction) },
          findingExtra = { row ->
            items.filterIsInstance<FixItem.QaFinding>().firstOrNull { it.finding.id == row.id }?.let { fix ->
              VerifyFixItemControls(fix, items, state, verifySelectionAllowed(run), onAction)
            }
          },
        )
        else BasicText(run.qaVerdict?.takeIf { it.isNotBlank() } ?: "skipped", style = SkillBillTheme.type.tSm.copy(color = SkillBillTheme.colors.fg2))
      }
      "verdict" -> run.verdict?.let { receipt ->
        listOfNotNull(receipt.verdict, receipt.summary).forEach { BasicText(it, style = SkillBillTheme.type.tSm.copy(color = SkillBillTheme.colors.fg2)) }
        receipt.findings.forEach { BasicText(it, style = SkillBillTheme.type.tSm.copy(color = SkillBillTheme.colors.fg2)) }
      }
      "gather_diff" -> {
        VerifyChangedDirectories(run.changedFiles)
        SbButton("Open changes", { onAction(DetailAction.OpenChangesWindow()) })
      }
    }
  }
}

@Composable
private fun Modifier.verifyRail(): Modifier {
  val border = SkillBillTheme.colors.borderSubtle
  return drawBehind { drawLine(border, Offset(8.dp.toPx(), 0f), Offset(8.dp.toPx(), size.height), 1.dp.toPx()) }
}

@Composable
private fun VerifyChangedDirectories(files: List<String>) {
  val groups = files.groupingBy { it.substringBeforeLast('/', ".") }.eachCount().entries.toList()
  val max = groups.maxOfOrNull { it.value } ?: 1
  groups.take(7).forEach { (directory, count) ->
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
      BasicText(directory, Modifier.weight(1f), style = SkillBillTheme.type.monoSm.copy(color = SkillBillTheme.colors.fg2), maxLines = 1, overflow = TextOverflow.Ellipsis)
      Column(Modifier.weight(1f)) { SegmentBar(listOf(BarSegment(count.toFloat(), SkillBillTheme.colors.stActive), BarSegment((max - count).toFloat(), SkillBillTheme.colors.bgInset)), "$count changed files") }
      BasicText(count.toString(), style = SkillBillTheme.type.tXs.copy(color = SkillBillTheme.colors.fgMuted))
    }
  }
  if (groups.size > 7) BasicText("${groups.size - 7} more directories", style = SkillBillTheme.type.tXs.copy(color = SkillBillTheme.colors.fgFaint))
}
