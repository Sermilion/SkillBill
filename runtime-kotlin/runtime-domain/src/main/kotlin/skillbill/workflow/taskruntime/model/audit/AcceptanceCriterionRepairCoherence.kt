package skillbill.workflow.taskruntime.model.audit

import skillbill.workflow.model.repair.FocusedRepairStrategy

internal object AcceptanceCriterionRepairCoherence {
  private val SCENARIO_REF = Regex("""^(IS-\d{3}|AC-\d{3}\+AC-\d{3})$""")
  private val CRITERION_REF = Regex("""^AC-\d{3}$""")

  fun admissionIsCoherent(admission: AcceptanceCriterionRepairAdmission): Boolean {
    val identity =
      admission.catalogId.isNotBlank() && admission.executionId.isNotBlank() &&
        admission.specDigest.isDigest()
    return identity && criteriaAreCoherent(admission) && dependenciesAreCoherent(admission)
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

  private fun dependenciesAreCoherent(admission: AcceptanceCriterionRepairAdmission): Boolean {
    val edges = admission.dependencies
    val byRef = admission.criteria.associateBy { it.canonicalRef }
    val bounded = edges.size <= AcceptanceCriterionRepairState.MAX_REPAIR_DEPENDENCIES
    val unique = edges.map { it.dependentRef to it.prerequisiteRef }.distinct().size == edges.size
    if (!bounded || !unique || !edges.all { edgeIsCoherent(it, byRef) }) return false
    return AcceptanceCriterionRepairState.topologicalOrder(admission.criteria, edges) != null
  }

  private fun edgeIsCoherent(
    edge: AcceptanceCriterionRepairDependency,
    criteria: Map<String, AcceptanceCriterionRepairCriterion>,
  ): Boolean {
    val prerequisite = criteria[edge.prerequisiteRef] ?: return false
    val identity = edge.dependentRef in criteria && edge.dependentRef != edge.prerequisiteRef
    val evidence =
      edge.reason.isNotBlank() && edge.declaringOutputRef.isNotBlank() &&
        edge.sourceEvidenceRef.isNotBlank()
    val available =
      !isUnavailableValidationPrerequisite(
        prerequisite.sourceDisposition,
        prerequisite.validationObligation,
        prerequisite.validationOwnerAvailable,
      )
    return identity && evidence && available
  }

  fun isCoherent(state: AcceptanceCriterionRepairState): Boolean =
    catalogIsCoherent(
      state,
    ) && discoverySnapshotsAreCoherent(state) && gapsAreCoherent(state) && progressIsCoherent(state) &&
      outcomesAreCoherent(state) && historyIsCoherent(state)

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
    val queue =
      AcceptanceCriterionRepairState.stableQueue(
        state.criteria,
        state.dependencies,
        state.criteria.associateBy { it.canonicalRef },
      )
    val pending = state.pendingAuditRefs
    val pendingIsCoherent =
      pending.distinct().size == pending.size &&
        pending.all { ref ->
          state.criteria.any { it.canonicalRef == ref }
        }
    return state.revision >= 0 && state.unresolvedQueue == queue && pendingIsCoherent &&
      state.currentCriterionRef == (pending.firstOrNull() ?: state.unresolvedQueue.firstOrNull())
  }

  private fun progressIsCoherent(state: AcceptanceCriterionRepairState): Boolean {
    val reserved =
      state.stage == AcceptanceCriterionRepairStage.REPAIR_RESERVED ||
        state.stage == AcceptanceCriterionRepairStage.REPAIR_COMPLETED_AWAITING_TARGETED_VERIFICATION
    val awaiting = state.stage == AcceptanceCriterionRepairStage.REPAIR_COMPLETED_AWAITING_TARGETED_VERIFICATION
    val readonly = state.stage == AcceptanceCriterionRepairStage.READ_ONLY_AUDIT_REQUIRED
    val hasEvidence =
      state.completedRepairEvidenceRef != null || state.admittedTreeDigest != null ||
        state.admittedCheckpointId != null
    if (!awaiting && hasEvidence) return false
    if (reserved != (state.reservation != null)) return false
    if (readonly && (state.reservation != null || state.pendingAuditRefs.isEmpty())) return false
    return stageIsCoherent(state) && reservationIsCoherent(state) && interactionTraceIsCoherent(state)
  }

