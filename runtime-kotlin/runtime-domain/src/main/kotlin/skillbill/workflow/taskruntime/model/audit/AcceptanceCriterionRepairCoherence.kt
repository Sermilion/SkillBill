package skillbill.workflow.taskruntime.model.audit

internal object AcceptanceCriterionRepairCoherence {
  fun admissionIsCoherent(admission: AcceptanceCriterionRepairAdmission): Boolean {
    val identity =
      admission.catalogId.isNotBlank() && admission.executionId.isNotBlank() &&
        admission.specDigest.isDigest()
    return identity && criteriaAreCoherent(admission)
  }

  private fun criteriaAreCoherent(admission: AcceptanceCriterionRepairAdmission): Boolean {
    val criteria = admission.criteria
    val bounded = criteria.size in 1..AcceptanceCriterionRepairState.MAX_REPAIR_CRITERIA
    val unique =
      criteria.map { it.canonicalRef }.distinct().size == criteria.size &&
        criteria.map { it.ordinal }.distinct().size == criteria.size
    if (!bounded || !unique || admission.authoredCriteria.size != criteria.size) return false
    val byOrdinal = criteria.associateBy { it.ordinal }
    return admission.authoredCriteria.withIndex().all { (index, text) ->
      val criterion = byOrdinal[index + 1]
      val identity = acceptanceCriterionIdentity(text, index + 1)
      criterion != null && identity != null && authoredIdentityMatches(criterion, text, identity.canonicalRef)
    }
  }

  private fun authoredIdentityMatches(
    criterion: AcceptanceCriterionRepairCriterion,
    text: String,
    canonicalRef: String,
  ): Boolean {
    val identity =
      criterion.originalText == text && criterion.canonicalRef == canonicalRef &&
        criterion.originalAlias == originalAcceptanceCriterionAlias(text)
    return identity && text.isNotBlank() && criterion.evidenceRef.isNotBlank()
  }

  fun isCoherent(state: AcceptanceCriterionRepairState): Boolean =
    catalogIsCoherent(state) && clarificationIsCoherent(state) && progressIsCoherent(state) &&
      outcomesAreCoherent(state) && historyIsCoherent(state)

  private fun clarificationIsCoherent(state: AcceptanceCriterionRepairState): Boolean =
    state.pendingClarificationQuestion?.let {
      it.isNotBlank() && it.length <= AcceptanceCriterionRepairState.MAX_CLARIFICATION_LENGTH
    } ?: true

  private fun catalogIsCoherent(state: AcceptanceCriterionRepairState): Boolean {
    val admission =
      AcceptanceCriterionRepairAdmission(
        state.catalogId,
        state.executionId,
        state.specDigest,
        state.criteria.sortedBy { it.ordinal }.map { it.originalText },
        state.criteria,
        state.dependencies,
      )
    if (!admissionIsCoherent(admission)) return false
    val queue = AcceptanceCriterionRepairState.stableQueue(state.criteria)
    return state.revision >= 0 && state.unresolvedQueue == queue &&
      state.currentCriterionRef == (state.pendingAuditRefs.firstOrNull() ?: state.unresolvedQueue.firstOrNull()) &&
      state.pendingAuditRefs ==
      state.criteria.sortedBy {
        it.ordinal
      }.map { it.canonicalRef }.filter { it in state.pendingAuditRefs }
  }

  private fun progressIsCoherent(state: AcceptanceCriterionRepairState): Boolean {
    val reserved =
      state.stage == AcceptanceCriterionRepairStage.REPAIR_RESERVED ||
        state.stage == AcceptanceCriterionRepairStage.REPAIR_COMPLETED_AWAITING_TARGETED_VERIFICATION
    val awaiting = state.stage == AcceptanceCriterionRepairStage.REPAIR_COMPLETED_AWAITING_TARGETED_VERIFICATION
    val hasEvidence =
      state.completedRepairEvidenceRef != null || state.admittedTreeDigest != null ||
        state.admittedCheckpointId != null
    if (!awaiting && hasEvidence) return false
    if (reserved != (state.reservation != null)) return false
    val finalFlag = state.finalAuditRequired == (state.stage == AcceptanceCriterionRepairStage.FINAL_AUDIT_REQUIRED)
    return finalFlag && stageIsCoherent(state) && reservationIsCoherent(state)
  }

