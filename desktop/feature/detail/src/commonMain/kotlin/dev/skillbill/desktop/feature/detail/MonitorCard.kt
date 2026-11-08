package dev.skillbill.desktop.feature.detail

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import dev.skillbill.desktop.core.designsystem.detail.PeekCheck
import dev.skillbill.desktop.core.designsystem.detail.PeekCheckState
import dev.skillbill.desktop.core.designsystem.detail.PeekFooter
import dev.skillbill.desktop.core.designsystem.detail.PeekPart
import dev.skillbill.desktop.core.designsystem.detail.PeekTimeline
import dev.skillbill.desktop.core.designsystem.detail.PeekTimelineRow
import dev.skillbill.desktop.core.designsystem.detail.PhasePeek
import dev.skillbill.desktop.core.designsystem.detail.relativeAge
import dev.skillbill.desktop.core.domain.model.CiCheck
import dev.skillbill.desktop.core.domain.model.PhaseCard
import kotlin.time.Instant

internal fun checkState(bucket: String): PeekCheckState = when (bucket) {
  "pass" -> PeekCheckState.Ok
  "fail" -> PeekCheckState.Bad
  "pending" -> PeekCheckState.Run
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
internal fun LinkedCiCheck(check: CiCheck) {
  val uri = LocalUriHandler.current
  val link = check.link
  Box(if (link == null) Modifier else Modifier.clickable(onClickLabel = "Open ${check.name} on GitHub") { uri.openUri(link) }) {
    PeekCheck(check.name, checkState(check.bucket), time = check.durationMillis?.let(::checkDurationLabel))
  }
}

@Composable
internal fun MonitorCard(card: PhaseCard, url: String?, now: Instant) {
  val uri = LocalUriHandler.current
  val latest = card.ciCycles.maxByOrNull { it.cycle }
  PhasePeek(
    title = "Monitor", phaseId = card.phaseId, state = card.state.toPeekState(), why = card.blockedReason,
    footer = url?.let { PeekFooter("Open checks on GitHub", true) { uri.openUri("${it.trimEnd('/')}/checks") } },
  ) {
    PeekPart(
      card.pullRequestNumber?.let { "CI on #$it" } ?: "CI checks",
      end = latest?.takeIf { it.finishedAt == null }?.let { lastPollLabel(it.observedAt, now) },
    ) { latest?.checks?.forEach { LinkedCiCheck(it) } }
    val earlier = card.ciCycles.filter { it != latest }.takeLast(5)
    if (earlier.isNotEmpty()) PeekTimeline(earlier.map {
      PeekTimelineRow(formatInstant(it.finishedAt ?: it.observedAt, "HH:mm"), "Cycle ${it.cycle} · ${it.outcome?.let(::humanized) ?: "Running"}", null)
    })
  }
}
