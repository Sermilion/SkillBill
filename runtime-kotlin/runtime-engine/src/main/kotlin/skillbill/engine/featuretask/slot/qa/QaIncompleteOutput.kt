package skillbill.engine.featuretask.slot.qa

import skillbill.contracts.SharedPayloadKeys
import skillbill.contracts.qa.QaOutcomePayloadKeys
import skillbill.contracts.qa.QaRepairStatePayloadKeys
import skillbill.contracts.workflow.featuretask.FEATURE_TASK_RUNTIME_CONTRACT_VERSION
import skillbill.engine.featuretask.qa.report.QaPhaseOutputKeys
import skillbill.engine.featuretask.qa.report.QaVerdict
import skillbill.ports.featuretask.model.FeatureTaskQaFailureKind
import skillbill.workflow.model.validation.FeatureTaskRuntimeVerdict
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimeWorkflowArtifactMap
import skillbill.workflow.taskruntime.model.handoff.task.NormalizedFeatureTaskRuntimePhaseOutput
import skillbill.workflow.taskruntime.model.qa.QaRepairState

internal object QaIncompleteOutput {
  fun build(
    phaseId: String,
    repairState: QaRepairState?,
    reason: String,
    kind: FeatureTaskQaFailureKind?,
  ): NormalizedFeatureTaskRuntimePhaseOutput {
    val produced =
      listOfNotNull(
        QaPhaseOutputKeys.VERDICT to QaVerdict.INCOMPLETE.wireValue,
        QaPhaseOutputKeys.REASON to reason,
        repairState?.let { state ->
          QaPhaseOutputKeys.OPEN_FINDINGS to
            state.openFindings.map { finding ->
              mapOf(
                QaOutcomePayloadKeys.ID to finding.id,
                QaRepairStatePayloadKeys.AFFECTED_CRITERIA to finding.affectedCriteria,
                QaRepairStatePayloadKeys.RECEIPT_REFS to finding.receiptRefs,
              )
            }
        },
        repairState?.lastReceiptTreeIdentity?.let { QaPhaseOutputKeys.TREE_IDENTITY to it },
        kind?.let { QaPhaseOutputKeys.FAILURE_KIND to it.wireValue },
      ).toMap()
    val base =
      NormalizedFeatureTaskRuntimePhaseOutput.fromRecordMap(
        FeatureTaskRuntimeWorkflowArtifactMap.from(
          mapOf(
            SharedPayloadKeys.CONTRACT_VERSION to FEATURE_TASK_RUNTIME_CONTRACT_VERSION,
            SharedPayloadKeys.PHASE_ID to phaseId,
            SharedPayloadKeys.STATUS to "completed",
            SharedPayloadKeys.SUMMARY to reason,
            SharedPayloadKeys.PRODUCED_OUTPUTS to produced,
          ),
        ),
      )
    return base.copy(verdict = FeatureTaskRuntimeVerdict.QA_INCOMPLETE.wireValue, qaRepairState = repairState)
  }
}
