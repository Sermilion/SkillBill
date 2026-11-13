package dev.skillbill.desktop.feature.detail

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import dev.skillbill.desktop.core.designsystem.SkillBillTheme
import dev.skillbill.desktop.core.designsystem.detail.PeekCheck
import dev.skillbill.desktop.core.designsystem.detail.PeekCheckState
import dev.skillbill.desktop.core.designsystem.detail.PeekFooter
import dev.skillbill.desktop.core.designsystem.detail.PeekPart
import dev.skillbill.desktop.core.designsystem.detail.PeekTimeline
import dev.skillbill.desktop.core.designsystem.detail.PeekTimelineRow
import dev.skillbill.desktop.core.designsystem.detail.relativeAge
import dev.skillbill.desktop.core.domain.model.CiCheck
import dev.skillbill.desktop.core.domain.model.PhaseCardState
import dev.skillbill.desktop.core.domain.model.PhaseCard
import kotlin.time.Instant

internal fun checkState(bucket: String, watching: Boolean = true): PeekCheckState = when (bucket) {
  "pass" -> PeekCheckState.Ok
  "fail" -> PeekCheckState.Bad
  "pending" -> if (watching) PeekCheckState.Run else PeekCheckState.Waiting
  else -> PeekCheckState.Waiting
}

internal fun checkDurationLabel(millis: Long): String {
  val seconds = millis.coerceAtLeast(0) / 1000
  return when {
    seconds >= 3600 -> "${seconds / 3600}h ${seconds % 3600 / 60}m"
    seconds >= 60 -> "${seconds / 60}m ${seconds % 60}s"
    else -> "${seconds}s"
  }
}

internal fun lastPollLabel(observedAt: Instant, now: Instant): String = "last poll ${relativeAge(now - observedAt)}"

@Composable
internal fun LinkedCiCheck(check: CiCheck, watching: Boolean = true) {
  val uri = LocalUriHandler.current
  val link = check.link
  Box(if (link == null) Modifier else Modifier.clickable(onClickLabel = "Open ${check.name} on GitHub") { uri.openUri(link) }) {
    PeekCheck(check.name, checkState(check.bucket, watching), time = check.durationMillis?.let(::checkDurationLabel))
  }
}

@Composable
internal fun MonitorCard(card: PhaseCard, url: String?, now: Instant) {
  val uri = LocalUriHandler.current
  val latest = card.ciCycles.lastOrNull()
  val watching = card.state == PhaseCardState.Running && latest?.finishedAt == null
  RecordedPhasePeek(card,
    title = "Monitor", phaseId = card.phaseId, state = card.state.toPeekState(), why = card.blockedReason,
    activity = emptyList(),
    footer = url?.let { PeekFooter("Open checks on GitHub", true) { uri.openUri("${it.trimEnd('/')}/checks") } },
  ) {
    PeekPart(
      card.pullRequestNumber?.let { "CI on #$it" } ?: "CI checks",
      end = latest?.takeIf { watching }?.let { lastPollLabel(it.observedAt, now) },
    ) {
      if (latest == null) androidx.compose.foundation.text.BasicText("No CI checks were retained for this phase.", style = dev.skillbill.desktop.core.designsystem.SkillBillTheme.type.tSm.copy(color = dev.skillbill.desktop.core.designsystem.SkillBillTheme.colors.fgMuted))
      else {
        if (!watching && latest.finishedAt == null) {
          BasicText("Final CI outcome was not retained. Checks show the last recorded observation.", style = SkillBillTheme.type.tSm.copy(color = SkillBillTheme.colors.fgMuted))
        }
        latest.checks.forEach { LinkedCiCheck(it, watching) }
      }
    }
    val earlier = card.ciCycles.dropLast(1).takeLast(5)
    val currentResult = latest?.let {
      PeekTimelineRow(formatInstant(it.finishedAt ?: it.observedAt, "HH:mm"), "Cycle ${it.cycle} · ${it.outcome?.let { outcome -> capitalized(humanized(outcome)) } ?: if (watching) "Running" else "Final outcome not retained"}", null)
    }
    if (currentResult != null || earlier.isNotEmpty()) PeekTimeline(listOfNotNull(currentResult) + earlier.map {
      PeekTimelineRow(formatInstant(it.finishedAt ?: it.observedAt, "HH:mm"), "Cycle ${it.cycle} · ${it.outcome?.let { outcome -> capitalized(humanized(outcome)) } ?: "Final outcome not retained"}", null)
    })
  }
}
