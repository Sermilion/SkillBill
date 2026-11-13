package dev.skillbill.desktop.feature.detail

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.runtime.Composable
import dev.skillbill.desktop.core.designsystem.detail.PeekFooter
import dev.skillbill.desktop.core.designsystem.detail.PeekState
import dev.skillbill.desktop.core.designsystem.detail.PhasePeek
import dev.skillbill.desktop.core.designsystem.detail.PeekTimelineRow
import dev.skillbill.desktop.core.domain.model.PhaseCard

@Composable
internal fun RecordedPhasePeek(
  card: PhaseCard,
  title: String,
  phaseId: String,
  state: PeekState,
  word: String? = null,
  why: String? = null,
  footer: PeekFooter? = null,
  endControl: (@Composable () -> Unit)? = null,
  activity: List<PeekTimelineRow> = phaseCardTimeline(card),
  parts: @Composable ColumnScope.() -> Unit,
) {
  PhasePeek(
    title, phaseId, state, word = word, why = why,
    agentId = card.agentId,
    model = listOfNotNull(card.model, card.effort).joinToString(" · ").ifBlank { null },
    round = card.round?.let { "round $it" },
    startLabel = card.startedAt?.let { formatInstant(it, "d MMM HH:mm") },
    durationLabel = card.durationMillis?.let(::activityDuration),
    activity = activity,
    footer = footer, endControl = endControl, parts = parts,
  )
}

internal fun phaseCardTimeline(card: PhaseCard): List<PeekTimelineRow> = listOfNotNull(
  card.startedAt?.let { PeekTimelineRow(formatInstant(it, "HH:mm"), "Started", null) },
  card.finishedAt?.let { PeekTimelineRow(formatInstant(it, "HH:mm"), if (card.state == dev.skillbill.desktop.core.domain.model.PhaseCardState.Done) "Finished" else phaseStateLabel(card), card.state.toPeekState()) },
)

private fun phaseStateLabel(card: PhaseCard): String = when (card.state) {
  dev.skillbill.desktop.core.domain.model.PhaseCardState.Skipped -> "Skipped"
  dev.skillbill.desktop.core.domain.model.PhaseCardState.Failed -> "Failed"
  dev.skillbill.desktop.core.domain.model.PhaseCardState.Blocked -> "Blocked"
  dev.skillbill.desktop.core.domain.model.PhaseCardState.Paused -> "Paused"
  else -> "Finished"
}
