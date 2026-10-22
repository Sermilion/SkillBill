package skillbill.workflow.taskruntime.model.blockincident

import java.time.Instant
import java.util.Collections

class BlockIncidentOccurrence(
  val occurrenceToken: String,
  val workflowId: String?,
  val parentWorkflowId: String?,
  val phaseId: String?,
  val phaseRunId: String?,
  val invocationId: String?,
  val executionId: String?,
  val attempt: Int?,
  val generation: Long?,
  val origin: BlockIncidentOrigin,
  val reason: String,
  val reasonTruncated: Boolean = false,
  val originalReasonLength: Int = reason.length,
  val originalReasonDigest: String? = null,
  val failureCode: String?,
  val failureDisposition: String?,
  val throwableType: String?,
  val exitCode: Int?,
  val observedAt: Instant,
  val evidenceAvailability: BlockIncidentEvidenceAvailability,
  val identityAvailability: BlockIncidentIdentityAvailability,
  val repairTurn: Int? = null,
  val outputGeneration: Long? = null,
  val monitorSelector: String? = null,
  val pullRequestId: String? = null,
  val pullRequestUrl: String? = null,
  val headRepository: String? = null,
  val headBranch: String? = null,
  val worktreePath: String? = null,
  val exitBehavior: BlockIncidentExitBehavior,
  fieldAvailability: Map<BlockIncidentCorrelatedField, BlockIncidentFieldAvailability>,
  val reasonRetention: BlockIncidentReasonRetention,
  retainedEvidenceReferences: List<BlockIncidentEvidenceReference> = emptyList(),
) {
  val evidenceReferences: List<BlockIncidentEvidenceReference> =
    Collections.unmodifiableList(retainedEvidenceReferences.toList())
  val fieldAvailability: Map<BlockIncidentCorrelatedField, BlockIncidentFieldAvailability> =
    Collections.unmodifiableMap(LinkedHashMap(fieldAvailability))

  fun copy(
    occurrenceToken: String = this.occurrenceToken,
    workflowId: String? = this.workflowId,
    parentWorkflowId: String? = this.parentWorkflowId,
    phaseId: String? = this.phaseId,
    phaseRunId: String? = this.phaseRunId,
    invocationId: String? = this.invocationId,
    executionId: String? = this.executionId,
    attempt: Int? = this.attempt,
    generation: Long? = this.generation,
    origin: BlockIncidentOrigin = this.origin,
    reason: String = this.reason,
    reasonTruncated: Boolean = this.reasonTruncated,
    originalReasonLength: Int = this.originalReasonLength,
    originalReasonDigest: String? = this.originalReasonDigest,
    failureCode: String? = this.failureCode,
    failureDisposition: String? = this.failureDisposition,
    throwableType: String? = this.throwableType,
    exitCode: Int? = this.exitCode,
    observedAt: Instant = this.observedAt,
    evidenceAvailability: BlockIncidentEvidenceAvailability = this.evidenceAvailability,
    identityAvailability: BlockIncidentIdentityAvailability = this.identityAvailability,
    repairTurn: Int? = this.repairTurn,
    outputGeneration: Long? = this.outputGeneration,
    monitorSelector: String? = this.monitorSelector,
    pullRequestId: String? = this.pullRequestId,
    pullRequestUrl: String? = this.pullRequestUrl,
    headRepository: String? = this.headRepository,
    headBranch: String? = this.headBranch,
    worktreePath: String? = this.worktreePath,
    exitBehavior: BlockIncidentExitBehavior = this.exitBehavior,
    fieldAvailability: Map<BlockIncidentCorrelatedField, BlockIncidentFieldAvailability> = this.fieldAvailability,
    reasonRetention: BlockIncidentReasonRetention = this.reasonRetention,
    retainedEvidenceReferences: List<BlockIncidentEvidenceReference> = this.evidenceReferences,
  ): BlockIncidentOccurrence = BlockIncidentOccurrence(
    occurrenceToken, workflowId, parentWorkflowId, phaseId, phaseRunId, invocationId, executionId, attempt,
    generation, origin, reason, reasonTruncated, originalReasonLength, originalReasonDigest, failureCode,
    failureDisposition, throwableType, exitCode, observedAt, evidenceAvailability, identityAvailability,
    repairTurn, outputGeneration, monitorSelector, pullRequestId, pullRequestUrl, headRepository, headBranch,
    worktreePath, exitBehavior, fieldAvailability, reasonRetention, retainedEvidenceReferences,
  )

  fun correlatedValue(field: BlockIncidentCorrelatedField): Any? = when (field) {
    BlockIncidentCorrelatedField.WORKFLOW_ID -> workflowId
    BlockIncidentCorrelatedField.PARENT_WORKFLOW_ID -> parentWorkflowId
    BlockIncidentCorrelatedField.PHASE_ID -> phaseId
    BlockIncidentCorrelatedField.PHASE_RUN_ID -> phaseRunId
    BlockIncidentCorrelatedField.INVOCATION_ID -> invocationId
    BlockIncidentCorrelatedField.EXECUTION_ID -> executionId
    BlockIncidentCorrelatedField.ATTEMPT -> attempt
    BlockIncidentCorrelatedField.GENERATION -> generation
    BlockIncidentCorrelatedField.FAILURE_CODE -> failureCode
    BlockIncidentCorrelatedField.THROWABLE_TYPE -> throwableType
    BlockIncidentCorrelatedField.EXIT_CODE -> exitCode
    BlockIncidentCorrelatedField.REASON -> reason
    BlockIncidentCorrelatedField.REPAIR_TURN -> repairTurn
    BlockIncidentCorrelatedField.OUTPUT_GENERATION -> outputGeneration
    BlockIncidentCorrelatedField.MONITOR_SELECTOR -> monitorSelector
    BlockIncidentCorrelatedField.PULL_REQUEST_ID -> pullRequestId
    BlockIncidentCorrelatedField.PULL_REQUEST_URL -> pullRequestUrl
    BlockIncidentCorrelatedField.HEAD_REPOSITORY -> headRepository
    BlockIncidentCorrelatedField.HEAD_BRANCH -> headBranch
    BlockIncidentCorrelatedField.WORKTREE_PATH -> worktreePath
  }

  override fun equals(other: Any?): Boolean = other is BlockIncidentOccurrence &&
    occurrenceToken == other.occurrenceToken && workflowId == other.workflowId &&
    parentWorkflowId == other.parentWorkflowId && phaseId == other.phaseId && phaseRunId == other.phaseRunId &&
    invocationId == other.invocationId && executionId == other.executionId && attempt == other.attempt &&
    generation == other.generation && origin == other.origin && reason == other.reason &&
    reasonTruncated == other.reasonTruncated && originalReasonLength == other.originalReasonLength &&
    originalReasonDigest == other.originalReasonDigest && failureCode == other.failureCode &&
    failureDisposition == other.failureDisposition && throwableType == other.throwableType &&
    exitCode == other.exitCode &&
    observedAt == other.observedAt && evidenceAvailability == other.evidenceAvailability &&
    identityAvailability == other.identityAvailability && repairTurn == other.repairTurn &&
    outputGeneration == other.outputGeneration && monitorSelector == other.monitorSelector &&
    pullRequestId == other.pullRequestId && pullRequestUrl == other.pullRequestUrl &&
    headRepository == other.headRepository && headBranch == other.headBranch && worktreePath == other.worktreePath &&
    exitBehavior == other.exitBehavior && fieldAvailability == other.fieldAvailability &&
    reasonRetention == other.reasonRetention && evidenceReferences == other.evidenceReferences

  override fun hashCode(): Int = listOf(
    occurrenceToken, workflowId, parentWorkflowId, phaseId, phaseRunId, invocationId, executionId, attempt,
    generation, origin, reason, reasonTruncated, originalReasonLength, originalReasonDigest, failureCode,
    failureDisposition, throwableType, exitCode, observedAt, evidenceAvailability, identityAvailability, repairTurn,
    outputGeneration, monitorSelector, pullRequestId, pullRequestUrl, headRepository, headBranch, worktreePath,
    exitBehavior, fieldAvailability, reasonRetention, evidenceReferences,
  ).hashCode()
}

