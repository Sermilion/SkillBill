package skillbill.engine.goalrunner.model

import skillbill.ports.goalrunner.GoalRunnerPersistenceSession
import skillbill.workflow.decomposition.model.DecompositionSubtask
import skillbill.workflow.engine.model.DurableWorkflowArtifacts
import java.nio.file.Path

enum class GoalRunnerWedgeClass(val wireValue: String, val durableField: String) {
  MISSING_VALIDATION_DEPTH("missing_validation_depth", "validation_depth"),
  MISSING_QUALITY_GATE_SELECTION("missing_quality_gate_selection", "quality_gate_selection"),
  UNREACHABLE_REVIEW_BASE("unreachable_review_base", "review_base_sha"),
  UNREACHABLE_REMEDIATION_BASE("unreachable_remediation_base", "remediation_base_sha"),
  STALE_BLOCKED_CONTINUATION_OUTCOME("stale_blocked_continuation_outcome", "goal_continuation_outcome"),
  COMPLETED_UPSTREAM_MISSING_OUTPUT("completed_upstream_missing_output", "phase_output"),
  PHASE_OUTPUT_CONTRACT_INCOMPATIBLE("phase_output_contract_incompatible", "phase_output_contract_version"),
  STALE_EXECUTION_LEASE("stale_execution_lease", "execution_lease"),
  STALE_CHILD_WORKER_LEASE("stale_child_worker_lease", "worker_lease"),
  STALE_RUNNER_INTERRUPTED_PAUSE("stale_runner_interrupted_pause", "pause_state"),
  ;

  companion object {
    fun fromWire(value: String): GoalRunnerWedgeClass? = entries.firstOrNull { it.wireValue == value }
  }

  val operatorRequired: Boolean
    get() = this == PHASE_OUTPUT_CONTRACT_INCOMPATIBLE
}

enum class GoalRunnerRepairStatus(val wireValue: String) {
  INSPECTED("inspected"),
  REPAIRED("repaired"),
  HEALTHY("healthy"),
  NOT_WEDGED("not_wedged"),
  LIVE_LEASE_REFUSED("live_lease_refused"),
  OPERATOR_REQUIRED("operator_required"),
  NOT_FOUND("not_found"),
}

data class GoalRunnerRepairRequest(
  val issueKey: String,
  val apply: Boolean = false,
  val subtaskId: Int? = null,
  val repoRoot: Path? = null,
) {
  init {
    require(issueKey.isNotBlank()) { "issueKey is required." }
    require(subtaskId == null || subtaskId > 0) { "subtaskId must be positive." }
  }
}

data class GoalRunnerWedgeFinding(
  val wedgeClass: GoalRunnerWedgeClass,
  val field: String,
  val currentValue: String?,
)

data class GoalRunnerChildWedgeDiagnosis(
  val subtaskId: Int,
  val workflowId: String?,
  val wedges: List<GoalRunnerWedgeFinding> = emptyList(),
  val passedChecks: List<String> = emptyList(),
) {
  val isHealthy: Boolean get() = wedges.isEmpty()
}

data class GoalRunnerChildWedgeDiagnosisRequest(
  val workflowId: String,
  val issueKey: String,
  val subtaskId: Int,
  val subtasks: List<DecompositionSubtask>,
  val repoRoot: Path,
)

data class GoalRunnerChildWedgeRepairRequest(
  val workflowId: String,
  val issueKey: String,
  val subtaskId: Int,
  val wedgeClasses: List<GoalRunnerWedgeClass>,
  val repoRoot: Path,
  val wedgeFindings: List<GoalRunnerWedgeFinding> = emptyList(),
)

data class GoalRunnerChildRepairApplyRequest(
  val unitOfWork: GoalRunnerPersistenceSession,
  val workflowId: String,
  val issueKey: String,
  val subtaskId: Int,
  val wedgeClasses: List<GoalRunnerWedgeClass>,
  val repoRoot: Path,
  val wedgeFindings: List<GoalRunnerWedgeFinding> = emptyList(),
)

data class GoalRunnerChildRepairApplyResult(
  val repairs: List<GoalRunnerAppliedRepair> = emptyList(),
  val manifestProjectionArtifacts: DurableWorkflowArtifacts? = null,
)

data class GoalRunnerAppliedRepair(
  val subtaskId: Int,
  val workflowId: String,
  val wedgeClass: GoalRunnerWedgeClass,
  val field: String,
  val priorValue: String?,
  val newValue: String?,
)

data class GoalRunnerRepairResult(
  val issueKey: String,
  val status: GoalRunnerRepairStatus,
  val parentWorkflowId: String? = null,
  val parentWedges: List<GoalRunnerWedgeFinding> = emptyList(),
  val parentPassedChecks: List<String> = emptyList(),
  val diagnoses: List<GoalRunnerChildWedgeDiagnosis> = emptyList(),
  val appliedRepairs: List<GoalRunnerAppliedRepair> = emptyList(),
  val refusalReason: String? = null,
  val liveLeaseWorkflowId: String? = null,
) {
  init {
    require(issueKey.isNotBlank()) { "issueKey is required." }
  }
}
