package skillbill.engine.featuretask.slot.codereview

import skillbill.engine.featuretask.slot.state.PhaseBlockResume
import skillbill.engine.featuretask.slot.state.PhaseResumeRules

internal object CodeReviewResumeRules : PhaseResumeRules {
  override val tracksReviewPasses: Boolean = true

  override fun persistedBlockResume(
    reason: String,
    recentBlockedReasons: List<String?>,
  ): PhaseBlockResume =
    when {
      isRetryableReviewPreparation(reason) || legacyPreparationRetryConsumedBudget(reason, recentBlockedReasons) ->
        PhaseBlockResume.RELAUNCH_WITH_FRESH_BUDGET
      reason.startsWith("Goal-subtask review output failed schema validation after its reserved pass") ->
        PhaseBlockResume.RELAUNCH
      else -> PhaseBlockResume.DEFAULT
    }

  private fun isRetryableReviewPreparation(reason: String): Boolean {
    val legacyDatabaseContention =
      reason.startsWith("Goal-subtask review state or durable raw evidence is malformed:") &&
        LEGACY_SQLITE_BUSY_REASON_MARKER in reason
    return legacyDatabaseContention ||
      LEGACY_SQLITE_BUSY_REASON_MARKER in reason && (
        reason.startsWith("Goal-subtask review reservation failed") ||
          reason.startsWith("Goal-subtask review input persistence failed")
      )
  }

  private fun legacyPreparationRetryConsumedBudget(
    reason: String,
    recentBlockedReasons: List<String?>,
  ): Boolean =
    reason.startsWith("Phase 'review' exhausted the bounded fix loop") &&
      recentBlockedReasons.firstOrNull() == reason &&
      recentBlockedReasons.getOrNull(1)
        ?.startsWith(
          "Goal-subtask review state or durable raw evidence is malformed: $LEGACY_SQLITE_BUSY_REASON_MARKER",
        ) == true
}

private const val LEGACY_SQLITE_BUSY_REASON_MARKER: String = "[SQLITE_BUSY]"
