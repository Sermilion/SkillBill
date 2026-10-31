package skillbill.engine.featuretask.model.subtask

import skillbill.workflow.model.goalreview.GoalSubtaskReviewState

public sealed interface RemediationBaseCoherenceResult

public data class RemediationBaseCoherent(val state: GoalSubtaskReviewState?) : RemediationBaseCoherenceResult

public data class RemediationBaseBlocked(val operatorGuidance: String) : RemediationBaseCoherenceResult
