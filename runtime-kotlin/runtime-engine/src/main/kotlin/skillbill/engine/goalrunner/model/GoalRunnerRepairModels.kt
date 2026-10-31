package skillbill.engine.goalrunner.model

import skillbill.goalrunner.model.GoalRunnerAcceptedBlockReference
import skillbill.ports.goalrunner.GoalRunnerPersistenceSession
import skillbill.workflow.decomposition.model.DecompositionSubtask
import skillbill.workflow.engine.model.DurableWorkflowArtifacts
import java.nio.file.Path

public enum class GoalRunnerWedgeClass(public val wireValue: String, internal val durableField: String) {
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

  internal companion object {
    internal fun fromWire(value: String): GoalRunnerWedgeClass? = entries.firstOrNull { it.wireValue == value }
  }

  internal val operatorRequired: Boolean
    get() = this == PHASE_OUTPUT_CONTRACT_INCOMPATIBLE
}

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

public data class GoalRunnerAppliedRepair(
  public val subtaskId: Int,
  public val workflowId: String,
  public val wedgeClass: GoalRunnerWedgeClass,
  public val field: String,
  public val priorValue: String?,
  public val newValue: String?,
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
