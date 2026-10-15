package skillbill.workflow.taskruntime.model.persistence

import skillbill.contracts.SharedPayloadKeys
import skillbill.error.shellcontent.invalidWorkflowStateSchemaError
import skillbill.workflow.model.persistence.artifact.durableArtifactMapReader

data class FeatureTaskRuntimeGoalContinuationFieldAdoption(
  val field: String,
  val adoptedValue: String,
  val reason: String,
) {
  init {
    require(field.isNotBlank()) {
      "FeatureTaskRuntimeGoalContinuationFieldAdoption.field must be non-blank."
    }
    require(adoptedValue.isNotBlank()) {
      "FeatureTaskRuntimeGoalContinuationFieldAdoption.adoptedValue must be non-blank."
    }
    require(reason.isNotBlank()) {
      "FeatureTaskRuntimeGoalContinuationFieldAdoption.reason must be non-blank."
    }
  }

  internal fun toArtifactMap(): Map<String, Any?> =
    linkedMapOf(
      "field" to field,
      "adopted_value" to adoptedValue,
      "reason" to reason,
    )

  companion object {
    internal fun fromArtifactMap(raw: Map<String, Any?>): FeatureTaskRuntimeGoalContinuationFieldAdoption {
      val reader = durableArtifactMapReader(raw)
      return FeatureTaskRuntimeGoalContinuationFieldAdoption(
        field = reader.requiredString("field"),
        adoptedValue = reader.requiredString("adopted_value"),
        reason = reader.requiredString("reason"),
      )
    }
  }
}

private object FeatureTaskRuntimeGoalPlanningImportKeys {
  const val SOURCE_KIND = "source_kind"
  const val PARENT_GOAL_WORKFLOW_ID = "parent_goal_workflow_id"
  const val NORMALIZED_ISSUE_KEY = "normalized_issue_key"
  const val REPOSITORY_IDENTITY = "repository_identity"
  const val PARENT_SPEC_HASH = "parent_spec_hash"
  const val DECOMPOSITION_MANIFEST_HASH = "decomposition_manifest_hash"
  const val PLANNING_CONTRACT_ID = "planning_contract_id"
  const val PLANNING_CONTRACT_VERSION = "planning_contract_version"
  const val PHASE_OUTPUT_CONTRACT_ID = "phase_output_contract_id"
  const val PHASE_OUTPUT_CONTRACT_VERSION = "phase_output_contract_version"
  const val MANIFEST_ORDER = "manifest_order"
  const val GOVERNED_SUB_SPEC_PATH = "governed_sub_spec_path"
  const val SUB_SPEC_HASH = "sub_spec_hash"
  const val PREPLAN_PAYLOAD_SHA256 = "preplan_payload_sha256"
  const val PLAN_PAYLOAD_SHA256 = "plan_payload_sha256"
  const val IMPORTED_GOAL_PLANNING = "imported_goal_planning"
  val ALL =
    setOf(
      SOURCE_KIND,
      PARENT_GOAL_WORKFLOW_ID,
      NORMALIZED_ISSUE_KEY,
      REPOSITORY_IDENTITY,
      PARENT_SPEC_HASH,
      DECOMPOSITION_MANIFEST_HASH,
      PLANNING_CONTRACT_ID,
      PLANNING_CONTRACT_VERSION,
      PHASE_OUTPUT_CONTRACT_ID,
      PHASE_OUTPUT_CONTRACT_VERSION,
      SharedPayloadKeys.SUBTASK_ID,
      MANIFEST_ORDER,
      GOVERNED_SUB_SPEC_PATH,
      SUB_SPEC_HASH,
      PREPLAN_PAYLOAD_SHA256,
      PLAN_PAYLOAD_SHA256,
    )
}

