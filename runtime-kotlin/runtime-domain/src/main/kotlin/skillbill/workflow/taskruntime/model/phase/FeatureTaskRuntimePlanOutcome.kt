package skillbill.workflow.taskruntime.model.phase

import skillbill.contracts.SharedPayloadKeys
import skillbill.contracts.decomposition.DecompositionManifestPayloadKeys
import skillbill.contracts.decomposition.DecompositionPlanningPayloadKeys
import skillbill.workflow.decomposition.model.SpecSource

private const val DECOMPOSE_MODE: String = "decompose"

data class FeatureTaskRuntimeDecomposePlanOutcome(
  val reason: String,
  val featureName: String,
  val parentSpecOverview: String,
  val validationStrategy: String,
  val baseBranch: String,
  val featureBranch: String,
  val subtasks: List<FeatureTaskRuntimeDecomposeSubtask>,
  val specSource: SpecSource = SpecSource.LOCAL,
) {
  init {
    require(reason.isNotBlank()) { "FeatureTaskRuntimeDecomposePlanOutcome.reason must be non-blank." }
    require(featureName.isNotBlank()) { "FeatureTaskRuntimeDecomposePlanOutcome.featureName must be non-blank." }
    require(subtasks.isNotEmpty()) {
      "FeatureTaskRuntimeDecomposePlanOutcome.subtasks must contain at least one subtask."
    }
    if (specSource == SpecSource.LINEAR) {
      require(subtasks.all { !it.linearIssueId.isNullOrBlank() }) {
        "Linear decompose plans require a nonblank linear_issue_id for every subtask."
      }
    }
  }
}

data class FeatureTaskRuntimeDecomposeSubtask(
  val id: Int,
  val name: String,
  val scope: String,
  val acceptanceCriteria: List<String>,
  val nonGoals: List<String>,
  val dependencyNotes: String,
  val validationStrategy: String,
  val nextPath: String,
  val dependsOn: List<Int>,
  val linearIssueId: String? = null,
)

internal fun featureTaskRuntimeIsDecompositionPackage(phaseOutput: Map<String, Any?>): Boolean {
  val producedOutputs = phaseOutput.stringAnyMap(SharedPayloadKeys.PRODUCED_OUTPUTS) ?: return false
  val packageMap = producedOutputs.stringAnyMap("decomposition_package") ?: return false
  return packageMap[DecompositionPlanningPayloadKeys.MODE]?.toString() == DECOMPOSE_MODE
}

internal fun featureTaskRuntimeDecomposePlanOutcomeOrNull(
  phaseOutput: Map<String, Any?>,
  specSource: SpecSource,
): FeatureTaskRuntimeDecomposePlanOutcome? {
  val producedOutputs = phaseOutput.stringAnyMap(SharedPayloadKeys.PRODUCED_OUTPUTS) ?: return null
  val packageMap = producedOutputs.stringAnyMap("decomposition_package") ?: return null
  if (packageMap[DecompositionPlanningPayloadKeys.MODE]?.toString() != DECOMPOSE_MODE) return null
  val summary = phaseOutput[SharedPayloadKeys.SUMMARY]?.toString().orEmpty()
  return FeatureTaskRuntimeDecomposePlanOutcome(
    reason = packageMap.firstString("reason", "decomposition_reason").ifBlank { summary },
    featureName =
      packageMap.firstString(
        DecompositionManifestPayloadKeys.FEATURE_NAME,
        DecompositionPlanningPayloadKeys.NAME,
      ).ifBlank {
        "feature"
      },
    parentSpecOverview = packageMap.firstString("parent_spec_overview", "overview").ifBlank { summary },
    validationStrategy = packageMap.firstString("validation_strategy").ifBlank { "skill-bill phase validation" },
    baseBranch = packageMap.firstString(DecompositionPlanningPayloadKeys.BASE_BRANCH).ifBlank { "main" },
    featureBranch = packageMap.firstString(DecompositionManifestPayloadKeys.FEATURE_BRANCH),
    specSource = specSource,
    subtasks = packageMap.requireSubtasks(),
  )
}
