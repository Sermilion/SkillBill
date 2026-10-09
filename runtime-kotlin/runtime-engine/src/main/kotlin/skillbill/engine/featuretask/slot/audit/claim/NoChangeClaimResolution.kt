package skillbill.engine.featuretask.slot.audit.claim

import skillbill.contracts.JsonCodec
import skillbill.contracts.SharedPayloadKeys
import skillbill.engine.featuretask.runloop.state.FeatureTaskRuntimeProgressSnapshotAccess
import skillbill.engine.featuretask.slot.attempt.NO_CHANGE_PAUSE_REASON
import skillbill.engine.featuretask.slot.audit.AcceptanceAuditCatalog
import skillbill.workflow.model.WorkflowStepStatus
import skillbill.workflow.model.goalreview.GoalSubtaskOperatorDecision
import skillbill.workflow.taskruntime.artifact.envelopeWireMap
import skillbill.workflow.taskruntime.model.audit.FeatureTaskRuntimeNoChangeClaim
import skillbill.workflow.taskruntime.model.audit.FeatureTaskRuntimeNoChangeClaimValidation
import skillbill.workflow.taskruntime.model.audit.FeatureTaskRuntimeNoChangeClaimValidator
import skillbill.workflow.taskruntime.model.audit.FeatureTaskRuntimeNoChangePause
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimePhaseRecord
import skillbill.workflow.taskruntime.phase.task.FeatureTaskRuntimePhaseWorkflowDefinition

internal object NoChangeClaimResolution {
  fun activeClaimMap(progress: FeatureTaskRuntimeProgressSnapshotAccess): Map<String, Any?>? =
    claimOf(
      progress.phase(producingPhaseId(progress)).output?.normalizedOutput?.envelopeWireMap()
        ?.get(SharedPayloadKeys.PRODUCED_OUTPUTS),
    )

  fun claimOf(producedOutputs: Any?): Map<String, Any?>? {
    val produced = JsonCodec.anyToStringAnyMap(producedOutputs) ?: return null
    val claim = produced[FeatureTaskRuntimeNoChangeClaim.KEY] ?: return null
    return JsonCodec.anyToStringAnyMap(claim) ?: emptyMap()
  }

  fun renderForAudit(claim: Map<String, Any?>): String {
    val criteria =
      (claim["criteria"] as? List<*>).orEmpty()
        .mapNotNull { JsonCodec.anyToStringAnyMap(it) }
        .map { entry -> "- ${entry["criterion_id"]}: ${entry["verdict"]}. Evidence: ${entry["evidence"]}" }
    val citations = (claim["citations"] as? List<*>).orEmpty().joinToString()
    val rendered =
      (
        listOf("Reason: ${claim["reason"]}", "Criteria:") +
          criteria +
          listOf(
            "Citations: $citations",
            "Boundary trace: ${claim["boundary_trace"]}",
            "Owning system: ${claim["owning_system"] ?: "none"}",
            "Suggested handoff: ${claim["suggested_handoff"] ?: "none"}",
          )
      ).joinToString("\n")
    return if (rendered.length <= MAX_RENDERED_CLAIM_CHARS) {
      rendered
    } else {
      rendered.take(MAX_RENDERED_CLAIM_CHARS) + "\n[claim truncated]"
    }
  }

  private const val MAX_RENDERED_CLAIM_CHARS = 4000

  fun changedFiles(progress: FeatureTaskRuntimeProgressSnapshotAccess): List<String> {
    val record = progress.phase(producingPhaseId(progress)).record ?: return emptyList()
    val before = record.fileManifestBefore.toSet()
    val after = record.fileManifestAfter.toSet()
    return (record.fileManifestIntroduced + (before - after) + (after - before)).distinct().sorted()
  }

  fun pendingOperatorRetry(
    pause: FeatureTaskRuntimeNoChangePause?,
    auditRecord: FeatureTaskRuntimePhaseRecord?,
  ): NoChangeOperatorRetry? {
    val retry = pause?.takeIf { it.operatorDecision == GoalSubtaskOperatorDecision.RETRY_FIX.wireValue } ?: return null
    val instructions = retry.operatorInstructions?.takeIf(String::isNotBlank) ?: return null
    val auditPaused =
      auditRecord != null &&
        auditRecord.status == WorkflowStepStatus.PAUSED &&
        auditRecord.blockedReason == NO_CHANGE_PAUSE_REASON
    return if (auditPaused) NoChangeOperatorRetry(instructions, retry) else null
  }

  fun validate(
    progress: FeatureTaskRuntimeProgressSnapshotAccess,
    acceptanceCriteria: List<String>,
  ): FeatureTaskRuntimeNoChangeClaimValidation {
    val catalog = AcceptanceAuditCatalog.create(acceptanceCriteria)
    if (catalog is AcceptanceAuditCatalog.Unusable) {
      return FeatureTaskRuntimeNoChangeClaimValidation.Rejected(listOf(catalog.reason))
    }
    val known = catalog as AcceptanceAuditCatalog.Known
    return FeatureTaskRuntimeNoChangeClaimValidator.validate(
      claim = activeClaimMap(progress)?.let { claim -> withCatalogCriterionIds(claim, known) }.orEmpty(),
      catalogCriterionIds = known.aliases.values.distinct(),
      changedFiles = changedFiles(progress),
    )
  }

  private fun withCatalogCriterionIds(
    claim: Map<String, Any?>,
    catalog: AcceptanceAuditCatalog.Known,
  ): Map<String, Any?> {
    val criteria = claim["criteria"] as? List<*> ?: return claim
    val canonical =
      criteria.map { entry ->
        val map = JsonCodec.anyToStringAnyMap(entry) ?: return@map entry
        val id = (map["criterion_id"] as? String)?.trim() ?: return@map entry
        map + ("criterion_id" to (catalog.resolve(id) ?: id))
      }
    return claim + ("criteria" to canonical)
  }

  private fun producingPhaseId(progress: FeatureTaskRuntimeProgressSnapshotAccess): String {
    val repair = progress.phase(FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_AUDIT_IMPLEMENT_FIX)
    val repairFinished = repair.record?.finishedAt
    val auditFinished = progress.phase(FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_AUDIT).record?.finishedAt
    val implementFinished = progress.phase(FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_IMPLEMENT).record?.finishedAt
    val repairIsLatest =
      repair.completed &&
        repairFinished != null &&
        implementFinished != null &&
        !repairFinished.isBefore(implementFinished) &&
        (auditFinished == null || !repairFinished.isBefore(auditFinished))
    return if (repairIsLatest) {
      FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_AUDIT_IMPLEMENT_FIX
    } else {
      FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_IMPLEMENT
    }
  }
}
