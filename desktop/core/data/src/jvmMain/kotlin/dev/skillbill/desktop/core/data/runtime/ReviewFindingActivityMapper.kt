package dev.skillbill.desktop.core.data.runtime

import dev.skillbill.desktop.core.domain.model.ActivityFact
import skillbill.review.model.ReviewFindingAction
import skillbill.review.model.ReviewFindingActionKind
import kotlin.time.toKotlinInstant

internal fun ReviewFindingAction.toActivityFacts(): List<ActivityFact> {
  val name = if (kind == ReviewFindingActionKind.VERIFY) "Findings verification" else "Findings repair"
  val start = ActivityFact("$name started", null, startedAt.toKotlinInstant(), null, null, null, null, false)
  val end = finishedAt ?: return listOf(start)
  val result = when {
    !completed -> "Stopped before completion"
    open == 0 && other == 0 -> "All $fixed findings fixed"
    else -> "$fixed fixed · $open still open" + if (other > 0) " · $other closed without a fix" else ""
  }
  return listOf(start, ActivityFact(
    if (completed) "$name completed" else "$name stopped", null, end.toKotlinInstant(), null, null, null, result, false,
  ))
}
