package skillbill.engine.goalrunner.planning.model

import skillbill.contracts.SharedPayloadKeys
import skillbill.engine.goalrunner.model.GoalChildPlanningHydrationRequest

internal fun expectedProvenance(request: GoalChildPlanningHydrationRequest): Map<String, Any?> =
  mapOf(
    "source_kind" to "imported_goal_planning",
    "parent_goal_workflow_id" to request.identity.parentGoalWorkflowId,
    "normalized_issue_key" to request.identity.normalizedIssueKey,
    "repository_identity" to request.identity.repositoryIdentity,
    SharedPayloadKeys.SUBTASK_ID to request.descriptor.subtaskId,
    "manifest_order" to request.descriptor.manifestOrder,
    "governed_sub_spec_path" to request.descriptor.governedSubSpecPath,
    "decomposition_manifest_hash" to request.provenance.decompositionManifestHash,
    "planning_contract_id" to request.provenance.planningContractId,
    "planning_contract_version" to request.provenance.planningContractVersion,
    "phase_output_contract_id" to request.provenance.phaseOutputContractId,
    "phase_output_contract_version" to request.provenance.phaseOutputContractVersion,
  )
