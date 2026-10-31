package skillbill.engine.featuretask.model.subtask

import skillbill.workflow.taskruntime.model.handoff.task.FeatureTaskRuntimeFeatureSize
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimePhaseLedgerEntry
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimePhaseRecord
import skillbill.workflow.taskruntime.model.skeleton.FeatureTaskRuntimeQualityGateSelection
import java.time.Clock

public data class CompletedUpstreamRepairRequest(
  public val phaseRecords: Map<String, FeatureTaskRuntimePhaseRecord>,
  public val ledger: List<FeatureTaskRuntimePhaseLedgerEntry>,
  public val featureSize: FeatureTaskRuntimeFeatureSize,
  public val resumePhaseId: String,
  public val reason: String,
  public val clock: Clock,
  public val qualityGateSelection: FeatureTaskRuntimeQualityGateSelection =
    FeatureTaskRuntimeQualityGateSelection.VALIDATE,
)
