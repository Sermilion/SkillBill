package dev.skillbill.desktop.feature.detail

import androidx.compose.ui.draw.alpha
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.unit.dp
import dev.skillbill.desktop.core.designsystem.SkillBillTheme
import dev.skillbill.desktop.core.designsystem.component.SbClock
import dev.skillbill.desktop.core.designsystem.component.rememberRuntimeText
import dev.skillbill.desktop.core.designsystem.detail.BarSegment
import dev.skillbill.desktop.core.designsystem.detail.MetricCell
import dev.skillbill.desktop.core.designsystem.detail.MetricStrip
import dev.skillbill.desktop.core.designsystem.detail.SegmentBar
import dev.skillbill.desktop.core.designsystem.detail.SbBox
import dev.skillbill.desktop.core.domain.model.Detail
import dev.skillbill.desktop.core.domain.model.Lifecycle
import dev.skillbill.desktop.core.domain.model.ValidationBranchRun
import dev.skillbill.desktop.core.domain.model.ValidationOutcome
import dev.skillbill.desktop.core.domain.model.WorkItem

@Composable
internal fun ValidationOutcomeCard(item: WorkItem, detail: Detail, shape: ValidationReportShape?) {
  val colors = SkillBillTheme.colors
  val active = item.lifecycle == Lifecycle.Active
  val run = currentExecution(item, detail)
  val live = run != null && qaRunLive(item, run)
  val summary = run?.let { validationChecksSummary(it, live) }
  val showsChecks = run?.liveChecks == true && !run.validationChecks.isEmpty()
  val formattedSummary = if (!active && !shape?.summary.isNullOrBlank()) {
    requireNotNull(shape)
    rememberRuntimeText(shape.summary)
  } else {
    null
  }
  val (summaryHeadline, summaryRest) = formattedSummary?.let(::firstFormattedSentence) ?: (null to null)
  val tone = if (run?.liveChecks == true && run.validationOutcome != null) when (run.validationOutcome) {
    ValidationOutcome.Passed -> colors.stDone
    ValidationOutcome.Blocked -> colors.stBlocked
    ValidationOutcome.Failed -> colors.stFailed
    null -> colors.fgFaint
  } else when (item.lifecycle) {
    Lifecycle.Active, Lifecycle.Terminal -> colors.stDone
    Lifecycle.Paused, Lifecycle.Blocked -> colors.stBlocked
    Lifecycle.Canceled, Lifecycle.Idle -> colors.fgFaint
    Lifecycle.Failed -> colors.stFailed
  }
  val runningCheck = summary?.running.takeIf { showsChecks }
  val plainHeadline = when {
    live && runningCheck != null -> "Running ${runningCheck.check.name}"
    active -> detail.currentActivity ?: item.stepLabel
    summaryHeadline == null -> item.reason.orEmpty()
    else -> null
  }
  val elapsed = if (active) item.activeDurationMs ?: 0L else {
    val start = item.startedAt
    val end = item.finishedAt ?: detail.updatedAt
    if (start != null) (end - start).inWholeMilliseconds.coerceAtLeast(0) else 0L
  }
  val borderTone = if (run?.liveChecks == true && run.validationOutcome != null) tone
  else if (item.lifecycle == Lifecycle.Terminal) colors.borderSubtle else tone
  SbBox(borderColor = borderTone) {
    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
      Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
          if (active) BasicText("● LIVE", Modifier.alpha(activityPulse(active)), style = SkillBillTheme.type.tXs.copy(color = tone))
          if (summaryHeadline != null) {
            BasicText(summaryHeadline, style = SkillBillTheme.type.tBaseMedium.copy(color = colors.fg))
          } else {
            BasicText(plainHeadline.orEmpty(), style = SkillBillTheme.type.tBaseMedium.copy(color = colors.fg))
          }
          if (live && runningCheck != null) {
            BasicText("${runningCheck.check.workingDir}  ${runningCheck.check.command}", style = SkillBillTheme.type.monoSm.copy(color = colors.fgMuted))
            val attempt = runningCheck.check.attempts.last()
            SbClock(0L, attempt.startedAt, live = true, showSeconds = true)
          } else if (active) {
            BasicText("The report appears here when the agent finishes.", style = SkillBillTheme.type.tSm.copy(color = colors.fgMuted))
            detail.lastAgentActivityAt?.let { at ->
              BasicText("${detail.lastAgentActivityLabel ?: "Last agent signal"} · ${formatInstant(at, "d MMM HH:mm")}", style = SkillBillTheme.type.tXs.copy(color = colors.fgFaint))
            }
          } else {
            summaryRest?.let { BasicText(it, style = SkillBillTheme.type.tSm.copy(color = colors.fg2)) }
          }
        }
        if (live && runningCheck != null) Unit
        else if (active) SbClock(item.activeDurationMs, item.activeDurationAsOf, live = true, showSeconds = true)
        else BasicText(activityDuration(elapsed), style = SkillBillTheme.type.tBaseMedium.copy(color = colors.fgMuted))
      }
      if (run?.liveChecks == true && run.validationOutcome != null) {
        BasicText(when (run.validationOutcome) {
          ValidationOutcome.Passed -> "Passed"
          ValidationOutcome.Blocked -> "Blocked · needs an operator"
          ValidationOutcome.Failed -> "Failed"
          null -> ""
        }, style = SkillBillTheme.type.tSmMedium.copy(color = tone))
      }
      if (showsChecks && summary != null) {
        val tallyEntries = summary.tally.map { (state, count) -> "$count ${state.word}" to state.tone() }
        val tallyText = buildAnnotatedString {
          tallyEntries.forEachIndexed { index, (text, color) ->
            if (index > 0) append(", ")
            pushStyle(SpanStyle(color = color))
            append(text)
            pop()
          }
          if (summary.fixedFailedAttempts > 0) {
            if (tallyEntries.isNotEmpty()) append(", ")
            append("${summary.fixedFailedAttempts} failed attempts fixed")
          }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
          BasicText(tallyText, style = SkillBillTheme.type.tXs.copy(color = colors.fgMuted))
        }
        SegmentBar(summary.rows.map { BarSegment(1f, it.state.tone()) }, description = tallyText.text, gap = 2.dp)
      }
      MetricStrip(
        listOf(
          MetricCell("Active") { SbClock(item.activeDurationMs, item.activeDurationAsOf, live = active, showSeconds = true) },
          MetricCell("Started", item.startedAt?.let { formatInstant(it, "d MMM HH:mm") } ?: "—", note = item.finishedAt?.let { "→ finished ${formatInstant(it, "HH:mm")}" }),
          MetricCell("Agent", if (showsChecks && summary != null) "Checks ${summary.done}/${summary.total}${if (summary.retried > 0) ", ${summary.retried} retried" else ""}" else item.agentId ?: "—"),
          MetricCell("Run", "#${item.control.runSequence}", note = validationBranchPosition(detail.invocationId, detail.validationBranchRuns)),
        ),
      )
    }
  }
}

