package skillbill.workflow.taskruntime.model.audit

import skillbill.workflow.model.repair.FocusedRepairStrategy

internal const val UNKNOWN_AUDIT_SUFFIX: String = ":trace:unknown"

data class AcceptanceCriterionProseAudit(
  val id: String,
  val revision: Long,
  val criterionRef: String?,
  val satisfied: Boolean,
  val digest: String,
  val prose: String,
  val treeDigest: String,
  val checkpointId: String,
  val discoverySnapshot: AcceptanceCriterionDiscoverySnapshot? = null,
)

data class AcceptanceCriterionDiscoverySnapshot(
  val listedCriterionRefs: List<String>,
  val unresolvedCriterionRefs: List<String>,
)

fun AcceptanceCriterionProseAudit.sameProducerAs(other: AcceptanceCriterionProseAudit): Boolean =
  id == other.id && revision == other.revision && criterionRef == other.criterionRef &&
    satisfied == other.satisfied && digest == other.digest && prose == other.prose &&
    treeDigest == other.treeDigest && checkpointId == other.checkpointId &&
    (discoverySnapshot == null || discoverySnapshot == other.discoverySnapshot)

fun AcceptanceCriterionRepairState.retainProseProgress(): AcceptanceCriterionRepairState = copy(revision = revision + 1)

fun AcceptanceCriterionRepairState.retainClarification(question: String): AcceptanceCriterionRepairState? {
  val readable = question.trim().take(AcceptanceCriterionRepairState.MAX_CLARIFICATION_LENGTH)
  if (readable.isBlank()) return null
  return copy(revision = revision + 1, pendingClarificationQuestion = readable)
}

fun AcceptanceCriterionRepairState.recordProseAudit(
  audit: AcceptanceCriterionProseAudit,
): AcceptanceCriterionRepairState? {
  val existing = proseAudits.firstOrNull { it.id == audit.id }
  if (existing != null) return if (audit.sameProducerAs(existing)) this else null
  return when {
    !canAdmitProseAudit(audit) -> null
    audit.criterionRef == null && AcceptanceRepairDecision.auditKind(this) == AcceptanceRepairStep.Discover ->
      applyInitialAudit(audit)?.copy(pendingClarificationQuestion = null)
    audit.criterionRef == null -> null
    audit.discoverySnapshot != null -> null
    stage != AcceptanceCriterionRepairStage.READ_ONLY_AUDIT_REQUIRED -> null
    audit.criterionRef != pendingAuditRefs.firstOrNull() -> null
    else -> applyFocusedAudit(audit)
  }
}

private fun AcceptanceCriterionRepairState.applyInitialAudit(
  audit: AcceptanceCriterionProseAudit,
): AcceptanceCriterionRepairState? {
  val scope = AcceptanceRepairDecision.discoveryCriteria(this)
  val listed = scope.map { it.canonicalRef }
  val refs = listed.toSet()
  val named =
    if (audit.satisfied) {
      emptySet()
    } else {
      openCriteriaNamedIn(audit.prose)
        ?.takeIf { it.intersect(refs).isNotEmpty() } ?: refs
    }
  return applyDiscoveredGaps(audit, named, refs, listed)
}

