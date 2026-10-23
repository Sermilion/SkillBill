package skillbill.workflow.taskruntime.model.blockincident

import java.time.Instant
import java.util.Collections

data class BlockIncidentScope(
  val workflowId: String? = null,
  val parentWorkflowId: String? = null,
  val phaseId: String? = null,
  val phaseRunId: String? = null,
  val invocationId: String? = null,
  val executionId: String? = null,
  val attempt: Int? = null,
  val generation: Long? = null,
)

data class BlockIncidentFailure(
  val origin: BlockIncidentOrigin,
  val reason: String,
  val reasonTruncated: Boolean = false,
  val originalReasonLength: Int = reason.length,
  val originalReasonDigest: String? = null,
  val reasonRetention: BlockIncidentReasonRetention,
  val failureCode: String? = null,
  val failureDisposition: String? = null,
  val throwableType: String? = null,
  val exitCode: Int? = null,
  val exitBehavior: BlockIncidentExitBehavior,
)

data class BlockIncidentCorrelation(
  val repairTurn: Int? = null,
  val outputGeneration: Long? = null,
  val monitorSelector: String? = null,
  val pullRequestId: String? = null,
  val pullRequestUrl: String? = null,
  val headRepository: String? = null,
  val headBranch: String? = null,
  val worktreePath: String? = null,
)

