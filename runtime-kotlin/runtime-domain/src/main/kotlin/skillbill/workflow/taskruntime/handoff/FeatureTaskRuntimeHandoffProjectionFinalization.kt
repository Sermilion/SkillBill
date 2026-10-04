package skillbill.workflow.taskruntime.handoff

import skillbill.contracts.decomposition.DecompositionPlanningPayloadKeys
import skillbill.contracts.review.ReviewVerificationSignalKeys
import skillbill.workflow.taskruntime.model.handoff.PhaseHandoffProjectionDeclaration
import skillbill.workflow.taskruntime.model.handoff.assembly.FeatureTaskRuntimeHandoffProjectionInputs
import skillbill.workflow.taskruntime.phase.task.FeatureTaskRuntimePhaseWorkflowDefinition

internal object FeatureTaskRuntimeHandoffProjectionFinalization {
  fun finalizationProjectionValues(
    inputs: FeatureTaskRuntimeHandoffProjectionInputs,
    declaration: PhaseHandoffProjectionDeclaration,
  ): Map<String, Any?> {
    val context = finalizationProjectionContext(inputs)
    return when (declaration.projectionContractId) {
      FeatureTaskRuntimePhaseWorkflowDefinition.PhaseProjectionContract.VALIDATION_REQUEST ->
        mapOf(
          "changed_paths" to context.changedPaths,
          ReviewVerificationSignalKeys.REPOSITORY_CHECKPOINT to context.checkpoint,
        )
      FeatureTaskRuntimePhaseWorkflowDefinition.PhaseProjectionContract.BOUNDARY_CANDIDATES ->
        mapOf(
          "changed_paths" to context.changedPaths,
          "boundary_candidates" to
            context.changedPaths
              .map { it.substringBeforeLast('/', "") }
              .filter(String::isNotBlank)
              .distinct(),
        )
      FeatureTaskRuntimePhaseWorkflowDefinition.PhaseProjectionContract.COMMIT_REQUEST ->
        mapOf(
          "path_inventory" to context.changedPaths,
          "required_inclusions" to context.changedPaths,
          "branch_identity" to context.branch,
          "gate_attestations" to listOf("audit", "review", "validate", "write_history"),
          ReviewVerificationSignalKeys.REPOSITORY_CHECKPOINT to context.checkpoint,
        )
      FeatureTaskRuntimePhaseWorkflowDefinition.PhaseProjectionContract.PR_REQUEST ->
        prRequestProjection(context)
      else -> emptyMap()
    }
  }

  private fun finalizationProjectionContext(
    inputs: FeatureTaskRuntimeHandoffProjectionInputs,
  ): FinalizationProjectionContext {
    val validation =
      inputs.resolvedUpstream.outputsByPhaseId[FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_VALIDATE]
        ?.output
        ?.value
        ?.takeIf(String::isNotBlank)
    val checkpoint =
      inputs.resolvedCheckpoint?.let {
        mapOf(ReviewVerificationSignalKeys.REPOSITORY_CHECKPOINT_FINGERPRINT to it.fingerprint)
      }
    val changedPaths =
      inputs.resolvedCheckpoint?.workingTreeOwnedPaths.orEmpty()
        .distinct()
        .sorted()
    return FinalizationProjectionContext(
      validation = validation,
      checkpoint = checkpoint,
      changedPaths = changedPaths,
      branch = inputs.branchIdentity ?: "unknown",
      base = inputs.baseBranch,
      checkpointFingerprint = inputs.resolvedCheckpoint?.fingerprint,
    )
  }

  private fun prRequestProjection(context: FinalizationProjectionContext): Map<String, Any?> =
    mapOf(
      "changed_paths" to context.changedPaths,
      "validation_summary" to (context.validation ?: "completed"),
      DecompositionPlanningPayloadKeys.BASE_BRANCH to context.base,
      "diff_reference" to (context.checkpointFingerprint ?: "repository-checkpoint-unavailable"),
    )

  private data class FinalizationProjectionContext(
    val validation: String?,
    val checkpoint: Map<String, String>?,
    val changedPaths: List<String>,
    val branch: String,
    val base: String,
    val checkpointFingerprint: String?,
  )
}
