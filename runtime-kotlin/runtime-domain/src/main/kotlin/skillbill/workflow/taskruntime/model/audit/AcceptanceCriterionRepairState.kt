package skillbill.workflow.taskruntime.model.audit

enum class AcceptanceCriterionRepairMode(val wireValue: String) {
  INITIAL_DISCOVERY("initial_discovery"),
  CURRENT_CRITERION_TARGETED("current_criterion_targeted"),
  ACCEPTED("accepted"),
}

enum class AcceptanceCriterionRepairStage(val wireValue: String) {
  DISCOVERY_ADMITTED("discovery_admitted"),
  READ_ONLY_AUDIT_REQUIRED("read_only_audit_required"),
  REPAIR_RESERVED("repair_reserved"),
  REPAIR_COMPLETED_AWAITING_TARGETED_VERIFICATION("repair_completed_awaiting_targeted_verification"),
  ACCEPTED("accepted"),
  BUDGET_BLOCKED("budget_blocked"),
  INTERACTION_TRACE_REQUIRED("interaction_trace_required"),
}

enum class AcceptanceCriterionRepairAdoptionComposition(val wireValue: String) {
  STANDALONE("standalone"),
  GOAL_CHILD_BUILD("goal_child_build"),
  GOAL_CHILD_VALIDATE("goal_child_validate"),
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

fun isUnavailableValidationPrerequisite(
  sourceDisposition: AcceptanceCriterionSourceDisposition,
  validationObligation: AcceptanceCriterionValidationObligation,
  validationOwnerAvailable: Boolean,
): Boolean =
  sourceDisposition != AcceptanceCriterionSourceDisposition.SOURCE_CLOSED &&
    validationObligation != AcceptanceCriterionValidationObligation.NOT_REQUIRED && !validationOwnerAvailable

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
  val closureEvidenceRef: String? = null,
  val closureTreeDigest: String? = null,
  val closureCheckpointId: String? = null,
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
  val stageRevision: Long = revision,
)

data class AcceptanceCriterionRepairOutcome(
  val outcomeId: String,
  val reservationId: String,
  val criterionRef: String,
  val digest: String,
  val kind: AcceptanceCriterionRepairOutcomeKind,
  val evidenceRef: String,
  val verifiedRevision: Long? = null,
  val treeDigest: String? = null,
  val checkpointId: String? = null,
  val auditBindingDigest: String? = null,
) {
  val successful: Boolean get() = kind == AcceptanceCriterionRepairOutcomeKind.TARGETED_SUCCESS
}

data class AcceptanceCriterionRepairSourceTransition(
  val criterionRef: String,
  val revision: Long,
  val fromDisposition: AcceptanceCriterionSourceDisposition,
  val toDisposition: AcceptanceCriterionSourceDisposition,
  val producingId: String,
  val producingDigest: String,
  val evidenceRef: String,
  val treeDigest: String?,
  val checkpointId: String?,
)