  private fun stageIsCoherent(state: AcceptanceCriterionRepairState): Boolean =
    with(state) {
      when (stage) {
        AcceptanceCriterionRepairStage.DISCOVERY_ADMITTED -> discoveryIsCoherent(state)
        AcceptanceCriterionRepairStage.READ_ONLY_AUDIT_REQUIRED ->
          pendingAuditRefs.isNotEmpty() && mode != AcceptanceCriterionRepairMode.FINAL_FULL
        AcceptanceCriterionRepairStage.REPAIR_RESERVED ->
          mode == AcceptanceCriterionRepairMode.CURRENT_CRITERION_TARGETED &&
            !AcceptanceCriterionRepairState.hasExhaustedUnresolvedCriterion(unresolvedQueue, unsuccessfulTotals)
        AcceptanceCriterionRepairStage.REPAIR_COMPLETED_AWAITING_TARGETED_VERIFICATION ->
          awaitingIsCoherent(state) &&
            !AcceptanceCriterionRepairState.hasExhaustedUnresolvedCriterion(unresolvedQueue, unsuccessfulTotals)
        AcceptanceCriterionRepairStage.FINAL_AUDIT_REQUIRED ->
          finalQueueIsCoherent(state)
        AcceptanceCriterionRepairStage.FINAL_ACCEPTED ->
          finalAcceptanceIsCoherent(state)
        AcceptanceCriterionRepairStage.BUDGET_BLOCKED ->
          mode == AcceptanceCriterionRepairMode.CURRENT_CRITERION_TARGETED && currentCriterionRef != null &&
            AcceptanceCriterionRepairState.hasExhaustedUnresolvedCriterion(unresolvedQueue, unsuccessfulTotals)
      }
    }

  private fun finalAcceptanceIsCoherent(state: AcceptanceCriterionRepairState): Boolean =
    finalQueueIsCoherent(state) && state.finalAudits.lastOrNull()?.digest == state.finalAuditDigest &&
      state.finalAuditDigest != null

  private fun finalQueueIsCoherent(state: AcceptanceCriterionRepairState): Boolean =
    state.mode == AcceptanceCriterionRepairMode.FINAL_FULL && state.unresolvedQueue.isEmpty() &&
      state.pendingAuditRefs.isEmpty()

  private fun discoveryIsCoherent(state: AcceptanceCriterionRepairState): Boolean {
    val mode =
      state.mode == AcceptanceCriterionRepairMode.CURRENT_CRITERION_TARGETED ||
        state.mode == AcceptanceCriterionRepairMode.INITIAL_DISCOVERY &&
        state.proseAudits.isEmpty() && state.reservation == null && state.outcomes.isEmpty()
    val budget =
      !AcceptanceCriterionRepairState.hasExhaustedUnresolvedCriterion(
        state.unresolvedQueue,
        state.unsuccessfulTotals,
      )
    return mode && budget && state.unresolvedQueue.isNotEmpty()
  }

  private fun awaitingIsCoherent(state: AcceptanceCriterionRepairState): Boolean {
    val evidence = !state.completedRepairEvidenceRef.isNullOrBlank() && !state.admittedCheckpointId.isNullOrBlank()
    return state.mode == AcceptanceCriterionRepairMode.CURRENT_CRITERION_TARGETED && evidence &&
      state.admittedTreeDigest?.isDigest() == true
  }

  private fun reservationIsCoherent(state: AcceptanceCriterionRepairState): Boolean {
    val live = state.reservation ?: return true
    val identity = live.criterionRef == state.currentCriterionRef && live.reservationId.isNotBlank()
    val revision = live.revision in 1..state.revision && live.stageRevision in live.revision..state.revision
    val unspent =
      state.outcomes.none { it.reservationId == live.reservationId } &&
        state.unsuccessfulTotals.getOrDefault(
          live.criterionRef,
          0,
        ) < AcceptanceCriterionRepairState.MAX_UNSUCCESSFUL_REPAIR_OUTCOMES
    val capacity =
      state.outcomes.size < AcceptanceCriterionRepairState.MAX_REPAIR_OUTCOMES &&
        state.sourceTransitions.size < AcceptanceCriterionRepairState.MAX_REPAIR_SOURCE_TRANSITIONS
    return identity && revision && unspent && capacity
  }

