package skillbill.engine.diagnostics.block

import skillbill.error.core.BlockIncidentFailureCode
import skillbill.error.core.SkillBillRuntimeException
import skillbill.ports.blockincident.BlockIncidentLookup
import skillbill.ports.blockincident.BlockIncidentRepository
import skillbill.workflow.taskruntime.model.blockincident.BlockIncident
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentAlias
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentAliasKind
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentCorrelatedField
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentEvidenceAvailability
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentEvidenceReference
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentEvidenceReferenceAvailability
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentExitBehavior
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentFieldAvailability
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentIdentityAvailability
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentOccurrence
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentOrigin
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentReasonRetention
import java.security.MessageDigest
import java.time.Clock
import java.time.Duration

internal object BlockIncidentService {
  fun observeBlock(
    repository: BlockIncidentRepository,
    clock: Clock,
    observation: BlockIncidentObservation,
  ): BlockIncident {
    val alias = observation.alias ?: run {
      val identity =
        listOfNotNull(observation.workflowId, observation.phaseId, observation.invocationId, observation.executionId)
          .joinToString(":")
      require(identity.isNotBlank()) { "A block incident requires an actual correlation identity or explicit alias." }
      BlockIncidentAlias(
        if (observation.workflowId != null && observation.phaseId != null) {
          BlockIncidentAliasKind.PHASE_TRANSITION
        } else {
          BlockIncidentAliasKind.ENTRY_BOUNDARY
        },
        "$identity:${observation.attempt ?: 0}",
      )
    }
    val observedAt = clock.instant()
    val retainedReason = observation.reason.retainedPrefix()
    val reasonTruncated = retainedReason.length < observation.reason.length
    val fieldAvailability = observation.resolvedFieldAvailability()
    val incident = repository.createOrLink(
      occurrence =
        BlockIncidentOccurrence(
          occurrenceToken = occurrenceToken(alias),
          workflowId = observation.workflowId,
          parentWorkflowId = observation.parentWorkflowId,
          phaseId = observation.phaseId,
          phaseRunId = observation.phaseRunId,
          invocationId = observation.invocationId,
          executionId = observation.executionId,
          attempt = observation.attempt,
          generation = observation.generation,
          origin = observation.origin,
          reason = retainedReason,
          reasonTruncated = reasonTruncated,
          originalReasonLength = observation.reason.length,
          originalReasonDigest = observation.reason.takeIf { reasonTruncated }?.digestSha256(),
          failureCode = observation.failureCode,
          failureDisposition = observation.failureDisposition,
          throwableType = observation.throwableType,
          exitCode = observation.exitCode,
          observedAt = observedAt,
          evidenceAvailability = BlockIncidentEvidenceAvailability.DATABASE,
          identityAvailability = identityAvailability(fieldAvailability),
          repairTurn = observation.repairTurn,
          outputGeneration = observation.outputGeneration,
          monitorSelector = observation.monitorSelector,
          pullRequestId = observation.pullRequestId,
          pullRequestUrl = observation.pullRequestUrl,
          headRepository = observation.headRepository,
          headBranch = observation.headBranch,
          worktreePath = observation.worktreePath,
          exitBehavior = observation.exitBehavior,
          fieldAvailability = fieldAvailability,
          reasonRetention =
            if (reasonTruncated) {
              BlockIncidentReasonRetention.PREFIX_WITH_SHA256
            } else {
              BlockIncidentReasonRetention.COMPLETE
            },
          retainedEvidenceReferences = observation.evidenceReferences,
        ),
      alias = alias,
      deadline = observedAt.plus(INVESTIGATION_DEADLINE),
    )
    if (!incident.state.deadline.isAfter(observedAt)) {
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
    return incident
  }

  fun inspectIncident(
    repository: BlockIncidentRepository,
    occurrenceToken: String,
  ): BlockIncidentLookup = repository.lookup(occurrenceToken)

  fun reconcilePending(repository: BlockIncidentRepository, clock: Clock, limit: Int): List<BlockIncident> =
    repository.pending(limit).mapNotNull { it.settleExpiredDeadline(repository, clock) }

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

  private fun occurrenceToken(alias: BlockIncidentAlias): String {
    val bytes = MessageDigest.getInstance("SHA-256").digest("${alias.kind.wireValue}:${alias.value}".toByteArray())
    return "bi_" + bytes.joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
  }

  // A split surrogate pair would leave an unpaired high surrogate at the end of the retained prefix.
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
    MessageDigest.getInstance("SHA-256").digest(toByteArray()).joinToString("") { byte ->
      "%02x".format(byte.toInt() and 0xff)
    }

  private val INVESTIGATION_DEADLINE: Duration = Duration.ofMinutes(5)
  private val EXPIRABLE_DIAGNOSES = setOf(
    skillbill.workflow.taskruntime.model.blockincident.BlockIncidentDiagnosis.PENDING,
    skillbill.workflow.taskruntime.model.blockincident.BlockIncidentDiagnosis.INVESTIGATING,
  )
  private const val MAX_REASON_CHARS = 8192
  private val IDENTITY_FIELDS = listOf(
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
  /** Present values are available; absent values carry the producer's reason, which defaults to not captured. */
  fun resolvedFieldAvailability(): Map<BlockIncidentCorrelatedField, BlockIncidentFieldAvailability> =
    BlockIncidentCorrelatedField.entries.associateWith { field ->
      if (value(field) != null) {
        BlockIncidentFieldAvailability.AVAILABLE
      } else {
        unavailableFields[field] ?: BlockIncidentFieldAvailability.NOT_CAPTURED
      }
    }.let { resolved ->
      // A runtime-authored reason statement records that the producer did not capture one.
      if (unavailableFields[BlockIncidentCorrelatedField.REASON] == BlockIncidentFieldAvailability.NOT_CAPTURED) {
        resolved + (BlockIncidentCorrelatedField.REASON to BlockIncidentFieldAvailability.NOT_CAPTURED)
      } else {
        resolved
      }
    }

  private fun value(field: BlockIncidentCorrelatedField): Any? = when (field) {
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
}
