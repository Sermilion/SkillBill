package skillbill.idestatus.model

import skillbill.workflow.model.goalobservability.GoalObservabilityFileDiffStat
import java.time.Instant

enum class WorktreeEditSource(val wireValue: String) {
  WORKTREE_PROBE("worktree_probe"),
  ;

  companion object {
    fun fromWire(value: String?): WorktreeEditSource? =
      value?.trim()?.let { candidate ->
        entries.firstOrNull { it.wireValue == candidate }
      }
  }
}

data class WorktreeEditTick(
  val recordedAt: Instant,
  val phaseId: String?,
  val source: WorktreeEditSource,
  val entries: List<GoalObservabilityFileDiffStat>,
)

data class WorktreeEditSummary(
  val recordedAt: Instant,
  val phaseId: String?,
  val pathSample: List<String>,
  val netInsertions: Int,
  val netDeletions: Int,
)

fun WorktreeEditTick.summary(sampleLimit: Int): WorktreeEditSummary =
  WorktreeEditSummary(
    recordedAt = recordedAt,
    phaseId = phaseId,
    pathSample = entries.map(GoalObservabilityFileDiffStat::path).take(sampleLimit),
    netInsertions = entries.sumOf(GoalObservabilityFileDiffStat::insertions),
    netDeletions = entries.sumOf(GoalObservabilityFileDiffStat::deletions),
  )
