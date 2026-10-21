package skillbill.engine.diagnostics.block

import skillbill.error.core.BlockIncidentFailureCode
import skillbill.error.core.SkillBillRuntimeException
import skillbill.ports.blockincident.BlockIncidentLookup
import skillbill.ports.blockincident.BlockIncidentRepository
import skillbill.workflow.taskruntime.model.blockincident.BlockIncident
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentAlias
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentAliasKind
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentEvidenceAvailability
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentEvidenceReference
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentEvidenceReferenceAvailability
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentExitBehavior
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentIdentityAvailability
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentOccurrence
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentOrigin
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
    val retainedReason = observation.reason.take(MAX_REASON_CHARS)
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
          reasonTruncated = retainedReason.length < observation.reason.length,
          originalReasonLength = observation.reason.length,
          originalReasonDigest = observation.reason.takeIf { retainedReason.length < it.length }?.digestSha256(),
          failureCode = observation.failureCode,
          failureDisposition = observation.failureDisposition,
          throwableType = observation.throwableType,
          exitCode = observation.exitCode,
          observedAt = observedAt,
          evidenceAvailability = BlockIncidentEvidenceAvailability.DATABASE,
          identityAvailability = observation.identityAvailability,
          repairTurn = observation.repairTurn,
          outputGeneration = observation.outputGeneration,
          monitorSelector = observation.monitorSelector,
          pullRequestId = observation.pullRequestId,
          pullRequestUrl = observation.pullRequestUrl,
          headRepository = observation.headRepository,
          headBranch = observation.headBranch,
          worktreePath = observation.worktreePath,
          exitBehavior = observation.exitBehavior,
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
  val generation: Long = 1,
  val origin: BlockIncidentOrigin = BlockIncidentOrigin.ACCEPTED_PHASE_BLOCK,
  val failureCode: String? = null,
  val failureDisposition: String? = null,
  val throwableType: String? = null,
  val exitCode: Int? = null,
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
  val evidenceReferences: List<BlockIncidentEvidenceReference> = emptyList(),
)
