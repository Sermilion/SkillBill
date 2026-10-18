package skillbill.engine.featuretask.slot.audit

import skillbill.contracts.SharedPayloadKeys
import skillbill.contracts.workflow.featuretask.FEATURE_TASK_RUNTIME_CONTRACT_VERSION
import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeRunRequest
import skillbill.engine.featuretask.runloop.core.PhaseOutcome
import skillbill.engine.featuretask.runloop.core.PhaseRun
import skillbill.engine.featuretask.slot.state.PhaseAcceptedStepExecution
import skillbill.engine.featuretask.slot.state.PhaseQaStepBinding
import skillbill.engine.featuretask.slot.state.PhaseStepBinding
import skillbill.workflow.model.validation.FeatureTaskRuntimeVerdict
import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionRepairStage
import skillbill.workflow.taskruntime.model.audit.qaReauditRefs
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimeWorkflowArtifactMap
import skillbill.workflow.taskruntime.model.handoff.assembly.FeatureTaskRuntimePhaseOutput
import skillbill.workflow.taskruntime.model.handoff.task.NormalizedFeatureTaskRuntimePhaseOutput

internal object AcceptanceQaReaudit {
  private const val EMPTY_SCOPE_SUMMARY =
    "Every acceptance criterion is accepted and no open QA finding names one, so audit re-checks nothing. " +
      "QA decides whether the open findings close."

  fun settledWithoutScope(
    run: PhaseRun,
    state: PhaseAcceptedStepExecution,
  ): PhaseOutcome? {
    val accepted =
      (run.request as? FeatureTaskRuntimeRunRequest)?.acceptanceCriterionRepair?.snapshot
        ?.takeIf { it.stage == AcceptanceCriterionRepairStage.ACCEPTED }
        ?: return null
    val findings = (state as? PhaseQaStepBinding)?.qaRepairState()?.openFindingCriteria.orEmpty()
    if (accepted.qaReauditRefs(findings)?.isEmpty() != true) return null
    val output =
      NormalizedFeatureTaskRuntimePhaseOutput.fromRecordMap(
        FeatureTaskRuntimeWorkflowArtifactMap.from(
          mapOf(
            SharedPayloadKeys.CONTRACT_VERSION to FEATURE_TASK_RUNTIME_CONTRACT_VERSION,
            SharedPayloadKeys.PHASE_ID to run.phaseId,
            SharedPayloadKeys.STATUS to "completed",
            SharedPayloadKeys.SUMMARY to EMPTY_SCOPE_SUMMARY,
            SharedPayloadKeys.VERDICT to FeatureTaskRuntimeVerdict.SATISFIED.wireValue,
            SharedPayloadKeys.PRODUCED_OUTPUTS to mapOf(SharedPayloadKeys.VALUE to EMPTY_SCOPE_SUMMARY),
          ),
        ),
      )
    val iteration = state.nextStepIteration()
    state.settleRuntimeAuthoredCompletion(run.phaseId, output)
    return PhaseOutcome.completed(
      FeatureTaskRuntimePhaseOutput(run.phaseId, iteration, output.canonicalJson, output, null),
    )
  }

  fun promptSupplement(state: PhaseStepBinding): String {
    val findings = (state as? PhaseQaStepBinding)?.qaRepairState()?.openFindings.orEmpty()
    if (findings.isEmpty()) return ""
    val criteria = findings.flatMap { it.affectedCriteria }.distinct().sorted()
    return "\n\nPost-QA re-audit scope: ${criteria.joinToString()}. qa_fix repaired the open QA findings " +
      findings.joinToString { "${it.id} (${it.affectedCriteria.joinToString()})" } +
      ", so the runtime re-audits only the accepted criteria those findings name. Criteria outside this scope " +
      "stay accepted and are not re-checked. QA, not audit, decides whether the findings close."
  }
}