class BlockIncidentOccurrence(
  val occurrenceToken: String,
  val scope: BlockIncidentScope,
  val failure: BlockIncidentFailure,
  val observedAt: Instant,
  val evidenceAvailability: BlockIncidentEvidenceAvailability,
  val identityAvailability: BlockIncidentIdentityAvailability,
  fieldAvailability: Map<BlockIncidentCorrelatedField, BlockIncidentFieldAvailability>,
  val correlation: BlockIncidentCorrelation = BlockIncidentCorrelation(),
  retainedEvidenceReferences: List<BlockIncidentEvidenceReference> = emptyList(),
) {
  val evidenceReferences: List<BlockIncidentEvidenceReference> =
    Collections.unmodifiableList(retainedEvidenceReferences.toList())
  val fieldAvailability: Map<BlockIncidentCorrelatedField, BlockIncidentFieldAvailability> =
    Collections.unmodifiableMap(LinkedHashMap(fieldAvailability))

  val workflowId: String? get() = scope.workflowId
  val parentWorkflowId: String? get() = scope.parentWorkflowId
  val phaseId: String? get() = scope.phaseId
  val phaseRunId: String? get() = scope.phaseRunId
  val invocationId: String? get() = scope.invocationId
  val executionId: String? get() = scope.executionId
  val attempt: Int? get() = scope.attempt
  val generation: Long? get() = scope.generation
  val origin: BlockIncidentOrigin get() = failure.origin
  val reason: String get() = failure.reason
  val reasonTruncated: Boolean get() = failure.reasonTruncated
  val originalReasonLength: Int get() = failure.originalReasonLength
  val originalReasonDigest: String? get() = failure.originalReasonDigest
  val reasonRetention: BlockIncidentReasonRetention get() = failure.reasonRetention
  val failureCode: String? get() = failure.failureCode
  val failureDisposition: String? get() = failure.failureDisposition
  val throwableType: String? get() = failure.throwableType
  val exitCode: Int? get() = failure.exitCode
  val exitBehavior: BlockIncidentExitBehavior get() = failure.exitBehavior
  val repairTurn: Int? get() = correlation.repairTurn
  val outputGeneration: Long? get() = correlation.outputGeneration
  val monitorSelector: String? get() = correlation.monitorSelector
  val pullRequestId: String? get() = correlation.pullRequestId
  val pullRequestUrl: String? get() = correlation.pullRequestUrl
  val headRepository: String? get() = correlation.headRepository
  val headBranch: String? get() = correlation.headBranch
  val worktreePath: String? get() = correlation.worktreePath

  fun correlatedValue(field: BlockIncidentCorrelatedField): Any? = field.read(this)

  override fun equals(other: Any?): Boolean =
    other is BlockIncidentOccurrence &&
      occurrenceToken == other.occurrenceToken && scope == other.scope && failure == other.failure &&
      observedAt == other.observedAt && evidenceAvailability == other.evidenceAvailability &&
      identityAvailability == other.identityAvailability && fieldAvailability == other.fieldAvailability &&
      correlation == other.correlation && evidenceReferences == other.evidenceReferences

  override fun hashCode(): Int =
    listOf(
      occurrenceToken, scope, failure, observedAt, evidenceAvailability, identityAvailability, fieldAvailability,
      correlation, evidenceReferences,
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

  fun validation(): List<BlockIncidentViolation> =
    buildList {
      addIdentityViolations()
      addFailureViolations()
      addEvidenceViolations()
      addAliasViolations()
      addStateViolations()
    }

  fun isValid(): Boolean = validation().isEmpty()

  private fun MutableList<BlockIncidentViolation>.addIdentityViolations() {
    if (!OCCURRENCE_TOKEN_PATTERN.matches(occurrence.occurrenceToken)) {
      addViolation(BlockIncidentViolationCode.INVALID_OCCURRENCE_TOKEN, "occurrence.occurrence_token")
    }
    if (!occurrence.generation.isValidOptionalPositive()) {
      addViolation(BlockIncidentViolationCode.INVALID_GENERATION, "occurrence.generation")
    }
    if (!occurrence.attempt.isValidOptionalPositive()) {
      addViolation(BlockIncidentViolationCode.INVALID_ATTEMPT, "occurrence.attempt")
    }
    listOf(
      occurrence.workflowId,
      occurrence.parentWorkflowId,
      occurrence.phaseId,
      occurrence.phaseRunId,
      occurrence.invocationId,
      occurrence.executionId,
    ).forEachIndexed { index, value ->
      if (!value.isValidOptionalBounded(MAX_IDENTITY_CHARS)) {
        addViolation(BlockIncidentViolationCode.INVALID_IDENTITY, IDENTITY_FIELDS[index])
      }
    }
    if (!occurrence.hasConsistentFieldAvailability()) {
      addViolation(BlockIncidentViolationCode.FIELD_AVAILABILITY_MISMATCH, "occurrence.field_availability")
    }
    if (!occurrence.identityAvailability.isConsistentWith(occurrence.fieldAvailability)) {
      addViolation(BlockIncidentViolationCode.IDENTITY_AVAILABILITY_MISMATCH, "occurrence.identity_availability")
    }
  }

  private fun MutableList<BlockIncidentViolation>.addFailureViolations() {
    val failure = occurrence.failure
    if (!failure.reason.isValidOptionalBounded(MAX_REASON_CHARS)) {
      addViolation(BlockIncidentViolationCode.INVALID_REASON, "occurrence.reason")
    }
    if (!failure.hasCoherentReasonTruncation()) {
      addViolation(BlockIncidentViolationCode.INCOHERENT_REASON_TRUNCATION, "occurrence.reason_truncated")
    }
    if (failure.originalReasonDigest != null && !DIGEST_PATTERN.matches(failure.originalReasonDigest)) {
      addViolation(BlockIncidentViolationCode.INVALID_ORIGINAL_REASON_DIGEST, "occurrence.original_reason_digest")
    }
    if ((failure.exitBehavior == BlockIncidentExitBehavior.EXITED_WITH_CODE) != (failure.exitCode != null)) {
      addViolation(BlockIncidentViolationCode.INCOHERENT_EXIT, "occurrence.exit_code")
    }
    if (!failure.hasValidFailureFacts()) {
      addViolation(BlockIncidentViolationCode.INVALID_FAILURE_FACT, "occurrence.failure_code")
    }
    if (!occurrence.correlation.isValid()) {
      addViolation(BlockIncidentViolationCode.INVALID_CORRELATION_FACT, "occurrence.correlation")
    }
  }

  private fun MutableList<BlockIncidentViolation>.addEvidenceViolations() {
    val references = occurrence.evidenceReferences
    if (references.size > MAX_EVIDENCE_REFERENCES) {
      addViolation(BlockIncidentViolationCode.TOO_MANY_EVIDENCE_REFERENCES, "occurrence.evidence_references")
    }
    if (references.selectedEvidenceBytes() > MAX_SELECTED_EVIDENCE_BYTES) {
      addViolation(BlockIncidentViolationCode.EVIDENCE_SELECTION_TOO_LARGE, "occurrence.evidence_references")
    }
    if (references.distinctBy { it.selectionKey() }.size != references.size) {
      addViolation(BlockIncidentViolationCode.DUPLICATE_EVIDENCE_REFERENCE, "occurrence.evidence_references")
    }
    references.forEachIndexed { index, evidence ->
      if (!evidence.isValid()) {
        addViolation(BlockIncidentViolationCode.INVALID_EVIDENCE_REFERENCE, "occurrence.evidence_references[$index]")
      }
      if (evidence.exceedsRetainedSize()) {
        addViolation(
          BlockIncidentViolationCode.EVIDENCE_SOURCE_TOO_LARGE,
          "occurrence.evidence_references[$index].byte_size",
        )
      }
    }
  }

  private fun MutableList<BlockIncidentViolation>.addAliasViolations() {
    val distinct = aliases.distinctBy { it.kind to it.value }.size == aliases.size
    val bounded = aliases.all { it.value.isValidOptionalBounded(MAX_ALIAS_CHARS) }
    if (aliases.size !in 1..MAX_ALIASES || !distinct || !bounded) {
      addViolation(BlockIncidentViolationCode.INVALID_ALIASES, "aliases")
    }
  }

  private fun MutableList<BlockIncidentViolation>.addStateViolations() {
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

  private fun MutableList<BlockIncidentViolation>.addViolation(
    code: BlockIncidentViolationCode,
    field: String,
  ) {
    add(BlockIncidentViolation(code, field))
  }

  private fun String?.isValidOptionalBounded(limit: Int): Boolean =
    this == null || (this.isNotBlank() && this.length <= limit)

  private fun Int?.isValidOptionalNonnegative(): Boolean = this == null || this >= 0

  private fun Long?.isValidOptionalNonnegative(): Boolean = this == null || this >= 0

  private fun Int?.isValidOptionalPositive(): Boolean = this == null || this > 0

  private fun Long?.isValidOptionalPositive(): Boolean = this == null || this > 0

  private fun BlockIncidentFailure.hasCoherentReasonTruncation(): Boolean {
    val lengthCoherent =
      originalReasonLength >= reason.length && reasonTruncated == (originalReasonLength > reason.length)
    return lengthCoherent &&
      reasonTruncated == (originalReasonDigest != null) &&
      reasonRetention.isConsistentWith(reasonTruncated)
  }

  private fun BlockIncidentFailure.hasValidFailureFacts(): Boolean =
    failureCode.isValidOptionalBounded(MAX_FAILURE_CODE_CHARS) &&
      failureDisposition.isValidOptionalBounded(MAX_FAILURE_DISPOSITION_CHARS) &&
      throwableType.isValidOptionalBounded(MAX_THROWABLE_TYPE_CHARS)

  private fun BlockIncidentCorrelation.isValid(): Boolean =
    repairTurn.isValidOptionalNonnegative() &&
      outputGeneration.isValidOptionalNonnegative() &&
      listOf(
        monitorSelector to MAX_MONITOR_SELECTOR_CHARS,
        pullRequestId to MAX_PR_ID_CHARS,
        pullRequestUrl to MAX_PR_URL_CHARS,
        headRepository to MAX_REPOSITORY_CHARS,
        headBranch to MAX_BRANCH_CHARS,
        worktreePath to MAX_WORKTREE_PATH_CHARS,
      ).all { (value, limit) -> value.isValidOptionalBounded(limit) }

  private fun List<BlockIncidentEvidenceReference>.selectedEvidenceBytes(): Long =
    filter { it.availability == BlockIncidentEvidenceReferenceAvailability.AVAILABLE }
      .mapNotNull { it.byteSize }
      .fold(0L) { total, size ->
        if (size > MAX_SELECTED_EVIDENCE_BYTES - total) MAX_SELECTED_EVIDENCE_BYTES + 1 else total + size
      }

  private fun BlockIncidentEvidenceReference.selectionKey(): List<String?> =
    listOf(
      kind.wireValue,
      provenance.wireValue,
      sourceIdentity ?: source,
      repairTurn?.toString(),
      outputGeneration?.toString(),
      sourceOffset?.toString(),
      sourceLine?.toString(),
      sourceColumn?.toString(),
    )

  private fun BlockIncidentEvidenceReference.exceedsRetainedSize(): Boolean =
    availability == BlockIncidentEvidenceReferenceAvailability.AVAILABLE &&
      byteSize != null &&
      byteSize > MAX_RETAINED_EVIDENCE_BYTES

  private fun BlockIncidentOccurrence.hasConsistentFieldAvailability(): Boolean {
    if (fieldAvailability.keys != BlockIncidentCorrelatedField.entries.toSet()) return false
    val historical = fieldAvailability.values.all { it == BlockIncidentFieldAvailability.UNKNOWN_HISTORICAL }
    if (historical) return true
    return BlockIncidentCorrelatedField.entries.all { field ->
      val value = correlatedValue(field)
      when (fieldAvailability.getValue(field)) {
        BlockIncidentFieldAvailability.AVAILABLE -> value != null
        BlockIncidentFieldAvailability.NOT_CAPTURED -> value == null || field == BlockIncidentCorrelatedField.REASON
        BlockIncidentFieldAvailability.NOT_APPLICABLE -> value == null
        BlockIncidentFieldAvailability.UNKNOWN_HISTORICAL -> false
      }
    } && (
      origin != BlockIncidentOrigin.ACCEPTED_GOAL_BLOCK ||
        GOAL_NOT_APPLICABLE_FIELDS.all { field ->
          correlatedValue(field) != null ||
            fieldAvailability.getValue(field) == BlockIncidentFieldAvailability.NOT_APPLICABLE
        }
    )
  }

  private fun BlockIncidentIdentityAvailability.isConsistentWith(
    fieldAvailability: Map<BlockIncidentCorrelatedField, BlockIncidentFieldAvailability>,
  ): Boolean {
    val identities = IDENTITY_CORRELATED_FIELDS.mapNotNull { fieldAvailability[it] }
    val available = identities.count { it == BlockIncidentFieldAvailability.AVAILABLE }
    val notCaptured = identities.count { it == BlockIncidentFieldAvailability.NOT_CAPTURED }
    if (fieldAvailability.values.all { it == BlockIncidentFieldAvailability.UNKNOWN_HISTORICAL }) return true
    return when (this) {
      BlockIncidentIdentityAvailability.AVAILABLE -> available > 0 && notCaptured == 0
      BlockIncidentIdentityAvailability.PARTIAL -> available > 0 && notCaptured > 0
      BlockIncidentIdentityAvailability.UNAVAILABLE -> available == 0
      BlockIncidentIdentityAvailability.UNKNOWN_HISTORICAL -> false
    }
  }

  private fun BlockIncidentReasonRetention.isConsistentWith(reasonTruncated: Boolean): Boolean =
    when (this) {
      BlockIncidentReasonRetention.COMPLETE -> !reasonTruncated
      BlockIncidentReasonRetention.PREFIX_WITH_SHA256 -> reasonTruncated
      BlockIncidentReasonRetention.UNKNOWN_HISTORICAL -> true
    }

  private fun BlockIncidentEvidenceReference.isValid(): Boolean =
    hasValidText() && hasValidPosition() && hasAvailableFacts() && hasCoherentProvenance()

  private fun BlockIncidentEvidenceReference.hasValidText(): Boolean =
    source.isValidOptionalBounded(MAX_EVIDENCE_SOURCE_CHARS) &&
      listOf(reference, sourceIdentity, retainedReferenceId).all {
        it.isValidOptionalBounded(MAX_EVIDENCE_REFERENCE_CHARS)
      } &&
      (digest == null || DIGEST_PATTERN.matches(digest))

  private fun BlockIncidentEvidenceReference.hasValidPosition(): Boolean {
    val counters =
      byteSize.isValidOptionalNonnegative() &&
        repairTurn.isValidOptionalNonnegative() &&
        outputGeneration.isValidOptionalNonnegative()
    val location =
      sourceOffset.isValidOptionalNonnegative() &&
        sourceLine.isValidOptionalPositive() &&
        sourceColumn.isValidOptionalPositive()
    return counters && location && (sourceLine == null) == (sourceColumn == null)
  }

  private fun BlockIncidentEvidenceReference.hasAvailableFacts(): Boolean {
    if (availability != BlockIncidentEvidenceReferenceAvailability.AVAILABLE) return true
    val retained = listOf(reference, digest, byteSize, retainedReferenceId, sourceIdentity).all { it != null }
    val producerTurn =
      kind != BlockIncidentEvidenceKind.PRODUCER_OUTPUT || repairTurn != null && outputGeneration != null
    return retained && provenance != BlockIncidentEvidenceProvenance.UNKNOWN_HISTORICAL && producerTurn
  }

  private fun BlockIncidentEvidenceReference.hasCoherentProvenance(): Boolean =
    when (kind) {
      BlockIncidentEvidenceKind.PRODUCER_OUTPUT ->
        provenance == BlockIncidentEvidenceProvenance.ORIGINAL_PRODUCER_OUTPUT ||
          provenance == BlockIncidentEvidenceProvenance.REPAIRED_PRODUCER_OUTPUT
      BlockIncidentEvidenceKind.REJECTED_OUTPUT_DIAGNOSTIC ->
        provenance == BlockIncidentEvidenceProvenance.REJECTED_OUTPUT
      BlockIncidentEvidenceKind.OTHER -> true
    }

  private fun BlockIncidentClaim?.isValidClaim(
    deadline: Instant,
    observedAt: Instant,
  ): Boolean =
    this == null ||
      (
        ownerToken.isNotBlank() && ownerToken.length <= MAX_IDENTITY_CHARS && ownerGeneration > 0 &&
          hostIdentity.isNotBlank() && hostIdentity.length <= MAX_IDENTITY_CHARS && processId > 0 &&
          expiresAt.isAfter(observedAt) && !expiresAt.isAfter(deadline)
      )

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
    private val IDENTITY_FIELDS =
      listOf(
        "occurrence.workflow_id",
        "occurrence.parent_workflow_id",
        "occurrence.phase_id",
        "occurrence.phase_run_id",
        "occurrence.invocation_id",
        "occurrence.execution_id",
      )
    private val IDENTITY_CORRELATED_FIELDS =
      listOf(
        BlockIncidentCorrelatedField.WORKFLOW_ID, BlockIncidentCorrelatedField.PARENT_WORKFLOW_ID,
        BlockIncidentCorrelatedField.PHASE_ID, BlockIncidentCorrelatedField.PHASE_RUN_ID,
        BlockIncidentCorrelatedField.INVOCATION_ID, BlockIncidentCorrelatedField.EXECUTION_ID,
        BlockIncidentCorrelatedField.MONITOR_SELECTOR, BlockIncidentCorrelatedField.PULL_REQUEST_ID,
        BlockIncidentCorrelatedField.PULL_REQUEST_URL, BlockIncidentCorrelatedField.HEAD_REPOSITORY,
        BlockIncidentCorrelatedField.HEAD_BRANCH, BlockIncidentCorrelatedField.WORKTREE_PATH,
      )
    private val GOAL_NOT_APPLICABLE_FIELDS =
      listOf(
        BlockIncidentCorrelatedField.PHASE_ID,
        BlockIncidentCorrelatedField.PHASE_RUN_ID,
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

enum class BlockIncidentCorrelatedField(
  val wireValue: String,
  val read: (BlockIncidentOccurrence) -> Any?,
) {
  WORKFLOW_ID("workflow_id", BlockIncidentOccurrence::workflowId),
  PARENT_WORKFLOW_ID("parent_workflow_id", BlockIncidentOccurrence::parentWorkflowId),
  PHASE_ID("phase_id", BlockIncidentOccurrence::phaseId),
  PHASE_RUN_ID("phase_run_id", BlockIncidentOccurrence::phaseRunId),
  INVOCATION_ID("invocation_id", BlockIncidentOccurrence::invocationId),
  EXECUTION_ID("execution_id", BlockIncidentOccurrence::executionId),
  ATTEMPT("attempt", BlockIncidentOccurrence::attempt),
  GENERATION("generation", BlockIncidentOccurrence::generation),
  FAILURE_CODE("failure_code", BlockIncidentOccurrence::failureCode),
  THROWABLE_TYPE("throwable_type", BlockIncidentOccurrence::throwableType),
  EXIT_CODE("exit_code", BlockIncidentOccurrence::exitCode),
  REASON("reason", BlockIncidentOccurrence::reason),
  REPAIR_TURN("repair_turn", BlockIncidentOccurrence::repairTurn),
  OUTPUT_GENERATION("output_generation", BlockIncidentOccurrence::outputGeneration),
  MONITOR_SELECTOR("monitor_selector", BlockIncidentOccurrence::monitorSelector),
  PULL_REQUEST_ID("pull_request_id", BlockIncidentOccurrence::pullRequestId),
  PULL_REQUEST_URL("pull_request_url", BlockIncidentOccurrence::pullRequestUrl),
  HEAD_REPOSITORY("head_repository", BlockIncidentOccurrence::headRepository),
  HEAD_BRANCH("head_branch", BlockIncidentOccurrence::headBranch),
  WORKTREE_PATH("worktree_path", BlockIncidentOccurrence::worktreePath),
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

object StandalonePhaseIncidentAlias {
  fun create(
    invocationId: String,
    stepId: String,
  ): BlockIncidentAlias = BlockIncidentAlias(BlockIncidentAliasKind.PHASE_TRANSITION, "$invocationId:$stepId")

  fun invocationPrefix(invocationId: String): String = "$invocationId:"

  fun belongsToInvocation(
    alias: BlockIncidentAlias,
    invocationId: String,
  ): Boolean = alias.kind == BlockIncidentAliasKind.PHASE_TRANSITION && belongsToInvocation(alias.value, invocationId)

  fun belongsToInvocation(
    aliasValue: String,
    invocationId: String,
  ): Boolean {
    val prefix = invocationPrefix(invocationId)
    val stepId = aliasValue.takeIf { it.startsWith(prefix) }?.removePrefix(prefix) ?: return false
    return stepId.isNotBlank() && ':' !in stepId
  }
}