  private fun stageIsCoherent(state: AcceptanceCriterionRepairState): Boolean =
    when (state.stage) {
      AcceptanceCriterionRepairStage.DISCOVERY_ADMITTED -> discoveryIsCoherent(state)
      AcceptanceCriterionRepairStage.READ_ONLY_AUDIT_REQUIRED -> readOnlyAuditIsRequired(state)
      AcceptanceCriterionRepairStage.REPAIR_RESERVED -> reservedIsCoherent(state)
      AcceptanceCriterionRepairStage.REPAIR_COMPLETED_AWAITING_TARGETED_VERIFICATION -> awaitingStageIsCoherent(state)
      AcceptanceCriterionRepairStage.ACCEPTED -> acceptedIsCoherent(state)
      AcceptanceCriterionRepairStage.BUDGET_BLOCKED -> budgetIsBlocked(state)
      AcceptanceCriterionRepairStage.INTERACTION_TRACE_REQUIRED -> acceptedIsCoherent(state)
    }

  private fun interactionTraceIsCoherent(state: AcceptanceCriterionRepairState): Boolean {
    val trace = state.interactionTrace
    val scenarioLists =
      listOf(
        trace.pendingScenarioRefs,
        trace.tracedScenarioRefs,
        trace.brokenScenarioRefs,
        trace.repairedScenarioRefs,
        trace.unknownScenarioRefs,
      )
    val wellFormed =
      refsAreWellFormed(scenarioLists, SCENARIO_REF) &&
        refsAreWellFormed(listOf(trace.recheckRefs, trace.recheckAnyChangeRefs), CRITERION_REF) &&
        trace.recheckRefs.intersect(trace.recheckAnyChangeRefs.toSet()).isEmpty()
    val digest = trace.tracedSpecDigest?.isDigest() != false
    val awaiting = state.stage == AcceptanceCriterionRepairStage.INTERACTION_TRACE_REQUIRED
    val pendingWork = trace.pendingScenarioRefs.isNotEmpty() || trace.pendingRechecks.isNotEmpty()
    val staged = if (awaiting) pendingWork else trace.pendingScenarioRefs.isEmpty()
    return wellFormed && digest && staged
  }

  private fun refsAreWellFormed(
    lists: List<List<String>>,
    pattern: Regex,
  ): Boolean =
    lists.all { refs ->
      refs.size <= AcceptanceCriterionInteractionTrace.MAX_SCENARIO_REFS && refs.distinct().size == refs.size &&
        refs.all(pattern::matches)
    }

  private fun reservedIsCoherent(state: AcceptanceCriterionRepairState): Boolean =
    state.mode == AcceptanceCriterionRepairMode.CURRENT_CRITERION_TARGETED && !hasExhaustedBudget(state)

  private fun awaitingStageIsCoherent(state: AcceptanceCriterionRepairState): Boolean =
    awaitingIsCoherent(state) && !hasExhaustedBudget(state)

  private fun acceptedIsCoherent(state: AcceptanceCriterionRepairState): Boolean =
    state.mode == AcceptanceCriterionRepairMode.ACCEPTED && state.unresolvedQueue.isEmpty() &&
      state.pendingAuditRefs.isEmpty()

  private fun budgetIsBlocked(state: AcceptanceCriterionRepairState): Boolean =
    state.mode == AcceptanceCriterionRepairMode.CURRENT_CRITERION_TARGETED && state.currentCriterionRef != null &&
      state.chargedAttempts.getOrDefault(state.currentCriterionRef, 0) ==
      FocusedRepairStrategy.MAX_UNSUCCESSFUL_OUTCOMES && hasExhaustedBudget(state)

  private fun readOnlyAuditIsRequired(state: AcceptanceCriterionRepairState): Boolean =
    state.mode == AcceptanceCriterionRepairMode.CURRENT_CRITERION_TARGETED &&
      state.currentCriterionRef == state.pendingAuditRefs.firstOrNull()

  private fun hasExhaustedBudget(state: AcceptanceCriterionRepairState): Boolean =
    AcceptanceCriterionRepairState.hasExhaustedUnresolvedCriterion(state.unresolvedQueue, state.chargedAttempts)

  private fun discoveryIsCoherent(state: AcceptanceCriterionRepairState): Boolean {
    val mode =
      state.mode == AcceptanceCriterionRepairMode.CURRENT_CRITERION_TARGETED ||
        state.mode == AcceptanceCriterionRepairMode.INITIAL_DISCOVERY && hasNoRepairHistory(state)
    val budget = !hasExhaustedBudget(state)
    return mode && budget && state.unresolvedQueue.isNotEmpty()
  }

