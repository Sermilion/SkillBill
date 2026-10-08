package skillbill.engine.featuretask.slot.audit.claim

import skillbill.contracts.JsonCodec
import skillbill.contracts.SharedPayloadKeys
import skillbill.engine.featuretask.runloop.state.FeatureTaskRuntimeProgressSnapshotAccess
import skillbill.engine.featuretask.slot.audit.AcceptanceAuditCatalog
import skillbill.workflow.taskruntime.artifact.envelopeWireMap
import skillbill.workflow.taskruntime.model.audit.FeatureTaskRuntimeNoChangeClaim
import skillbill.workflow.taskruntime.model.audit.FeatureTaskRuntimeNoChangeClaimValidation
import skillbill.workflow.taskruntime.model.audit.FeatureTaskRuntimeNoChangeClaimValidator
import skillbill.workflow.taskruntime.phase.task.FeatureTaskRuntimePhaseWorkflowDefinition

/**
 * The single reader of a no-change claim: which producing step carries it, which files that step changed, and
 * whether the claim is structurally valid. Audit, repair, simplify and the pause all route through here.
 */
internal object NoChangeClaimResolution {
  /** The claim an implement or audit_implement_fix step carries, or null when the latest producer carries none. */
  fun activeClaimMap(progress: FeatureTaskRuntimeProgressSnapshotAccess): Map<String, Any?>? =
    claimOf(
      progress.phase(producingPhaseId(progress)).output?.normalizedOutput?.envelopeWireMap()
        ?.get(SharedPayloadKeys.PRODUCED_OUTPUTS),
    )

  /**
   * The claim carried by a raw `produced_outputs` value. A present `no_change` key counts as a claim even when it
   * is malformed, so the validator can reject it with a specific reason instead of the round being ignored.
   */
  fun claimOf(producedOutputs: Any?): Map<String, Any?>? {
    val produced = JsonCodec.anyToStringAnyMap(producedOutputs) ?: return null
    val claim = produced[FeatureTaskRuntimeNoChangeClaim.KEY] ?: return null
    return JsonCodec.anyToStringAnyMap(claim) ?: emptyMap()
  }

  /** A bounded prose rendering of [claim] for the audit prompt. Reads the raw map so a malformed claim still shows. */
  fun renderForAudit(claim: Map<String, Any?>): String {
    val criteria =
      (claim["criteria"] as? List<*>).orEmpty()
        .mapNotNull { JsonCodec.anyToStringAnyMap(it) }
        .map { entry -> "- ${entry["criterion_id"]}: ${entry["verdict"]}. Evidence: ${entry["evidence"]}" }
    val citations = (claim["citations"] as? List<*>).orEmpty().joinToString()
    val rendered =
      listOf(
        "Reason: ${claim["reason"]}",
        "Criteria:",
        *criteria.toTypedArray(),
        "Citations: $citations",
        "Boundary trace: ${claim["boundary_trace"]}",
        "Owning system: ${claim["owning_system"] ?: "none"}",
        "Suggested handoff: ${claim["suggested_handoff"] ?: "none"}",
      ).joinToString("\n")
    return if (rendered.length <= MAX_RENDERED_CLAIM_CHARS) {
      rendered
    } else {
      rendered.take(MAX_RENDERED_CLAIM_CHARS) + "\n[claim truncated]"
    }
  }

  private const val MAX_RENDERED_CLAIM_CHARS = 4000

  /** The files the producing step changed: its introduced files plus paths whose before and after manifests differ. */
  fun changedFiles(progress: FeatureTaskRuntimeProgressSnapshotAccess): List<String> {
    val record = progress.phase(producingPhaseId(progress)).record ?: return emptyList()
    val before = record.fileManifestBefore.toSet()
    val after = record.fileManifestAfter.toSet()
    return (record.fileManifestIntroduced + (before - after) + (after - before)).distinct().sorted()
  }

  /** Validates the active claim against the accepted criterion catalog and the producing step's changed files. */
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

  /** Rewrites each entry's criterion_id to its catalog id, so `ac1` or `AC-01` matches the catalog's `AC-1`. */
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

  /**
   * audit_implement_fix is the producer only when it completed after the latest audit and after the latest implement
   * step; otherwise the claim, if any, is the implement step's.
   */
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
