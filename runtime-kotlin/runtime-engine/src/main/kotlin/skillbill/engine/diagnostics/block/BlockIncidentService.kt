package skillbill.engine.diagnostics.block

import skillbill.error.blockincident.BlockIncidentFailureCode
import skillbill.error.core.SkillBillRuntimeException
import skillbill.goalrunner.model.GoalRunnerAcceptedBlockReference
import skillbill.ports.blockincident.BlockIncidentRepository
import skillbill.ports.blockincident.model.BlockIncidentLookup
import skillbill.ports.blockincident.model.BlockIncidentSpoolRecord
import skillbill.workflow.taskruntime.model.blockincident.BlockIncident
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentAlias
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentCorrelatedField
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentCorrelation
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentDiagnosis
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentEvidenceAvailability
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentEvidenceReference
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentExitBehavior
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentFailure
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentFieldAvailability
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentIdentityAvailability
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentOccurrence
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentOrigin
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentReasonRetention
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentScope
import java.security.MessageDigest
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.HexFormat

internal object BlockIncidentService {
  fun observeBlock(
    repository: BlockIncidentRepository,
    clock: Clock,
    observation: BlockIncidentObservation,
  ): BlockIncident {
    val now = clock.instant()
    return observe(repository, observation, now, now, BlockIncidentEvidenceAvailability.DATABASE)
  }

  fun importSpooled(
    repository: BlockIncidentRepository,
    clock: Clock,
    record: BlockIncidentSpoolRecord,
  ): BlockIncident {
    val linkToken =
      record.occurrenceToken
        .takeIf { it != BlockIncidentAliases.token(record.alias) && repository.find(it) != null }
    return observe(
      repository,
      record.toObservation(linkToken),
      record.observedAt,
      clock.instant(),
      BlockIncidentEvidenceAvailability.FALLBACK_SPOOL,
    )
  }

  private fun observe(
    repository: BlockIncidentRepository,
    observation: BlockIncidentObservation,
    observedAt: Instant,
    now: Instant,
    evidenceAvailability: BlockIncidentEvidenceAvailability,
  ): BlockIncident {
    val alias = BlockIncidentAliases.fallback(observation)
    val proposedOccurrence = proposedOccurrence(observation, alias, observedAt, evidenceAvailability)
    val linkedOccurrence = observation.linkOccurrenceToken?.let(repository::find)
    val aliasOccurrence = repository.findByAlias(alias)
    requireLinkable(observation, proposedOccurrence.occurrenceToken, linkedOccurrence, aliasOccurrence)
    val existing = linkedOccurrence ?: aliasOccurrence
    val incident =
      observation.additionalAliases.fold(
        repository.createOrLink(
          occurrence = existing?.occurrence ?: proposedOccurrence,
          alias = alias,
          deadline = existing?.state?.deadline ?: observedAt.plus(INVESTIGATION_PICKUP_WINDOW),
        ),
      ) { linked, additionalAlias ->
        repository.createOrLink(linked.occurrence, additionalAlias, linked.state.deadline)
      }
    if (incident.state.deadline.isAfter(now)) return incident
    repository.expireDeadline(
      incident.occurrence.occurrenceToken,
      incident.state.revision,
      incident.state.claim?.ownerToken,
      incident.state.claim?.ownerGeneration,
    )
    return repository.find(incident.occurrence.occurrenceToken)
      ?: throw SkillBillRuntimeException(
        BlockIncidentFailureCode.INVALID_SCHEMA,
        "Expired block incident disappeared before validated readback.",
      )
  }

