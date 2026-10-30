package skillbill.infrastructure.sqlite.workflow.artifact

import skillbill.contracts.SharedPayloadKeys
import skillbill.error.shellcontent.invalidWorkflowStateSchemaError
import skillbill.workflow.taskruntime.model.persistence.FeatureTaskRuntimeGoalContinuationFieldAdoption
import skillbill.workflow.taskruntime.model.persistence.FeatureTaskRuntimeGoalPlanningImport
import skillbill.workflow.taskruntime.model.persistence.FeatureTaskRuntimeGoalPlanningImportSource

private const val FIELD = "field"
private const val ADOPTED_VALUE = "adopted_value"
private const val REASON = "reason"

internal object GoalContinuationFieldAdoptionCodec {
  fun encode(adoption: FeatureTaskRuntimeGoalContinuationFieldAdoption): Map<String, Any?> =
    linkedMapOf(
      FIELD to adoption.field,
      ADOPTED_VALUE to adoption.adoptedValue,
      REASON to adoption.reason,
    )

  fun decode(raw: Map<String, Any?>): FeatureTaskRuntimeGoalContinuationFieldAdoption {
    val reader = ArtifactFieldReader(raw)
    return FeatureTaskRuntimeGoalContinuationFieldAdoption(
      field = reader.requiredString(FIELD),
      adoptedValue = reader.requiredString(ADOPTED_VALUE),
      reason = reader.requiredString(REASON),
    )
  }
}

private const val SOURCE_KIND = "source_kind"
private const val PARENT_GOAL_WORKFLOW_ID = "parent_goal_workflow_id"
private const val NORMALIZED_ISSUE_KEY = "normalized_issue_key"
private const val REPOSITORY_IDENTITY = "repository_identity"
private const val PARENT_SPEC_HASH = "parent_spec_hash"
private const val DECOMPOSITION_MANIFEST_HASH = "decomposition_manifest_hash"
private const val PLANNING_CONTRACT_ID = "planning_contract_id"
private const val PLANNING_CONTRACT_VERSION = "planning_contract_version"
private const val PHASE_OUTPUT_CONTRACT_ID = "phase_output_contract_id"
private const val PHASE_OUTPUT_CONTRACT_VERSION = "phase_output_contract_version"
private const val MANIFEST_ORDER = "manifest_order"
private const val GOVERNED_SUB_SPEC_PATH = "governed_sub_spec_path"
private const val SUB_SPEC_HASH = "sub_spec_hash"
private const val PREPLAN_PAYLOAD_SHA256 = "preplan_payload_sha256"
private const val PLAN_PAYLOAD_SHA256 = "plan_payload_sha256"
private const val SOURCE = "source"
private const val IMPORTED_GOAL_PLANNING = "imported_goal_planning"
private const val SOURCE_KIND_FIELD = "kind"
private const val SOURCE_PLAN_WORKFLOW_ID = "plan_workflow_id"
private const val SOURCE_DETAILS_SHA256 = "details_sha256"

private val planningImportKeys: Set<String> =
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
    SOURCE,
  )

internal object GoalPlanningImportCodec {
  fun encode(import: FeatureTaskRuntimeGoalPlanningImport): Map<String, Any?> =
    linkedMapOf<String, Any?>(
      SOURCE_KIND to IMPORTED_GOAL_PLANNING,
      PARENT_GOAL_WORKFLOW_ID to import.parentGoalWorkflowId,
      NORMALIZED_ISSUE_KEY to import.normalizedIssueKey,
      REPOSITORY_IDENTITY to import.repositoryIdentity,
      PARENT_SPEC_HASH to import.parentSpecHash,
      DECOMPOSITION_MANIFEST_HASH to import.decompositionManifestHash,
      PLANNING_CONTRACT_ID to import.planningContractId,
      PLANNING_CONTRACT_VERSION to import.planningContractVersion,
      PHASE_OUTPUT_CONTRACT_ID to import.phaseOutputContractId,
      PHASE_OUTPUT_CONTRACT_VERSION to import.phaseOutputContractVersion,
      SharedPayloadKeys.SUBTASK_ID to import.subtaskId,
      MANIFEST_ORDER to import.manifestOrder,
      GOVERNED_SUB_SPEC_PATH to import.governedSubSpecPath,
      SUB_SPEC_HASH to import.subSpecHash,
      PREPLAN_PAYLOAD_SHA256 to import.preplanPayloadSha256,
      PLAN_PAYLOAD_SHA256 to import.planPayloadSha256,
    ).apply {
      import.source?.let { put(SOURCE, encodeSource(it)) }
    }

  fun decode(raw: Map<String, Any?>): FeatureTaskRuntimeGoalPlanningImport {
    val reader = ArtifactFieldReader(raw)
    reader.rejectUnknownKeys(planningImportKeys, "Goal planning import")
    if (reader.requiredString(SOURCE_KIND) != IMPORTED_GOAL_PLANNING) {
      throw invalidWorkflowStateSchemaError("Goal planning import has an unsupported source kind.")
    }
    return FeatureTaskRuntimeGoalPlanningImport(
      parentGoalWorkflowId = reader.requiredString(PARENT_GOAL_WORKFLOW_ID),
      normalizedIssueKey = reader.requiredString(NORMALIZED_ISSUE_KEY),
      repositoryIdentity = reader.requiredString(REPOSITORY_IDENTITY),
      parentSpecHash = reader.requiredString(PARENT_SPEC_HASH),
      decompositionManifestHash = reader.requiredString(DECOMPOSITION_MANIFEST_HASH),
      planningContractId = reader.requiredString(PLANNING_CONTRACT_ID),
      planningContractVersion = reader.requiredString(PLANNING_CONTRACT_VERSION),
      phaseOutputContractId = reader.requiredString(PHASE_OUTPUT_CONTRACT_ID),
      phaseOutputContractVersion = reader.requiredString(PHASE_OUTPUT_CONTRACT_VERSION),
      subtaskId = reader.requiredInt(SharedPayloadKeys.SUBTASK_ID),
      manifestOrder = reader.requiredInt(MANIFEST_ORDER),
      governedSubSpecPath = reader.requiredString(GOVERNED_SUB_SPEC_PATH),
      subSpecHash = reader.requiredString(SUB_SPEC_HASH),
      preplanPayloadSha256 = reader.requiredString(PREPLAN_PAYLOAD_SHA256),
      planPayloadSha256 = reader.requiredString(PLAN_PAYLOAD_SHA256),
      source = decodeSource(raw[SOURCE]),
    )
  }

  private fun encodeSource(source: FeatureTaskRuntimeGoalPlanningImportSource): Map<String, Any?> =
    linkedMapOf<String, Any?>(SOURCE_KIND_FIELD to source.kind).apply {
      source.planWorkflowId?.let { put(SOURCE_PLAN_WORKFLOW_ID, it) }
      source.detailsSha256?.let { put(SOURCE_DETAILS_SHA256, it) }
    }

  private fun decodeSource(raw: Any?): FeatureTaskRuntimeGoalPlanningImportSource? {
    if (raw == null) return null
    val source =
      raw as? Map<*, *> ?: throw invalidWorkflowStateSchemaError("Goal planning import source must be an object.")
    return FeatureTaskRuntimeGoalPlanningImportSource(
      kind =
        source[SOURCE_KIND_FIELD] as? String
          ?: throw invalidWorkflowStateSchemaError("Goal planning import source has no kind."),
      planWorkflowId = source[SOURCE_PLAN_WORKFLOW_ID] as? String,
      detailsSha256 = source[SOURCE_DETAILS_SHA256] as? String,
    )
  }
}
