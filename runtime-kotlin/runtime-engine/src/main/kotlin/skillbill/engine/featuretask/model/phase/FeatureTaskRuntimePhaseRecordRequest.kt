package skillbill.engine.featuretask.model.phase

import skillbill.workflow.taskruntime.model.handoff.task.NormalizedFeatureTaskRuntimePhaseOutput
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimeFailureDisposition
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimePhaseLedgerAction
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimePhaseOutputRepairEvidence
import skillbill.workflow.taskruntime.model.validation.FeatureTaskRuntimeFindingVerificationDisposition

data class FeatureTaskRuntimePhaseStateRequest(
  val workflowId: String,
  val phaseId: String,
  val status: String,
  val attemptCount: Int,
  val resolvedAgentId: String,
  val finished: Boolean,
  val outputArtifact: String? = null,
  val rejectedOutput: String? = null,
  val normalizedOutput: NormalizedFeatureTaskRuntimePhaseOutput? = null,
  val repairEvidence: FeatureTaskRuntimePhaseOutputRepairEvidence? = null,
  val repositoryFingerprint: String? = null,
  val blockedReason: String? = null,
  val failureDisposition: FeatureTaskRuntimeFailureDisposition? = null,
  val fileManifestBefore: List<String> = emptyList(),
  val fileManifestAfter: List<String> = emptyList(),
  val fileManifestIntroduced: List<String> = emptyList(),
  val loopId: String? = null,
  val edgeIteration: Int? = null,
  val reviewPassNumber: Int? = null,
  val launchedModel: String? = null,
  val launchedEffort: String? = null,
  val launchOutcomeKnown: Boolean = false,
  val reviewRunId: String? = null,
  val findingVerificationCheckpoint: List<FeatureTaskRuntimeFindingVerificationDisposition>? = null,
  val mutating: Boolean = false,
)

data class FeatureTaskRuntimePhaseLedgerRequest(
  val workflowId: String,
  val action: FeatureTaskRuntimePhaseLedgerAction,
  val phaseId: String,
  val attemptCount: Int,
  val resolvedAgentId: String? = null,
  val fixLoopIteration: Int? = null,
  val blockedReason: String? = null,
  val loopId: String? = null,
  val edgeIteration: Int? = null,
)
