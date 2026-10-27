package skillbill.engine.goalrunner.assessment

import skillbill.review.spec.model.InteractionScenarioCatalog

internal sealed interface GoalAssessmentCommitRange {
  data class Committed(val fromSha: String, val toSha: String) : GoalAssessmentCommitRange

  data object NoCommit : GoalAssessmentCommitRange

  data class Unavailable(val reason: String) : GoalAssessmentCommitRange
}

internal data class GoalAssessmentSubtaskInput(
  val id: Int,
  val name: String,
  val specText: String?,
  val range: GoalAssessmentCommitRange,
  val rangeFiles: List<String> = emptyList(),
)

internal data class GoalAssessmentBundle(
  val issueKey: String,
  val headSha: String,
  val scopeFiles: List<String>,
  val parentSpec: String,
  val declared: InteractionScenarioCatalog?,
  val subtasks: List<GoalAssessmentSubtaskInput>,
  val reassessmentScope: List<String>? = null,
)

internal sealed interface GoalAssessmentAssembly {
  data class Ready(val bundle: GoalAssessmentBundle) : GoalAssessmentAssembly

  data class Unavailable(val reason: String) : GoalAssessmentAssembly
}

internal sealed interface GoalAssessmentFocus {
  data class Subtask(val subtaskId: Int) : GoalAssessmentFocus

  data object CrossSubtask : GoalAssessmentFocus

  data class Verifier(val candidates: List<GoalAssessmentVerifierCandidate>) : GoalAssessmentFocus
}

internal data class GoalAssessmentVerifierCandidate(
  val findingRef: String,
  val path: String,
  val line: Int,
  val failureScenario: String,
)