  private fun proposedOccurrence(
    observation: BlockIncidentObservation,
    alias: BlockIncidentAlias,
    observedAt: Instant,
    evidenceAvailability: BlockIncidentEvidenceAvailability,
  ): BlockIncidentOccurrence {
    val retainedReason = observation.reason.retainedPrefix()
    val reasonTruncated = retainedReason.length < observation.reason.length
    val fieldAvailability = observation.resolvedFieldAvailability()
    return BlockIncidentOccurrence(
      occurrenceToken = BlockIncidentAliases.token(alias),
      scope =
        BlockIncidentScope(
          workflowId = observation.workflowId,
          parentWorkflowId = observation.parentWorkflowId,
          phaseId = observation.phaseId,
          phaseRunId = observation.phaseRunId,
          invocationId = observation.invocationId,
          executionId = observation.executionId,
          attempt = observation.attempt,
          generation = observation.generation,
        ),
      failure =
        BlockIncidentFailure(
          origin = observation.origin,
          reason = retainedReason,
          reasonTruncated = reasonTruncated,
          originalReasonLength = observation.reason.length,
          originalReasonDigest = observation.reason.takeIf { reasonTruncated }?.digestSha256(),
          reasonRetention =
            if (reasonTruncated) {
              BlockIncidentReasonRetention.PREFIX_WITH_SHA256
            } else {
              BlockIncidentReasonRetention.COMPLETE
            },
          failureCode = observation.failureCode,
          failureDisposition = observation.failureDisposition,
          throwableType = observation.throwableType,
          exitCode = observation.exitCode,
          exitBehavior = observation.exitBehavior,
        ),
      observedAt = observedAt,
      evidenceAvailability = evidenceAvailability,
      identityAvailability = identityAvailability(fieldAvailability),
      fieldAvailability = fieldAvailability,
      correlation =
        BlockIncidentCorrelation(
          repairTurn = observation.repairTurn,
          outputGeneration = observation.outputGeneration,
          monitorSelector = observation.monitorSelector,
          pullRequestId = observation.pullRequestId,
          pullRequestUrl = observation.pullRequestUrl,
          headRepository = observation.headRepository,
          headBranch = observation.headBranch,
          worktreePath = observation.worktreePath,
        ),
      retainedEvidenceReferences = observation.evidenceReferences,
    )
  }

  private fun requireLinkable(
    observation: BlockIncidentObservation,
    proposedToken: String,
    linkedOccurrence: BlockIncident?,
    aliasOccurrence: BlockIncident?,
  ) {
    val linkToken = observation.linkOccurrenceToken ?: return
    if (linkedOccurrence == null && linkToken != proposedToken) {
      throw SkillBillRuntimeException(
        BlockIncidentFailureCode.PERSISTENCE,
        "Linked block incident is not durable yet.",
      )
    }
    val aliasOwnerToken = aliasOccurrence?.occurrence?.occurrenceToken ?: return
    if (aliasOwnerToken != linkedOccurrence?.occurrence?.occurrenceToken) {
      throw SkillBillRuntimeException(
        BlockIncidentFailureCode.IMMUTABLE_CONFLICT,
        "Block incident alias is already owned by a different linked occurrence.",
      )
    }
  }

  private fun BlockIncidentSpoolRecord.toObservation(linkOccurrenceToken: String?) =
    BlockIncidentObservation(
      workflowId = workflowId,
      parentWorkflowId = parentWorkflowId,
      phaseId = phaseId,
      invocationId = invocationId,
      attempt = attempt,
      reason = reason,
      alias = alias,
      linkOccurrenceToken = linkOccurrenceToken,
      additionalAliases = additionalAliases,
      generation = null,
      origin = origin,
      failureCode = failureCode,
      failureDisposition = failureDisposition,
      throwableType = throwableType,
      exitCode = exitCode,
      exitBehavior =
        when {
          exitCode != null -> BlockIncidentExitBehavior.EXITED_WITH_CODE
          throwableType != null -> BlockIncidentExitBehavior.THROWN
          else -> BlockIncidentExitBehavior.RETURNED_FAILURE
        },
      unavailableFields =
        if (origin == BlockIncidentOrigin.ACCEPTED_GOAL_BLOCK) GOAL_UNAVAILABLE_FIELDS else emptyMap(),
    )

  fun inspectIncident(
    repository: BlockIncidentRepository,
    occurrenceToken: String,
  ): BlockIncidentLookup = repository.lookup(occurrenceToken)

  fun reconcilePending(
    repository: BlockIncidentRepository,
    clock: Clock,
    limit: Int,
  ): List<BlockIncident> = repository.pending(limit).mapNotNull { it.settleExpiredDeadline(repository, clock) }

