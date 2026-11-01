package dev.skillbill.desktop.feature.detail

import dev.skillbill.desktop.core.designsystem.status.StatusKind
import dev.skillbill.desktop.core.domain.model.PhaseConclusion
import dev.skillbill.desktop.core.domain.model.ReviewPass
import dev.skillbill.desktop.core.domain.model.SubtaskPhaseResults

/** What a subtask's Review and Audit area shows. */
internal sealed interface SubtaskResultsState {
  /** The status read failed at [source], so the results are unknown. */
  data class Unreadable(val source: String) : SubtaskResultsState

  /** A started subtask whose results have not arrived with a page yet. */
  data object Loading : SubtaskResultsState

  data class Shown(val review: SubtaskReviewState, val audit: SubtaskAuditState) : SubtaskResultsState
}

internal sealed interface SubtaskReviewState {
  data class Passes(val passes: List<ReviewPass>) : SubtaskReviewState
  data object RunsAfterImplementation : SubtaskReviewState
  data object Omitted : SubtaskReviewState
}

internal sealed interface SubtaskAuditState {
  data object RunsAfterReview : SubtaskAuditState
  data class Conclusion(val conclusion: PhaseConclusion?) : SubtaskAuditState
}

/**
 * Decides the area for one subtask; the first matching rule wins. Skipped has no [SubtaskResultsState.Loading] because the runtime
 * never selects a skipped subtask, so its entry would never arrive.
 */
internal fun subtaskResultsState(kind: StatusKind, results: SubtaskPhaseResults?, goalStatusSource: String?): SubtaskResultsState {
  results?.degradedReadSource?.let { return SubtaskResultsState.Unreadable(it) }
  if (results == null && kind != StatusKind.Pending && goalStatusSource != null) return SubtaskResultsState.Unreadable(goalStatusSource)
  if (results == null && kind != StatusKind.Pending && kind != StatusKind.Skipped) return SubtaskResultsState.Loading
  val upcoming = kind == StatusKind.Pending || kind == StatusKind.Active || kind == StatusKind.Blocked
  val passes = results?.reviewPasses.orEmpty()
  val audit = results?.audit
  return SubtaskResultsState.Shown(
    review = when {
      passes.isNotEmpty() -> SubtaskReviewState.Passes(passes)
      upcoming -> SubtaskReviewState.RunsAfterImplementation
      else -> SubtaskReviewState.Omitted
    },
    audit = if (audit == null && upcoming) SubtaskAuditState.RunsAfterReview else SubtaskAuditState.Conclusion(audit),
  )
}
