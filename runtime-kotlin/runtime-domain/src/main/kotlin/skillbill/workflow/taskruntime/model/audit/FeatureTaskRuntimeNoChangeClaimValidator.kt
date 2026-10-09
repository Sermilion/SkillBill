package skillbill.workflow.taskruntime.model.audit

import skillbill.contracts.JsonCodec

sealed interface FeatureTaskRuntimeNoChangeClaimValidation {
  data class Valid(val claim: FeatureTaskRuntimeNoChangeClaim) : FeatureTaskRuntimeNoChangeClaimValidation

  data class Rejected(val reasons: List<String>) : FeatureTaskRuntimeNoChangeClaimValidation
}

object FeatureTaskRuntimeNoChangeClaimValidator {
  val FIELDS: Set<String> =
    setOf("reason", "criteria", "citations", "boundary_trace", "owning_system", "suggested_handoff")

  private val CITATION = Regex("""^\S+:\d+(-\d+)?$""")
  private val REASON_WORDS = FeatureTaskRuntimeNoChangeReason.entries.joinToString { it.wireValue }

  fun validate(
    claim: Any?,
    catalogCriterionIds: List<String>,
    changedFiles: Collection<String>,
  ): FeatureTaskRuntimeNoChangeClaimValidation {
    val raw = JsonCodec.anyToStringAnyMap(claim).orEmpty()
    val reasons = mutableListOf<String>()
    val unknownFields = raw.keys - FIELDS
    if (unknownFields.isNotEmpty()) {
      reasons += "No-change claim has unsupported fields: ${unknownFields.sorted().joinToString()}."
    }
    val reason = FeatureTaskRuntimeNoChangeReason.fromWireOrNull(raw["reason"])
    if (reason == null) reasons += "No-change claim reason is missing or not one of $REASON_WORDS."
    val criteria = criteriaFor(raw["criteria"], catalogCriterionIds, reasons)
    val citations =
      (raw["citations"] as? List<*>).orEmpty()
        .mapNotNull { (it as? String)?.trim()?.takeIf(String::isNotEmpty) }
    if (citations.none { CITATION.matches(it) }) {
      reasons += "No-change claim needs at least one path:line or path:start-end citation."
    }
    val boundaryTrace = (raw["boundary_trace"] as? String)?.trim().orEmpty()
    if (boundaryTrace.isEmpty()) reasons += "No-change claim boundary_trace is blank."
    val changed = changedFiles.filter(String::isNotBlank).sorted()
    if (changed.isNotEmpty()) {
      reasons += "No-change claim is invalid because this step changed files: ${changed.joinToString()}."
    }
    if (reason == null || reasons.isNotEmpty()) return FeatureTaskRuntimeNoChangeClaimValidation.Rejected(reasons)
    val owningSystem = (raw["owning_system"] as? String)?.trim()?.takeIf(String::isNotEmpty)
    val suggestedHandoff =
      (raw["suggested_handoff"] as? String)?.trim()?.takeIf(String::isNotEmpty)
        ?: derivedHandoff(reason, owningSystem)
    return FeatureTaskRuntimeNoChangeClaimValidation.Valid(
      FeatureTaskRuntimeNoChangeClaim(
        reason = reason,
        criteria = criteria,
        citations = citations,
        boundaryTrace = boundaryTrace,
        owningSystem = owningSystem,
        suggestedHandoff = suggestedHandoff,
      ),
    )
  }

  private fun criteriaFor(
    rawCriteria: Any?,
    catalogCriterionIds: List<String>,
    reasons: MutableList<String>,
  ): List<FeatureTaskRuntimeNoChangeCriterion> {
    val entries = (rawCriteria as? List<*>)?.mapNotNull { JsonCodec.anyToStringAnyMap(it) }.orEmpty()
    if (rawCriteria !is List<*>) reasons += "No-change claim criteria must be a list of criterion entries."
    val byId = entries.associateBy { (it["criterion_id"] as? String)?.trim().orEmpty() }
    return catalogCriterionIds.mapNotNull { criterionId ->
      val entry = byId[criterionId]
      if (entry == null) {
        reasons += "No-change claim has no criterion entry for $criterionId."
        return@mapNotNull null
      }
      val verdict = FeatureTaskRuntimeNoChangeReason.fromWireOrNull(entry["verdict"])
      val evidence = (entry["evidence"] as? String)?.trim().orEmpty()
      if (verdict == null) {
        reasons += "No-change claim verdict for $criterionId is blank or not one of $REASON_WORDS."
      }
      if (evidence.isEmpty()) reasons += "No-change claim evidence for $criterionId is blank."
      if (verdict == null || evidence.isEmpty()) {
        null
      } else {
        FeatureTaskRuntimeNoChangeCriterion(criterionId, verdict, evidence)
      }
    }
  }

  private fun derivedHandoff(
    reason: FeatureTaskRuntimeNoChangeReason,
    owningSystem: String?,
  ): String =
    when (reason) {
      FeatureTaskRuntimeNoChangeReason.OUT_OF_REPO ->
        "Hand off to ${owningSystem ?: "the owning system (not identified)"}"
      FeatureTaskRuntimeNoChangeReason.ALREADY_SATISFIED -> "Close the issue as already satisfied"
      FeatureTaskRuntimeNoChangeReason.NOT_REPRODUCIBLE ->
        "Return the issue to the reporter for reproduction steps"
    }
}
