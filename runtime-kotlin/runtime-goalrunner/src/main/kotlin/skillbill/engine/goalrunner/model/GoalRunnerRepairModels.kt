package skillbill.engine.goalrunner.model

import skillbill.engine.goal.GoalRunnerAppliedRepair
import skillbill.engine.goal.GoalRunnerWedgeClass
import skillbill.goalrunner.model.GoalRunnerAcceptedBlockReference
import skillbill.ports.goalrunner.GoalRunnerPersistenceSession
import skillbill.workflow.decomposition.model.DecompositionSubtask
import skillbill.workflow.engine.model.DurableWorkflowArtifacts
import java.nio.file.Path

public enum class GoalRunnerRepairStatus(public val wireValue: String) {
  INSPECTED("inspected"),
  REPAIRED("repaired"),
  HEALTHY("healthy"),
  NOT_WEDGED("not_wedged"),
  LIVE_LEASE_REFUSED("live_lease_refused"),
  OPERATOR_REQUIRED("operator_required"),
  NOT_FOUND("not_found"),
}

public data class GoalRunnerRepairRequest(
  public val issueKey: String,
  public val apply: Boolean = false,
  public val subtaskId: Int? = null,
  public val repoRoot: Path? = null,
) {
  init {
    require(issueKey.isNotBlank()) { "issueKey is required." }
    require(subtaskId == null || subtaskId > 0) { "subtaskId must be positive." }
  }
}

public data class GoalRunnerWedgeFinding(
  public val wedgeClass: GoalRunnerWedgeClass,
  public val field: String,
  public val currentValue: String?,
  public val acceptedBlockReference: GoalRunnerAcceptedBlockReference? = null,
)

public data class GoalRunnerChildWedgeDiagnosis(
  public val subtaskId: Int,
  public val workflowId: String?,
  public val wedges: List<GoalRunnerWedgeFinding> = emptyList(),
  public val passedChecks: List<String> = emptyList(),
) {
  public val isHealthy: Boolean get() = wedges.isEmpty()
}

public data class GoalRunnerChildWedgeDiagnosisRequest(
  public val workflowId: String,
  public val issueKey: String,
  public val subtaskId: Int,
  public val subtasks: List<DecompositionSubtask>,
  public val repoRoot: Path,
)

public data class GoalRunnerChildWedgeRepairRequest(
  public val workflowId: String,
  public val issueKey: String,
  public val subtaskId: Int,
  internal val wedgeClasses: List<GoalRunnerWedgeClass>,
  public val repoRoot: Path,
  internal val wedgeFindings: List<GoalRunnerWedgeFinding> = emptyList(),
)

public data class GoalRunnerChildRepairApplyRequest(
  public val unitOfWork: GoalRunnerPersistenceSession,
  public val workflowId: String,
  public val issueKey: String,
  public val subtaskId: Int,
  internal val wedgeClasses: List<GoalRunnerWedgeClass>,
  public val repoRoot: Path,
  internal val wedgeFindings: List<GoalRunnerWedgeFinding> = emptyList(),
)

public data class GoalRunnerChildRepairApplyResult(
  public val repairs: List<GoalRunnerAppliedRepair> = emptyList(),
  internal val manifestProjectionArtifacts: DurableWorkflowArtifacts? = null,
)

public data class GoalRunnerRepairResult(
  public val issueKey: String,
  public val status: GoalRunnerRepairStatus,
  public val parentWorkflowId: String? = null,
  public val parentWedges: List<GoalRunnerWedgeFinding> = emptyList(),
  public val parentPassedChecks: List<String> = emptyList(),
  public val diagnoses: List<GoalRunnerChildWedgeDiagnosis> = emptyList(),
  public val appliedRepairs: List<GoalRunnerAppliedRepair> = emptyList(),
  public val refusalReason: String? = null,
  public val liveLeaseWorkflowId: String? = null,
) {
  init {
    require(issueKey.isNotBlank()) { "issueKey is required." }
  }
}
