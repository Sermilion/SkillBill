package dev.skillbill.desktop.feature.detail

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import dev.skillbill.desktop.core.designsystem.LocalContentColor
import dev.skillbill.desktop.core.designsystem.SkillBillTheme
import dev.skillbill.desktop.core.designsystem.component.SbButton
import dev.skillbill.desktop.core.designsystem.detail.BarSegment
import dev.skillbill.desktop.core.designsystem.detail.SegmentBar
import dev.skillbill.desktop.core.designsystem.detail.sbBoxSegment
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
    steps.forEachIndexed { index, step ->
      val targetKey = if (step.stepId == "qa") VERIFY_QA_KEY else verifyStepKey(step.stepId)
      val receipt = verifyStepHasReceipt(run, step)
      val stateKey = verifyStepCollapseKey(run.workflowId, step.stepId)
      val open = receipt && !collapse.isCollapsed(stateKey, default = true)
      item(key = targetKey) {
        VerifyStepHeader(run, step, index + 1, index == steps.lastIndex, open, receipt) { collapse.toggle(stateKey, default = true) }
      }
      if (open) item(key = "$targetKey:receipt") {
        VerifyReceiptBody(run, step, collapse, state, items, onAction, onScrollToSection, index == steps.lastIndex)
      }
    }
  }
}

internal fun verifyStepHasReceipt(run: VerifyRunDetails, step: VerifyRunStep): Boolean {
  if (step.status == "pending" && step.attemptCount == 0 && !(verifyRunState(run) == VerifyRunState.Parked && step.stepId == "extract_criteria")) return false
  return when (step.stepId) {
  "collect_inputs" -> step.status == "completed"
  "extract_criteria" -> run.criteria.isNotEmpty() || run.criteriaRaw != null
  "feature_flag_audit" -> run.flagAudit != null && run.flagAudit?.verdict != "skipped"
  "code_review" -> step.status != "running" && run.reviewFindings != null
  "unit_test_value_check" -> run.unitTestValue != null && run.unitTestValue?.verdict != "skipped"
  "completeness_audit" -> run.completenessAudit != null
  "qa" -> run.qaSelection == "on" && (run.qa != null || !run.qaReportText.isNullOrBlank())
  "verdict" -> false
  "gather_diff" -> run.changedFiles.isNotEmpty()
  else -> false
  }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun VerifyStepHeader(run: VerifyRunDetails, step: VerifyRunStep, index: Int, last: Boolean, open: Boolean, expandable: Boolean, onToggle: () -> Unit) {
  val colors = SkillBillTheme.colors
  val pending = step.status == "pending" && step.attemptCount == 0
  val modifier = Modifier.fillMaxWidth().verifyRail(last, 26f)
  Row(modifier) {
    Box(Modifier.width(28.dp).padding(top = 9.dp), contentAlignment = Alignment.TopCenter) { VerifyStepNode(run, step) }
    val frame = if (open) Modifier.sbBoxSegment(0, 2, colors.bgRaised, colors.border) else Modifier
    Column(Modifier.weight(1f).padding(top = 2.dp, bottom = if (open) 0.dp else 6.dp).then(frame)) {
      Column(Modifier.fillMaxWidth().then(if (expandable) Modifier.clickable(role = Role.Button, onClick = onToggle) else Modifier)
        .semantics { contentDescription = "Toggle step ${step.stepId}"; stateDescription = "${step.status}, ${if (open) "expanded" else "collapsed"}" }.padding(horizontal = 8.dp, vertical = 7.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          BasicText(index.toString(), style = SkillBillTheme.type.t2xs.copy(color = colors.fgFaint, fontWeight = FontWeight.Normal, lineHeight = 1.em))
          BasicText(verifyStepName(step.stepId), Modifier.weight(1f), style = SkillBillTheme.type.tBaseMedium.copy(color = if (pending) colors.fgFaint else colors.fg, fontWeight = if (pending) FontWeight.Normal else FontWeight.Medium, lineHeight = 1.45.em))
          if (step.attemptCount > 1) VerifyBadge("×${step.attemptCount}", colors.stBlocked)
          if (expandable) VerifyChevron(open) else Spacer(Modifier.width(12.dp))
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
          verifyStepSummaryParts(run, step).forEach { part ->
            BasicText(part.text, style = (if (part.mono) SkillBillTheme.type.monoSm else SkillBillTheme.type.tSm).copy(color = if (part.accent) colors.accentText else part.tone?.let { verifyTone(it) } ?: colors.fgMuted, lineHeight = 1.45.em))
          }
        }
      }
    }
  }
}

@Composable
private fun VerifyStepNode(run: VerifyRunDetails, step: VerifyRunStep) {
  val colors = SkillBillTheme.colors
  val tone = verifyTone(verifyStepTone(run, step))
  val state = verifyRunState(run)
  val waiting = state == VerifyRunState.Parked && step.stepId == "extract_criteria"
  val cut = state == VerifyRunState.Superseded && step.stepId == "extract_criteria"
  val stopped = state == VerifyRunState.Stopped && step == auditedSteps(run).getOrNull(stoppedStepIndex(run))
  val running = state == VerifyRunState.Running && step.status == "running"
  val dashed = cut || step.status == "skipped"
  val pending = step.status == "pending" && !waiting
  Box(Modifier.size(18.dp).verifyLiveRing(running, tone, 5.dp, .35f).background(if (pending || running || dashed) colors.bgPanel else tone.copy(alpha = .18f), CircleShape), contentAlignment = Alignment.Center) {
    if (pending || running || dashed) Canvas(Modifier.size(18.dp)) {
      val stroke = if (running) 2.dp.toPx() else 1.5.dp.toPx()
      drawCircle(if (pending && !dashed) colors.borderStrong else tone, radius = size.minDimension / 2 - stroke / 2, style = Stroke(stroke, pathEffect = if (dashed) PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 2.dp.toPx())) else null))
    }
    val icon = when {
      cut -> LucideIcons.x
      waiting -> LucideIcons.info
      stopped -> LucideIcons.pause
      pending || running || dashed -> null
      step.status == "failed" || tone == colors.stFailed -> LucideIcons.x
      tone == colors.stBlocked -> LucideIcons.info
      else -> LucideIcons.check
    }
    if (icon != null) CompositionLocalProvider(LocalContentColor provides tone) { SbIcon(icon, 12.dp, strokeWidth = 2.25f) }
  }
}

