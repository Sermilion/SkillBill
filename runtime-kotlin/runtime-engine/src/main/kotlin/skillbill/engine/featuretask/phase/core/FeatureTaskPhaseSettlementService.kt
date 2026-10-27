package skillbill.engine.featuretask.phase.core

import me.tatarka.inject.annotations.Inject
import skillbill.agent.model.PhaseOutput
import skillbill.contracts.JsonCodec
import skillbill.contracts.SharedPayloadKeys
import skillbill.contracts.workflow.identity.evidence.ValidationEvidencePayloadKeys
import skillbill.engine.diagnostics.block.BlockIncidentAliases
import skillbill.engine.diagnostics.block.BlockIncidentCapture
import skillbill.engine.diagnostics.block.BlockIncidentFeatureTaskCorrelation
import skillbill.engine.featuretask.model.phase.FeatureTaskPhaseSettlementAckStatus
import skillbill.engine.featuretask.model.phase.FeatureTaskPhaseSettlementAcknowledgment
import skillbill.engine.featuretask.model.phase.FeatureTaskPhaseSettlementBlockRequest
import skillbill.engine.featuretask.model.phase.FeatureTaskPhaseSettlementCompleteRequest
import skillbill.engine.featuretask.model.phase.FeatureTaskPhaseSettlementEnvelope
import skillbill.engine.featuretask.slot.PhaseStrategyRegistry
import skillbill.error.shellcontent.FeatureTaskRuntimeFailureCode
import skillbill.ports.db.DatabaseSessionFactory
import skillbill.ports.featuretask.FeatureTaskPhaseSettlementRepository
import skillbill.ports.featuretask.model.FeatureTaskPhaseSettlement
import skillbill.ports.featuretask.model.FeatureTaskPhaseSettlementKind
import skillbill.ports.featuretask.model.FeatureTaskPhaseSettlementWriteResult
import skillbill.workflow.model.WorkflowStepStatus
import skillbill.workflow.model.validation.FeatureTaskRuntimeVerdict
import skillbill.workflow.taskruntime.artifact.decodeValidationEvidenceFromArtifact
import skillbill.workflow.taskruntime.artifact.envelopeWireMap
import skillbill.workflow.taskruntime.artifact.toWorkflowArtifactMap
import skillbill.workflow.taskruntime.model.audit.FeatureTaskRuntimeNoChangeClaim
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimeWorkflowArtifactMap
import skillbill.workflow.taskruntime.model.handoff.task.NormalizedFeatureTaskRuntimePhaseOutput
import skillbill.workflow.taskruntime.phase.task.FeatureTaskRuntimePhaseWorkflowDefinition
import java.time.Clock

