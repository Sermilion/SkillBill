package skillbill.engine.goalrunner.repair

import skillbill.engine.goalrunner.model.GoalRunnerChildRepairApplyResult
import skillbill.engine.goalrunner.model.GoalRunnerChildWedgeDiagnosis
import skillbill.engine.goalrunner.model.GoalRunnerChildWedgeDiagnosisRequest
import skillbill.engine.goalrunner.model.GoalRunnerChildWedgeRepairRequest

object NoopGoalRunnerChildRepairStore : GoalRunnerChildRepairStore {
  override fun diagnoseChildWedges(request: GoalRunnerChildWedgeDiagnosisRequest): GoalRunnerChildWedgeDiagnosis {
    return GoalRunnerChildWedgeDiagnosis(
      subtaskId = request.subtaskId,
      workflowId = request.workflowId,
      passedChecks =
        listOf(
          "validation_depth_present",
          "quality_gate_selection_present",
          "review_base_reachable",
          "remediation_base_reachable_or_absent",
          "continuation_outcome_corroborated_or_absent",
          "upstream_output_present",
        ),
    )
  }

  override fun applyChildWedgeRepairs(request: GoalRunnerChildWedgeRepairRequest): GoalRunnerChildRepairApplyResult {
    return GoalRunnerChildRepairApplyResult()
  }
}
