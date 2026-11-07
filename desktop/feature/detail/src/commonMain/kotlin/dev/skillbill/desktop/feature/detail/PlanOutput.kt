package dev.skillbill.desktop.feature.detail

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.skillbill.desktop.core.designsystem.SkillBillTheme
import dev.skillbill.desktop.core.designsystem.component.SbButton
import dev.skillbill.desktop.core.designsystem.component.SbButtonVariant
import dev.skillbill.desktop.core.designsystem.component.SbControlSize
import dev.skillbill.desktop.core.designsystem.detail.SbBox
import dev.skillbill.desktop.core.domain.model.Detail
import dev.skillbill.desktop.core.domain.model.DetailList

internal fun DetailSections.planOutput(state: DetailUiState, detail: Detail, onAction: (DetailAction) -> Unit) {
  section("plan-agent-output", if (detail.standalonePlan?.units.isNullOrEmpty()) "Full agent output" else "Agent output") {
    item(key = "plan-agent-output-log") { PlanOutputLog(state, detail, onAction) }
  }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun PlanOutputLog(state: DetailUiState, detail: Detail, onAction: (DetailAction) -> Unit, raw: Boolean = false) {
  val hasRaw = detail.planOutputLines.any { it.isRaw }
  var subtask by rememberSaveable(state.header.id, raw) { mutableStateOf<Int?>(null) }
  val ids = (detail.standalonePlan?.units.orEmpty().map { it.subtaskId } + detail.planOutputLines.mapNotNull { it.subtaskId }).distinct()
  Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
      (listOf<Int?>(null) + ids).forEach { id ->
        SbButton(id?.let { "Subtask $it" } ?: "All output", { subtask = id }, variant = if (id == subtask) SbButtonVariant.Secondary else SbButtonVariant.Ghost, size = SbControlSize.Small)
      }
    }
    val lines = detail.planOutputLines.filter { (if (raw && hasRaw) it.isRaw else !it.isRaw) && (subtask == null || it.subtaskId == subtask) }
    if (raw && !hasRaw) BasicText("Raw transcript was not recorded. Showing saved output.", style = SkillBillTheme.type.tXs.copy(color = SkillBillTheme.colors.fgFaint))
    SelectionContainer {
      LazyColumn(Modifier.fillMaxWidth().heightIn(min = 80.dp, max = 420.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
        if (lines.isEmpty()) item(key = "output-empty") {
          BasicText(if (detail.planOutputTail.isNotEmpty()) "Earlier output was not recorded for this run." else "Waiting for agent output.", style = SkillBillTheme.type.tSm.copy(color = SkillBillTheme.colors.fgFaint))
        }
        items(lines, key = { it.id }) { line ->
          Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            BasicText(formatInstant(line.at, "HH:mm:ss"), style = SkillBillTheme.type.tXs.copy(color = SkillBillTheme.colors.fgFaint))
            BasicText(line.text, Modifier.weight(1f), style = SkillBillTheme.type.monoSm.copy(color = SkillBillTheme.colors.fgMuted))
          }
        }
        pageFooter(DetailList.PlanOutput, state, onAction)
      }
    }
  }
}

internal fun DetailSections.planOutputTail(detail: Detail) {
  val captured = detail.planOutputLines.filterNot { it.isRaw }.take(4).asReversed()
  val text = if (captured.isEmpty()) detail.planOutputTail else captured.map { "${formatInstant(it.at, "HH:mm:ss")}  ${it.text.take(160)}" }
  if (text.isEmpty()) return
  section("plan-output-tail", "Agent output", count = "latest ${text.size} lines") {
    item(key = "plan-output-tail-lines") {
      SbBox {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
          text.forEach { line -> BasicText(line, style = SkillBillTheme.type.monoSm.copy(color = SkillBillTheme.colors.fgMuted), maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
      }
    }
  }
}
