package dev.skillbill.desktop.feature.detail

import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import dev.skillbill.desktop.core.designsystem.SkillBillTheme
import dev.skillbill.desktop.core.designsystem.component.SbClock
import dev.skillbill.desktop.core.designsystem.detail.MetricCell
import dev.skillbill.desktop.core.designsystem.detail.MetricStrip
import dev.skillbill.desktop.core.designsystem.detail.metricValueStyle
import dev.skillbill.desktop.core.designsystem.status.StatusKind
import dev.skillbill.desktop.core.domain.model.Detail
import dev.skillbill.desktop.core.domain.model.Lifecycle
import dev.skillbill.desktop.core.domain.model.WorkItem
import dev.skillbill.desktop.core.domain.model.WorkKind
import dev.skillbill.desktop.core.ui.status.statusKind

private fun activeNote(running: Boolean) = if (running) "ticking" else "last known duration"

/** A finished standalone run has no current step; it shows how the run ended instead. */
internal fun terminalStepLabel(item: WorkItem, detail: Detail): String =
  when (val result = currentExecution(item, detail)?.result) {
    null, "completed" -> "Complete"
    else -> result.replaceFirstChar(Char::uppercase)
  }

/** HANDOFF 9.2 item 8, as screens.js `detail()` builds the strip: operations and phases get Step and the elapsed clock. */
@Composable
internal fun DetailMetrics(item: WorkItem, detail: Detail) {
  val goal = item.kind == WorkKind.Goal
  val planning = item.statusKind() == StatusKind.Planning
  val finished = finishedAt(item)
  val total = item.progress?.total?.toString()
  val valueStyle = metricValueStyle()
  val cells = buildList {
    add(
      when {
        !goal && item.lifecycle == Lifecycle.Terminal -> MetricCell("Step", terminalStepLabel(item, detail), note = " ")
        // A standalone run's step is a phase id: mono at `--t-md`, and the note line always holds its place.
        !goal -> MetricCell("Step", note = item.phaseExecutionLabel ?: " ") {
          BasicText(
            item.stepLabel,
            style = valueStyle.copy(fontFamily = SkillBillTheme.type.mono.fontFamily, fontSize = 14.sp, letterSpacing = (-0.16).sp, fontWeight = FontWeight.Medium),
            maxLines = 1,
          )
        }
        planning -> MetricCell("Goal stage", "Planning")
        finished != null -> MetricCell("Subtasks", item.progress?.done?.toString() ?: "0", total?.let { "/ $it" }, "complete")
        detail.currentPhase != null && detail.currentPhase in detail.goalFinishPipeline -> MetricCell("Goal stage", "Goal finish")
        else -> MetricCell(
          "Subtask", item.currentSubtaskId ?: "—", total?.let { "/ $it" }, "${detail.completeCount} complete · ${detail.pendingCount} pending",
        )
      },
    )
    if (goal) {
      if (planning) {
        val plans = item.planningProgress
        add(MetricCell("Phase", "plan", note = plans?.let { "${it.done}/${it.total} plans saved" }))
      } else {
        add(MetricCell("Phase", detail.currentPhase ?: item.stepLabel, note = item.phaseExecutionLabel ?: " "))
      }
    }
    if (finished != null) {
      add(MetricCell("Total run time", note = "finished ${formatInstant(finished, "d MMM HH:mm")}") { SbClock(item.activeDurationMs, null, live = false, style = valueStyle) })
    } else {
      add(
        MetricCell(elapsedLabel(item.kind), note = activeNote(item.running)) {
          SbClock(item.activeDurationMs, item.activeDurationAsOf, live = item.running, showSeconds = !goal, style = valueStyle)
        },
      )
    }
    if (goal && finished == null && detail.subtaskActiveDurationMs != null) {
      add(
        MetricCell("Subtask active", note = activeNote(item.running)) {
          SbClock(detail.subtaskActiveDurationMs, detail.subtaskActiveDurationAsOf, live = item.running, showSeconds = true, style = valueStyle)
        },
      )
    }
  }
  MetricStrip(cells)
}
