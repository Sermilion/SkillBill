package skillbill.engine.featuretask.phase.record

import skillbill.engine.featuretask.persist.durationMillis
import skillbill.workflow.model.WorkflowStepStatus
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimePhaseRecord

public fun FeatureTaskRuntimePhaseRecord.asPendingForOperatorResume(): FeatureTaskRuntimePhaseRecord =
  copy(
    status = WorkflowStepStatus.PENDING,
    finishedAt = null,
    durationMillis = null,
    outputArtifact = null,
    rejectedOutput = null,
    blockedReason = null,
    failureDisposition = null,
    fileManifestBefore = emptyList(),
    fileManifestAfter = emptyList(),
    fileManifestIntroduced = emptyList(),
  )
