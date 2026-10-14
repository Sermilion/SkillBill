package skillbill.workflow.taskruntime.model.persistence

import skillbill.contracts.SharedPayloadKeys
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

data class FeatureTaskRuntimeGoalPlanningImportSource(
  val kind: String,
  val planWorkflowId: String? = null,
  val detailsSha256: String? = null,
) {
  init {
    require(kind in KINDS) { "FeatureTaskRuntimeGoalPlanningImportSource.kind must be one of $KINDS." }
  }

  internal fun toArtifactMap(): Map<String, Any?> =
    linkedMapOf<String, Any?>("kind" to kind).apply {
      planWorkflowId?.let { put("plan_workflow_id", it) }
      detailsSha256?.let { put("details_sha256", it) }
    }

  companion object {
    const val PLAN_WORKFLOW: String = "plan_workflow"
    const val LEGACY_TABLES: String = "legacy_tables"
    private val KINDS: Set<String> = setOf(PLAN_WORKFLOW, LEGACY_TABLES)

    fun isPlanWorkflowArtifact(artifact: Map<*, *>): Boolean =
      (artifact["source"] as? Map<*, *>)?.get("kind") == PLAN_WORKFLOW
  }
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
  val source: FeatureTaskRuntimeGoalPlanningImportSource? = null,
) {
  internal fun toArtifactMap(): Map<String, Any?> =
    linkedMapOf<String, Any?>(
      "source_kind" to "imported_goal_planning",
      "parent_goal_workflow_id" to parentGoalWorkflowId,
      "normalized_issue_key" to normalizedIssueKey,
      "repository_identity" to repositoryIdentity,
      "parent_spec_hash" to parentSpecHash,
      "decomposition_manifest_hash" to decompositionManifestHash,
      "planning_contract_id" to planningContractId,
      "planning_contract_version" to planningContractVersion,
      "phase_output_contract_id" to phaseOutputContractId,
      "phase_output_contract_version" to phaseOutputContractVersion,
      SharedPayloadKeys.SUBTASK_ID to subtaskId,
      "manifest_order" to manifestOrder,
      "governed_sub_spec_path" to governedSubSpecPath,
      "sub_spec_hash" to subSpecHash,
      "preplan_payload_sha256" to preplanPayloadSha256,
      "plan_payload_sha256" to planPayloadSha256,
    ).apply {
      source?.let { put("source", it.toArtifactMap()) }
    }
}
