package skillbill.engine.featuretask.model.core

import skillbill.ports.idestatus.model.IdeStatusCurrentPhaseExecution
import skillbill.workflow.taskruntime.model.validation.FeatureTaskRuntimeValidationGateExecutionEvidence

data class FeatureTaskRuntimeStatusRequest(
  val workflowId: String,
) {
  init {
    require(workflowId.isNotBlank()) { "FeatureTaskRuntimeStatusRequest.workflowId is required." }
  }
}

data class FeatureTaskRuntimePhaseStatus(
  val phaseId: String,
  val status: String,
  val attemptCount: Int,
  val resolvedAgentId: String?,
  val finished: Boolean,
  val executionOrigin: String? = null,
  val continuationKind: String? = null,
  val launchedModel: String? = null,
  val launchedEffort: String? = null,
)

data class FeatureTaskRuntimeStatusProjection(
  val workflowId: String,
  val featureSize: String?,
  val phases: List<FeatureTaskRuntimePhaseStatus>,
  val completeCount: Int,
  val pendingCount: Int,
  val blockedCount: Int,
  val currentPhaseId: String?,
  val resolvedBranch: String? = null,
  val finalizingAgentId: String? = null,
  val decomposeTerminal: FeatureTaskRuntimeDecomposeTerminalStatus? = null,
  val gateRunCount: Int? = null,
  val validationGateExecutionEvidence: FeatureTaskRuntimeValidationGateExecutionEvidence? = null,
  val currentPhaseExecution: IdeStatusCurrentPhaseExecution? = null,
  val degradedDiagnostic: FeatureTaskRuntimeDegradedDiagnosticStatus? = null,
  val operatorDecisionPause: FeatureTaskRuntimeOperatorDecisionPause? = null,
)

data class FeatureTaskRuntimeOperatorDecisionPause(
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

data class FeatureTaskRuntimeDegradedDiagnosticStatus(
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

data class FeatureTaskRuntimeDecomposeTerminalStatus(
  val reason: String,
  val parentSpecPath: String,
  val decompositionManifestPath: String,
  val subtaskSpecPaths: List<String>,
) {
  val subtaskCount: Int get() = subtaskSpecPaths.size
}