data class BlockIncidentEvidenceReference(
  val source: String,
  val reference: String?,
  val digest: String?,
  val availability: BlockIncidentEvidenceReferenceAvailability,
  val repairTurn: Int? = null,
  val outputGeneration: Long? = null,
  val kind: BlockIncidentEvidenceKind,
  val provenance: BlockIncidentEvidenceProvenance,
  val byteSize: Long? = null,
  val sourceIdentity: String? = null,
  val retainedReferenceId: String? = null,
  val sourceOffset: Int? = null,
  val sourceLine: Int? = null,
  val sourceColumn: Int? = null,
)

enum class BlockIncidentEvidenceKind(val wireValue: String) {
  REJECTED_OUTPUT_DIAGNOSTIC("rejected_output_diagnostic"),
  PRODUCER_OUTPUT("producer_output"),
  OTHER("other"),
}

enum class BlockIncidentEvidenceProvenance(val wireValue: String) {
  REJECTED_OUTPUT("rejected_output"),
  ORIGINAL_PRODUCER_OUTPUT("original_producer_output"),
  REPAIRED_PRODUCER_OUTPUT("repaired_producer_output"),
  UNKNOWN_HISTORICAL("unknown_historical"),
}

data class BlockIncidentViolation(val code: BlockIncidentViolationCode, val field: String)