  private fun hasNoRepairHistory(state: AcceptanceCriterionRepairState): Boolean =
    state.outcomes.isEmpty() && state.proseAudits.isEmpty() && state.sourceTransitions.isEmpty() &&
      state.pendingAuditRefs.isEmpty()

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
        state.chargedAttempts.getOrDefault(
          live.criterionRef,
          0,
        ) < FocusedRepairStrategy.MAX_UNSUCCESSFUL_OUTCOMES
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
    val totals = state.chargedAttempts
    val validTotals =
      totals.all {
          (ref, total) ->
        ref in refs && total in 0..FocusedRepairStrategy.MAX_UNSUCCESSFUL_OUTCOMES
      }
    val chargedOutcomes = outcomes.filter { it.charged }.groupingBy { it.criterionRef }.eachCount()
    val attemptsAreBacked = totals.all { (ref, count) -> count <= chargedOutcomes.getOrDefault(ref, 0) }
    val escalationIsConsistent =
      state.criteria.all { criterion ->
        totals.getOrDefault(criterion.canonicalRef, 0) < 2 || criterion.escalated
      }
    return validTotals && attemptsAreBacked && escalationIsConsistent
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
    return identity && evidence && outcomeAccountingIsCoherent(outcome) && (!targeted || verified)
  }

  private fun outcomeAccountingIsCoherent(outcome: AcceptanceCriterionRepairOutcome): Boolean =
    (!outcome.successful || !outcome.charged) &&
      outcome.openGapsBefore in 0..AcceptanceCriterionGapLimits.MAX_GAPS_PER_CRITERION &&
      outcome.openGapsAfter in 0..AcceptanceCriterionGapLimits.MAX_GAPS_PER_CRITERION &&
      (!outcome.successful || outcome.openGapsAfter == 0)

  private fun historyIsCoherent(state: AcceptanceCriterionRepairState): Boolean =
    transitionsAreCoherent(state) && currentSourcesAreCoherent(state)

  private fun transitionsAreCoherent(state: AcceptanceCriterionRepairState): Boolean {
    val history = state.sourceTransitions
    val bounded = history.size <= AcceptanceCriterionRepairState.MAX_REPAIR_SOURCE_TRANSITIONS
    val unique = history.map { it.revision to it.criterionRef }.distinct().size == history.size
    if (!bounded || !unique || !history.all { transitionIsCoherent(it, state) }) return false
    val criteria = state.criteria.associateBy { it.canonicalRef }
    val chainsAreCoherent =
      history.groupBy { it.criterionRef }.all { (ref, transitions) ->
        val criterion = criteria[ref] ?: return@all false
        transitions.sortedBy { it.revision }.zipWithNext().all { (before, after) ->
          before.toDisposition == after.fromDisposition ||
            discoveryBridgeIsCoherent(before, after, criterion, state)
        }
      }
    if (!chainsAreCoherent) return false
    val outcomeClosures =
      history.filter {
        it.toDisposition == AcceptanceCriterionSourceDisposition.SOURCE_CLOSED
      }.map { it.producingId }.toSet()
    val recordedClosures =
      (
        state.outcomes.filter { it.successful }.map { it.outcomeId } +
          state.proseAudits.filter { audit ->
            audit.satisfied && audit.criterionRef != null && outcomeClosures.contains(audit.id)
          }.map { it.id }
      ).toSet()
    return outcomeClosures == recordedClosures
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
    val unresolved = t.fromDisposition == AcceptanceCriterionSourceDisposition.SOURCE_UNRESOLVED
    val reaffirmed = t.fromDisposition == AcceptanceCriterionSourceDisposition.SOURCE_CLOSED
    return if (reaffirmed) {
      proseClosureIsCoherent(t, state)
    } else {
      unresolved && (outcomeClosureIsCoherent(t, state) || proseClosureIsCoherent(t, state))
    }
  }

  private fun outcomeClosureIsCoherent(
    transition: AcceptanceCriterionRepairSourceTransition,
    state: AcceptanceCriterionRepairState,
  ): Boolean =
    state.outcomes.singleOrNull { it.outcomeId == transition.producingId }?.let {
      it.successful && it.criterionRef == transition.criterionRef && it.digest == transition.producingDigest &&
        it.evidenceRef == transition.evidenceRef && it.treeDigest == transition.treeDigest &&
        it.checkpointId == transition.checkpointId && transition.revision == it.verifiedRevision?.plus(1)
    } == true

  private fun proseClosureIsCoherent(
    transition: AcceptanceCriterionRepairSourceTransition,
    state: AcceptanceCriterionRepairState,
  ): Boolean =
    state.proseAudits.singleOrNull { it.id == transition.producingId }?.let {
      it.satisfied && it.criterionRef == transition.criterionRef && it.digest == transition.producingDigest &&
        it.prose == transition.evidenceRef && it.treeDigest == transition.treeDigest &&
        it.checkpointId == transition.checkpointId && it.revision == transition.revision
    } == true

  private fun reopeningIsCoherent(
    t: AcceptanceCriterionRepairSourceTransition,
    state: AcceptanceCriterionRepairState,
  ): Boolean {
    val closed = t.fromDisposition == AcceptanceCriterionSourceDisposition.SOURCE_CLOSED
    return closed && proseAuditReopeningIsCoherent(t, state)
  }

  private fun proseAuditReopeningIsCoherent(
    transition: AcceptanceCriterionRepairSourceTransition,
    state: AcceptanceCriterionRepairState,
  ): Boolean =
    state.proseAudits.singleOrNull { it.id == transition.producingId }?.let {
      !it.satisfied && it.criterionRef == transition.criterionRef && transition.producingDigest == it.digest &&
        transition.revision == it.revision && transition.treeDigest == it.treeDigest &&
        transition.checkpointId == it.checkpointId && transition.evidenceRef == it.prose
    } == true

  private fun currentSourcesAreCoherent(state: AcceptanceCriterionRepairState): Boolean =
    state.criteria.all { criterion ->
      val history =
        state.sourceTransitions.filter { it.criterionRef == criterion.canonicalRef }.sortedBy { it.revision }
      val latest = history.lastOrNull()
      val discoveryClosure = discoveryClosureMatching(criterion, latest?.revision ?: 0, state)
      when {
        discoveryClosure != null ->
          criterion.sourceDisposition == AcceptanceCriterionSourceDisposition.SOURCE_CLOSED &&
            discoveryClosureMatches(discoveryClosure, criterion)
        else -> latest == null || latestSourceMatches(latest, criterion)
      }
    }

  private fun discoveryBridgeIsCoherent(
    before: AcceptanceCriterionRepairSourceTransition,
    after: AcceptanceCriterionRepairSourceTransition,
    criterion: AcceptanceCriterionRepairCriterion,
    state: AcceptanceCriterionRepairState,
  ): Boolean =
    before.toDisposition == AcceptanceCriterionSourceDisposition.SOURCE_UNRESOLVED &&
      after.fromDisposition == AcceptanceCriterionSourceDisposition.SOURCE_CLOSED &&
      discoveryClosureAfter(criterion, before.revision, state, after.revision) != null

  private fun discoveryClosureAfter(
    criterion: AcceptanceCriterionRepairCriterion,
    revision: Long,
    state: AcceptanceCriterionRepairState,
    beforeRevision: Long = Long.MAX_VALUE,
  ): AcceptanceCriterionProseAudit? =
    discoveryAudits(criterion, revision, state, beforeRevision).maxByOrNull { it.revision }

  private fun discoveryClosureMatching(
    criterion: AcceptanceCriterionRepairCriterion,
    revision: Long,
    state: AcceptanceCriterionRepairState,
  ): AcceptanceCriterionProseAudit? =
    discoveryAudits(criterion, revision, state).filter { discoveryClosureMatches(it, criterion) }
      .maxByOrNull { it.revision }

  private fun discoveryAudits(
    criterion: AcceptanceCriterionRepairCriterion,
    revision: Long,
    state: AcceptanceCriterionRepairState,
    beforeRevision: Long = Long.MAX_VALUE,
  ): Sequence<AcceptanceCriterionProseAudit> =
    state.proseAudits.asSequence()
      .filter {
        it.criterionRef == null && it.id.isNotBlank() && it.revision in 1..state.revision &&
          it.revision > revision && it.revision < beforeRevision &&
          state.proseAudits.count { audit -> audit.id == it.id } == 1
      }
      .filter { audit ->
        audit.discoverySnapshot?.let { snapshot ->
          criterion.canonicalRef in snapshot.listedCriterionRefs &&
            criterion.canonicalRef !in snapshot.unresolvedCriterionRefs
        } ?: (openCriteriaNamedIn(audit.prose)?.contains(criterion.canonicalRef) == false)
      }
      .filter {
        it.digest.isDigest() && it.treeDigest.isDigest() && it.checkpointId.isNotBlank() && it.prose.isNotBlank()
      }

  private fun discoveryClosureMatches(
    audit: AcceptanceCriterionProseAudit,
    criterion: AcceptanceCriterionRepairCriterion,
  ): Boolean =
    criterion.closureEvidenceRef == audit.prose && criterion.closureTreeDigest == audit.treeDigest &&
      criterion.closureCheckpointId == audit.checkpointId

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