private fun AcceptanceCriterionRepairState.applyDiscoveredGaps(
  audit: AcceptanceCriterionProseAudit,
  named: Set<String>,
  scope: Set<String>,
  listed: List<String>,
): AcceptanceCriterionRepairState? {
  val listedOpen = scope.intersect(named)
  val resolution = resolveAcceptanceCriterionDiscovery(audit.prose, listedOpen)
  val fallbackRefs = resolution.fallbackCriteria
  val entries =
    resolution.entries +
      fallbackRefs.map { ref ->
        AcceptanceCriterionGapReportEntry(
          ref,
          1,
          AcceptanceCriterionGapReportParser.FALLBACK_GAP_TEXT,
          "spec:$ref",
        )
      }
  val settled =
    criteria.map { criterion ->
      when {
        criterion.canonicalRef !in scope -> criterion
        criterion.canonicalRef in listedOpen -> criterion.appendDiscoveryGaps(entries, audit.prose)
        criterion.sourceDisposition == AcceptanceCriterionSourceDisposition.NOT_APPLICABLE -> criterion
        else ->
          criterion.copy(
            sourceDisposition = AcceptanceCriterionSourceDisposition.SOURCE_CLOSED,
            evidenceRef = audit.prose,
            closureEvidenceRef = audit.prose,
            closureTreeDigest = audit.treeDigest,
            closureCheckpointId = audit.checkpointId,
          ).closeOpenGaps(audit.revision)
      }
    }
  val queue =
    AcceptanceCriterionRepairState.stableQueue(
      settled,
      dependencies,
      settled.associateBy { it.canonicalRef },
    ) ?: return null
  val pending = pendingAuditRefs.filterNot { it in scope }
  val accepted = queue.isEmpty() && pending.isEmpty()
  val current = pending.firstOrNull() ?: queue.firstOrNull()
  val capturedSnapshot =
    AcceptanceCriterionDiscoverySnapshot(
      listedCriterionRefs = listed,
      unresolvedCriterionRefs = queue,
    )
  if (audit.discoverySnapshot != null && audit.discoverySnapshot != capturedSnapshot) return null
  val capturedAudit = audit.copy(discoverySnapshot = capturedSnapshot)
  return copy(
    criteria = settled,
    unresolvedQueue = queue,
    currentCriterionRef = current,
    pendingAuditRefs = pending,
    mode =
      if (accepted) {
        AcceptanceCriterionRepairMode.ACCEPTED
      } else {
        AcceptanceCriterionRepairMode.CURRENT_CRITERION_TARGETED
      },
    stage =
      when {
        accepted -> AcceptanceCriterionRepairStage.ACCEPTED
        pending.isNotEmpty() -> AcceptanceCriterionRepairStage.READ_ONLY_AUDIT_REQUIRED
        else -> AcceptanceCriterionRepairStage.DISCOVERY_ADMITTED
      },
    proseAudits = proseAudits + capturedAudit,
    revision = audit.revision,
  )
}

private fun AcceptanceCriterionRepairCriterion.appendDiscoveryGaps(
  entries: List<AcceptanceCriterionGapReportEntry>,
  prose: String,
): AcceptanceCriterionRepairCriterion {
  val existing = gaps.maxOfOrNull { it.id.substringAfterLast(".G").toIntOrNull() ?: 0 } ?: 0
  val additions = entries.filter { it.criterionRef == canonicalRef }
  return copy(
    evidenceRef = prose,
    gaps =
      gaps +
        additions.mapIndexed { index, gap ->
          AcceptanceCriterionGap(
            id = "$canonicalRef.G${existing + index + 1}",
            text = gap.text,
            citation = gap.citation,
            status = AcceptanceCriterionGapStatus.OPEN,
            origin = AcceptanceCriterionGapOrigin.DISCOVERY,
            closedRevision = null,
          )
        },
  )
}

data class AcceptanceCriterionDiscoveryResolution(
  val entries: List<AcceptanceCriterionGapReportEntry>,
  val fallbackCriteria: Set<String>,
  val strayCriteria: Set<String>,
  val malformedLines: List<String>,
)

fun resolveAcceptanceCriterionDiscovery(
  prose: String,
  namedOpenCriteria: Set<String>,
): AcceptanceCriterionDiscoveryResolution {
  val report = AcceptanceCriterionGapReportParser.parse(prose, namedOpenCriteria)
  return AcceptanceCriterionDiscoveryResolution(
    entries = report.entries.filterNot { it.criterionRef in report.malformedCriteria },
    fallbackCriteria = report.malformedCriteria.intersect(namedOpenCriteria),
    strayCriteria = report.strayCriteria,
    malformedLines = report.malformedLines,
  )
}

