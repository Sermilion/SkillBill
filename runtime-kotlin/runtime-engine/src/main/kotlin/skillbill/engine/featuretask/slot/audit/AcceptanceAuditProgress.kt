package skillbill.engine.featuretask.slot.audit

import skillbill.engine.featuretask.runloop.core.PhaseRun
import skillbill.engine.featuretask.runloop.phase.FeatureTaskRuntimeRunLoopPhaseBlocking
import skillbill.engine.featuretask.slot.attempt.PhaseCheckpointRemediationContext
import skillbill.workflow.taskruntime.artifact.envelopeWireMap
import skillbill.workflow.taskruntime.phase.task.FeatureTaskRuntimePhaseWorkflowDefinition

internal object AcceptanceAuditProgress {
  fun rejectionReason(
    context: PhaseCheckpointRemediationContext,
    run: PhaseRun,
    text: String,
  ): String? {
    val catalog = AcceptanceAuditCatalog.create(context.request.runInvariants.acceptanceCriteria)
    if (catalog is AcceptanceAuditCatalog.Unusable) return catalog.reason
    catalog as AcceptanceAuditCatalog.Known
    val current = AcceptanceAuditRemainingCriteriaParser.parse(text, catalog)
    if (current is AcceptanceAuditRemainingCriteria.Unusable) return current.reason
    current as AcceptanceAuditRemainingCriteria.Known
    return comparisonRejection(context, run, catalog, current)
  }

  private fun comparisonRejection(
    context: PhaseCheckpointRemediationContext,
    run: PhaseRun,
    catalog: AcceptanceAuditCatalog.Known,
    current: AcceptanceAuditRemainingCriteria.Known,
  ): String? {
    if (FeatureTaskRuntimeRunLoopPhaseBlocking.operatorReopenedPhase(context.session, run.phaseId)) return null
    val priorOutput = context.progress.outputFor(run.phaseId)?.normalizedOutput?.envelopeWireMap()
    val priorText = AcceptanceAuditVerdictRule.auditProseValue(priorOutput)
    val repaired =
      context.progress.hasPriorRecord(FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_AUDIT_IMPLEMENT_FIX) ||
        context.progress.edgeIterationCount(FeatureTaskRuntimePhaseWorkflowDefinition.AUDIT_REPAIR_LOOP_ID) > 0
    if (priorText == null) {
      return if (repaired) {
        "Audit comparison baseline is missing after repair; refusing another automatic repair."
      } else {
        null
      }
    }
    val prior = AcceptanceAuditRemainingCriteriaParser.parse(priorText, catalog)
    if (prior is AcceptanceAuditRemainingCriteria.Unusable) {
      return "Audit comparison baseline is unusable: ${prior.reason}"
    }
    prior as AcceptanceAuditRemainingCriteria.Known
    return if (current.identities.size >= prior.identities.size) {
      "Audit reported ${current.identities.size} remaining production criteria after repair " +
        "(${current.identities.sorted().joinToString(", ")}) against ${prior.identities.size} before it " +
        "(${prior.identities.sorted().joinToString(", ")}). The remaining list did not shrink, so the run blocks " +
        "for operator intervention instead of relaunching another repair."
    } else {
      null
    }
  }
}