@Composable
private fun VerifyReceiptBody(run: VerifyRunDetails, step: VerifyRunStep, collapse: dev.skillbill.desktop.core.designsystem.detail.SectionCollapse, state: DetailUiState, items: List<FixItem>, onAction: (DetailAction) -> Unit, onScrollToSection: (String) -> Unit, last: Boolean) {
  VerifyControlStyle {
    Column(Modifier.fillMaxWidth().verifyRail(last).padding(start = 28.dp, bottom = 6.dp).sbBoxSegment(1, 2, SkillBillTheme.colors.bgRaised, SkillBillTheme.colors.border).padding(start = 12.dp, end = 12.dp, top = 2.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
      when (step.stepId) {
        "collect_inputs" -> {
          listOf("Intake" to run.intake, "Repository" to run.repoRoot, "Target" to run.target, "Range" to "${run.baseRevision.orEmpty().take(7)}..${run.headRevision.orEmpty().take(7)}", "Review mode" to run.reviewMode, "QA" to run.qaSelection).forEach { (label, value) ->
            if (value != null) VerifyReceiptField(label, value, mono = label in setOf("Repository", "Target", "Range"))
          }
        }
        "extract_criteria" -> VerifyTextLink("Show the criteria") { onScrollToSection(VERIFY_CRITERIA_KEY) }
        "feature_flag_audit" -> run.flagAudit?.let { VerifyFlagAuditReceipt(it) }
        "code_review" -> run.reviewFindings?.let { receipt ->
          VerifyFixSelectAll(items, FixKind.Finding, state, verifySelectionAllowed(run), onAction)
          val fixes = items.filterIsInstance<FixItem.Finding>().associateBy { it.finding.ref }
          val allowed = verifySelectionAllowed(run)
          VerifyReviewReceipt(receipt, selectable = allowed,
            trailing = { finding -> fixes[finding.ref]?.let { VerifyFixRoute(it, state, onAction) } },
            extra = { finding -> fixes[finding.ref]?.let { VerifyFixItemDetails(it, items, onAction) } },
            leading = { finding -> fixes[finding.ref]?.let { VerifyFixCheckbox(it, state, onAction) } })
        }
        "unit_test_value_check" -> run.unitTestValue?.let { receipt ->
          val fixes = items.filterIsInstance<FixItem.TestRow>().associateBy { it.key }
          VerifyTestValueReceipt(receipt, head = { VerifyFixSelectAll(items, FixKind.TestRow, state, verifySelectionAllowed(run), onAction) }, selectable = verifySelectionAllowed(run),
            trailing = { index, _ -> fixes["t:$index"]?.let { VerifyFixRoute(it, state, onAction) } },
            extra = { index, _ -> fixes["t:$index"]?.let { VerifyFixItemDetails(it, items, onAction) } },
            leading = { index, _ -> fixes["t:$index"]?.let { VerifyFixCheckbox(it, state, onAction) } })
        }
        "completeness_audit" -> run.completenessAudit?.let { VerifyCompletenessReceipt(it, onScrollToSection) }
        "qa" -> {
          val input = verifyQaInput(run)
          if (input != null) VerifyQaReceipt(run, qaResultsView(input), collapse, state, items, onAction)
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
}

@Composable
private fun Modifier.verifyRail(last: Boolean = false, top: Float = 0f): Modifier {
  val border = SkillBillTheme.colors.borderSubtle
  return if (last) this else drawBehind { drawLine(border, Offset(14.dp.toPx(), top.dp.toPx()), Offset(14.dp.toPx(), size.height), 1.dp.toPx()) }
}

@Composable
private fun VerifyChangedDirectories(files: List<String>) {
  val groups = files.groupingBy { it.substringBeforeLast('/', ".") }.eachCount().entries.toList()
  val max = groups.maxOfOrNull { it.value } ?: 1
  groups.take(7).forEach { (directory, count) ->
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
      BasicText(directory, Modifier.weight(1f), style = SkillBillTheme.type.monoSm.copy(color = SkillBillTheme.colors.fg2), maxLines = 1, overflow = TextOverflow.Ellipsis)
      Column(Modifier.width(120.dp)) { SegmentBar(listOf(BarSegment(count.toFloat(), SkillBillTheme.colors.stActive), BarSegment((max - count).toFloat(), SkillBillTheme.colors.bgInset)), "$count changed files") }
      BasicText(count.toString(), Modifier.width(28.dp), style = SkillBillTheme.type.tXs.copy(color = SkillBillTheme.colors.fgMuted, fontWeight = FontWeight.Normal, textAlign = androidx.compose.ui.text.style.TextAlign.End))
    }
  }
  if (groups.size > 7) BasicText("${groups.size - 7} more directories", style = SkillBillTheme.type.tXs.copy(color = SkillBillTheme.colors.fgFaint))
}