private fun discoverySnapshotsAreCoherent(state: AcceptanceCriterionRepairState): Boolean {
  val refs = state.criteria.mapTo(hashSetOf()) { it.canonicalRef }
  val byRef = state.criteria.associateBy { it.canonicalRef }
  val queueOrder =
    AcceptanceCriterionRepairState.topologicalOrder(state.criteria, state.dependencies)
      ?.withIndex()
      ?.associate { it.value to it.index }
      ?: return false
  return state.proseAudits.all { audit ->
    audit.discoverySnapshot?.let { snapshot ->
      val listed = snapshot.listedCriterionRefs
      val unresolved = snapshot.unresolvedCriterionRefs
      audit.criterionRef == null && listed.size <= state.criteria.size &&
        unresolved.size <= state.criteria.size && listed.distinct().size == listed.size &&
        unresolved.distinct().size == unresolved.size && listed.all { it in refs } &&
        unresolved.all { it in refs } &&
        listed == listed.sortedBy { byRef.getValue(it).ordinal } &&
        unresolved == unresolved.sortedBy { queueOrder.getValue(it) }
    } != false
  }
}

private fun gapsAreCoherent(state: AcceptanceCriterionRepairState): Boolean =
  state.criteria.all { criterionGapsAreCoherent(it, state.revision) } &&
    lateFindingsAreCoherent(state) && changedPathsAreCoherent(state)