  private fun BlockIncident.settleExpiredDeadline(
    repository: BlockIncidentRepository,
    clock: Clock,
  ): BlockIncident? {
    if (state.deadline > clock.instant() || state.diagnosis !in EXPIRABLE_DIAGNOSES) return this
    repository.expireDeadline(
      occurrence.occurrenceToken,
      state.revision,
      state.claim?.ownerToken,
      state.claim?.ownerGeneration,
    )
    return (repository.lookup(occurrence.occurrenceToken) as? BlockIncidentLookup.Found)?.incident
  }

  private fun String.retainedPrefix(): String {
    if (length <= MAX_REASON_CHARS) return this
    val end = if (this[MAX_REASON_CHARS - 1].isHighSurrogate()) MAX_REASON_CHARS - 1 else MAX_REASON_CHARS
    return substring(0, end)
  }

  private fun identityAvailability(
    fieldAvailability: Map<BlockIncidentCorrelatedField, BlockIncidentFieldAvailability>,
  ): BlockIncidentIdentityAvailability {
    val identities = IDENTITY_FIELDS.map(fieldAvailability::getValue)
    val available = identities.count { it == BlockIncidentFieldAvailability.AVAILABLE }
    val notCaptured = identities.count { it == BlockIncidentFieldAvailability.NOT_CAPTURED }
    return when {
      available == 0 -> BlockIncidentIdentityAvailability.UNAVAILABLE
      notCaptured == 0 -> BlockIncidentIdentityAvailability.AVAILABLE
      else -> BlockIncidentIdentityAvailability.PARTIAL
    }
  }

  private fun String.digestSha256(): String =
    HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(toByteArray()))

  private const val INVESTIGATION_DEADLINE_MINUTES = 5L
  private const val INVESTIGATION_PICKUP_HOURS = 24L
  val INVESTIGATION_DEADLINE: Duration = Duration.ofMinutes(INVESTIGATION_DEADLINE_MINUTES)
  val INVESTIGATION_PICKUP_WINDOW: Duration = Duration.ofHours(INVESTIGATION_PICKUP_HOURS)
  private val EXPIRABLE_DIAGNOSES =
    setOf(
      BlockIncidentDiagnosis.PENDING,
      BlockIncidentDiagnosis.INVESTIGATING,
    )
  private const val MAX_REASON_CHARS = 8192
  private val GOAL_UNAVAILABLE_FIELDS =
    listOf(
      BlockIncidentCorrelatedField.PHASE_ID,
      BlockIncidentCorrelatedField.PHASE_RUN_ID,
      BlockIncidentCorrelatedField.ATTEMPT,
    ).associateWith { BlockIncidentFieldAvailability.NOT_APPLICABLE }
  private val IDENTITY_FIELDS =
    listOf(
      BlockIncidentCorrelatedField.WORKFLOW_ID, BlockIncidentCorrelatedField.PARENT_WORKFLOW_ID,
      BlockIncidentCorrelatedField.PHASE_ID, BlockIncidentCorrelatedField.PHASE_RUN_ID,
      BlockIncidentCorrelatedField.INVOCATION_ID, BlockIncidentCorrelatedField.EXECUTION_ID,
      BlockIncidentCorrelatedField.MONITOR_SELECTOR, BlockIncidentCorrelatedField.PULL_REQUEST_ID,
      BlockIncidentCorrelatedField.PULL_REQUEST_URL, BlockIncidentCorrelatedField.HEAD_REPOSITORY,
      BlockIncidentCorrelatedField.HEAD_BRANCH, BlockIncidentCorrelatedField.WORKTREE_PATH,
    )
}

