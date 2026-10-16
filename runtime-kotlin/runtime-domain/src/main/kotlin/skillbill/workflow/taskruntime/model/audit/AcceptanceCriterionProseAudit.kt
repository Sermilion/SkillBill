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

fun AcceptanceCriterionRepairState.retainClarification(question: String): AcceptanceCriterionRepairState? {
  val readable = question.trim().take(AcceptanceCriterionRepairState.MAX_CLARIFICATION_LENGTH)
  if (readable.isBlank()) return null
  return copy(revision = revision + 1, pendingClarificationQuestion = readable)
}

fun AcceptanceCriterionRepairState.requireFinalProseAudit(): AcceptanceCriterionRepairState? =
  if (stage == AcceptanceCriterionRepairStage.FINAL_ACCEPTED) {
    copy(
      stage = AcceptanceCriterionRepairStage.FINAL_AUDIT_REQUIRED,
      finalAuditRequired = true,
      revision = revision + 1,
    )
  } else {
    null
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
    audit.criterionRef == null -> beginFinalRecheck(audit)
    stage != AcceptanceCriterionRepairStage.READ_ONLY_AUDIT_REQUIRED -> null
    audit.criterionRef != pendingAuditRefs.firstOrNull() -> null
    else -> applyFocusedAudit(audit)
  }
}

private fun AcceptanceCriterionRepairState.applyInitialAudit(
  audit: AcceptanceCriterionProseAudit,
): AcceptanceCriterionRepairState {
  if (!audit.satisfied) {
    return copy(
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
    mode = AcceptanceCriterionRepairMode.FINAL_FULL,
    stage = AcceptanceCriterionRepairStage.FINAL_AUDIT_REQUIRED,
    finalAuditRequired = true,
    proseAudits = proseAudits + audit,
    revision = audit.revision,
  )
}

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
        AcceptanceCriterionRepairMode.FINAL_FULL
      } else {
        AcceptanceCriterionRepairMode.CURRENT_CRITERION_TARGETED
      },
    stage = proseAuditStage(audit.satisfied, blocked, pending, queue),
    finalAuditRequired = pending.isEmpty() && queue.isEmpty(),
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
    else -> AcceptanceCriterionRepairStage.FINAL_AUDIT_REQUIRED
  }

private fun AcceptanceCriterionRepairState.beginFinalRecheck(
  audit: AcceptanceCriterionProseAudit,
): AcceptanceCriterionRepairState? {
  if (stage != AcceptanceCriterionRepairStage.FINAL_AUDIT_REQUIRED || audit.satisfied) return null
  val pending =
    criteria.filter { it.sourceDisposition != AcceptanceCriterionSourceDisposition.NOT_APPLICABLE }
      .sortedBy { it.ordinal }.map { it.canonicalRef }
  val lastSettlement =
    maxOf(
      sourceTransitions.maxOfOrNull { it.revision } ?: 0,
      finalAudits.maxOfOrNull { it.revision } ?: 0,
    )
  val repeatedSource =
    proseAudits.any {
      it.revision > lastSettlement && it.criterionRef == null && !it.satisfied && it.treeDigest == audit.treeDigest &&
        it.checkpointId == audit.checkpointId
    }
  val named =
    Regex(
      "\\b(AC-[0-9]{3})\\b\\s+(?:(?:is|remains|still)\\s+)?" +
        "(?:open|unresolved|unsatisfied|not\\s+(?:satisfied|met|implemented)|requires\\s+repair)\\b",
      RegexOption.IGNORE_CASE,
    ).findAll(audit.prose).map { it.groupValues[1].uppercase() }.toSet()
  val focused = pending.filter { it in named }.takeUnless { repeatedSource || it.isEmpty() } ?: pending
  return copy(
    criteria = criteria.map { if (it.canonicalRef in focused) it.copy(evidenceRef = audit.prose) else it },
    pendingAuditRefs = focused,
    currentCriterionRef = focused.firstOrNull(),
    finalAuditRequired = false,
    mode = AcceptanceCriterionRepairMode.CURRENT_CRITERION_TARGETED,
    stage = AcceptanceCriterionRepairStage.READ_ONLY_AUDIT_REQUIRED,
    proseAudits = proseAudits + audit,
    revision = audit.revision,
  )
}