data class FeatureTaskRuntimeGoalPlanningImport(
  val parentGoalWorkflowId: String,
  val normalizedIssueKey: String,
  val repositoryIdentity: String,
  val parentSpecHash: String,
  val decompositionManifestHash: String,
  val planningContractId: String,
  val planningContractVersion: String,
  val phaseOutputContractId: String,
  val phaseOutputContractVersion: String,
  val subtaskId: Int,
  val manifestOrder: Int,
  val governedSubSpecPath: String,
  val subSpecHash: String,
  val preplanPayloadSha256: String,
  val planPayloadSha256: String,
) {
  internal fun toArtifactMap(): Map<String, Any?> =
    linkedMapOf(
      FeatureTaskRuntimeGoalPlanningImportKeys.SOURCE_KIND to
        FeatureTaskRuntimeGoalPlanningImportKeys.IMPORTED_GOAL_PLANNING,
      FeatureTaskRuntimeGoalPlanningImportKeys.PARENT_GOAL_WORKFLOW_ID to parentGoalWorkflowId,
      FeatureTaskRuntimeGoalPlanningImportKeys.NORMALIZED_ISSUE_KEY to normalizedIssueKey,
      FeatureTaskRuntimeGoalPlanningImportKeys.REPOSITORY_IDENTITY to repositoryIdentity,
      FeatureTaskRuntimeGoalPlanningImportKeys.PARENT_SPEC_HASH to parentSpecHash,
      FeatureTaskRuntimeGoalPlanningImportKeys.DECOMPOSITION_MANIFEST_HASH to decompositionManifestHash,
      FeatureTaskRuntimeGoalPlanningImportKeys.PLANNING_CONTRACT_ID to planningContractId,
      FeatureTaskRuntimeGoalPlanningImportKeys.PLANNING_CONTRACT_VERSION to planningContractVersion,
      FeatureTaskRuntimeGoalPlanningImportKeys.PHASE_OUTPUT_CONTRACT_ID to phaseOutputContractId,
      FeatureTaskRuntimeGoalPlanningImportKeys.PHASE_OUTPUT_CONTRACT_VERSION to phaseOutputContractVersion,
      SharedPayloadKeys.SUBTASK_ID to subtaskId,
      FeatureTaskRuntimeGoalPlanningImportKeys.MANIFEST_ORDER to manifestOrder,
      FeatureTaskRuntimeGoalPlanningImportKeys.GOVERNED_SUB_SPEC_PATH to governedSubSpecPath,
      FeatureTaskRuntimeGoalPlanningImportKeys.SUB_SPEC_HASH to subSpecHash,
      FeatureTaskRuntimeGoalPlanningImportKeys.PREPLAN_PAYLOAD_SHA256 to preplanPayloadSha256,
      FeatureTaskRuntimeGoalPlanningImportKeys.PLAN_PAYLOAD_SHA256 to planPayloadSha256,
    )

  companion object {
    internal fun fromArtifactMap(raw: Map<String, Any?>): FeatureTaskRuntimeGoalPlanningImport {
      val allowed = FeatureTaskRuntimeGoalPlanningImportKeys.ALL
      val unknown = raw.keys - allowed
      if (unknown.isNotEmpty()) throw invalidWorkflowStateSchemaError("Goal planning import has unknown fields.")
      val reader = durableArtifactMapReader(raw)
      if (reader.requiredString(FeatureTaskRuntimeGoalPlanningImportKeys.SOURCE_KIND) !=
        FeatureTaskRuntimeGoalPlanningImportKeys.IMPORTED_GOAL_PLANNING
      ) {
        throw invalidWorkflowStateSchemaError("Goal planning import has an unsupported source kind.")
      }
      return FeatureTaskRuntimeGoalPlanningImport(
        parentGoalWorkflowId =
          reader.requiredString(FeatureTaskRuntimeGoalPlanningImportKeys.PARENT_GOAL_WORKFLOW_ID),
        normalizedIssueKey = reader.requiredString(FeatureTaskRuntimeGoalPlanningImportKeys.NORMALIZED_ISSUE_KEY),
        repositoryIdentity = reader.requiredString(FeatureTaskRuntimeGoalPlanningImportKeys.REPOSITORY_IDENTITY),
        parentSpecHash = reader.requiredString(FeatureTaskRuntimeGoalPlanningImportKeys.PARENT_SPEC_HASH),
        decompositionManifestHash =
          reader.requiredString(FeatureTaskRuntimeGoalPlanningImportKeys.DECOMPOSITION_MANIFEST_HASH),
        planningContractId = reader.requiredString(FeatureTaskRuntimeGoalPlanningImportKeys.PLANNING_CONTRACT_ID),
        planningContractVersion =
          reader.requiredString(FeatureTaskRuntimeGoalPlanningImportKeys.PLANNING_CONTRACT_VERSION),
        phaseOutputContractId =
          reader.requiredString(FeatureTaskRuntimeGoalPlanningImportKeys.PHASE_OUTPUT_CONTRACT_ID),
        phaseOutputContractVersion =
          reader.requiredString(FeatureTaskRuntimeGoalPlanningImportKeys.PHASE_OUTPUT_CONTRACT_VERSION),
        subtaskId = reader.requiredInt(SharedPayloadKeys.SUBTASK_ID),
        manifestOrder = reader.requiredInt(FeatureTaskRuntimeGoalPlanningImportKeys.MANIFEST_ORDER),
        governedSubSpecPath = reader.requiredString(FeatureTaskRuntimeGoalPlanningImportKeys.GOVERNED_SUB_SPEC_PATH),
        subSpecHash = reader.requiredString(FeatureTaskRuntimeGoalPlanningImportKeys.SUB_SPEC_HASH),
        preplanPayloadSha256 = reader.requiredString(FeatureTaskRuntimeGoalPlanningImportKeys.PREPLAN_PAYLOAD_SHA256),
        planPayloadSha256 = reader.requiredString(FeatureTaskRuntimeGoalPlanningImportKeys.PLAN_PAYLOAD_SHA256),
      )
    }
  }
}
