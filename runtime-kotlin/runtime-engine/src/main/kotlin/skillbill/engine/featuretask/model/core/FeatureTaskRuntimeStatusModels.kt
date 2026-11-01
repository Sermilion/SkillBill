package skillbill.engine.featuretask.model.core

import skillbill.ports.idestatus.model.IdeStatusCurrentPhaseExecution
import skillbill.workflow.taskruntime.model.validation.FeatureTaskRuntimeValidationGateExecutionEvidence

public data class FeatureTaskRuntimeStatusRequest(
  public val workflowId: String,
) {
  init {
    require(workflowId.isNotBlank()) { "FeatureTaskRuntimeStatusRequest.workflowId is required." }
  }
}

public data class FeatureTaskRuntimePhaseStatus(
  public val phaseId: String,
  public val status: String,
  public val attemptCount: Int,
  public val resolvedAgentId: String?,
  public val finished: Boolean,
  public val executionOrigin: String? = null,
  public val continuationKind: String? = null,
  public val launchedModel: String? = null,
  public val launchedEffort: String? = null,
)

public data class FeatureTaskRuntimeStatusProjection(
  public val workflowId: String,
  public val featureSize: String?,
  public val phases: List<FeatureTaskRuntimePhaseStatus>,
  public val completeCount: Int,
  public val pendingCount: Int,
  public val blockedCount: Int,
  public val currentPhaseId: String?,
  public val resolvedBranch: String? = null,
  public val finalizingAgentId: String? = null,
  public val decomposeTerminal: FeatureTaskRuntimeDecomposeTerminalStatus? = null,
  public val gateRunCount: Int? = null,
  public val validationGateExecutionEvidence: FeatureTaskRuntimeValidationGateExecutionEvidence? = null,
  public val currentPhaseExecution: IdeStatusCurrentPhaseExecution? = null,
  public val degradedDiagnostic: FeatureTaskRuntimeDegradedDiagnosticStatus? = null,
  public val operatorDecisionPause: FeatureTaskRuntimeOperatorDecisionPause? = null,
  public val criterionExplanation: String? = null,
)

public data class FeatureTaskRuntimeOperatorDecisionPause(
  val phaseId: String,
  val reason: String? = null,
) {
  init {
    require(phaseId.isNotBlank()) { "FeatureTaskRuntimeOperatorDecisionPause.phaseId is required." }
    require(reason == null || reason.isNotBlank()) {
      "FeatureTaskRuntimeOperatorDecisionPause.reason must be absent or non-blank."
    }
  }
}

public data class FeatureTaskRuntimeDegradedDiagnosticStatus(
  val count: Int,
  val failureClass: String,
  val phaseId: String,
  val attempt: Int,
) {
  init {
    require(count >= 1) { "FeatureTaskRuntimeDegradedDiagnosticStatus.count must be >= 1 when present." }
    require(failureClass.isNotBlank()) {
      "FeatureTaskRuntimeDegradedDiagnosticStatus.failureClass must be non-blank."
    }
    require(phaseId.isNotBlank()) { "FeatureTaskRuntimeDegradedDiagnosticStatus.phaseId must be non-blank." }
    require(attempt >= 0) { "FeatureTaskRuntimeDegradedDiagnosticStatus.attempt must be >= 0." }
  }
}

public data class FeatureTaskRuntimeDecomposeTerminalStatus(
  val reason: String,
  val parentSpecPath: String,
  val decompositionManifestPath: String,
  val subtaskSpecPaths: List<String>,
) {
  public val subtaskCount: Int get() = subtaskSpecPaths.size
}