enum class BlockIncidentViolationCode(val wireValue: String) {
  INVALID_OCCURRENCE_TOKEN("invalid_occurrence_token"),
  INVALID_GENERATION("invalid_generation"),
  INVALID_ATTEMPT("invalid_attempt"),
  INVALID_REASON("invalid_reason"),
  INCOHERENT_REASON_TRUNCATION("incoherent_reason_truncation"),
  INVALID_ORIGINAL_REASON_DIGEST("invalid_original_reason_digest"),
  INCOHERENT_EXIT("incoherent_exit"),
  INVALID_IDENTITY("invalid_identity"),
  INVALID_FAILURE_FACT("invalid_failure_fact"),
  INVALID_CORRELATION_FACT("invalid_correlation_fact"),
  IDENTITY_AVAILABILITY_MISMATCH("identity_availability_mismatch"),
  FIELD_AVAILABILITY_MISMATCH("field_availability_mismatch"),
  TOO_MANY_EVIDENCE_REFERENCES("too_many_evidence_references"),
  EVIDENCE_SOURCE_TOO_LARGE("evidence_source_too_large"),
  EVIDENCE_SELECTION_TOO_LARGE("evidence_selection_too_large"),
  DUPLICATE_EVIDENCE_REFERENCE("duplicate_evidence_reference"),
  INVALID_EVIDENCE_REFERENCE("invalid_evidence_reference"),
  INVALID_ALIASES("invalid_aliases"),
  INVALID_REVISION("invalid_revision"),
  INVALID_RETRY_COUNT("invalid_retry_count"),
  RETRIES_EXHAUSTED("retries_exhausted"),
  INVALID_DEADLINE("invalid_deadline"),
  INCOHERENT_CLAIM("incoherent_claim"),
  INVALID_CLAIM("invalid_claim"),
  INVALID_ELIGIBILITY("invalid_eligibility"),
}

data class BlockIncidentAlias(
  val kind: BlockIncidentAliasKind,
  val value: String,
)

data class BlockIncidentState(
  val diagnosis: BlockIncidentDiagnosis,
  val recovery: BlockIncidentRecovery,
  val report: BlockIncidentReport,
  val revision: Long,
  val deadline: Instant,
  val retryCount: Int,
  val nextEligibleAt: Instant?,
  val claim: BlockIncidentClaim?,
)

data class BlockIncidentClaim(
  val ownerToken: String,
  val ownerGeneration: Long,
  val hostIdentity: String,
  val processId: Long,
  val expiresAt: Instant,
)

