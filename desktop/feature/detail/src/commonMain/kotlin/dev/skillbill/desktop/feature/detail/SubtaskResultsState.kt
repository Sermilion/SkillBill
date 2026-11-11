package dev.skillbill.desktop.feature.detail

import dev.skillbill.desktop.core.designsystem.status.StatusKind
import dev.skillbill.desktop.core.domain.model.ReviewPass
import dev.skillbill.desktop.core.domain.model.RunningReviewPass
import dev.skillbill.desktop.core.domain.model.SubtaskPhaseResults

/** What a subtask's Review and Audit area shows. */
internal sealed interface SubtaskResultsState {
  /** The status read failed at [source], so the results are unknown. */
  data class Unreadable(val source: String) : SubtaskResultsState

  /** A started subtask whose results have not arrived with a page yet. */
  data object Loading : SubtaskResultsState

  data class Shown(val review: SubtaskReviewState) : SubtaskResultsState
}

internal sealed interface SubtaskReviewState {
  data class Passes(
    val passes: List<ReviewPass>,
    val runningReviewPass: RunningReviewPass? = null,
  ) : SubtaskReviewState
  data object RunsAfterAudit : SubtaskReviewState
  data object Omitted : SubtaskReviewState
}

/**
 * Decides the area for one subtask; the first matching rule wins. Skipped has no [SubtaskResultsState.Loading] because the runtime
 * never selects a skipped subtask, so its entry would never arrive.
 */
internal fun subtaskResultsState(kind: StatusKind, results: SubtaskPhaseResults?, goalStatusSource: String?): SubtaskResultsState {
  results?.degradedReadSource?.let { source ->
    val unavailableHistory = source == "subtask_audit_history" &&
      results.auditResult?.attempts?.any { it.unavailableReason != null } == true
    if (!unavailableHistory) return SubtaskResultsState.Unreadable(source)
  }
  if (results == null && kind != StatusKind.Pending && goalStatusSource != null) return SubtaskResultsState.Unreadable(goalStatusSource)
  if (results == null && kind != StatusKind.Pending && kind != StatusKind.Skipped) return SubtaskResultsState.Loading
  val upcoming = kind == StatusKind.Pending || kind == StatusKind.Active || kind == StatusKind.Blocked
  val passes = results?.reviewPasses.orEmpty()
  val runningReviewPass = results?.runningReviewPass
  return SubtaskResultsState.Shown(
    review = when {
      passes.isNotEmpty() || runningReviewPass != null -> SubtaskReviewState.Passes(passes, runningReviewPass)
      upcoming -> SubtaskReviewState.RunsAfterAudit
      else -> SubtaskReviewState.Omitted
    },
  )
}
