package skillbill.engine.featuretask.model.subtask

import skillbill.workflow.taskruntime.model.handoff.task.FeatureTaskRuntimeFeatureSize
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimePhaseLedgerEntry
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimePhaseRecord
import skillbill.workflow.taskruntime.model.skeleton.FeatureTaskRuntimeQualityGateSelection
import java.time.Clock

data class CompletedUpstreamRepairRequest(
  val phaseRecords: Map<String, FeatureTaskRuntimePhaseRecord>,
  val ledger: List<FeatureTaskRuntimePhaseLedgerEntry>,
  val featureSize: FeatureTaskRuntimeFeatureSize,
  val resumePhaseId: String,
  val reason: String,
  val clock: Clock,
  val qualityGateSelection: FeatureTaskRuntimeQualityGateSelection =
    FeatureTaskRuntimeQualityGateSelection.VALIDATE,
)
