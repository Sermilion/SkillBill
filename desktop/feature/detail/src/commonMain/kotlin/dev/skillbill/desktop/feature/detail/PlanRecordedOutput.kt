package dev.skillbill.desktop.feature.detail

import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.width
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.style.TextOverflow
import dev.skillbill.desktop.core.designsystem.component.ExpandableResultRow
import dev.skillbill.desktop.core.designsystem.component.ResultRowBadge
import dev.skillbill.desktop.core.designsystem.SkillBillTheme
import dev.skillbill.desktop.core.designsystem.detail.PeekFacts
import dev.skillbill.desktop.core.designsystem.detail.PeekOutput
import dev.skillbill.desktop.core.designsystem.detail.PeekPart
import dev.skillbill.desktop.core.designsystem.icon.LucideIcons
import dev.skillbill.desktop.core.domain.model.AuditCriterion
import dev.skillbill.desktop.core.domain.model.PhaseCard
import dev.skillbill.desktop.core.domain.model.PhaseCardOutput

@Composable
internal fun PlanRecordedOutput(specPath: String?, output: PhaseCardOutput?, criteria: List<AuditCriterion>, onAction: (DetailAction) -> Unit) {
  PeekPart("Plan") {
    specPath?.let {
      PeekOutput(LucideIcons.fileText, fileName(it), onClick = { onAction(DetailAction.OpenSpecFile(it)) }, tooltip = it)
    }
    output?.summary?.let { BasicText(it, style = SkillBillTheme.type.tSm.copy(color = SkillBillTheme.colors.fg2)) }
    val text = output?.text?.takeIf(String::isNotBlank)
    val sections = text?.let(::digestSections).orEmpty()
    if (sections.isEmpty()) {
      PlanningFold("Steps") { ReportText("The ordered plan was not retained for this run.") }
    } else sections.forEach { section ->
      key(section.title, section.items) {
        val steps = if (section.title.contains("steps", ignoreCase = true)) recordedPlanSteps(section.items) else emptyList()
        PlanningFold(if (section.title == "Preplan digest") "Steps" else section.title) {
          if (steps.isEmpty()) ReportText(section.items.joinToString("\n\n"))
          else steps.forEachIndexed { index, step -> RecordedPlanStep(index + 1, step) }
        }
      }
    }
    if (sections.none { it.title.contains("coverage", ignoreCase = true) }) {
      PlanningFold("Criteria coverage") {
        ReportText("The criteria coverage map was not retained for this run.")
        criteria.forEach { criterion ->
          ReportText("${criterion.ref} · ${criterion.text}")
        }
      }
    }
    val record = output?.planRecord
    PlanningFold("Plan record") {
      if (record == null) ReportText("The plan provenance was not retained for this run.")
      else PeekFacts(listOfNotNull(
        "Sub-spec" to record.specPath,
        "Manifest order" to record.manifestOrder.toString(),
        "Spec hash" to record.specHash,
        "Payload hash" to record.payloadHash,
        record.repair?.let { "Repaired" to it },
      ))
    }
  }
}

internal fun recordedPlanSteps(items: List<String>): List<String> {
  val marker = Regex("^\\d+[.)]\\s+(.+)$")
  val steps = mutableListOf<String>()
  items.forEach { line ->
    val text = marker.matchEntire(line)?.groupValues?.get(1)
    if (text != null) steps += text
    else if (steps.isNotEmpty()) steps[steps.lastIndex] += "\n$line"
  }
  return steps
}

@Composable
private fun RecordedPlanStep(number: Int, text: String) {
  var open by rememberSaveable(number, text) { mutableStateOf(false) }
  val refs = Regex("AC-\\d{3}").findAll(text).map { it.value }.distinct().toList()
  val colors = SkillBillTheme.colors
  ExpandableResultRow(
    open = open, onToggle = { open = !open }, toggleLabel = "Toggle step $number",
    ref = { BasicText(number.toString(), Modifier.width(24.dp), style = SkillBillTheme.type.monoSm.copy(color = colors.fgMuted)) },
    title = { BasicText(text, style = SkillBillTheme.type.tSm.copy(color = colors.fg2), maxLines = if (open) Int.MAX_VALUE else 2, overflow = TextOverflow.Ellipsis) },
    stateBadge = { ResultRowBadge(if (refs.size == 1) refs.single() else if (refs.isEmpty()) "Setup" else "${refs.size} criteria", if (refs.isEmpty()) colors.fgFaint else colors.stBlocked) },
    disclosure = { ReportText(if (refs.isEmpty()) "No criterion reference was recorded for this step." else "Covers ${refs.joinToString(", ")}") },
  )
}

@Composable
internal fun RecordedPhaseDetails(output: PhaseCardOutput?) {
  val text = output?.text?.takeIf(String::isNotBlank) ?: return
  PeekPart("Details") {
    digestSections(text).forEach { section ->
      key(section.title, section.items) {
        PlanningFold(if (section.title == "Preplan digest") "Report" else section.title) {
          ReportText(section.items.joinToString("\n\n"))
        }
      }
    }
  }
}

@Composable
internal fun RecordedPhaseReport(card: PhaseCard) {
  PeekPart("Result", end = card.output?.verdict) {
    BasicText(
      card.output?.summary ?: "No result summary was retained for this phase.",
      style = SkillBillTheme.type.tSm.copy(color = SkillBillTheme.colors.fg2),
    )
    card.files.forEach { PeekOutput(LucideIcons.file, it, tooltip = it) }
  }
  RecordedPhaseDetails(card.output)
}