  private fun outcomesAreCoherent(state: AcceptanceCriterionRepairState): Boolean {
    val outcomes = state.outcomes
    val unique =
      outcomes.map { it.reservationId }.distinct().size == outcomes.size &&
        outcomes.map { it.outcomeId }.distinct().size == outcomes.size
    val bounded = outcomes.size <= AcceptanceCriterionRepairState.MAX_REPAIR_OUTCOMES
    if (!unique || !bounded || !outcomes.all { outcomeIsCoherent(it, state) }) return false
    val refs = state.criteria.map { it.canonicalRef }.toSet()
    val totals = state.unsuccessfulTotals
    val validTotals =
      totals.all {
          (ref, total) ->
        ref in refs && total in 0..AcceptanceCriterionRepairState.MAX_UNSUCCESSFUL_REPAIR_OUTCOMES
      }
    return validTotals && outcomes.filterNot {
      it.successful
    }.groupingBy { it.criterionRef }.eachCount() == totals.filterValues { it > 0 }
  }

  private fun outcomeIsCoherent(
    outcome: AcceptanceCriterionRepairOutcome,
    state: AcceptanceCriterionRepairState,
  ): Boolean {
    val identity =
      outcome.outcomeId.isNotBlank() && outcome.reservationId.isNotBlank() &&
        state.criteria.any { it.canonicalRef == outcome.criterionRef }
    val evidence = outcome.digest.isDigest() && outcome.evidenceRef.isNotBlank()
    val targeted =
      outcome.kind == AcceptanceCriterionRepairOutcomeKind.TARGETED_SUCCESS ||
        outcome.kind == AcceptanceCriterionRepairOutcomeKind.TARGETED_FAILURE
    val verified =
      outcome.verifiedRevision?.let { it in 1 until state.revision } == true &&
        outcome.treeDigest?.isDigest() == true && !outcome.checkpointId.isNullOrBlank()
    return identity && evidence && (!targeted || verified)
  }

  private fun historyIsCoherent(state: AcceptanceCriterionRepairState): Boolean =
    transitionsAreCoherent(
      state,
    ) && auditsAreCoherent(state) && proseAuditsAreCoherent(state) && currentSourcesAreCoherent(state)

  private fun transitionsAreCoherent(state: AcceptanceCriterionRepairState): Boolean {
    val history = state.sourceTransitions
    val bounded = history.size <= AcceptanceCriterionRepairState.MAX_REPAIR_SOURCE_TRANSITIONS
    val unique = history.map { it.revision to it.criterionRef }.distinct().size == history.size
    if (!bounded || !unique || !history.all { transitionIsCoherent(it, state) }) return false
    val closures =
      history.filter {
        it.toDisposition == AcceptanceCriterionSourceDisposition.SOURCE_CLOSED
      }.map { it.producingId }.toSet()
    val repairClosures = closures - state.proseAudits.map { it.id }.toSet()
    return repairClosures == state.outcomes.filter { it.successful }.map { it.outcomeId }.toSet()
  }

  private fun transitionIsCoherent(
    transition: AcceptanceCriterionRepairSourceTransition,
    state: AcceptanceCriterionRepairState,
  ): Boolean {
    val identity =
      transition.revision in 1..state.revision &&
        state.criteria.any {
          it.canonicalRef == transition.criterionRef
        }
    return identity &&
      when (transition.toDisposition) {
        AcceptanceCriterionSourceDisposition.SOURCE_CLOSED -> closureIsCoherent(transition, state)
        AcceptanceCriterionSourceDisposition.SOURCE_UNRESOLVED -> reopeningIsCoherent(transition, state)
        AcceptanceCriterionSourceDisposition.NOT_APPLICABLE -> false
      }
  }

  private fun closureIsCoherent(
    t: AcceptanceCriterionRepairSourceTransition,
    state: AcceptanceCriterionRepairState,
  ): Boolean {
    val prose = state.proseAudits.singleOrNull { it.id == t.producingId }
    if (prose != null) {
      return prose.satisfied && proseTransitionMatches(t, prose) &&
        t.fromDisposition == AcceptanceCriterionSourceDisposition.SOURCE_UNRESOLVED
    }
    val outcome = state.outcomes.singleOrNull { it.outcomeId == t.producingId } ?: return false
    val producer = outcome.successful && outcome.criterionRef == t.criterionRef && outcome.digest == t.producingDigest
    val evidence =
      outcome.evidenceRef == t.evidenceRef && outcome.treeDigest == t.treeDigest &&
        outcome.checkpointId == t.checkpointId
    return producer && evidence &&
      t.fromDisposition == AcceptanceCriterionSourceDisposition.SOURCE_UNRESOLVED && t.revision ==
      outcome.verifiedRevision?.plus(
        1,
      )
  }

