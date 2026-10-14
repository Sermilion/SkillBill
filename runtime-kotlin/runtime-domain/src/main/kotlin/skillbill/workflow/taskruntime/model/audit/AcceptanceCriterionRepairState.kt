package skillbill.workflow.taskruntime.model.audit

enum class AcceptanceCriterionRepairMode(val wireValue: String) {
  INITIAL_DISCOVERY("initial_discovery"),
  CURRENT_CRITERION_TARGETED("current_criterion_targeted"),
  FINAL_FULL("final_full"),
}

enum class AcceptanceCriterionSourceDisposition(val wireValue: String) {
  NOT_APPLICABLE("not_applicable"),
  SOURCE_CLOSED("source_closed"),
  SOURCE_UNRESOLVED("source_unresolved"),
}

enum class AcceptanceCriterionValidationObligation(val wireValue: String) {
  REQUIRED("required"),
  DEFERRED("deferred"),
  NOT_REQUIRED("not_required"),
}

enum class AcceptanceCriterionRepairOutcomeKind(val wireValue: String) {
  TARGETED_SUCCESS("targeted_success"),
  INCOMPLETE("incomplete"),
  BLOCKED("blocked"),
  TARGETED_FAILURE("targeted_failure"),
}

data class AcceptanceCriterionRepairAdmission(
  val catalogId: String,
  val executionId: String,
  val specDigest: String,
  val authoredCriteria: List<String>,
  val criteria: List<AcceptanceCriterionRepairCriterion>,
  val dependencies: List<AcceptanceCriterionRepairDependency>,
)

data class AcceptanceCriterionRepairCriterion(
  val canonicalRef: String,
  val originalAlias: String?,
  val originalText: String,
  val ordinal: Int,
  val sourceDisposition: AcceptanceCriterionSourceDisposition,
  val validationObligation: AcceptanceCriterionValidationObligation,
  val validationOwnerAvailable: Boolean,
  val evidenceRef: String,
)

data class AcceptanceCriterionRepairDependency(
  val dependentRef: String,
  val prerequisiteRef: String,
  val reason: String,
  val declaringOutputRef: String,
  val sourceEvidenceRef: String,
)

data class AcceptanceCriterionRepairReservation(
  val reservationId: String,
  val criterionRef: String,
  val revision: Long,
)

data class AcceptanceCriterionRepairOutcome(
  val outcomeId: String,
  val reservationId: String,
  val criterionRef: String,
  val digest: String,
  val kind: AcceptanceCriterionRepairOutcomeKind,
  val evidenceRef: String,
) {
  val successful: Boolean get() = kind == AcceptanceCriterionRepairOutcomeKind.TARGETED_SUCCESS
}

