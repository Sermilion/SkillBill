package skillbill.workflow.taskruntime.model.audit

import skillbill.error.shellcontent.invalidAcceptanceCriterionAuditResultSchema
import skillbill.workflow.model.repair.FocusedRepairStrategy
import skillbill.workflow.model.repair.RepairTargetProgress

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
  if (!permitsFocusedClosure(audit)) return null
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
  val blocked = !audit.satisfied && budgetBlocks(previous.canonicalRef)
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

private fun AcceptanceCriterionRepairState.permitsFocusedClosure(audit: AcceptanceCriterionProseAudit): Boolean {
  val mutation = completedMutations.lastOrNull { it.criterionRef == audit.criterionRef }
  val repaired = outcomes.any { it.criterionRef == audit.criterionRef }
  if (audit.satisfied && repaired && mutation == null) return false
  if (audit.satisfied && mutation != null) {
    val check = independentChecks.singleOrNull { it.mutation == mutation } ?: return false
    check.validate()
    val source =
      check.comparison.currentTreeDigest == audit.treeDigest &&
        check.comparison.currentCheckpointId == audit.checkpointId
    if (!source || check.comparison.classification != AcceptanceCriterionProgressClassification.SATISFIED) return false
  }
  return true
}

internal fun AcceptanceCriterionRepairCriterion.afterProseAudit(
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

data class AcceptanceCriterionIndependentProgress(
  val criterionRef: String,
  val repairAttemptId: String,
  val baselineTreeDigest: String,
  val currentTreeDigest: String,
  val baselineCheckpointId: String,
  val currentCheckpointId: String,
  val verificationRevision: Long,
  val auditBindingDigest: String,
  val resolvedObligations: List<AcceptanceCriterionResolvedObligation>,
  val preservedObligations: List<AcceptanceCriterionPreservedObligation>,
  val remainingGaps: List<String>,
  val classification: AcceptanceCriterionProgressClassification,
)

data class AcceptanceCriterionResolvedObligation(
  val obligationId: String,
  val obligation: String,
  val baselineCitations: List<String>,
  val currentCitations: List<String>,
)

data class AcceptanceCriterionEstablishedObligation(
  val obligationId: String,
  val obligation: String,
  val currentCitations: List<String>,
)

data class AcceptanceCriterionPreservedObligation(
  val obligationId: String,
  val obligation: String,
  val currentCitations: List<String>,
)

data class AcceptanceCriterionProgressContext(
  val catalogId: String,
  val specDigest: String,
  val executionId: String,
  val criterionRef: String,
  val reservationId: String,
  val workerExecutionId: String,
  val workerOutputDigest: String,
  val baselineTreeDigest: String,
  val baselineCheckpointId: String,
  val currentTreeDigest: String,
  val currentCheckpointId: String,
  val verificationRevision: Long,
  val auditBindingDigest: String,
  val checkerExecutionId: String,
  val checkerOutputId: String,
  val checkerOutputDigest: String,
  val sourceUnchangedDuringCheck: Boolean,
  val baselineCitations: Set<String>,
  val currentCitations: Set<String>,
  val establishedObligations: List<AcceptanceCriterionEstablishedObligation>,
  val verifiedResolvedObligations: List<AcceptanceCriterionResolvedObligation>,
  val verifiedPreservedObligations: List<AcceptanceCriterionPreservedObligation>,
  val completedMutation: AcceptanceCriterionCompletedMutation? = null,
)

sealed interface AcceptanceCriterionProgressAdmission {
  data class Admitted(val comparison: AcceptanceCriterionIndependentProgress) : AcceptanceCriterionProgressAdmission

  data class ClarificationRequired(val reason: String) : AcceptanceCriterionProgressAdmission
}

fun AcceptanceCriterionIndependentProgress.admit(
  context: AcceptanceCriterionProgressContext?,
  criterionSatisfied: Boolean,
): AcceptanceCriterionProgressAdmission {
  validateShape()
  if (context == null) return AcceptanceCriterionProgressAdmission.ClarificationRequired("Runtime capture is required")
  val mutation =
    context.completedMutation
      ?: return AcceptanceCriterionProgressAdmission.ClarificationRequired("Completed mutation capture is required")
  mutation.validate()
  mutation.validateContext(context)
  mutation.validateComparison(this)
  context.validateChecker()
  validateBinding(context)
  validateAttestations(context)
  validatePreservation(context.establishedObligations)
  if (criterionSatisfied != (classification == AcceptanceCriterionProgressClassification.SATISFIED)) {
    throw invalidAcceptanceCriterionAuditResultSchema(
      "independent-progress",
      "Classification contradicts criterion closure",
    )
  }
  return AcceptanceCriterionProgressAdmission.Admitted(this)
}

private fun AcceptanceCriterionProgressContext.validateChecker() {
  val identities =
    workerExecutionId.isNotBlank() && checkerExecutionId.isNotBlank() &&
      workerExecutionId != checkerExecutionId
  val outputs = workerOutputDigest.isDigest() && checkerOutputDigest.isDigest() && checkerOutputId.isNotBlank()
  val authority = catalogId.isNotBlank() && specDigest.isDigest() && executionId.isNotBlank()
  val evidence = outputs && sourceUnchangedDuringCheck
  if (!identities || !evidence || !authority) {
    throw invalidAcceptanceCriterionAuditResultSchema(
      "independent-progress",
      "Checker authority or source stability is invalid",
    )
  }
}

private fun AcceptanceCriterionIndependentProgress.validateBinding(context: AcceptanceCriterionProgressContext) {
  val identity = criterionRef == context.criterionRef && repairAttemptId == context.reservationId
  val trees = baselineTreeDigest == context.baselineTreeDigest && currentTreeDigest == context.currentTreeDigest
  val checkpoints =
    baselineCheckpointId == context.baselineCheckpointId &&
      currentCheckpointId == context.currentCheckpointId
  val revision =
    verificationRevision == context.verificationRevision &&
      auditBindingDigest == context.auditBindingDigest
  val sourcePair = trees && checkpoints
  if (!identity || !sourcePair || !revision) {
    throw invalidAcceptanceCriterionAuditResultSchema(
      "independent-progress",
      "Comparison does not match the reserved source pair",
    )
  }
}

private fun AcceptanceCriterionIndependentProgress.validateAttestations(context: AcceptanceCriterionProgressContext) {
  val obligations =
    resolvedObligations == context.verifiedResolvedObligations &&
      preservedObligations == context.verifiedPreservedObligations
  val baseline =
    resolvedObligations.flatMap { it.baselineCitations } +
      context.establishedObligations.flatMap { it.currentCitations }
  val current =
    resolvedObligations.flatMap { it.currentCitations } +
      preservedObligations.flatMap { it.currentCitations }
  val resolvable =
    baseline.all { it in context.baselineCitations } &&
      current.all { it in context.currentCitations }
  if (!obligations || !resolvable) {
    throw invalidAcceptanceCriterionAuditResultSchema(
      "independent-progress",
      "Obligations lack runtime-attested source evidence",
    )
  }
}

private fun AcceptanceCriterionIndependentProgress.validatePreservation(
  established: List<AcceptanceCriterionEstablishedObligation>,
) {
  val establishedIds = established.map { it.obligationId }.toSet()
  val establishedById = established.associateBy { it.obligationId }
  val malformed =
    establishedIds.size != established.size ||
      established.any {
        it.obligationId.isBlank() || it.obligation.isBlank() || !it.currentCitations.validCitations()
      }
  val preserved =
    preservedObligations.map { it.obligationId }.toSet() == establishedIds &&
      preservedObligations.all { establishedById[it.obligationId]?.obligation == it.obligation }
  if (malformed || !preserved || resolvedObligations.any { it.obligationId in establishedIds }) {
    throw invalidAcceptanceCriterionAuditResultSchema(
      "independent-progress",
      "Previously established obligations must be preserved",
    )
  }
}

enum class AcceptanceCriterionProgressClassification(val wireValue: String) {
  SATISFIED("satisfied"),
  VERIFIED_PARTIAL_PROGRESS("verified_partial_progress"),
  NO_PROGRESS("no_progress"),
}

enum class AcceptanceCriterionEvidenceLifecycle(val wireValue: String) {
  NO_COMPLETED_MUTATION("no_completed_mutation"),
  COMPLETED_AWAITING_INDEPENDENT_CHECK("completed_awaiting_independent_check"),
  UNKNOWN_HISTORICAL_AUTHORITY("unknown_historical_authority"),
  CHECKED_EVIDENCE_RETAINED("checked_evidence_retained"),
}

fun AcceptanceCriterionIndependentProgress.validateShape() {
  validateIdentity()
  validateObligations()
  val incoherent =
    when (classification) {
      AcceptanceCriterionProgressClassification.SATISFIED -> {
        remainingGaps.isNotEmpty() || resolvedObligations.isEmpty()
      }
      AcceptanceCriterionProgressClassification.VERIFIED_PARTIAL_PROGRESS -> {
        remainingGaps.isEmpty() || resolvedObligations.isEmpty()
      }
      AcceptanceCriterionProgressClassification.NO_PROGRESS -> {
        remainingGaps.isEmpty() || resolvedObligations.isNotEmpty()
      }
    }
  if (incoherent) {
    throw invalidAcceptanceCriterionAuditResultSchema(
      "independent-progress",
      "Classification contradicts obligations and gaps",
    )
  }
}

private fun AcceptanceCriterionIndependentProgress.validateIdentity() {
  val identity = Regex("AC-[0-9]{3}").matches(criterionRef) && repairAttemptId.validText(MAX_PROGRESS_ID_LENGTH)
  val trees = baselineTreeDigest.isDigest() && currentTreeDigest.isDigest() && auditBindingDigest.isDigest()
  val checkpoints =
    baselineCheckpointId.validText(
      MAX_PROGRESS_ID_LENGTH,
    ) && currentCheckpointId.validText(MAX_PROGRESS_ID_LENGTH)
  val sourcePair = trees && checkpoints
  if (!identity || !sourcePair || verificationRevision < 0) {
    throw invalidAcceptanceCriterionAuditResultSchema("independent-progress", "Comparison identities are malformed")
  }
}

private fun AcceptanceCriterionIndependentProgress.validateObligations() {
  resolvedObligations.forEach { it.validate() }
  preservedObligations.forEach { it.validate() }
  val ids = resolvedObligations.map { it.obligationId } + preservedObligations.map { it.obligationId }
  val capacity =
    resolvedObligations.size <= MAX_PROGRESS_ITEMS &&
      preservedObligations.size <= MAX_PROGRESS_ITEMS && remainingGaps.size <= MAX_PROGRESS_ITEMS
  if (!capacity || ids.distinct().size != ids.size || remainingGaps.any { !it.validText(MAX_PROGRESS_TEXT_LENGTH) }) {
    throw invalidAcceptanceCriterionAuditResultSchema(
      "independent-progress",
      "Obligation identities or remaining gaps are malformed",
    )
  }
}

private fun AcceptanceCriterionResolvedObligation.validate() {
  val identity = obligationId.validText(MAX_PROGRESS_ID_LENGTH) && obligation.validText(MAX_PROGRESS_TEXT_LENGTH)
  if (!identity || !baselineCitations.validCitations() || !currentCitations.validCitations()) {
    throw invalidAcceptanceCriterionAuditResultSchema(
      "independent-progress",
      "Resolved obligation lacks concrete source citations",
    )
  }
}

private fun AcceptanceCriterionPreservedObligation.validate() {
  if (!obligationId.validText(
      MAX_PROGRESS_ID_LENGTH,
    ) || !obligation.validText(MAX_PROGRESS_TEXT_LENGTH) || !currentCitations.validCitations()
  ) {
    throw invalidAcceptanceCriterionAuditResultSchema(
      "independent-progress",
      "Preserved obligation lacks concrete source citations",
    )
  }
}

private fun List<String>.validCitations(): Boolean =
  size in 1..MAX_PROGRESS_ITEMS && all { it.validText(MAX_PROGRESS_TEXT_LENGTH) }

private fun String.validText(maximum: Int): Boolean = isNotBlank() && length <= maximum

private const val MAX_PROGRESS_ID_LENGTH = 256
private const val MAX_PROGRESS_TEXT_LENGTH = 4096
private const val MAX_PROGRESS_ITEMS = 256

data class AcceptanceCriterionCompletedMutation(
  val catalogId: String,
  val specDigest: String,
  val executionId: String,
  val criterionRef: String,
  val repairAttemptId: String,
  val workerExecutionId: String,
  val workerOutputId: String,
  val workerOutputDigest: String,
  val baselineTreeDigest: String,
  val baselineCheckpointId: String,
  val currentTreeDigest: String,
  val currentCheckpointId: String,
  val establishedObligations: List<AcceptanceCriterionEstablishedObligation>,
  val terminalDisposition: AcceptanceCriterionMutationDisposition,
  val launchAttribution: AcceptanceCriterionRepairLaunch? = null,
) {
  fun validate() {
    val identities = listOf(catalogId, executionId, repairAttemptId, workerExecutionId, workerOutputId)
    val snapshots = listOf(baselineCheckpointId, currentCheckpointId)
    val digests = listOf(specDigest, workerOutputDigest, baselineTreeDigest, currentTreeDigest)
    val inventory = establishedObligations
    val malformed =
      inventory.any {
        !it.obligationId.validText(MAX_PROGRESS_ID_LENGTH) || !it.obligation.validText(MAX_PROGRESS_TEXT_LENGTH) ||
          !it.currentCitations.validCitations()
      }
    val unique = inventory.map { it.obligationId }.distinct().size == inventory.size
    val validLaunch = launchAttribution?.validFor(repairAttemptId) != false
    val invalidCapture =
      identities.any { !it.validText(MAX_PROGRESS_ID_LENGTH) } ||
        snapshots.any { !it.validText(MAX_PROGRESS_ID_LENGTH) } || digests.any { !it.isDigest() }
    if (!validLaunch || invalidCapture) {
      throw invalidAcceptanceCriterionAuditResultSchema("completed-mutation", "Runtime capture identities are invalid")
    }
    val validInventory = !malformed && unique && inventory.size <= MAX_PROGRESS_ITEMS
    if (!Regex("AC-[0-9]{3}").matches(criterionRef) || !validInventory) {
      throw invalidAcceptanceCriterionAuditResultSchema(
        "completed-mutation",
        "Criterion obligation inventory is invalid",
      )
    }
  }

  fun validateComparison(comparison: AcceptanceCriterionIndependentProgress) {
    validate()
    comparison.validateShape()
    if (!terminal) {
      throw invalidAcceptanceCriterionAuditResultSchema("completed-mutation", "Historical terminal facts are unknown")
    }
    val identity = criterionRef == comparison.criterionRef && repairAttemptId == comparison.repairAttemptId
    val trees = baselineTreeDigest == comparison.baselineTreeDigest && currentTreeDigest == comparison.currentTreeDigest
    val checkpoints =
      baselineCheckpointId == comparison.baselineCheckpointId &&
        currentCheckpointId == comparison.currentCheckpointId
    if (!identity || !trees || !checkpoints) {
      throw invalidAcceptanceCriterionAuditResultSchema(
        "completed-mutation",
        "Comparison does not match retained source capture",
      )
    }
    comparison.validatePreservation(establishedObligations)
  }

  fun validateContext(context: AcceptanceCriterionProgressContext) {
    val identity =
      catalogId == context.catalogId &&
        specDigest == context.specDigest &&
        executionId == context.executionId
    val attempt = criterionRef == context.criterionRef && repairAttemptId == context.reservationId
    val worker = workerExecutionId == context.workerExecutionId && workerOutputDigest == context.workerOutputDigest
    val trees = baselineTreeDigest == context.baselineTreeDigest && currentTreeDigest == context.currentTreeDigest
    val checkpoints =
      baselineCheckpointId == context.baselineCheckpointId &&
        currentCheckpointId == context.currentCheckpointId
    val independentOutput = workerOutputId != context.checkerOutputId
    val capture = identity && attempt && worker
    val snapshot = trees && checkpoints
    val inventory = establishedObligations == context.establishedObligations
    val authority = independentOutput && inventory
    if (!capture || !snapshot || !authority) {
      throw invalidAcceptanceCriterionAuditResultSchema(
        "completed-mutation",
        "Checker does not match retained mutation authority",
      )
    }
  }
}

data class AcceptanceCriterionCheckedMutation(
  val mutation: AcceptanceCriterionCompletedMutation,
  val comparison: AcceptanceCriterionIndependentProgress,
  val context: AcceptanceCriterionProgressContext,
) {
  fun validate() {
    if (context.completedMutation != mutation) {
      throw invalidAcceptanceCriterionAuditResultSchema(
        "independent-evidence",
        "Checked evidence lacks matching runtime capture",
      )
    }
    comparison.admit(
      context,
      comparison.classification == AcceptanceCriterionProgressClassification.SATISFIED,
    )
  }

  fun establishedObligations(): List<AcceptanceCriterionEstablishedObligation> {
    validate()
    return comparison.preservedObligations.map {
      AcceptanceCriterionEstablishedObligation(it.obligationId, it.obligation, it.currentCitations)
    } +
      comparison.resolvedObligations.map {
        AcceptanceCriterionEstablishedObligation(it.obligationId, it.obligation, it.currentCitations)
      }
  }
}

fun validateAcceptanceCriterionEvidence(
  mutations: List<AcceptanceCriterionCompletedMutation>,
  checks: List<AcceptanceCriterionCheckedMutation>,
) {
  val unique =
    mutations.map { it.repairAttemptId }.distinct().size == mutations.size &&
      checks.map { it.mutation.repairAttemptId }.distinct().size == checks.size
  val bounded =
    mutations.size <= AcceptanceCriterionRepairState.MAX_REPAIR_OUTCOMES &&
      checks.size <= AcceptanceCriterionRepairState.MAX_REPAIR_OUTCOMES
  if (!unique || !bounded) {
    invalidRetainedEvidence("Duplicate attempts or evidence capacity exceeded")
  }
  val checkedByAttempt = checks.associateBy { it.mutation.repairAttemptId }
  val inventoryByCriterion = mutableMapOf<String, List<AcceptanceCriterionEstablishedObligation>>()
  mutations.forEach { mutation ->
    mutation.validate()
    val inventory = inventoryByCriterion[mutation.criterionRef]
    if (inventory != null && inventory != mutation.establishedObligations) {
      invalidRetainedEvidence("Retained obligation inventory was replaced")
    }
    checkedByAttempt[mutation.repairAttemptId]?.let { check ->
      check.validate()
      if (check.mutation != mutation) {
        invalidRetainedEvidence("Check does not match completed mutation")
      }
      inventoryByCriterion[mutation.criterionRef] = check.establishedObligations()
    }
  }
  if (checks.any { it.mutation !in mutations }) {
    invalidRetainedEvidence("Check lacks completed mutation")
  }
}

private fun invalidRetainedEvidence(reason: String): Nothing =
  throw invalidAcceptanceCriterionAuditResultSchema("independent-evidence", reason)

enum class AcceptanceCriterionMutationDisposition(val wireValue: String) {
  COMPLETE("complete"),
  INCOMPLETE("incomplete"),
  NO_CHANGE("no_change"),
  HISTORICAL_UNKNOWN("historical_unknown"),
}

data class AcceptanceCriterionRepairLaunch(
  val attemptId: String,
  val provider: String,
  val effectiveModel: String,
  val effectiveEffort: String,
  val executionPlanRef: String,
  val launchRef: String,
  val reasoning: Boolean,
) {
  internal fun validFor(attempt: String): Boolean =
    attemptId == attempt &&
      listOf(provider, effectiveModel, effectiveEffort, executionPlanRef, launchRef)
        .all { it.validText(MAX_PROGRESS_ID_LENGTH) }
}

data class AcceptanceCriterionRepairBudget(
  val criterionRef: String,
  val retryIdentity: String,
  val startMutationIndex: Int,
)

data class AcceptanceCriterionRepairAccounting(
  val criterionRef: String,
  val budgetIdentity: String,
  val completedAttemptIds: List<String>,
  val noProgressOutcomes: Int,
  val pendingCheckAttemptIds: List<String>,
  val escalationRequired: Boolean,
  val triggeringAttemptIds: List<String>,
  val escalatedAttemptIds: List<String>,
  val evidenceMissing: Boolean,
) {
  val escalatedOpportunityOwed: Boolean get() = escalationRequired && escalatedAttemptIds.isEmpty()

  fun target(): RepairTargetProgress =
    RepairTargetProgress(
      criterionRef,
      noProgressOutcomes,
      pendingCheckAttemptIds.isNotEmpty(),
      evidenceMissing,
      escalationRequired,
      escalatedOpportunityOwed,
    )
}

fun acceptanceCriterionRepairAccounting(
  criterionRef: String,
  mutations: List<AcceptanceCriterionCompletedMutation>,
  checks: List<AcceptanceCriterionCheckedMutation>,
  budgets: List<AcceptanceCriterionRepairBudget>,
): AcceptanceCriterionRepairAccounting {
  val completed = mutations.filter { it.criterionRef == criterionRef && it.terminal }
  val byAttempt = checks.associateBy { it.mutation.repairAttemptId }
  val budget = budgets.lastOrNull { it.criterionRef == criterionRef }
  val start = budget?.startMutationIndex ?: 0
  val charged =
    mutations.drop(start).count {
      it in completed && byAttempt[it.repairAttemptId]?.comparison?.classification ==
        AcceptanceCriterionProgressClassification.NO_PROGRESS
    }
  val lastSatisfied =
    completed.indexOfLast {
      byAttempt[it.repairAttemptId]?.comparison?.classification == AcceptanceCriterionProgressClassification.SATISFIED
    }
  val unmet =
    completed.drop(lastSatisfied + 1).filter {
      byAttempt[it.repairAttemptId]?.comparison?.classification in
        setOf(
          AcceptanceCriterionProgressClassification.NO_PROGRESS,
          AcceptanceCriterionProgressClassification.VERIFIED_PARTIAL_PROGRESS,
        )
    }
  val triggers = unmet.take(2).takeIf { it.size == 2 }.orEmpty()
  val afterTrigger = triggers.lastOrNull()?.let { completed.indexOf(it) + 1 } ?: completed.size
  val escalated =
    completed.drop(afterTrigger).filter {
      it.launchAttribution?.let { launch -> launch.reasoning && launch.validFor(it.repairAttemptId) } == true &&
        byAttempt.containsKey(it.repairAttemptId)
    }
  return AcceptanceCriterionRepairAccounting(
    criterionRef = criterionRef,
    budgetIdentity = budget?.retryIdentity ?: "initial",
    completedAttemptIds = completed.map { it.repairAttemptId },
    noProgressOutcomes = minOf(charged, FocusedRepairStrategy.MAX_UNSUCCESSFUL_OUTCOMES),
    pendingCheckAttemptIds = completed.filter { it.repairAttemptId !in byAttempt }.map { it.repairAttemptId },
    escalationRequired = triggers.isNotEmpty(),
    triggeringAttemptIds = triggers.map { it.repairAttemptId },
    escalatedAttemptIds = escalated.map { it.repairAttemptId },
    evidenceMissing = mutations.any { it.criterionRef == criterionRef && !it.terminal },
  )
}

fun validAcceptanceCriterionRepairBudgets(
  budgets: List<AcceptanceCriterionRepairBudget>,
  mutations: List<AcceptanceCriterionCompletedMutation>,
  criterionRefs: List<String>,
): Boolean =
  budgets.size <= AcceptanceCriterionRepairState.MAX_REPAIR_OUTCOMES &&
    budgets.map { it.retryIdentity }.distinct().size == budgets.size &&
    budgets.all {
      it.criterionRef in criterionRefs && it.retryIdentity.validText(MAX_PROGRESS_ID_LENGTH) &&
        it.startMutationIndex in 0..mutations.size
    } && budgets.zipWithNext().all { (before, after) -> before.startMutationIndex <= after.startMutationIndex }

val AcceptanceCriterionCompletedMutation.terminal: Boolean
  get() = terminalDisposition != AcceptanceCriterionMutationDisposition.HISTORICAL_UNKNOWN

fun AcceptanceCriterionRepairState.repairAccounting(ref: String): AcceptanceCriterionRepairAccounting =
  acceptanceCriterionRepairAccounting(ref, completedMutations, independentChecks, repairBudgets).let { accounting ->
    if (ref != currentCriterionRef || pendingClarificationQuestion == null) accounting
    else accounting.copy(evidenceMissing = true)
  }

internal fun AcceptanceCriterionRepairState.budgetBlocks(ref: String): Boolean {
  val accounting = repairAccounting(ref)
  return FocusedRepairStrategy.exhausted(accounting.noProgressOutcomes) &&
    !accounting.escalatedOpportunityOwed && accounting.pendingCheckAttemptIds.isEmpty() && !accounting.evidenceMissing
}