private val sentenceEnd = Regex("[.!?](?=\\s|$)")

private fun firstFormattedSentence(text: AnnotatedString): Pair<AnnotatedString, AnnotatedString?> {
  val split = sentenceEnd.find(text.text)?.range?.last?.plus(1) ?: text.length
  val headlineEnd = (0 until split).firstOrNull { !text.text[it].isWhitespace() }?.let { start ->
    (start until split).lastOrNull { !text.text[it].isWhitespace() }?.plus(1)
  } ?: 0
  val headlineStart = (0 until headlineEnd).firstOrNull { !text.text[it].isWhitespace() } ?: headlineEnd
  val restStart = (split until text.length).firstOrNull { !text.text[it].isWhitespace() }
  val restEnd = restStart?.let { start -> (start until text.length).lastOrNull { !text.text[it].isWhitespace() }?.plus(1) }
  return text.subSequence(headlineStart, headlineEnd) to restStart?.let { start -> text.subSequence(start, restEnd ?: start) }
}

internal fun validationBranchPosition(invocationId: String?, runs: List<ValidationBranchRun>): String? {
  if (runs.isEmpty()) return null
  if (runs.size == 1) return "only run on branch".takeIf { runs[0].invocationId == invocationId }
  val index = runs.indexOfFirst { it.invocationId == invocationId }
  return if (index < 0) null else "${runs.size - index} of ${runs.size} on branch"
}