data class AcceptanceCriterionInteractionTrace(
  val pendingScenarioRefs: List<String> = emptyList(),
  val tracedScenarioRefs: List<String> = emptyList(),
  val brokenScenarioRefs: List<String> = emptyList(),
  val repairedScenarioRefs: List<String> = emptyList(),
  val unknownScenarioRefs: List<String> = emptyList(),
  val recheckRefs: List<String> = emptyList(),
  val recheckAnyChangeRefs: List<String> = emptyList(),
  val tracedSpecDigest: String? = null,
) {
  companion object {
    val EMPTY: AcceptanceCriterionInteractionTrace = AcceptanceCriterionInteractionTrace()
    const val MAX_SCENARIO_REFS: Int = 64
  }
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
  val revision: Long,
  val mode: AcceptanceCriterionRepairMode,
  val stage: AcceptanceCriterionRepairStage,
  val completedRepairEvidenceRef: String?,
  val admittedTreeDigest: String?,
  val admittedCheckpointId: String?,
  val sourceTransitions: List<AcceptanceCriterionRepairSourceTransition>,
  val pendingAuditRefs: List<String> = emptyList(),
  val proseAudits: List<AcceptanceCriterionProseAudit> = emptyList(),
  val pendingClarificationQuestion: String? = null,
  val budgetStartOutcomeIndex: Int = 0,
  val interactionTrace: AcceptanceCriterionInteractionTrace = AcceptanceCriterionInteractionTrace.EMPTY,
) {
  fun clearPendingClarification(): AcceptanceCriterionRepairState = copy(pendingClarificationQuestion = null)

  fun resetBudgetForOperatorResume(): AcceptanceCriterionRepairState {
    if (stage != AcceptanceCriterionRepairStage.BUDGET_BLOCKED) return this
    return copy(
      unsuccessfulTotals = emptyMap(),
      budgetStartOutcomeIndex = outcomes.size,
      stage =
        if (pendingAuditRefs.isEmpty()) {
          AcceptanceCriterionRepairStage.DISCOVERY_ADMITTED
        } else {
          AcceptanceCriterionRepairStage.READ_ONLY_AUDIT_REQUIRED
        },
      revision = revision + 1,
    )
  }

  fun reserve(
    reservationId: String,
    mode: AcceptanceCriterionRepairMode = AcceptanceCriterionRepairMode.CURRENT_CRITERION_TARGETED,
  ): AcceptanceCriterionRepairState? {
    val current = currentCriterionRef ?: return null
    val available = reservation == null && !hasExhaustedUnresolvedCriterion(unresolvedQueue, unsuccessfulTotals)
    val validId = reservationId.isNotBlank() && outcomes.none { it.reservationId == reservationId }
    val capacity = outcomes.size < MAX_REPAIR_OUTCOMES && sourceTransitions.size < MAX_REPAIR_SOURCE_TRANSITIONS
    val allowedStage = stage == AcceptanceCriterionRepairStage.DISCOVERY_ADMITTED
    if (mode != AcceptanceCriterionRepairMode.CURRENT_CRITERION_TARGETED || !available || !validId) return null
    if (!capacity || !allowedStage) return null
    return copy(
      reservation = AcceptanceCriterionRepairReservation(reservationId, current, revision + 1, revision + 1),
      mode = AcceptanceCriterionRepairMode.CURRENT_CRITERION_TARGETED,
      stage = AcceptanceCriterionRepairStage.REPAIR_RESERVED,
      revision = revision + 1,
    )
  }

  fun completeRepairAwaitingTargetedVerification(
    reservationId: String,
    evidenceRef: String,
    treeDigest: String,
    checkpointId: String,
  ): AcceptanceCriterionRepairState? {
    val live = reservation ?: return null
    val validReservation = live.reservationId == reservationId && live.criterionRef == currentCriterionRef
    val validEvidence = evidenceRef.isNotBlank() && treeDigest.isDigest() && checkpointId.isNotBlank()
    if (stage != AcceptanceCriterionRepairStage.REPAIR_RESERVED || !validReservation || !validEvidence) return null
    return copy(
      mode = AcceptanceCriterionRepairMode.CURRENT_CRITERION_TARGETED,
      stage = AcceptanceCriterionRepairStage.REPAIR_COMPLETED_AWAITING_TARGETED_VERIFICATION,
      completedRepairEvidenceRef = evidenceRef,
      admittedTreeDigest = treeDigest,
      admittedCheckpointId = checkpointId,
      reservation = live.copy(stageRevision = revision + 1),
      revision = revision + 1,
    )
  }

  fun record(outcome: AcceptanceCriterionRepairOutcome): AcceptanceCriterionRepairState? {
    if (!outcome.isValidRepairOutcome()) return null
    val existing = outcomes.firstOrNull { it.reservationId == outcome.reservationId }
    if (existing != null) return if (outcome.replays(existing)) this else null
    if (!canRecord(outcome)) return null
    val acceptedOutcome = outcome.boundTo(this)
    return acceptedOutcome.takeIf { it.matchesVerification(this) }?.let(::applyOutcome)
  }

  private fun applyOutcome(acceptedOutcome: AcceptanceCriterionRepairOutcome): AcceptanceCriterionRepairState? {
    val outcome = acceptedOutcome
    val totals = unsuccessfulTotals.toMutableMap()
    if (!outcome.successful) {
      val next = totals.getOrDefault(outcome.criterionRef, 0) + 1
      if (next > MAX_UNSUCCESSFUL_REPAIR_OUTCOMES) return null
      totals[outcome.criterionRef] = next
    }
    val changedCriteria = criteriaAfter(outcome)
    val byRef = changedCriteria.associateBy { it.canonicalRef }
    val queue = stableQueue(changedCriteria, dependencies, byRef) ?: return null
    val pending = if (outcome.successful) pendingAuditRefs - outcome.criterionRef else pendingAuditRefs
    val current = pending.firstOrNull() ?: queue.firstOrNull()
    val blocked =
      !outcome.successful && totals.getOrDefault(
        outcome.criterionRef,
        0,
      ) >= MAX_UNSUCCESSFUL_REPAIR_OUTCOMES
    return copy(
      criteria = changedCriteria,
      unresolvedQueue = queue,
      currentCriterionRef = current,
      pendingAuditRefs = pending,
      reservation = null,
      outcomes = outcomes + acceptedOutcome,
      unsuccessfulTotals = totals,
      mode = modeAfterOutcome(pending, queue),
      stage = stageAfterOutcome(blocked, outcome.successful, pending, queue),
      completedRepairEvidenceRef = null,
      admittedTreeDigest = null,
      admittedCheckpointId = null,
      sourceTransitions = sourceTransitionsAfter(outcome),
      revision = revision + 1,
    )
  }

  private fun modeAfterOutcome(
    pending: List<String>,
    queue: List<String>,
  ): AcceptanceCriterionRepairMode =
    if (queue.isEmpty() && pending.isEmpty()) {
      AcceptanceCriterionRepairMode.ACCEPTED
    } else {
      AcceptanceCriterionRepairMode.CURRENT_CRITERION_TARGETED
    }

  private fun stageAfterOutcome(
    blocked: Boolean,
    successful: Boolean,
    pending: List<String>,
    queue: List<String>,
  ): AcceptanceCriterionRepairStage =
    when {
      blocked -> AcceptanceCriterionRepairStage.BUDGET_BLOCKED
      successful && pending.isNotEmpty() -> AcceptanceCriterionRepairStage.READ_ONLY_AUDIT_REQUIRED
      queue.isEmpty() && pending.isEmpty() -> AcceptanceCriterionRepairStage.ACCEPTED
      else -> AcceptanceCriterionRepairStage.DISCOVERY_ADMITTED
    }

  private fun sourceTransitionsAfter(
    outcome: AcceptanceCriterionRepairOutcome,
  ): List<AcceptanceCriterionRepairSourceTransition> =
    if (outcome.successful) {
      sourceTransitions +
        AcceptanceCriterionRepairSourceTransition(
          criterionRef = outcome.criterionRef,
          revision = revision + 1,
          fromDisposition = AcceptanceCriterionSourceDisposition.SOURCE_UNRESOLVED,
          toDisposition = AcceptanceCriterionSourceDisposition.SOURCE_CLOSED,
          producingId = outcome.outcomeId,
          producingDigest = outcome.digest,
          evidenceRef = outcome.evidenceRef,
          treeDigest = admittedTreeDigest,
          checkpointId = admittedCheckpointId,
        )
    } else {
      sourceTransitions
    }

  private fun canRecord(outcome: AcceptanceCriterionRepairOutcome): Boolean {
    val live = reservation ?: return false
    val identityMatches = outcome.reservationId == live.reservationId && outcome.criterionRef == live.criterionRef
    val capacity = outcomes.size < MAX_REPAIR_OUTCOMES && outcomes.none { it.outcomeId == outcome.outcomeId }
    val historyCapacity = !outcome.successful || sourceTransitions.size < MAX_REPAIR_SOURCE_TRANSITIONS
    val reserved = stage == AcceptanceCriterionRepairStage.REPAIR_RESERVED
    val awaiting = stage == AcceptanceCriterionRepairStage.REPAIR_COMPLETED_AWAITING_TARGETED_VERIFICATION
    val allowedStage = if (outcome.targeted) awaiting else reserved || awaiting
    return identityMatches && capacity && historyCapacity && allowedStage
  }

  private fun criteriaAfter(outcome: AcceptanceCriterionRepairOutcome): List<AcceptanceCriterionRepairCriterion> {
    return criteria.map { criterion ->
      if (criterion.canonicalRef == outcome.criterionRef && outcome.successful) {
        criterion.copy(
          sourceDisposition = AcceptanceCriterionSourceDisposition.SOURCE_CLOSED,
          closureEvidenceRef = outcome.evidenceRef,
          closureTreeDigest = admittedTreeDigest,
          closureCheckpointId = admittedCheckpointId,
        )
      } else if (criterion.canonicalRef == outcome.criterionRef) {
        criterion.copy(evidenceRef = outcome.evidenceRef)
      } else {
        criterion
      }
    }
  }

  companion object {
    const val MAX_UNSUCCESSFUL_REPAIR_OUTCOMES: Int = 3
    const val MAX_DURABLE_UTF8_BYTES: Int = 12_582_912
    const val RESERVATION_UTF8_HEADROOM: Int = 65_536

    internal fun hasExhaustedUnresolvedCriterion(
      unresolvedQueue: List<String>,
      unsuccessfulTotals: Map<String, Int>,
    ): Boolean =
      unresolvedQueue.any {
        unsuccessfulTotals.getOrDefault(it, 0) >= MAX_UNSUCCESSFUL_REPAIR_OUTCOMES
      }

    fun restore(state: AcceptanceCriterionRepairState): AcceptanceCriterionRepairState? =
      state.takeIf { AcceptanceCriterionRepairCoherence.isCoherent(it) }

    fun admit(
      admission: AcceptanceCriterionRepairAdmission,
      mode: AcceptanceCriterionRepairMode = AcceptanceCriterionRepairMode.INITIAL_DISCOVERY,
    ): AcceptanceCriterionRepairState? {
      if (mode != AcceptanceCriterionRepairMode.INITIAL_DISCOVERY) return null
      if (!AcceptanceCriterionRepairCoherence.admissionIsCoherent(admission)) return null
      val criteria = admission.criteria
      val queue = stableQueue(criteria, admission.dependencies, criteria.associateBy { it.canonicalRef }) ?: return null
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
        revision = 0,
        mode =
          if (queue.isEmpty()) {
            AcceptanceCriterionRepairMode.ACCEPTED
          } else {
            AcceptanceCriterionRepairMode.INITIAL_DISCOVERY
          },
        stage =
          if (queue.isEmpty()) {
            AcceptanceCriterionRepairStage.ACCEPTED
          } else {
            AcceptanceCriterionRepairStage.DISCOVERY_ADMITTED
          },
        completedRepairEvidenceRef = null,
        admittedTreeDigest = null,
        admittedCheckpointId = null,
        sourceTransitions = emptyList(),
      )
    }

    internal fun stableQueue(
      criteria: List<AcceptanceCriterionRepairCriterion>,
      dependencies: List<AcceptanceCriterionRepairDependency>,
      byRef: Map<String, AcceptanceCriterionRepairCriterion>,
    ): List<String>? =
      (topologicalOrder(criteria, dependencies) ?: return null).filter {
        byRef.getValue(it).sourceDisposition == AcceptanceCriterionSourceDisposition.SOURCE_UNRESOLVED
      }

    internal fun topologicalOrder(
      criteria: List<AcceptanceCriterionRepairCriterion>,
      dependencies: List<AcceptanceCriterionRepairDependency>,
    ): List<String>? {
      val prerequisites = criteria.associate { it.canonicalRef to mutableSetOf<String>() }
      dependencies.forEach { prerequisites.getValue(it.dependentRef).add(it.prerequisiteRef) }
      val result = mutableListOf<String>()
      while (result.size < criteria.size) {
        val next =
          criteria.asSequence().filter { it.canonicalRef !in result }
            .filter { prerequisites.getValue(it.canonicalRef).all(result::contains) }
            .minByOrNull { it.ordinal } ?: return null
        result += next.canonicalRef
      }
      return result
    }

    internal const val MAX_CLARIFICATION_LENGTH: Int = 1024
    internal const val MAX_REPAIR_CRITERIA: Int = 256
    internal const val MAX_REPAIR_DEPENDENCIES: Int = 4096
    internal const val MAX_REPAIR_OUTCOMES: Int = 4096
    internal const val MAX_REPAIR_SOURCE_TRANSITIONS: Int = 16384
  }
}

