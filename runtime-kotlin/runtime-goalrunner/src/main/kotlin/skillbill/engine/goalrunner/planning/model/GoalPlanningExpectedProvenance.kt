package skillbill.engine.goalrunner.planning.model

import skillbill.engine.goalrunner.model.GoalChildPlanningHydrationRequest
import skillbill.workflow.taskruntime.model.persistence.FeatureTaskRuntimeGoalPlanningImport

internal fun FeatureTaskRuntimeGoalPlanningImport.provenanceMismatches(
  request: GoalChildPlanningHydrationRequest,
): List<String> =
  listOfNotNull(
    "parent_goal_workflow_id".takeIf { parentGoalWorkflowId != request.identity.parentGoalWorkflowId },
    "normalized_issue_key".takeIf { normalizedIssueKey != request.identity.normalizedIssueKey },
    "repository_identity".takeIf { repositoryIdentity != request.identity.repositoryIdentity },
    "subtask_id".takeIf { subtaskId != request.descriptor.subtaskId },
    "manifest_order".takeIf { manifestOrder != request.descriptor.manifestOrder },
    "governed_sub_spec_path".takeIf { governedSubSpecPath != request.descriptor.governedSubSpecPath },
    "decomposition_manifest_hash".takeIf { decompositionManifestHash != request.provenance.decompositionManifestHash },
    "planning_contract_id".takeIf { planningContractId != request.provenance.planningContractId },
    "planning_contract_version".takeIf { planningContractVersion != request.provenance.planningContractVersion },
    "phase_output_contract_id".takeIf { phaseOutputContractId != request.provenance.phaseOutputContractId },
    "phase_output_contract_version".takeIf {
      phaseOutputContractVersion != request.provenance.phaseOutputContractVersion
    },
  )
