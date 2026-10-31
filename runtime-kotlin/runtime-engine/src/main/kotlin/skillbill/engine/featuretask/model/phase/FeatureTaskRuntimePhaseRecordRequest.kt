package skillbill.engine.featuretask.model.phase

import skillbill.workflow.taskruntime.model.handoff.task.NormalizedFeatureTaskRuntimePhaseOutput
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimeFailureDisposition
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimePhaseLedgerAction
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimePhaseOutputRepairEvidence
import skillbill.workflow.taskruntime.model.validation.FeatureTaskRuntimeFindingVerificationDisposition

public data class FeatureTaskRuntimePhaseStateRequest(
  public val workflowId: String,
  public val phaseId: String,
  public val status: String,
  public val attemptCount: Int,
  public val resolvedAgentId: String,
  public val finished: Boolean,
  public val outputArtifact: String? = null,
  public val rejectedOutput: String? = null,
  public val normalizedOutput: NormalizedFeatureTaskRuntimePhaseOutput? = null,
  public val repairEvidence: FeatureTaskRuntimePhaseOutputRepairEvidence? = null,
  public val repositoryFingerprint: String? = null,
  public val blockedReason: String? = null,
  public val failureDisposition: FeatureTaskRuntimeFailureDisposition? = null,
  public val failureCode: String? = null,
  public val fileManifestBefore: List<String> = emptyList(),
  public val fileManifestAfter: List<String> = emptyList(),
  public val fileManifestIntroduced: List<String> = emptyList(),
  public val loopId: String? = null,
  public val edgeIteration: Int? = null,
  public val reviewPassNumber: Int? = null,
  public val launchedModel: String? = null,
  public val launchedEffort: String? = null,
  public val launchOutcomeKnown: Boolean = false,
  public val reviewRunId: String? = null,
  internal val findingVerificationCheckpoint: List<FeatureTaskRuntimeFindingVerificationDisposition>? = null,
  public val mutating: Boolean = false,
)

public data class FeatureTaskRuntimePhaseLedgerRequest(
  public val workflowId: String,
  public val action: FeatureTaskRuntimePhaseLedgerAction,
  public val phaseId: String,
  public val attemptCount: Int,
  public val resolvedAgentId: String? = null,
  public val fixLoopIteration: Int? = null,
  public val blockedReason: String? = null,
  public val loopId: String? = null,
  public val edgeIteration: Int? = null,
)
