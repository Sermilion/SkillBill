package skillbill.engine.goalrunner.repair

import skillbill.engine.goalrunner.model.GoalRunnerChildRepairApplyRequest
import skillbill.engine.goalrunner.model.GoalRunnerChildRepairApplyResult
import skillbill.engine.goalrunner.model.GoalRunnerChildWedgeDiagnosis
import skillbill.engine.goalrunner.model.GoalRunnerChildWedgeDiagnosisRequest
import skillbill.engine.goalrunner.model.GoalRunnerChildWedgeRepairRequest
import skillbill.ports.workflow.WorkflowStateRepository
import java.nio.file.Path

public interface GoalRunnerChildRepairStore {
  public fun diagnoseChildWedges(request: GoalRunnerChildWedgeDiagnosisRequest): GoalRunnerChildWedgeDiagnosis

  public fun applyChildWedgeRepairs(request: GoalRunnerChildWedgeRepairRequest): GoalRunnerChildRepairApplyResult
}

public interface GoalRunnerChildRepairRunnerPort {
  public fun diagnose(
    workflowStates: WorkflowStateRepository,
    workflowId: String,
    issueKey: String,
    subtaskId: Int,
    repoRoot: Path,
  ): GoalRunnerChildWedgeDiagnosis

  public fun apply(request: GoalRunnerChildRepairApplyRequest): GoalRunnerChildRepairApplyResult
}