class BlockIncident(
  val occurrence: BlockIncidentOccurrence,
  aliases: List<BlockIncidentAlias>,
  val state: BlockIncidentState,
) {
  val aliases: List<BlockIncidentAlias> = Collections.unmodifiableList(aliases.toList())

  fun copy(
    occurrence: BlockIncidentOccurrence = this.occurrence,
    aliases: List<BlockIncidentAlias> = this.aliases,
    state: BlockIncidentState = this.state,
  ): BlockIncident = BlockIncident(occurrence, aliases, state)

  override fun equals(other: Any?): Boolean =
    other is BlockIncident && occurrence == other.occurrence && aliases == other.aliases && state == other.state

  override fun hashCode(): Int = listOf(occurrence, aliases, state).hashCode()

  fun validation(): List<BlockIncidentViolation> = buildList {
    if (!OCCURRENCE_TOKEN_PATTERN.matches(occurrence.occurrenceToken)) {
      addViolation(BlockIncidentViolationCode.INVALID_OCCURRENCE_TOKEN, "occurrence.occurrence_token")
    }
    if (occurrence.generation != null && occurrence.generation <= 0) {
      addViolation(BlockIncidentViolationCode.INVALID_GENERATION, "occurrence.generation")
    }
    if (occurrence.attempt != null && occurrence.attempt <= 0) {
      addViolation(BlockIncidentViolationCode.INVALID_ATTEMPT, "occurrence.attempt")
    }
    if (occurrence.reason.isBlank() || occurrence.reason.length > MAX_REASON_CHARS) {
      addViolation(BlockIncidentViolationCode.INVALID_REASON, "occurrence.reason")
    }
    if (occurrence.originalReasonLength < occurrence.reason.length ||
      occurrence.reasonTruncated != (occurrence.originalReasonLength > occurrence.reason.length) ||
      occurrence.reasonTruncated != (occurrence.originalReasonDigest != null) ||
      !occurrence.reasonRetention.isConsistentWith(occurrence.reasonTruncated)
    ) addViolation(BlockIncidentViolationCode.INCOHERENT_REASON_TRUNCATION, "occurrence.reason_truncated")
    if (occurrence.originalReasonDigest != null && !DIGEST_PATTERN.matches(occurrence.originalReasonDigest)) {
      addViolation(BlockIncidentViolationCode.INVALID_ORIGINAL_REASON_DIGEST, "occurrence.original_reason_digest")
    }
    if ((occurrence.exitBehavior == BlockIncidentExitBehavior.EXITED_WITH_CODE) != (occurrence.exitCode != null)) {
      addViolation(BlockIncidentViolationCode.INCOHERENT_EXIT, "occurrence.exit_code")
    }
    listOf(
      occurrence.workflowId, occurrence.parentWorkflowId, occurrence.phaseId, occurrence.phaseRunId,
      occurrence.invocationId, occurrence.executionId,
    ).forEachIndexed { index, value ->
      if (!value.isValidOptionalIdentity()) {
        addViolation(BlockIncidentViolationCode.INVALID_IDENTITY, IDENTITY_FIELDS[index])
      }
    }
    if (!occurrence.failureCode.isValidOptionalBounded(MAX_FAILURE_CODE_CHARS) ||
      !occurrence.failureDisposition.isValidOptionalBounded(MAX_FAILURE_DISPOSITION_CHARS) ||
      !occurrence.throwableType.isValidOptionalBounded(MAX_THROWABLE_TYPE_CHARS)
    ) addViolation(BlockIncidentViolationCode.INVALID_FAILURE_FACT, "occurrence.failure_code")
    if (!occurrence.repairTurn.isValidOptionalNonnegative() ||
      !occurrence.outputGeneration.isValidOptionalNonnegative() ||
      !occurrence.monitorSelector.isValidOptionalBounded(MAX_MONITOR_SELECTOR_CHARS) ||
      !occurrence.pullRequestId.isValidOptionalBounded(MAX_PR_ID_CHARS) ||
      !occurrence.pullRequestUrl.isValidOptionalBounded(MAX_PR_URL_CHARS) ||
      !occurrence.headRepository.isValidOptionalBounded(MAX_REPOSITORY_CHARS) ||
      !occurrence.headBranch.isValidOptionalBounded(MAX_BRANCH_CHARS) ||
      !occurrence.worktreePath.isValidOptionalBounded(MAX_WORKTREE_PATH_CHARS)
    ) addViolation(BlockIncidentViolationCode.INVALID_CORRELATION_FACT, "occurrence.correlation")
    if (!occurrence.hasConsistentFieldAvailability()) {
      addViolation(BlockIncidentViolationCode.FIELD_AVAILABILITY_MISMATCH, "occurrence.field_availability")
    }
    if (!occurrence.identityAvailability.isConsistentWith(occurrence.fieldAvailability)) {
      addViolation(BlockIncidentViolationCode.IDENTITY_AVAILABILITY_MISMATCH, "occurrence.identity_availability")
    }
    if (occurrence.evidenceReferences.size > MAX_EVIDENCE_REFERENCES) {
      addViolation(BlockIncidentViolationCode.TOO_MANY_EVIDENCE_REFERENCES, "occurrence.evidence_references")
    }
    val selectedEvidenceBytes = occurrence.evidenceReferences
      .filter { it.availability == BlockIncidentEvidenceReferenceAvailability.AVAILABLE }
      .mapNotNull { it.byteSize }
      .fold(0L) { total, size ->
        if (size > MAX_SELECTED_EVIDENCE_BYTES - total) MAX_SELECTED_EVIDENCE_BYTES + 1 else total + size
      }
    if (selectedEvidenceBytes > MAX_SELECTED_EVIDENCE_BYTES) {
      addViolation(BlockIncidentViolationCode.EVIDENCE_SELECTION_TOO_LARGE, "occurrence.evidence_references")
    }
    if (occurrence.evidenceReferences.distinctBy { evidence ->
        listOf(
          evidence.kind.wireValue,
          evidence.provenance.wireValue,
          evidence.sourceIdentity ?: evidence.source,
          evidence.repairTurn?.toString(),
          evidence.outputGeneration?.toString(),
          evidence.sourceOffset?.toString(),
          evidence.sourceLine?.toString(),
          evidence.sourceColumn?.toString(),
        )
      }.size != occurrence.evidenceReferences.size
    ) {
      addViolation(BlockIncidentViolationCode.DUPLICATE_EVIDENCE_REFERENCE, "occurrence.evidence_references")
    }
    occurrence.evidenceReferences.forEachIndexed { index, evidence ->
      if (!evidence.isValid()) {
        addViolation(BlockIncidentViolationCode.INVALID_EVIDENCE_REFERENCE, "occurrence.evidence_references[$index]")
      }
      if (evidence.availability == BlockIncidentEvidenceReferenceAvailability.AVAILABLE &&
        evidence.byteSize != null && evidence.byteSize > MAX_RETAINED_EVIDENCE_BYTES
      ) {
        addViolation(
          BlockIncidentViolationCode.EVIDENCE_SOURCE_TOO_LARGE,
          "occurrence.evidence_references[$index].byte_size",
        )
      }
    }
    if (aliases.size !in 1..MAX_ALIASES ||
      aliases.distinctBy { it.kind to it.value }.size != aliases.size ||
      aliases.any { it.value.isBlank() || it.value.length > MAX_ALIAS_CHARS }
    ) addViolation(BlockIncidentViolationCode.INVALID_ALIASES, "aliases")
    if (state.revision <= 0) addViolation(BlockIncidentViolationCode.INVALID_REVISION, "state.revision")
    if (state.retryCount !in 0..MAX_RETRIES) {
      addViolation(BlockIncidentViolationCode.INVALID_RETRY_COUNT, "state.retry_count")
    }
    if (state.diagnosis == BlockIncidentDiagnosis.PENDING && state.retryCount == MAX_RETRIES) {
      addViolation(BlockIncidentViolationCode.RETRIES_EXHAUSTED, "state.diagnosis")
    }
    if (!state.deadline.isAfter(occurrence.observedAt)) {
      addViolation(BlockIncidentViolationCode.INVALID_DEADLINE, "state.deadline")
    }
    if ((state.diagnosis == BlockIncidentDiagnosis.INVESTIGATING) != (state.claim != null)) {
      addViolation(BlockIncidentViolationCode.INCOHERENT_CLAIM, "state.claim")
    }
    if (!state.claim.isValidClaim(state.deadline, occurrence.observedAt)) {
      addViolation(BlockIncidentViolationCode.INVALID_CLAIM, "state.claim")
    }
    if (!state.nextEligibleAt.isValidNextEligibility(state.diagnosis, state.deadline, occurrence.observedAt)) {
      addViolation(BlockIncidentViolationCode.INVALID_ELIGIBILITY, "state.next_eligible_at")
    }
  }

  fun isValid(): Boolean = validation().isEmpty()

  private fun MutableList<BlockIncidentViolation>.addViolation(code: BlockIncidentViolationCode, field: String) {
    add(BlockIncidentViolation(code, field))
  }

  private fun String?.isValidOptionalIdentity(): Boolean =
    this == null || (this.isNotBlank() && this.length <= MAX_IDENTITY_CHARS)

  private fun String?.isValidOptionalBounded(limit: Int): Boolean =
    this == null || (this.isNotBlank() && this.length <= limit)

  private fun Int?.isValidOptionalNonnegative(): Boolean = this == null || this >= 0

  private fun Long?.isValidOptionalNonnegative(): Boolean = this == null || this >= 0

  private fun BlockIncidentOccurrence.hasConsistentFieldAvailability(): Boolean {
    if (fieldAvailability.keys != BlockIncidentCorrelatedField.entries.toSet()) return false
    val historical = fieldAvailability.values.all { it == BlockIncidentFieldAvailability.UNKNOWN_HISTORICAL }
    if (historical) return true
    return BlockIncidentCorrelatedField.entries.all { field ->
      val value = correlatedValue(field)
      when (fieldAvailability.getValue(field)) {
        BlockIncidentFieldAvailability.AVAILABLE -> value != null
        // The reason is always required text; NOT_CAPTURED marks a runtime-authored statement.
        BlockIncidentFieldAvailability.NOT_CAPTURED -> value == null || field == BlockIncidentCorrelatedField.REASON
        BlockIncidentFieldAvailability.NOT_APPLICABLE -> value == null
        BlockIncidentFieldAvailability.UNKNOWN_HISTORICAL -> false
      }
    } && (origin != BlockIncidentOrigin.ACCEPTED_GOAL_BLOCK || GOAL_NOT_APPLICABLE_FIELDS.all { field ->
      correlatedValue(field) != null ||
        fieldAvailability.getValue(field) == BlockIncidentFieldAvailability.NOT_APPLICABLE
    })
  }

  private fun BlockIncidentIdentityAvailability.isConsistentWith(
    fieldAvailability: Map<BlockIncidentCorrelatedField, BlockIncidentFieldAvailability>,
  ): Boolean {
    val identities = IDENTITY_CORRELATED_FIELDS.mapNotNull { fieldAvailability[it] }
    val available = identities.count { it == BlockIncidentFieldAvailability.AVAILABLE }
    val notCaptured = identities.count { it == BlockIncidentFieldAvailability.NOT_CAPTURED }
    // Rows written before per-field availability existed keep their recorded summary unchanged.
    if (fieldAvailability.values.all { it == BlockIncidentFieldAvailability.UNKNOWN_HISTORICAL }) return true
    return when (this) {
      BlockIncidentIdentityAvailability.AVAILABLE -> available > 0 && notCaptured == 0
      BlockIncidentIdentityAvailability.PARTIAL -> available > 0 && notCaptured > 0
      BlockIncidentIdentityAvailability.UNAVAILABLE -> available == 0
      BlockIncidentIdentityAvailability.UNKNOWN_HISTORICAL -> false
    }
  }

  private fun BlockIncidentReasonRetention.isConsistentWith(reasonTruncated: Boolean): Boolean = when (this) {
    BlockIncidentReasonRetention.COMPLETE -> !reasonTruncated
    BlockIncidentReasonRetention.PREFIX_WITH_SHA256 -> reasonTruncated
    BlockIncidentReasonRetention.UNKNOWN_HISTORICAL -> true
  }

  private fun BlockIncidentEvidenceReference.isValid(): Boolean =
    source.isNotBlank() && source.length <= MAX_EVIDENCE_SOURCE_CHARS &&
      (reference == null || reference.isNotBlank() && reference.length <= MAX_EVIDENCE_REFERENCE_CHARS) &&
      (digest == null || DIGEST_PATTERN.matches(digest)) &&
      (byteSize == null || byteSize >= 0) &&
      (sourceIdentity == null ||
        sourceIdentity.isNotBlank() && sourceIdentity.length <= MAX_EVIDENCE_REFERENCE_CHARS) &&
      (retainedReferenceId == null ||
        retainedReferenceId.isNotBlank() && retainedReferenceId.length <= MAX_EVIDENCE_REFERENCE_CHARS) &&
      (sourceOffset == null || sourceOffset >= 0) && (sourceLine == null || sourceLine > 0) &&
      (sourceColumn == null || sourceColumn > 0) &&
      ((sourceLine == null) == (sourceColumn == null)) &&
      (availability != BlockIncidentEvidenceReferenceAvailability.AVAILABLE ||
        reference != null && digest != null && byteSize != null && retainedReferenceId != null &&
          sourceIdentity != null && provenance != BlockIncidentEvidenceProvenance.UNKNOWN_HISTORICAL) &&
      (availability != BlockIncidentEvidenceReferenceAvailability.AVAILABLE ||
        kind != BlockIncidentEvidenceKind.PRODUCER_OUTPUT || repairTurn != null && outputGeneration != null) &&
      (kind != BlockIncidentEvidenceKind.PRODUCER_OUTPUT ||
        provenance == BlockIncidentEvidenceProvenance.ORIGINAL_PRODUCER_OUTPUT ||
        provenance == BlockIncidentEvidenceProvenance.REPAIRED_PRODUCER_OUTPUT) &&
      (kind != BlockIncidentEvidenceKind.REJECTED_OUTPUT_DIAGNOSTIC ||
        provenance == BlockIncidentEvidenceProvenance.REJECTED_OUTPUT) &&
      repairTurn.isValidOptionalNonnegative() && outputGeneration.isValidOptionalNonnegative()

  private fun BlockIncidentClaim?.isValidClaim(deadline: Instant, observedAt: Instant): Boolean =
    this == null ||
      (ownerToken.isNotBlank() && ownerToken.length <= MAX_IDENTITY_CHARS && ownerGeneration > 0 &&
        hostIdentity.isNotBlank() && hostIdentity.length <= MAX_IDENTITY_CHARS && processId > 0 &&
        expiresAt.isAfter(observedAt) && !expiresAt.isAfter(deadline))

  private fun Instant?.isValidNextEligibility(
    diagnosis: BlockIncidentDiagnosis,
    deadline: Instant,
    observedAt: Instant,
  ): Boolean =
    when {
      diagnosis == BlockIncidentDiagnosis.PENDING ->
        this?.let { !it.isBefore(observedAt) && it.isBefore(deadline) } ?: true
      else -> this == null
    }

  companion object {
    private const val MAX_REASON_CHARS = 8192
    private const val MAX_ALIAS_CHARS = 512
    private const val MAX_ALIASES = 32
    private const val MAX_IDENTITY_CHARS = 512
    private const val MAX_MONITOR_SELECTOR_CHARS = 2048
    private const val MAX_PR_ID_CHARS = 512
    private const val MAX_PR_URL_CHARS = 2048
    private const val MAX_REPOSITORY_CHARS = 512
    private const val MAX_BRANCH_CHARS = 512
    private const val MAX_WORKTREE_PATH_CHARS = 4096
    private const val MAX_EVIDENCE_REFERENCES = 16
    private const val MAX_RETAINED_EVIDENCE_BYTES = 8 * 1024L
    private const val MAX_SELECTED_EVIDENCE_BYTES = 64 * 1024L
    private const val MAX_EVIDENCE_SOURCE_CHARS = 512
    private const val MAX_EVIDENCE_REFERENCE_CHARS = 2048
    private const val MAX_FAILURE_CODE_CHARS = 256
    private const val MAX_FAILURE_DISPOSITION_CHARS = 256
    private const val MAX_THROWABLE_TYPE_CHARS = 512
    private const val MAX_RETRIES = 2
    private val IDENTITY_FIELDS = listOf(
      "occurrence.workflow_id", "occurrence.parent_workflow_id", "occurrence.phase_id",
      "occurrence.phase_run_id", "occurrence.invocation_id", "occurrence.execution_id",
    )
    private val IDENTITY_CORRELATED_FIELDS = listOf(
      BlockIncidentCorrelatedField.WORKFLOW_ID, BlockIncidentCorrelatedField.PARENT_WORKFLOW_ID,
      BlockIncidentCorrelatedField.PHASE_ID, BlockIncidentCorrelatedField.PHASE_RUN_ID,
      BlockIncidentCorrelatedField.INVOCATION_ID, BlockIncidentCorrelatedField.EXECUTION_ID,
      BlockIncidentCorrelatedField.MONITOR_SELECTOR, BlockIncidentCorrelatedField.PULL_REQUEST_ID,
      BlockIncidentCorrelatedField.PULL_REQUEST_URL, BlockIncidentCorrelatedField.HEAD_REPOSITORY,
      BlockIncidentCorrelatedField.HEAD_BRANCH, BlockIncidentCorrelatedField.WORKTREE_PATH,
    )
    private val GOAL_NOT_APPLICABLE_FIELDS = listOf(
      BlockIncidentCorrelatedField.PHASE_ID, BlockIncidentCorrelatedField.PHASE_RUN_ID,
      BlockIncidentCorrelatedField.ATTEMPT,
    )
    private val OCCURRENCE_TOKEN_PATTERN = Regex("bi_[0-9a-f]{64}")
    private val DIGEST_PATTERN = Regex("[0-9a-f]{64}")
  }
}