internal data class BlockIncidentObservation(
  val workflowId: String? = null,
  val parentWorkflowId: String? = null,
  val phaseId: String? = null,
  val phaseRunId: String? = null,
  val invocationId: String? = null,
  val executionId: String? = null,
  val attempt: Int? = null,
  val reason: String,
  val alias: BlockIncidentAlias? = null,
  val linkOccurrenceToken: String? = null,
  val linkAcceptedFeatureTaskPhase: Boolean = false,
  val linkedAcceptedBlockReference: GoalRunnerAcceptedBlockReference? = null,
  val additionalAliases: List<BlockIncidentAlias> = emptyList(),
  val generation: Long?,
  val origin: BlockIncidentOrigin = BlockIncidentOrigin.ACCEPTED_PHASE_BLOCK,
  val failureCode: String? = null,
  val failureDisposition: String? = null,
  val throwableType: String? = null,
  val exitCode: Int? = null,
  val repairTurn: Int? = null,
  val outputGeneration: Long? = null,
  val monitorSelector: String? = null,
  val pullRequestId: String? = null,
  val pullRequestUrl: String? = null,
  val headRepository: String? = null,
  val headBranch: String? = null,
  val worktreePath: String? = null,
  val exitBehavior: BlockIncidentExitBehavior,
  val evidenceReferences: List<BlockIncidentEvidenceReference> = emptyList(),
  val unavailableFields: Map<BlockIncidentCorrelatedField, BlockIncidentFieldAvailability> = emptyMap(),
) {
  fun resolvedFieldAvailability(): Map<BlockIncidentCorrelatedField, BlockIncidentFieldAvailability> =
    BlockIncidentCorrelatedField.entries.associateWith { field ->
      if (value(field) != null) {
        BlockIncidentFieldAvailability.AVAILABLE
      } else {
        unavailableFields[field] ?: BlockIncidentFieldAvailability.NOT_CAPTURED
      }
    }.let { resolved ->
      if (unavailableFields[BlockIncidentCorrelatedField.REASON] == BlockIncidentFieldAvailability.NOT_CAPTURED) {
        resolved + (BlockIncidentCorrelatedField.REASON to BlockIncidentFieldAvailability.NOT_CAPTURED)
      } else {
        resolved
      }
    }

  private fun value(field: BlockIncidentCorrelatedField): Any? = FIELD_VALUES.getValue(field)(this)

  private companion object {
    val FIELD_VALUES: Map<BlockIncidentCorrelatedField, (BlockIncidentObservation) -> Any?> =
      mapOf(
        BlockIncidentCorrelatedField.WORKFLOW_ID to BlockIncidentObservation::workflowId,
        BlockIncidentCorrelatedField.PARENT_WORKFLOW_ID to BlockIncidentObservation::parentWorkflowId,
        BlockIncidentCorrelatedField.PHASE_ID to BlockIncidentObservation::phaseId,
        BlockIncidentCorrelatedField.PHASE_RUN_ID to BlockIncidentObservation::phaseRunId,
        BlockIncidentCorrelatedField.INVOCATION_ID to BlockIncidentObservation::invocationId,
        BlockIncidentCorrelatedField.EXECUTION_ID to BlockIncidentObservation::executionId,
        BlockIncidentCorrelatedField.ATTEMPT to BlockIncidentObservation::attempt,
        BlockIncidentCorrelatedField.GENERATION to BlockIncidentObservation::generation,
        BlockIncidentCorrelatedField.FAILURE_CODE to BlockIncidentObservation::failureCode,
        BlockIncidentCorrelatedField.THROWABLE_TYPE to BlockIncidentObservation::throwableType,
        BlockIncidentCorrelatedField.EXIT_CODE to BlockIncidentObservation::exitCode,
        BlockIncidentCorrelatedField.REASON to BlockIncidentObservation::reason,
        BlockIncidentCorrelatedField.REPAIR_TURN to BlockIncidentObservation::repairTurn,
        BlockIncidentCorrelatedField.OUTPUT_GENERATION to BlockIncidentObservation::outputGeneration,
        BlockIncidentCorrelatedField.MONITOR_SELECTOR to BlockIncidentObservation::monitorSelector,
        BlockIncidentCorrelatedField.PULL_REQUEST_ID to BlockIncidentObservation::pullRequestId,
        BlockIncidentCorrelatedField.PULL_REQUEST_URL to BlockIncidentObservation::pullRequestUrl,
        BlockIncidentCorrelatedField.HEAD_REPOSITORY to BlockIncidentObservation::headRepository,
        BlockIncidentCorrelatedField.HEAD_BRANCH to BlockIncidentObservation::headBranch,
        BlockIncidentCorrelatedField.WORKTREE_PATH to BlockIncidentObservation::worktreePath,
      )
  }
}