  private fun reopeningIsCoherent(
    t: AcceptanceCriterionRepairSourceTransition,
    state: AcceptanceCriterionRepairState,
  ): Boolean {
    val prose = state.proseAudits.singleOrNull { it.id == t.producingId }
    if (prose != null) {
      return !prose.satisfied && proseTransitionMatches(t, prose) &&
        t.fromDisposition == AcceptanceCriterionSourceDisposition.SOURCE_CLOSED
    }
    val audit = state.finalAudits.singleOrNull { it.digest == t.producingId } ?: return false
    val producer =
      t.criterionRef in audit.regressedRefs && t.producingDigest == audit.digest &&
        t.revision == audit.revision
    val evidence =
      t.treeDigest == audit.treeDigest && t.checkpointId == audit.checkpointId &&
        t.evidenceRef == audit.digest
    return producer && evidence && t.fromDisposition == AcceptanceCriterionSourceDisposition.SOURCE_CLOSED
  }

  private fun proseTransitionMatches(
    t: AcceptanceCriterionRepairSourceTransition,
    audit: AcceptanceCriterionProseAudit,
  ): Boolean =
    t.criterionRef == audit.criterionRef && t.revision == audit.revision && t.producingDigest == audit.digest &&
      t.evidenceRef == audit.prose && t.treeDigest == audit.treeDigest && t.checkpointId == audit.checkpointId

  private fun proseAuditsAreCoherent(state: AcceptanceCriterionRepairState): Boolean =
    state.proseAudits.size <= AcceptanceCriterionRepairState.MAX_REPAIR_OUTCOMES &&
      state.proseAudits.map { it.id }.distinct().size == state.proseAudits.size &&
      state.proseAudits.all { audit ->
        audit.id.isNotBlank() && audit.revision in 1..state.revision && audit.prose.isNotBlank() &&
          audit.digest.isDigest() && audit.treeDigest.isDigest() && audit.checkpointId.isNotBlank() &&
          (audit.criterionRef == null || state.criteria.any { it.canonicalRef == audit.criterionRef })
      }

  private fun auditsAreCoherent(state: AcceptanceCriterionRepairState): Boolean {
    val history = state.finalAudits
    val bounded = history.size <= AcceptanceCriterionRepairState.MAX_REPAIR_FINAL_AUDITS
    val unique =
      history.map {
        it.digest
      }.distinct().size == history.size && history.map { it.revision }.distinct().size == history.size
    val ordered = history.map { it.revision } == history.map { it.revision }.sorted()
    return bounded && unique && ordered && history.all { auditIsCoherent(it, state) }
  }

  private fun auditIsCoherent(
    audit: AcceptanceCriterionRepairFinalAudit,
    state: AcceptanceCriterionRepairState,
  ): Boolean {
    val identity =
      audit.catalogId == state.catalogId && audit.executionId == state.executionId &&
        audit.specDigest == state.specDigest
    val refs =
      state.criteria.filter {
        it.sourceDisposition != AcceptanceCriterionSourceDisposition.NOT_APPLICABLE
      }.map { it.canonicalRef }.sorted()
    val coverage =
      audit.auditedRefs == refs && audit.regressedRefs.distinct().size == audit.regressedRefs.size &&
        refs.containsAll(
          audit.regressedRefs,
        )
    val evidence =
      audit.revision in 1..state.revision && audit.digest.isDigest() &&
        audit.treeDigest.isDigest() && audit.checkpointId.isNotBlank()
    val reopened =
      audit.regressedRefs.all {
          ref ->
        state.sourceTransitions.any {
          it.criterionRef == ref && it.revision == audit.revision && it.producingId == audit.digest
        }
      }
    return identity && coverage && evidence && reopened
  }

  private fun currentSourcesAreCoherent(state: AcceptanceCriterionRepairState): Boolean =
    state.criteria.all { criterion ->
      val history =
        state.sourceTransitions.filter { it.criterionRef == criterion.canonicalRef }.sortedBy { it.revision }
      val chain = history.zipWithNext().all { (before, after) -> before.toDisposition == after.fromDisposition }
      val latest = history.lastOrNull()
      chain && (latest == null || latestSourceMatches(latest, criterion))
    }

  private fun latestSourceMatches(
    t: AcceptanceCriterionRepairSourceTransition,
    criterion: AcceptanceCriterionRepairCriterion,
  ): Boolean {
    val evidence =
      t.evidenceRef == criterion.closureEvidenceRef &&
        t.treeDigest == criterion.closureTreeDigest && t.checkpointId == criterion.closureCheckpointId
    return t.toDisposition == criterion.sourceDisposition && (
      t.toDisposition != AcceptanceCriterionSourceDisposition.SOURCE_CLOSED || evidence
    )
  }
}