enum class BlockIncidentOrigin(val wireValue: String) {
  ACCEPTED_PHASE_BLOCK("accepted_phase_block"),
  ACCEPTED_GOAL_BLOCK("accepted_goal_block"),
  ENTRY_FAILURE("entry_failure"),
  ADMISSION_REFUSAL("admission_refusal"),
}

enum class BlockIncidentAliasKind(val wireValue: String) {
  SETTLEMENT("settlement"),
  PHASE_TRANSITION("phase_transition"),
  CHILD_CAUSE("child_cause"),
  ENTRY_BOUNDARY("entry_boundary"),
}

enum class BlockIncidentEvidenceAvailability(val wireValue: String) {
  DATABASE("database"),
  FALLBACK_SPOOL("fallback_spool"),
  UNSAVED("unsaved"),
}

enum class BlockIncidentIdentityAvailability(val wireValue: String) {
  AVAILABLE("available"),
  PARTIAL("partial"),
  UNAVAILABLE("unavailable"),
  UNKNOWN_HISTORICAL("unknown_historical"),
}

enum class BlockIncidentFieldAvailability(val wireValue: String) {
  AVAILABLE("available"),
  NOT_APPLICABLE("not_applicable"),
  NOT_CAPTURED("not_captured"),
  UNKNOWN_HISTORICAL("unknown_historical"),
}