data class AcceptanceCriterionRepairState internal constructor(
  val catalogId: String,
  val executionId: String,
  val specDigest: String,
  val criteria: List<AcceptanceCriterionRepairCriterion>,
  val dependencies: List<AcceptanceCriterionRepairDependency>,
  val unresolvedQueue: List<String>,
  val currentCriterionRef: String?,
  val reservation: AcceptanceCriterionRepairReservation?,
  val outcomes: List<AcceptanceCriterionRepairOutcome>,
  val unsuccessfulTotals: Map<String, Int>,
  val finalAuditRequired: Boolean,
  val finalAuditDigest: String?,
  val revision: Long,
) {
  fun reserve(
    reservationId: String,
    mode: AcceptanceCriterionRepairMode = AcceptanceCriterionRepairMode.CURRENT_CRITERION_TARGETED,
  ): AcceptanceCriterionRepairState? {
    val current = currentCriterionRef ?: return null
    if (
      mode != AcceptanceCriterionRepairMode.CURRENT_CRITERION_TARGETED || reservation != null ||
      reservationId.isBlank() || outcomes.any { it.reservationId == reservationId } ||
      unsuccessfulTotals.getOrDefault(current, 0) >= MAX_UNSUCCESSFUL_REPAIR_OUTCOMES
    ) return null
    return copy(
      reservation = AcceptanceCriterionRepairReservation(reservationId, current, revision + 1),
      revision = revision + 1,
    )
  }

  fun record(outcome: AcceptanceCriterionRepairOutcome): AcceptanceCriterionRepairState? {
    if (outcomes.size >= MAX_REPAIR_OUTCOMES) return null
    if (outcome.outcomeId.isBlank() || !outcome.digest.isDigest() || outcome.evidenceRef.isBlank()) return null
    val existing = outcomes.firstOrNull { it.reservationId == outcome.reservationId }
    if (existing != null) return if (existing == outcome) this else null
    if (outcomes.any { it.outcomeId == outcome.outcomeId }) return null
    val live = reservation ?: return null
    if (outcome.reservationId != live.reservationId || outcome.criterionRef != live.criterionRef) return null
    val totals = unsuccessfulTotals.toMutableMap()
    if (!outcome.successful) {
      val next = totals.getOrDefault(outcome.criterionRef, 0) + 1
      if (next > MAX_UNSUCCESSFUL_REPAIR_OUTCOMES) return null
      totals[outcome.criterionRef] = next
    }
    val queue = unresolvedQueue.toMutableList()
    if (outcome.successful) queue.remove(outcome.criterionRef)
    val current = queue.firstOrNull()
    return copy(
      unresolvedQueue = queue,
      currentCriterionRef = current,
      reservation = null,
      outcomes = outcomes + outcome,
      unsuccessfulTotals = totals,
      finalAuditRequired = queue.isEmpty(),
      finalAuditDigest = null,
      revision = revision + 1,
    )
  }

  fun recordFinalFullAudit(
    evaluatedRefs: Set<String>,
    regressedRefs: Set<String>,
    evidenceDigest: String,
    mode: AcceptanceCriterionRepairMode = AcceptanceCriterionRepairMode.FINAL_FULL,
  ): AcceptanceCriterionRepairState? {
    if (
      mode != AcceptanceCriterionRepairMode.FINAL_FULL || !finalAuditRequired || !evidenceDigest.isDigest() ||
      reservation != null
    ) {
      return null
    }
    val criterionRefs = criteria.mapTo(mutableSetOf()) { it.canonicalRef }
    if (evaluatedRefs != criterionRefs || !criterionRefs.containsAll(regressedRefs)) return null
    if (finalAuditDigest == evidenceDigest) return this
    val ordered =
      (topologicalOrder(criteria, dependencies) ?: return null).filter { it in regressedRefs }
    return copy(
      unresolvedQueue = ordered,
      currentCriterionRef = ordered.firstOrNull(),
      finalAuditRequired = ordered.isNotEmpty(),
      finalAuditDigest = evidenceDigest,
      revision = revision + 1,
    )
  }

  companion object {
    const val MAX_UNSUCCESSFUL_REPAIR_OUTCOMES: Int = 3

    fun admit(
      admission: AcceptanceCriterionRepairAdmission,
      mode: AcceptanceCriterionRepairMode = AcceptanceCriterionRepairMode.INITIAL_DISCOVERY,
    ): AcceptanceCriterionRepairState? {
      if (mode != AcceptanceCriterionRepairMode.INITIAL_DISCOVERY) return null
      if (
        admission.catalogId.isBlank() || admission.executionId.isBlank() || !admission.specDigest.isDigest()
      ) return null
      val criteria = admission.criteria
      if (
        criteria.isEmpty() || criteria.size > MAX_REPAIR_CRITERIA ||
        admission.authoredCriteria.size != criteria.size
      ) return null
      if (criteria.map { it.ordinal }.toSet().size != criteria.size) return null
      if (criteria.map { it.canonicalRef }.toSet().size != criteria.size) return null
      if (criteria.any {
          it.ordinal < 1 || it.canonicalRef.isBlank() || it.originalText.isBlank() || it.evidenceRef.isBlank()
        }
      ) {
        return null
      }
      val byOrdinal = criteria.associateBy { it.ordinal }
      if (admission.authoredCriteria.withIndex().any { (index, authoredText) ->
          val expected = acceptanceCriterionIdentity(authoredText, index + 1) ?: return null
          val admitted = byOrdinal[index + 1] ?: return null
          authoredText != admitted.originalText || expected.canonicalRef != admitted.canonicalRef ||
            originalAcceptanceCriterionAlias(authoredText) != admitted.originalAlias
        }
      ) return null
      val refs = criteria.mapTo(mutableSetOf()) { it.canonicalRef }
      if (admission.dependencies.any {
          it.dependentRef !in refs || it.prerequisiteRef !in refs || it.dependentRef == it.prerequisiteRef ||
            it.reason.isBlank() || it.declaringOutputRef.isBlank() || it.sourceEvidenceRef.isBlank()
        }
      ) return null
      if (
        admission.dependencies.map { it.dependentRef to it.prerequisiteRef }.toSet().size !=
        admission.dependencies.size
      ) return null
      if (admission.dependencies.size > MAX_REPAIR_DEPENDENCIES) return null
      val order = topologicalOrder(criteria, admission.dependencies) ?: return null
      val byRef = criteria.associateBy { it.canonicalRef }
      if (admission.dependencies.any { edge ->
          val prerequisite = byRef.getValue(edge.prerequisiteRef)
          prerequisite.sourceDisposition != AcceptanceCriterionSourceDisposition.SOURCE_CLOSED &&
            prerequisite.validationObligation == AcceptanceCriterionValidationObligation.REQUIRED &&
            !prerequisite.validationOwnerAvailable
        }
      ) return null
      val queue =
        order.filter { byRef.getValue(it).sourceDisposition == AcceptanceCriterionSourceDisposition.SOURCE_UNRESOLVED }
      return AcceptanceCriterionRepairState(
        catalogId = admission.catalogId,
        executionId = admission.executionId,
        specDigest = admission.specDigest,
        criteria = criteria.sortedBy { it.ordinal },
        dependencies = admission.dependencies,
        unresolvedQueue = queue,
        currentCriterionRef = queue.firstOrNull(),
        reservation = null,
        outcomes = emptyList(),
        unsuccessfulTotals = emptyMap(),
        finalAuditRequired = queue.isEmpty(),
        finalAuditDigest = null,
        revision = 0,
      )
    }

    private fun topologicalOrder(
      criteria: List<AcceptanceCriterionRepairCriterion>,
      dependencies: List<AcceptanceCriterionRepairDependency>,
    ): List<String>? {
      val prerequisites = criteria.associate { it.canonicalRef to mutableSetOf<String>() }
      dependencies.forEach { prerequisites.getValue(it.dependentRef).add(it.prerequisiteRef) }
      val result = mutableListOf<String>()
      while (result.size < criteria.size) {
        val next = criteria.asSequence().filter { it.canonicalRef !in result }
          .filter { prerequisites.getValue(it.canonicalRef).all(result::contains) }
          .minByOrNull { it.ordinal } ?: return null
        result += next.canonicalRef
      }
      return result
    }

    private const val MAX_REPAIR_CRITERIA: Int = 256
    private const val MAX_REPAIR_DEPENDENCIES: Int = 4096
    private const val MAX_REPAIR_OUTCOMES: Int = 4096
  }
}

private fun String.isDigest(): Boolean = matches(Regex("^[a-fA-F0-9]{64}$"))
