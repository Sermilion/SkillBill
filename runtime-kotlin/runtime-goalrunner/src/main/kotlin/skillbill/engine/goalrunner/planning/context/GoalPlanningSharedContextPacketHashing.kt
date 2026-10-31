package skillbill.engine.goalrunner.planning.context

import skillbill.application.decomposition.baseBranch
import skillbill.application.decomposition.executionModel
import skillbill.application.decomposition.parentSpecPath
import skillbill.application.decomposition.specSource
import skillbill.contracts.JsonCodec
import skillbill.contracts.SharedPayloadKeys
import skillbill.contracts.decomposition.DecompositionManifestPayloadKeys
import skillbill.contracts.decomposition.DecompositionPlanningPayloadKeys
import skillbill.text.sha256HexUtf8
import skillbill.workflow.decomposition.model.DecompositionManifest

internal fun goalPlanningImmutableDecompositionHash(manifest: DecompositionManifest): String {
  val immutable =
    linkedMapOf<String, Any?>(
      SharedPayloadKeys.CONTRACT_VERSION to manifest.contractVersion,
      SharedPayloadKeys.ISSUE_KEY to manifest.issueKey,
      DecompositionManifestPayloadKeys.FEATURE_NAME to manifest.featureName,
      DecompositionPlanningPayloadKeys.PARENT_SPEC_PATH to manifest.parentSpecPath,
      DecompositionPlanningPayloadKeys.SPEC_SOURCE to manifest.specSource.wireValue,
      DecompositionPlanningPayloadKeys.EXECUTION_MODEL to manifest.executionModel.wireValue,
      DecompositionPlanningPayloadKeys.BASE_BRANCH to manifest.baseBranch,
      DecompositionManifestPayloadKeys.FEATURE_BRANCH to manifest.featureBranch,
      DecompositionPlanningPayloadKeys.STACK_BRANCHES to
        manifest.stackBranches.map {
          linkedMapOf(
            SharedPayloadKeys.SUBTASK_ID to it.subtaskId,
            DecompositionPlanningPayloadKeys.BRANCH to it.branch,
            DecompositionPlanningPayloadKeys.BASE_BRANCH to it.baseBranch,
          )
        },
      DecompositionPlanningPayloadKeys.SUBTASKS to
        manifest.subtasks.map { subtask ->
          linkedMapOf(
            DecompositionPlanningPayloadKeys.ID to subtask.id,
            DecompositionPlanningPayloadKeys.NAME to subtask.name,
            DecompositionPlanningPayloadKeys.SPEC_PATH to subtask.specPath,
            DecompositionPlanningPayloadKeys.LINEAR_ISSUE_ID to subtask.linearIssueId,
            DecompositionPlanningPayloadKeys.DEPENDENCIES to
              subtask.dependencies.map { dependency ->
                linkedMapOf(
                  SharedPayloadKeys.SUBTASK_ID to dependency.subtaskId,
                  DecompositionPlanningPayloadKeys.OPTIONAL to dependency.optional,
                  DecompositionPlanningPayloadKeys.SKIPPED to dependency.skipped,
                )
              },
          )
        },
    )
  return sha256HexUtf8(JsonCodec.mapToJsonString(immutable))
}