enum class BlockIncidentCorrelatedField(val wireValue: String) {
  WORKFLOW_ID("workflow_id"),
  PARENT_WORKFLOW_ID("parent_workflow_id"),
  PHASE_ID("phase_id"),
  PHASE_RUN_ID("phase_run_id"),
  INVOCATION_ID("invocation_id"),
  EXECUTION_ID("execution_id"),
  ATTEMPT("attempt"),
  GENERATION("generation"),
  FAILURE_CODE("failure_code"),
  THROWABLE_TYPE("throwable_type"),
  EXIT_CODE("exit_code"),
  REASON("reason"),
  REPAIR_TURN("repair_turn"),
  OUTPUT_GENERATION("output_generation"),
  MONITOR_SELECTOR("monitor_selector"),
  PULL_REQUEST_ID("pull_request_id"),
  PULL_REQUEST_URL("pull_request_url"),
  HEAD_REPOSITORY("head_repository"),
  HEAD_BRANCH("head_branch"),
  WORKTREE_PATH("worktree_path"),
}

enum class BlockIncidentReasonRetention(val wireValue: String) {
  COMPLETE("complete"),
  PREFIX_WITH_SHA256("prefix_8192_chars_utf8_sha256"),
  UNKNOWN_HISTORICAL("unknown_historical"),
}