private fun criterionGapsAreCoherent(
  criterion: AcceptanceCriterionRepairCriterion,
  revision: Long,
): Boolean {
  val minimumGeneration = AcceptanceCriterionGapLimits.MIN_PLAN_GENERATION
  val maximumGeneration = AcceptanceCriterionGapLimits.MAX_PLAN_GENERATION
  return criterion.gaps.size <= AcceptanceCriterionGapLimits.MAX_GAPS_PER_CRITERION &&
    criterion.planGeneration in minimumGeneration..maximumGeneration &&
    criterion.gaps.withIndex().all { (index, gap) ->
      gap.id == "${criterion.canonicalRef}.G${index + 1}" && gapIsCoherent(gap, revision)
    } && (
      criterion.sourceDisposition != AcceptanceCriterionSourceDisposition.SOURCE_CLOSED ||
        criterion.gaps.none { it.status == AcceptanceCriterionGapStatus.OPEN }
    )
}

private fun gapIsCoherent(
  gap: AcceptanceCriterionGap,
  revision: Long,
): Boolean =
  gap.text.length in 1..AcceptanceCriterionGapLimits.MAX_TEXT_LENGTH &&
    gap.citation.length in 1..AcceptanceCriterionGapLimits.MAX_CITATION_LENGTH &&
    when (gap.status) {
      AcceptanceCriterionGapStatus.OPEN -> gap.closedRevision == null
      AcceptanceCriterionGapStatus.CLOSED -> gap.closedRevision?.let { it in 1..revision } == true
    }

private fun lateFindingsAreCoherent(state: AcceptanceCriterionRepairState): Boolean {
  val criteria = state.criteria.associateBy { it.canonicalRef }
  val findings = state.lateFindings
  return findings.size <= AcceptanceCriterionGapLimits.MAX_LATE_FINDINGS &&
    findings.all { finding ->
      finding.criterionRef in criteria &&
        finding.text.length in 1..AcceptanceCriterionGapLimits.MAX_TEXT_LENGTH &&
        finding.citation?.length?.let { it in 1..AcceptanceCriterionGapLimits.MAX_CITATION_LENGTH } != false &&
        finding.revision in 1..state.revision
    }
}

private fun changedPathsAreCoherent(state: AcceptanceCriterionRepairState): Boolean {
  val paths = state.repairChangedPaths
  return paths.size <= AcceptanceCriterionGapLimits.MAX_CHANGED_PATHS &&
    paths.distinct().size == paths.size &&
    paths.all {
      it.length in 1..AcceptanceCriterionGapLimits.MAX_CHANGED_PATH_LENGTH && it.isNotBlank()
    } &&
    (state.stage == AcceptanceCriterionRepairStage.REPAIR_COMPLETED_AWAITING_TARGETED_VERIFICATION || paths.isEmpty())
}
