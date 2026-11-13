package dev.skillbill.desktop.feature.detail

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.skillbill.desktop.core.designsystem.SkillBillTheme
import dev.skillbill.desktop.core.designsystem.detail.PeekOutput
import dev.skillbill.desktop.core.designsystem.detail.PeekPart
import dev.skillbill.desktop.core.designsystem.icon.LucideIcons
import dev.skillbill.desktop.core.designsystem.icon.SbIcon
import dev.skillbill.desktop.core.domain.model.WorkItem
import dev.skillbill.desktop.core.domain.model.Detail
import dev.skillbill.desktop.core.domain.model.PhaseCardOutput
import dev.skillbill.desktop.core.domain.model.StandalonePlanStage

@Composable
internal fun PreplanRecordedOutput(detail: Detail, output: PhaseCardOutput?, item: WorkItem? = null, onAction: (DetailAction) -> Unit) {
  val stages = detail.phases.map { StandalonePlanStage(it.id, null, null, it.briefingText) }
  val sections = output?.text?.takeIf(String::isNotBlank)?.let(::digestSections)
    ?: planBriefing(stages, detail.phases.filter { it.finished }.map { it.id }.toSet()).digest
    ?: output?.summary?.takeIf(String::isNotBlank)?.let { listOf(PlanDigestSection("Preplan report", listOf(it))) }
  PeekPart("Digest") {
    Column(Modifier.fillMaxWidth()) {
      preplanDigestRows(sections.orEmpty()).forEach { row ->
        key(row.title, row.items) {
          PreplanFold(row.title, row.preview) {
            if (row.items.isEmpty()) {
              ReportText("This evidence was not retained for this run.")
            } else if (row.title == "Add-ons" && row.addons.isNotEmpty()) {
              PreplanAddonLabels(row.addons)
              row.items.filterNot { it.trim('`', ' ') in row.addons }.forEach { ReportText(it) }
            } else if (row.title == "History and decisions") {
              row.items.forEach { PreplanHistoryEvidence(it, detail, item, onAction) }
            } else {
              row.items.forEach { ReportText(it) }
            }
          }
        }
      }
      val extra = sections.orEmpty().filter { preplanDigestTitle(it.title) == null }
      if (extra.isNotEmpty()) {
        key(extra) {
          PreplanFold("Recorded report", "Additional evidence") {
            extra.forEach { section ->
              ReportText(section.title)
              ReportText(section.items.joinToString("\n\n"))
            }
          }
        }
      }
      val files = goalSpecFiles(detail)
      if (files.isNotEmpty()) {
        key(files) {
          PreplanFold("Goal spec", files.joinToString(", ", transform = ::fileName)) {
            files.forEach { file ->
              PeekOutput(LucideIcons.fileText, fileName(file), onClick = { onAction(DetailAction.OpenSpecFile(file)) }, tooltip = file)
            }
          }
        }
      }
    }
    BasicText(
      "Shared by every subtask in this goal.",
      style = SkillBillTheme.type.tXs.copy(color = SkillBillTheme.colors.fgFaint),
    )
  }
}

@Composable
internal fun PlanningRecordedOutput(output: PhaseCardOutput?, files: List<String>) {
  PeekPart("Plan") {
    output?.summary?.takeIf(String::isNotBlank)?.let {
      BasicText(it, style = SkillBillTheme.type.tSm.copy(color = SkillBillTheme.colors.fg2))
    }
    val text = output?.text?.takeIf(String::isNotBlank)
    if (text != null) {
      digestSections(text).forEach { section ->
        key(section.title, section.items) {
          PlanningFold(section.title) {
            ReportText(section.items.joinToString("\n\n"))
          }
        }
      }
    } else if (files.isEmpty() && output?.summary.isNullOrBlank()) {
      BasicText("The planning output was not retained for this run.", style = SkillBillTheme.type.tSm.copy(color = SkillBillTheme.colors.fgMuted))
    }
    files.forEach { file -> PeekOutput(LucideIcons.fileText, fileName(file), tooltip = file) }
  }
}

@Composable
internal fun PlanningFold(title: String, body: @Composable () -> Unit) {
  var open by rememberSaveable { mutableStateOf(false) }
  Column(Modifier.fillMaxWidth()) {
    Row(
      Modifier.fillMaxWidth().semantics { stateDescription = if (open) "Expanded" else "Collapsed" }
        .clickable(role = Role.Button, onClickLabel = "Toggle $title") { open = !open }
        .padding(vertical = 8.dp),
      verticalAlignment = Alignment.Top,
      horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      SbIcon(if (open) LucideIcons.chevronDown else LucideIcons.chevronRight, 12.dp, modifier = Modifier.padding(top = 3.dp))
      BasicText(title, Modifier.widthIn(max = 180.dp), style = SkillBillTheme.type.tSm.copy(color = SkillBillTheme.colors.fg2), maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
    if (open) {
      Column(Modifier.fillMaxWidth().padding(start = 20.dp, bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) { body() }
    }
  }
}