enum class BlockIncidentEvidenceReferenceAvailability(val wireValue: String) {
  AVAILABLE("available"),
  UNAVAILABLE("unavailable"),
}

enum class BlockIncidentExitBehavior(val wireValue: String) {
  RETURNED_FAILURE("returned_failure"),
  THROWN("thrown"),
  CANCELED("canceled"),
  INTERRUPTED("interrupted"),
  EXITED_WITH_CODE("exited_with_code"),
  EXIT_CODE_UNAVAILABLE("exit_code_unavailable"),
  UNKNOWN_HISTORICAL("unknown_historical"),
}

enum class BlockIncidentDiagnosis(val wireValue: String) {
  PENDING("pending"),
  INVESTIGATING("investigating"),
  COMPLETE("complete"),
  PARTIAL("partial"),
  EXHAUSTED("exhausted"),
  TIMED_OUT("timed_out"),
  CANCELED("canceled"),
  UNAVAILABLE_AGENT("unavailable_agent"),
  PERSIST_FAILED("persist_failed"),
  CRASHED("crashed"),
}

enum class BlockIncidentRecovery(val wireValue: String) {
  UNAVAILABLE("unavailable"),
  NOT_STARTED("not_started"),
  COMPLETE("complete"),
  FAILED("failed"),
}

enum class BlockIncidentReport(val wireValue: String) {
  UNAVAILABLE("unavailable"),
  NOT_REQUESTED("not_requested"),
  PENDING("pending"),
  DELIVERED("delivered"),
  FAILED("failed"),
}