@Inject
class FeatureTaskPhaseSettlementService(
  private val repository: FeatureTaskPhaseSettlementRepository,
  private val clock: Clock,
  private val database: DatabaseSessionFactory,
  private val blockIncidentCapture: BlockIncidentCapture,
  private val strategies: PhaseStrategyRegistry,
) {
  fun completeFromAgent(request: FeatureTaskPhaseSettlementCompleteRequest): FeatureTaskPhaseSettlementAcknowledgment {
    val refusal =
      strategies.strategies.asSequence()
        .filter { request.phaseId in it.steps }
        .mapNotNull { it.stepHooks(request.phaseId).unfinishedAgentCompletionRejection }
        .firstOrNull()
    if (refusal != null &&
      (
        request.verdict == FeatureTaskRuntimeVerdict.PROGRESS.wireValue ||
          request.verdict == FeatureTaskRuntimeVerdict.NO_PROGRESS.wireValue
      )
    ) {
      return FeatureTaskPhaseSettlementAcknowledgment(
        status = FeatureTaskPhaseSettlementAckStatus.ERROR,
        workflowId = request.workflowId,
        phaseId = request.phaseId,
        attempt = request.attempt,
        kind = KIND_COMPLETE,
        envelope = null,
        diagnosticCode = refusal.name,
      )
    }
    return complete(request)
  }

  fun complete(request: FeatureTaskPhaseSettlementCompleteRequest): FeatureTaskPhaseSettlementAcknowledgment =
    complete(request, repository)

  internal fun complete(
    request: FeatureTaskPhaseSettlementCompleteRequest,
    transactionRepository: FeatureTaskPhaseSettlementRepository,
  ): FeatureTaskPhaseSettlementAcknowledgment {
    require(isSettleablePhase(request.phaseId)) { SETTLEABLE_PHASE_REQUIREMENT }
    require(
      request.noChange == null || request.phaseId in FeatureTaskRuntimePhaseWorkflowDefinition.noChangeClaimPhaseIds,
    ) { NO_CHANGE_PHASE_REQUIREMENT }
    val envelope =
      NormalizedFeatureTaskRuntimePhaseOutput(
        phaseId = request.phaseId,
        status = WorkflowStepStatus.COMPLETED.wireValue,
        summary = request.summary?.takeIf { it.any { ch -> !ch.isWhitespace() } } ?: truncateSummary(request.value),
        output = PhaseOutput(value = request.value, prompt = request.prompt),
        verdict = request.verdict?.takeIf(String::isNotBlank),
      ).envelopeWireMap().withNoChangeClaim(request.noChange)
    return persist(
      PersistRequest(
        workflowId = request.workflowId,
        phaseId = request.phaseId,
        attempt = request.attempt,
        kind = KIND_COMPLETE,
        envelope = envelope,
      ),
      transactionRepository,
    )
  }

  fun block(request: FeatureTaskPhaseSettlementBlockRequest): FeatureTaskPhaseSettlementAcknowledgment {
    require(isSettleablePhase(request.phaseId)) { SETTLEABLE_PHASE_REQUIREMENT }
    require(request.failureDisposition.any { !it.isWhitespace() }) {
      "feature_task_phase_block requires a non-blank failure_disposition."
    }
    val validationEvidence =
      request.validationEvidence?.let { decodeValidationEvidenceFromArtifact(it, "${request.phaseId} block") }
    val failureCode =
      validationEvidence?.takeIf { it.hasProjectSourceFailure }
        ?.let { FeatureTaskRuntimeFailureCode.PROJECT_QUALITY_GATE_FAILED }
        ?.let { "${it::class.simpleName}.${it.name}" }
    val envelope =
      NormalizedFeatureTaskRuntimePhaseOutput(
        phaseId = request.phaseId,
        status = WorkflowStepStatus.BLOCKED.wireValue,
        summary = truncateSummary(request.reason),
        output = PhaseOutput(value = request.reason),
        verdict = request.verdict?.takeIf(String::isNotBlank),
        failureDisposition = request.failureDisposition,
      ).envelopeWireMap()
    val persistRequest =
      PersistRequest(
        workflowId = request.workflowId,
        phaseId = request.phaseId,
        attempt = request.attempt,
        kind = KIND_BLOCK,
        envelope = envelope,
      )
    val (acknowledgment, observation) =
      database.transaction { unitOfWork ->
        val acknowledgment = persist(persistRequest, unitOfWork.featureTaskPhaseSettlements)
        val observation =
          if (acknowledgment.status == FeatureTaskPhaseSettlementAckStatus.OK) {
            val phaseObservation =
              BlockIncidentFeatureTaskCorrelation.resolve(
                unitOfWork.workflowStates,
                request.workflowId,
                request.phaseId,
                admitted = null,
              ).acceptedPhaseBlock(
                attempt = request.attempt,
                blockedReason = request.reason,
                failureDisposition = request.failureDisposition,
                failureCode = failureCode,
              )
            phaseObservation.copy(
              alias =
                phaseObservation.invocationId?.let {
                  BlockIncidentAliases.featureTaskPhase(request.workflowId, request.phaseId, it, request.attempt)
                },
            )
          } else {
            null
          }
        observation?.let { BlockIncidentFeatureTaskCorrelation.persistAcceptedIdentity(unitOfWork.workflowStates, it) }
        acknowledgment to observation
      }
    observation?.let(blockIncidentCapture::capture)
    return acknowledgment
  }

  fun findEnvelope(
    workflowId: String,
    phaseId: String,
    attempt: Int,
  ): FeatureTaskPhaseSettlementEnvelope? {
    val settlement = repository.find(workflowId, phaseId, attempt) ?: return null
    val envelope =
      JsonCodec.parseObjectOrNull(settlement.envelopeJson)
        ?.let { JsonCodec.anyToStringAnyMap(JsonCodec.jsonElementToValue(it)) }
        ?: return null
    val wire = envelope.toWorkflowArtifactMap()
    val evidence =
      wire[SharedPayloadKeys.PRODUCED_OUTPUTS]
        ?.let(JsonCodec::anyToStringAnyMap)
        ?.get(ValidationEvidencePayloadKeys.VALIDATION_RESULT)
        ?.let(JsonCodec::anyToStringAnyMap)
        ?.get(ValidationEvidencePayloadKeys.VALIDATION_EVIDENCE)
        ?.let(JsonCodec::anyToStringAnyMap)
    if (evidence != null) {
      decodeValidationEvidenceFromArtifact(evidence, "$phaseId settlement")
    }
    return FeatureTaskPhaseSettlementEnvelope(envelope = wire)
  }

  fun clear(
    workflowId: String,
    phaseId: String,
    attempt: Int,
  ): Boolean = repository.delete(workflowId, phaseId, attempt)

  private fun persist(
    request: PersistRequest,
    targetRepository: FeatureTaskPhaseSettlementRepository,
  ): FeatureTaskPhaseSettlementAcknowledgment {
    val envelopeJson = JsonCodec.mapToJsonString(request.envelopeAsMap())
    val result =
      targetRepository.upsert(
        FeatureTaskPhaseSettlement(
          workflowId = request.workflowId,
          phaseId = request.phaseId,
          attempt = request.attempt,
          kind = request.kind,
          envelopeJson = envelopeJson,
          recordedAt = clock.instant().toString(),
        ),
      )
    val accepted =
      result == FeatureTaskPhaseSettlementWriteResult.Accepted ||
        result == FeatureTaskPhaseSettlementWriteResult.IdenticalReplay
    return FeatureTaskPhaseSettlementAcknowledgment(
      status = if (accepted) FeatureTaskPhaseSettlementAckStatus.OK else FeatureTaskPhaseSettlementAckStatus.ERROR,
      workflowId = request.workflowId,
      phaseId = request.phaseId,
      attempt = request.attempt,
      kind = request.kind,
      envelope = request.envelope.takeIf { accepted },
      diagnosticCode =
        when (result) {
          FeatureTaskPhaseSettlementWriteResult.Accepted,
          FeatureTaskPhaseSettlementWriteResult.IdenticalReplay,
          -> null
          FeatureTaskPhaseSettlementWriteResult.Conflicting ->
            FeatureTaskRuntimeFailureCode.PHASE_SETTLEMENT_CONFLICT.name
          is FeatureTaskPhaseSettlementWriteResult.Refused -> result.reason.name
        },
    )
  }

  private fun FeatureTaskRuntimeWorkflowArtifactMap.withNoChangeClaim(
    claim: Map<String, Any?>?,
  ): FeatureTaskRuntimeWorkflowArtifactMap {
    if (claim == null) return this
    val envelope = toMutableMap()
    val produced = JsonCodec.anyToStringAnyMap(envelope[SharedPayloadKeys.PRODUCED_OUTPUTS]).orEmpty().toMutableMap()
    produced[FeatureTaskRuntimeNoChangeClaim.KEY] = claim
    envelope[SharedPayloadKeys.PRODUCED_OUTPUTS] = produced
    return FeatureTaskRuntimeWorkflowArtifactMap.from(envelope)
  }

  private fun truncateSummary(value: String): String {
    val compact = value.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }.orEmpty()
    return when {
      compact.isBlank() -> "Phase settlement recorded."
      compact.length <= SUMMARY_MAX_CHARS -> compact
      else -> compact.take(SUMMARY_ELLIPSIS_PREFIX) + "..."
    }
  }

  private data class PersistRequest(
    val workflowId: String,
    val phaseId: String,
    val attempt: Int,
    val kind: FeatureTaskPhaseSettlementKind,
    val envelope: FeatureTaskRuntimeWorkflowArtifactMap,
  ) {
    fun envelopeAsMap(): Map<String, Any?> = envelope
  }

  companion object {
    val KIND_COMPLETE: FeatureTaskPhaseSettlementKind = FeatureTaskPhaseSettlementKind.Complete
    val KIND_BLOCK: FeatureTaskPhaseSettlementKind = FeatureTaskPhaseSettlementKind.Block
    private const val SETTLEABLE_PHASE_REQUIREMENT: String =
      "phase_id must be an agent-run feature-task phase step " +
        "(every workflow step except intake, commit_push and monitor)."
    private const val NO_CHANGE_PHASE_REQUIREMENT: String =
      "no_change is accepted only for implement and audit_implement_fix."
    private const val SUMMARY_MAX_CHARS: Int = 240
    private const val SUMMARY_ELLIPSIS_PREFIX: Int = 237

    fun isSettleablePhase(phaseId: String): Boolean =
      phaseId in FeatureTaskRuntimePhaseWorkflowDefinition.agentSettledPhaseIds
  }
}