internal fun openCriteriaNamedIn(prose: String): Set<String>? = AcceptanceCriterionGapReportParser.openCriteria(prose)

private fun AcceptanceCriterionRepairState.canAdmitProseAudit(audit: AcceptanceCriterionProseAudit): Boolean {
  val current = reservation == null && audit.revision == revision + 1
  val evidence = audit.prose.isNotBlank() && audit.checkpointId.isNotBlank()
  val digests = audit.digest.isDigest() && audit.treeDigest.isDigest()
  val capacity = proseAudits.size < AcceptanceCriterionRepairState.MAX_REPAIR_OUTCOMES
  return current && evidence && digests && capacity
}

private fun AcceptanceCriterionRepairState.applyFocusedAudit(
  audit: AcceptanceCriterionProseAudit,
): AcceptanceCriterionRepairState? {
  val previous = criteria.single { it.canonicalRef == audit.criterionRef }
  val disposition =
    if (audit.satisfied) {
      AcceptanceCriterionSourceDisposition.SOURCE_CLOSED
    } else {
      AcceptanceCriterionSourceDisposition.SOURCE_UNRESOLVED
    }
  val changed = previous.afterProseAudit(audit, disposition)
  val nextCriteria = criteria.map { if (it.canonicalRef == previous.canonicalRef) changed else it }
  val pending = if (audit.satisfied) pendingAuditRefs.drop(1) else pendingAuditRefs
  val queue =
    AcceptanceCriterionRepairState.stableQueue(
      nextCriteria,
      dependencies,
      nextCriteria.associateBy { it.canonicalRef },
    ) ?: return null
  val blocked =
    !audit.satisfied && chargedAttempts.getOrDefault(previous.canonicalRef, 0) >=
      FocusedRepairStrategy.MAX_UNSUCCESSFUL_OUTCOMES
  val transition =
    if (previous.sourceDisposition != disposition) {
      listOf(
        AcceptanceCriterionRepairSourceTransition(
          previous.canonicalRef, audit.revision, previous.sourceDisposition, disposition,
          audit.id, audit.digest, audit.prose, audit.treeDigest, audit.checkpointId,
        ),
      )
    } else {
      emptyList()
    }
  return copy(
    criteria = nextCriteria,
    unresolvedQueue = queue,
    currentCriterionRef = pending.firstOrNull() ?: queue.firstOrNull(),
    pendingAuditRefs = pending,
    proseAudits = proseAudits + audit,
    sourceTransitions = sourceTransitions + transition,
    mode =
      if (pending.isEmpty() && queue.isEmpty()) {
        AcceptanceCriterionRepairMode.ACCEPTED
      } else {
        AcceptanceCriterionRepairMode.CURRENT_CRITERION_TARGETED
      },
    stage = proseAuditStage(audit.satisfied, blocked, pending, queue),
    revision = audit.revision,
  )
}

internal fun AcceptanceCriterionRepairCriterion.afterProseAudit(
  audit: AcceptanceCriterionProseAudit,
  disposition: AcceptanceCriterionSourceDisposition,
): AcceptanceCriterionRepairCriterion {
  val updated =
    if (sourceDisposition == disposition && audit.satisfied) {
      this
    } else {
      copy(
        sourceDisposition = disposition,
        evidenceRef = audit.prose,
        closureEvidenceRef = if (audit.satisfied) audit.prose else null,
        closureTreeDigest = if (audit.satisfied) audit.treeDigest else null,
        closureCheckpointId = if (audit.satisfied) audit.checkpointId else null,
      )
    }
  return if (disposition == AcceptanceCriterionSourceDisposition.SOURCE_CLOSED) {
    updated.closeOpenGaps(audit.revision)
  } else {
    updated
  }
}

