package dev.skillbill.desktop.feature.detail

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.skillbill.desktop.core.designsystem.SkillBillTheme
import dev.skillbill.desktop.core.designsystem.component.SbButton
import dev.skillbill.desktop.core.designsystem.component.SbControlSize
import dev.skillbill.desktop.core.designsystem.component.Segmented
import dev.skillbill.desktop.core.designsystem.component.SegmentedOption
import dev.skillbill.desktop.core.designsystem.detail.PeekPart
import dev.skillbill.desktop.core.designsystem.detail.SectionCollapse
import dev.skillbill.desktop.core.domain.model.QaRunDetails
import dev.skillbill.desktop.core.domain.model.QaRunOccurrence

@Composable
internal fun GoalQaHistory(history: QaRunDetails?, collapse: SectionCollapse, onAction: (DetailAction) -> Unit) {
  val rounds = history?.occurrences.orEmpty()
  var selectedId by rememberSaveable(history?.invocationDirectory) { mutableStateOf<String?>(null) }
  val selected = rounds.firstOrNull { it.occurrenceId == selectedId } ?: rounds.lastOrNull()
  PeekPart("QA rounds") {
    if (history?.historyAvailability != "recorded") HistoryText("Earlier round history is unavailable for this run.")
    if (selected == null) HistoryText("No QA rounds recorded in the available history.")
    else {
      Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Segmented(
          rounds.mapIndexed { index, round -> SegmentedOption(round.occurrenceId, "Round ${index + 1}") },
          selected.occurrenceId,
        ) { selectedId = it }
      }
      HistoryText("${selected.kind} · ${selected.disposition}")
    }
  }
  PeekPart("Last frame") {
    val path = selected?.frameReference
    if (path == null) HistoryText("No retained frame is available for this run.")
    else QaRetainedImage(path, "Last frame", onOpen = { onAction(DetailAction.OpenQaReport(path)) })
  }
  PeekPart("Screenshots") {
    val screenshots = selected?.screenshots.orEmpty()
    if (screenshots.isEmpty()) {
      HistoryText(
        when {
          selected != null -> "No screenshots recorded for this round."
          history?.historyAvailability == "recorded" -> "No screenshots recorded in the available history."
          else -> "Earlier screenshot inventory is unavailable."
        },
      )
    } else screenshots.forEach { screenshot ->
      QaRetainedImage(screenshot.path, screenshot.label, onOpen = { onAction(DetailAction.OpenQaReport(screenshot.path)) })
      HistoryText(screenshot.recordedAt)
    }
  }
  val emptyStagesMessage =
    when {
      selected != null -> "No stages recorded for this round."
      history?.historyAvailability == "recorded" -> "No stages recorded in the available history."
      else -> "Earlier stage history is unavailable."
    }
  QaStages(selected, emptyStagesMessage, collapse)
}

@Composable
private fun QaStages(round: QaRunOccurrence?, emptyMessage: String, collapse: SectionCollapse) {
  val stages = round?.stages.orEmpty()
  val key = "qa-stages:${round?.occurrenceId ?: "legacy"}"
  val collapsed = collapse.isCollapsed(key, default = true)
  PeekPart("Stages", end = "${stages.size} recorded", endControl = {
    if (stages.isNotEmpty()) SbButton(
      if (collapsed) "Show" else "Hide",
      { collapse.toggle(key, default = true) },
      size = SbControlSize.Small,
    )
  }) {
    if (stages.isEmpty()) HistoryText(emptyMessage)
    else if (!collapsed) stages.forEach { stage ->
      HistoryText("${stage.name} · ${stage.state}${stage.detail?.let { " · $it" }.orEmpty()}")
    }
  }
}

@Composable
internal fun HistoryText(text: String) {
  BasicText(text, style = SkillBillTheme.type.tSm.copy(color = SkillBillTheme.colors.fgMuted))
}
