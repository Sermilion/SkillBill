package skillbill.workflow.taskruntime.model.audit

data class AcceptanceCriterionProseAudit(
  val id: String,
  val revision: Long,
  val criterionRef: String?,
  val satisfied: Boolean,
  val digest: String,
  val prose: String,
  val treeDigest: String,
  val checkpointId: String,
)

fun AcceptanceCriterionRepairState.retainProseProgress(): AcceptanceCriterionRepairState = copy(revision = revision + 1)

fun AcceptanceCriterionRepairState.continueReservedRepair(
  reservationId: String,
  evidenceRef: String,
): AcceptanceCriterionRepairState? {
  val live = reservation?.takeIf { stage == AcceptanceCriterionRepairStage.REPAIR_RESERVED } ?: return null
  if (live.reservationId != reservationId ||
    live.criterionRef != currentCriterionRef || evidenceRef.isBlank()
  ) {
    return null
  }
  if (pendingClarificationQuestion == null && criteria.single {
      it.canonicalRef == live.criterionRef
    }.evidenceRef == evidenceRef
  ) {
    return this
  }
  return copy(
    criteria = criteria.map { if (it.canonicalRef == live.criterionRef) it.copy(evidenceRef = evidenceRef) else it },
    pendingClarificationQuestion = null,
    revision = revision + 1,
  )
}

fun AcceptanceCriterionRepairState.retainClarification(question: String): AcceptanceCriterionRepairState? {
  val readable = question.trim().take(AcceptanceCriterionRepairState.MAX_CLARIFICATION_LENGTH)
  if (readable.isBlank()) return null
  return copy(revision = revision + 1, pendingClarificationQuestion = readable)
}

fun AcceptanceCriterionRepairState.recordProseAudit(
  audit: AcceptanceCriterionProseAudit,
): AcceptanceCriterionRepairState? {
  val existing = proseAudits.firstOrNull { it.id == audit.id }
  if (existing != null) return if (existing == audit) this else null
  return when {
    !canAdmitProseAudit(audit) -> null
    mode == AcceptanceCriterionRepairMode.INITIAL_DISCOVERY &&
      stage == AcceptanceCriterionRepairStage.DISCOVERY_ADMITTED && audit.criterionRef == null ->
      applyInitialAudit(audit)
    audit.criterionRef == null -> null
    stage != AcceptanceCriterionRepairStage.READ_ONLY_AUDIT_REQUIRED -> null
    audit.criterionRef != pendingAuditRefs.firstOrNull() -> null
    else -> applyFocusedAudit(audit)
  }
}

private fun AcceptanceCriterionRepairState.applyInitialAudit(
  audit: AcceptanceCriterionProseAudit,
): AcceptanceCriterionRepairState {
  if (!audit.satisfied) {
    return openCriteriaNamedIn(audit.prose)?.let { named -> applyDiscoveredGaps(audit, named) }
      ?: copy(
        mode = AcceptanceCriterionRepairMode.CURRENT_CRITERION_TARGETED,
        stage = AcceptanceCriterionRepairStage.READ_ONLY_AUDIT_REQUIRED,
        pendingAuditRefs = unresolvedQueue,
        currentCriterionRef = unresolvedQueue.firstOrNull(),
        proseAudits = proseAudits + audit,
        revision = audit.revision,
      )
  }
  val closed =
    criteria.map { criterion ->
      if (criterion.sourceDisposition == AcceptanceCriterionSourceDisposition.NOT_APPLICABLE) {
        criterion
      } else {
        criterion.afterProseAudit(audit, AcceptanceCriterionSourceDisposition.SOURCE_CLOSED)
      }
    }
  return copy(
    criteria = closed,
    unresolvedQueue = emptyList(),
    currentCriterionRef = null,
    pendingAuditRefs = emptyList(),
    mode = AcceptanceCriterionRepairMode.ACCEPTED,
    stage = AcceptanceCriterionRepairStage.ACCEPTED,
    proseAudits = proseAudits + audit,
    revision = audit.revision,
  )
}

private fun AcceptanceCriterionRepairState.applyDiscoveredGaps(
  audit: AcceptanceCriterionProseAudit,
  named: Set<String>,
): AcceptanceCriterionRepairState? {
  val open = unresolvedQueue.filter { it in named }.toSet()
  if (open.isEmpty()) return null
  val settled =
    criteria.map { criterion ->
      when {
        criterion.canonicalRef in open -> criterion.copy(evidenceRef = audit.prose)
        criterion.sourceDisposition == AcceptanceCriterionSourceDisposition.NOT_APPLICABLE -> criterion
        else ->
          criterion.copy(
            sourceDisposition = AcceptanceCriterionSourceDisposition.SOURCE_CLOSED,
            evidenceRef = audit.prose,
            closureEvidenceRef = audit.prose,
            closureTreeDigest = audit.treeDigest,
            closureCheckpointId = audit.checkpointId,
          )
      }
    }
  val queue = AcceptanceCriterionRepairState.stableQueue(settled, dependencies, settled.associateBy { it.canonicalRef })
  if (queue.isNullOrEmpty()) return null
  return copy(
    criteria = settled,
    unresolvedQueue = queue,
    currentCriterionRef = queue.first(),
    pendingAuditRefs = emptyList(),
    mode = AcceptanceCriterionRepairMode.CURRENT_CRITERION_TARGETED,
    stage = AcceptanceCriterionRepairStage.DISCOVERY_ADMITTED,
    proseAudits = proseAudits + audit,
    revision = audit.revision,
  )
}

private val OPEN_CRITERIA_LINE = Regex("""^\W*open criteria\W*:(.*)$""", RegexOption.IGNORE_CASE)
private val CRITERION_ID = Regex("""\bAC-[0-9]{3}\b""", RegexOption.IGNORE_CASE)

internal fun openCriteriaNamedIn(prose: String): Set<String>? =
  prose.lineSequence().mapNotNull { OPEN_CRITERIA_LINE.find(it.trim())?.groupValues?.get(1) }.lastOrNull()
    ?.let { listed -> CRITERION_ID.findAll(listed).map { it.value.uppercase() }.toSet() }

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
    !audit.satisfied && unsuccessfulTotals.getOrDefault(previous.canonicalRef, 0) >=
      AcceptanceCriterionRepairState.MAX_UNSUCCESSFUL_REPAIR_OUTCOMES
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

private fun AcceptanceCriterionRepairCriterion.afterProseAudit(
  audit: AcceptanceCriterionProseAudit,
  disposition: AcceptanceCriterionSourceDisposition,
): AcceptanceCriterionRepairCriterion =
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