private fun proseAuditStage(
  satisfied: Boolean,
  blocked: Boolean,
  pending: List<String>,
  queue: List<String>,
): AcceptanceCriterionRepairStage =
  when {
    blocked -> AcceptanceCriterionRepairStage.BUDGET_BLOCKED
    !satisfied -> AcceptanceCriterionRepairStage.DISCOVERY_ADMITTED
    pending.isNotEmpty() -> AcceptanceCriterionRepairStage.READ_ONLY_AUDIT_REQUIRED
    queue.isNotEmpty() -> AcceptanceCriterionRepairStage.DISCOVERY_ADMITTED
    else -> AcceptanceCriterionRepairStage.ACCEPTED
  }

fun AcceptanceCriterionRepairState.auditAttempts(): List<AcceptanceCriterionAuditAttempt> {
  data class SettledStep(
    val id: String,
    val revision: Long,
    val criterionRef: String?,
    val success: Boolean,
    val discoverySnapshot: AcceptanceCriterionDiscoverySnapshot?,
    val sourceNeutral: Boolean,
  )

  val steps =
    (
      proseAudits.map {
        SettledStep(
          it.id,
          it.revision,
          it.criterionRef,
          it.satisfied,
          it.discoverySnapshot,
          it.id.endsWith(UNKNOWN_AUDIT_SUFFIX),
        )
      } +
        outcomes.mapNotNull { outcome ->
          if (
            outcome.kind !in
            setOf(
              AcceptanceCriterionRepairOutcomeKind.TARGETED_SUCCESS,
              AcceptanceCriterionRepairOutcomeKind.TARGETED_FAILURE,
            )
          ) {
            null
          } else {
            outcome.verifiedRevision?.let {
              SettledStep(outcome.outcomeId, it, outcome.criterionRef, outcome.successful, null, false)
            }
          }
        }
    ).sortedBy { it.revision }
  var open: MutableSet<String>? =
    criteria.filter { it.sourceDisposition != AcceptanceCriterionSourceDisposition.NOT_APPLICABLE }
      .mapTo(linkedSetOf()) { it.canonicalRef }

  return steps.mapIndexed { index, step ->
    if (step.criterionRef == null) {
      if (!step.sourceNeutral) {
        if (step.discoverySnapshot != null) {
          open = step.discoverySnapshot.unresolvedCriterionRefs.toMutableSet()
        } else {
          open = null
        }
      }
    } else {
      open?.let { current ->
        if (step.success) current.remove(step.criterionRef) else current.add(step.criterionRef)
      }
    }
    val knownOpen = open
    AcceptanceCriterionAuditAttempt(
      id = step.id,
      number = index + 1,
      passed = knownOpen?.isEmpty(),
      uncoveredCount = knownOpen?.size,
      unavailableReason =
        if (knownOpen == null) "Discovery scope was not retained for this historical audit." else null,
    )
  }
}

fun AcceptanceCriterionRepairState.qaReauditRefs(scope: Collection<String>): List<String>? {
  if (stage != AcceptanceCriterionRepairStage.ACCEPTED || reservation != null) return null
  if (scope.any { ref -> criteria.none { it.canonicalRef == ref } }) return null
  return criteria.sortedBy(AcceptanceCriterionRepairCriterion::ordinal)
    .filter { it.canonicalRef in scope && it.sourceDisposition == AcceptanceCriterionSourceDisposition.SOURCE_CLOSED }
    .map(AcceptanceCriterionRepairCriterion::canonicalRef)
}

fun AcceptanceCriterionRepairState.admitQaReaudit(refs: List<String>): AcceptanceCriterionRepairState? {
  if (refs.isEmpty() || qaReauditRefs(refs) != refs) return null
  return copy(
    mode = AcceptanceCriterionRepairMode.CURRENT_CRITERION_TARGETED,
    stage = AcceptanceCriterionRepairStage.READ_ONLY_AUDIT_REQUIRED,
    pendingAuditRefs = refs,
    currentCriterionRef = refs.first(),
    revision = revision + 1,
  )
}
