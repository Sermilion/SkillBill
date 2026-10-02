package skillbill.workflow.model.goalreview

import skillbill.contracts.SharedPayloadKeys
import skillbill.contracts.review.ReviewFindingPayloadKeys

const val GOAL_SUBTASK_REVIEW_RESULT_ARTIFACT_PREFIX: String = "goal_subtask_review_results"
const val GOAL_SUBTASK_REVIEW_BLOCKER_SEVERITY: String = "blocker"

enum class GoalSubtaskOperatorDecision(val wireValue: String) {
  RETRY_FIX("retry_fix"),
  ACCEPT_AND_ADVANCE("accept_and_advance"),
  ABANDON_SUBTASK("abandon_subtask"),
  ;

  companion object {
    fun fromWire(value: String): GoalSubtaskOperatorDecision =
      entries.firstOrNull { it.wireValue == value }
        ?: reviewStateError("operator_decision", "must be one of ${entries.joinToString { it.wireValue }}.")
  }
}

enum class GoalSubtaskBlockerDispositionVerdict(val wireValue: String) {
  RESOLVED("resolved"),
  UNRESOLVED("unresolved"),
  ;

  companion object {
    fun fromWire(value: String): GoalSubtaskBlockerDispositionVerdict =
      when (value) {
        "superseded" ->
          reviewStateError(
            "verdict",
            "superseded is removed; records naming it must be regenerated.",
          )
        else ->
          entries.firstOrNull { it.wireValue == value }
            ?: reviewStateError("verdict", "must be one of ${entries.joinToString { it.wireValue }}.")
      }
  }
}

data class GoalSubtaskBlockerDisposition(
  val findingId: String,
  val verdict: GoalSubtaskBlockerDispositionVerdict,
  val evidence: List<String>,
) {
  init {
    require(findingId.isNotBlank()) { "GoalSubtaskBlockerDisposition.findingId must be non-blank." }
    require(evidence.isNotEmpty()) {
      "GoalSubtaskBlockerDisposition.evidence must contain at least one evidence entry."
    }
    require(evidence.all(String::isNotBlank)) {
      "GoalSubtaskBlockerDisposition.evidence must contain only non-blank strings."
    }
  }

  internal fun toArtifactMap(): Map<String, Any?> =
    linkedMapOf(
      ReviewFindingPayloadKeys.FINDING_ID to findingId,
      SharedPayloadKeys.VERDICT to verdict.wireValue,
      "evidence" to evidence,
    )

  companion object {
    internal fun fromArtifactMap(
      raw: Map<String, Any?>,
      path: String,
    ): GoalSubtaskBlockerDisposition {
      raw.requireOnlyReviewStateKeys(setOf("finding_id", "verdict", "evidence"), path)
      val reader = reviewStateReader(raw, path)
      val evidence =
        reader.requiredList("evidence").mapIndexed { index, value ->
          (value as? String)?.takeIf(String::isNotBlank) ?: reviewStateError(
            "$path.evidence[$index]",
            "must be a non-blank string.",
          )
        }
      return GoalSubtaskBlockerDisposition(
        findingId = reader.requiredString("finding_id"),
        verdict = GoalSubtaskBlockerDispositionVerdict.fromWire(reader.requiredString("verdict")),
        evidence = evidence,
      )
    }
  }
}

fun unionRefutedBlockerDispositions(
  agentEmitted: List<GoalSubtaskBlockerDisposition>,
  runtimeSuperseded: List<GoalSubtaskBlockerDisposition>,
): List<GoalSubtaskBlockerDisposition> {
  if (runtimeSuperseded.isEmpty()) return agentEmitted
  val byId = agentEmitted.associateBy(GoalSubtaskBlockerDisposition::findingId).toMutableMap()
  runtimeSuperseded.forEach { disposition -> byId[disposition.findingId] = disposition }
  return byId.values.toList()
}

enum class GoalSubtaskReviewDisposition(val wireValue: String) {
  PENDING("pending"),
  PAUSED("paused"),
  REVIEW_CAP_REACHED("review_cap_reached"),
  ;

  companion object {
    fun fromWire(value: String): GoalSubtaskReviewDisposition =
      entries.firstOrNull { it.wireValue == value }
        ?: reviewStateError("disposition", "must be one of ${entries.joinToString { it.wireValue }}.")
  }
}
