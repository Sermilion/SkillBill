package skillbill.goalrunner.model.quality

import skillbill.agentaddon.model.PersistedAgentAddonSelectionEntry
import skillbill.config.model.ExecutionMatrix
import skillbill.config.model.PhaseModelDirective
import skillbill.workflow.taskruntime.model.skeleton.FeatureTaskRuntimeQaSelection

data class GoalQualityHydratedAddonInput(
  val persisted: PersistedAgentAddonSelectionEntry,
  val description: String,
  val content: String,
)

data class GoalQualityExecutionInputs(
  val issueKey: String,
  val intake: String?,
  val repoRoot: String,
  val invokedAgentId: String,
  val configuredAgentOverrideId: String?,
  val timeoutMillis: Long?,
  val qaSelection: FeatureTaskRuntimeQaSelection,
  val parentSpecPath: String,
  val acceptanceCriteria: List<String>?,
  val acceptanceCriteriaReadFailure: String?,
  val branch: String,
  val modelDirectives: Map<String, PhaseModelDirective>,
  val modelMatrix: ExecutionMatrix?,
  val addons: List<GoalQualityHydratedAddonInput>,
) {
  val effectiveAgentId: String get() = configuredAgentOverrideId ?: invokedAgentId

  init {
    require(issueKey.isNotBlank())
    require(repoRoot.isNotBlank())
    require(invokedAgentId.isNotBlank())
    require(configuredAgentOverrideId?.isNotBlank() != false)
    require(timeoutMillis?.let { it > 0 } != false)
    require(parentSpecPath.isNotBlank())
    require(acceptanceCriteriaReadFailure?.isNotBlank() != false)
    require(branch.isNotBlank())
    require(addons.map { it.persisted.slug }.distinct().size == addons.size)
  }
}