internal fun String.isDigest(): Boolean = matches(Regex("^[a-fA-F0-9]{64}$"))

private val AcceptanceCriterionRepairOutcome.targeted: Boolean get() =
  kind == AcceptanceCriterionRepairOutcomeKind.TARGETED_SUCCESS ||
    kind == AcceptanceCriterionRepairOutcomeKind.TARGETED_FAILURE

private fun AcceptanceCriterionRepairOutcome.isValidRepairOutcome(): Boolean =
  outcomeId.isNotBlank() && digest.isDigest() && evidenceRef.isNotBlank()

private fun AcceptanceCriterionRepairOutcome.replays(existing: AcceptanceCriterionRepairOutcome): Boolean =
  existing ==
    copy(
      verifiedRevision = verifiedRevision ?: existing.verifiedRevision,
      treeDigest = treeDigest ?: existing.treeDigest,
      checkpointId = checkpointId ?: existing.checkpointId,
      auditBindingDigest = auditBindingDigest ?: existing.auditBindingDigest,
    )

private fun AcceptanceCriterionRepairOutcome.boundTo(
  state: AcceptanceCriterionRepairState,
): AcceptanceCriterionRepairOutcome =
  if (targeted) {
    copy(
      verifiedRevision = verifiedRevision ?: state.revision,
      treeDigest = treeDigest ?: state.admittedTreeDigest,
      checkpointId = checkpointId ?: state.admittedCheckpointId,
    )
  } else {
    copy(verifiedRevision = verifiedRevision ?: state.revision)
  }

private fun AcceptanceCriterionRepairOutcome.matchesVerification(state: AcceptanceCriterionRepairState): Boolean =
  verifiedRevision == state.revision && (
    !targeted || (
      treeDigest == state.admittedTreeDigest &&
        checkpointId == state.admittedCheckpointId
    )
  )
