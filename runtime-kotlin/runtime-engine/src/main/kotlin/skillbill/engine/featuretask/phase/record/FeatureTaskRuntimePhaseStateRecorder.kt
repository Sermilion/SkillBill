package skillbill.engine.featuretask.phase.record

import skillbill.contracts.JsonCodec
import skillbill.contracts.SharedPayloadKeys
import skillbill.engine.featuretask.model.phase.FeatureTaskRuntimePhaseStateRequest
import skillbill.engine.featuretask.persist.FeatureTaskRuntimeWorkflowPersistence
import skillbill.engine.featuretask.persist.RuntimeOwnedPersistenceBoundary
import skillbill.engine.featuretask.persist.WorkflowRowAdvance
import skillbill.engine.featuretask.persist.attemptStatusFor
import skillbill.engine.featuretask.persist.durationMillis
import skillbill.engine.featuretask.persist.stepUpdatesFrom
import skillbill.engine.featuretask.persist.workflowArtifactEntryMap
import skillbill.engine.featuretask.persist.workflowArtifactEntryMaps
import skillbill.engine.featuretask.persist.workflowStatusFor
import skillbill.engine.featuretask.phase.core.decodePhaseLedger
import skillbill.engine.featuretask.phase.core.decodePhaseRecords
import skillbill.engine.featuretask.phase.core.operatorBlockRetryFromWorkflowArtifacts
import skillbill.engine.featuretask.review.core.FeatureTaskRuntimeOutputVerification
import skillbill.engine.featuretask.slot.state.RequiredPhaseWrite
import skillbill.engine.featuretask.slot.state.RequiredPhaseWriteKind
import skillbill.ports.db.DatabaseSessionFactory
import skillbill.ports.taskruntime.FeatureTaskRuntimeWireArtifactValidator
import skillbill.ports.workflow.model.WorkflowFamily
import skillbill.workflow.engine.model.DurableWorkflowArtifactFamily
import skillbill.workflow.model.WorkflowStepStatus
import skillbill.workflow.model.persistence.artifact.appendBoundedHistoryBySequence
import skillbill.workflow.model.workflowStepStatus
import skillbill.workflow.taskruntime.artifact.asWorkflowArtifactEntry
import skillbill.workflow.taskruntime.artifact.decodeImplementationAttemptsFromArtifact
import skillbill.workflow.taskruntime.artifact.envelopeWireMap
import skillbill.workflow.taskruntime.artifact.implementationAttemptRecordWorkflowArtifact
import skillbill.workflow.taskruntime.artifact.operatorBlockRetryFromWorkflowArtifacts
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimeWireArtifactKind
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimeWorkflowArtifactMap
import skillbill.workflow.taskruntime.model.persistence.FEATURE_TASK_RUNTIME_PHASE_LEDGER_LIMIT
import skillbill.workflow.taskruntime.model.persistence.FeatureTaskRuntimeImplementationAttempt
import skillbill.workflow.taskruntime.model.persistence.FeatureTaskRuntimeImplementationAttemptStatus
import skillbill.workflow.taskruntime.model.persistence.featureTaskRuntimeAppendImplementationAttempt
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimePhaseLedgerAction
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimePhaseLedgerAction.COMPLETE
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimePhaseLedgerEntry
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimePhaseRecord
import skillbill.workflow.taskruntime.model.repair.FeatureTaskRuntimeOperatorBlockRetry
import java.time.Clock
import java.time.Instant

class FeatureTaskRuntimePhaseStateRecorder(
  val database: DatabaseSessionFactory,
  val workflowPersistence: FeatureTaskRuntimeWorkflowPersistence,
  val runtimeOwnedPersistence: RuntimeOwnedPersistenceBoundary,
  val implementationAttemptValidator: FeatureTaskRuntimeWireArtifactValidator,
  val clock: Clock,
) {
  fun recordRequiredPhaseStart(request: FeatureTaskRuntimePhaseStateRequest): RequiredPhaseWrite =
    if (recordPhaseState(request)) {
      RequiredPhaseWrite.Acknowledged
    } else {
      RequiredPhaseWrite.Rejected(
        writeKind = RequiredPhaseWriteKind.START,
        workflowId = request.workflowId,
        phaseId = request.phaseId,
        attempt = request.attemptCount,
      )
    }

  fun recordPhaseState(request: FeatureTaskRuntimePhaseStateRequest): Boolean =
    database.transaction { unitOfWork ->
      val record =
        unitOfWork.workflowStates.get(WorkflowFamily.TASK_RUNTIME, request.workflowId)
          ?: return@transaction false
      val artifacts = record.artifacts
      val existingRecords = decodePhaseRecords(artifacts)
      val now = clock.instant()
      val previous = existingRecords[request.phaseId]
      val phaseRecord = phaseRecordFor(request, previous, now)
      val updatedRecords = LinkedHashMap(existingRecords).apply { put(request.phaseId, phaseRecord) }
      val patch =
        mapOf(
          DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_PHASE_RECORDS.entry(
            updatedRecords.mapValues { (_, value) -> value.asWorkflowArtifactEntry() },
          ),
        ) + implementationAttemptPatch(artifacts, request, attemptStatusFor(request)) +
          findingVerificationCheckpointPatch(request)
      workflowPersistence.persistArtifactsPatch(
        unitOfWork.workflowStates,
        record,
        patch,
        WorkflowRowAdvance(
          terminalInstant = now,
          currentStepId = request.phaseId,
          workflowStatus = workflowStatusFor(request),
          stepUpdates = stepUpdatesFrom(updatedRecords),
        ),
      )
      true
    }

  fun recordCompletedPhase(request: FeatureTaskRuntimePhaseStateRequest): Boolean {
    require(request.status.workflowStepStatus() == WorkflowStepStatus.COMPLETED && request.finished)
    return recordCompletedPhaseWrite(request)
  }

  fun recordIncompleteImplementationAttempt(request: FeatureTaskRuntimePhaseStateRequest): Boolean =
    database.transaction { unitOfWork ->
      val record =
        unitOfWork.workflowStates.get(WorkflowFamily.TASK_RUNTIME, request.workflowId)
          ?: return@transaction false
      val patch =
        implementationAttemptPatch(
          record.artifacts,
          request,
          FeatureTaskRuntimeImplementationAttemptStatus.INCOMPLETE,
        )
      if (patch.isEmpty()) return@transaction false
      workflowPersistence.persistArtifactsPatch(unitOfWork.workflowStates, record, patch)
      true
    }

  fun loadImplementationAttempts(workflowId: String): List<FeatureTaskRuntimeImplementationAttempt>? =
    database.read { unitOfWork ->
      val record =
        unitOfWork.workflowStates.get(WorkflowFamily.TASK_RUNTIME, workflowId)
          ?: return@read null
      implementationAttemptsFrom(record.artifacts)
    }

  fun clearBackwardEdgeContext(
    workflowId: String,
    phaseIds: Collection<String>,
  ): Boolean =
    database.transaction { unitOfWork ->
      val record =
        unitOfWork.workflowStates.get(WorkflowFamily.TASK_RUNTIME, workflowId)
          ?: return@transaction false
      val existingRecords = decodePhaseRecords(record.artifacts)
      val cleared = LinkedHashMap(existingRecords)
      phaseIds.forEach { phaseId ->
        val previous = existingRecords[phaseId] ?: return@forEach
        if (previous.loopId == null && previous.edgeIteration == null) {
          return@forEach
        }
        cleared[phaseId] = previous.copy(loopId = null, edgeIteration = null)
      }
      if (cleared == existingRecords) {
        return@transaction true
      }
      workflowPersistence.persistArtifactsPatch(
        unitOfWork.workflowStates,
        record,
        mapOf(
          DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_PHASE_RECORDS.entry(
            cleared.mapValues { (_, value) -> value.asWorkflowArtifactEntry() },
          ),
        ),
      )
      true
    }

  fun loadOperatorBlockRetry(workflowId: String): FeatureTaskRuntimeOperatorBlockRetry? =
    database.read { unitOfWork ->
      val record =
        unitOfWork.workflowStates.get(WorkflowFamily.TASK_RUNTIME, workflowId)
          ?: return@read null
      val artifacts = record.artifacts
      val retry = operatorBlockRetryFromWorkflowArtifacts(artifacts) ?: return@read null
      val phaseEntries = decodePhaseLedger(artifacts).filter { it.phaseId == retry.phaseId }
      val latestRetry =
        phaseEntries.lastOrNull { it.action == FeatureTaskRuntimePhaseLedgerAction.RETRY }
          ?: return@read null
      val settledAfterRetry =
        phaseEntries.any { entry ->
          entry.sequenceNumber > latestRetry.sequenceNumber &&
            entry.action in
            setOf(
              FeatureTaskRuntimePhaseLedgerAction.BLOCKED,
              FeatureTaskRuntimePhaseLedgerAction.COMPLETE,
            )
        }
      retry.takeUnless { settledAfterRetry }
    }
}

fun featureTaskRuntimePhaseRecordFor(
  request: FeatureTaskRuntimePhaseStateRequest,
  previous: FeatureTaskRuntimePhaseRecord?,
  now: Instant,
): FeatureTaskRuntimePhaseRecord {
  val firstStartedAt = previous?.firstStartedAt ?: now
  val startedAt =
    if (request.status.workflowStepStatus() == WorkflowStepStatus.RUNNING || previous == null) {
      now
    } else {
      previous.startedAt
    }
  val carryForward =
    previous != null &&
      previous.attemptCount == request.attemptCount &&
      previous.resolvedAgentId == request.resolvedAgentId
  val launched =
    when {
      request.launchOutcomeKnown -> request.launchedModel to request.launchedEffort
      carryForward -> previous.launchedModel to previous.launchedEffort
      else -> null to null
    }
  return FeatureTaskRuntimePhaseRecord(
    phaseId = request.phaseId,
    status =
      requireNotNull(request.status.workflowStepStatus()) {
        "Unknown feature-task-runtime phase status '${request.status}'."
      },
    attemptCount = request.attemptCount,
    startedAt = startedAt,
    firstStartedAt = firstStartedAt,
    finishedAt = if (request.finished) now else null,
    durationMillis = if (request.finished) durationMillis(startedAt, now) else null,
    resolvedAgentId = request.resolvedAgentId,
    outputArtifact = request.outputArtifact ?: previous?.outputArtifact,
    rejectedOutput = request.rejectedOutput,
    blockedReason = request.blockedReason,
    failureDisposition = request.failureDisposition,
    fileManifestBefore = request.fileManifestBefore,
    fileManifestAfter = request.fileManifestAfter,
    fileManifestIntroduced = request.fileManifestIntroduced,
    loopId = request.loopId,
    edgeIteration = request.edgeIteration,
    reviewPassNumber = request.reviewPassNumber,
    repairEvidence = request.repairEvidence,
    launchedModel = launched.first,
    launchedEffort = launched.second,
    reviewRunId = request.reviewRunId ?: previous?.reviewRunId,
  )
}

fun featureTaskRuntimePhaseRecordFor(
  request: FeatureTaskRuntimePhaseStateRequest,
  previous: FeatureTaskRuntimePhaseRecord?,
  now: String,
): FeatureTaskRuntimePhaseRecord = featureTaskRuntimePhaseRecordFor(request, previous, Instant.parse(now))

fun FeatureTaskRuntimePhaseStateRecorder.implementationAttemptsFrom(
  artifacts: Map<String, Any?>,
): List<FeatureTaskRuntimeImplementationAttempt> {
  val raw =
    DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_IMPLEMENTATION_ATTEMPTS.value(artifacts)
      ?: return emptyList()
  return decodeImplementationAttemptsFromArtifact(raw)
}

fun FeatureTaskRuntimePhaseStateRecorder.implementationAttemptPatch(
  artifacts: Map<String, Any?>,
  request: FeatureTaskRuntimePhaseStateRequest,
  attemptStatus: FeatureTaskRuntimeImplementationAttemptStatus,
): Map<String, Any?> {
  if (!request.mutating) return emptyMap()
  val produced =
    request.normalizedOutput?.envelopeWireMap()
      ?.let { JsonCodec.anyToStringAnyMap(it[SharedPayloadKeys.PRODUCED_OUTPUTS]) }
  val value = produced?.get(SharedPayloadKeys.VALUE)?.toString()?.trim().orEmpty()
  if (produced == null || value.isBlank()) return emptyMap()
  val prompt = produced[SharedPayloadKeys.PROMPT]?.toString()?.trim()?.takeIf(String::isNotBlank)
  val existing = implementationAttemptsFrom(artifacts)
  val appended =
    featureTaskRuntimeAppendImplementationAttempt(
      existing = existing,
      entry =
        FeatureTaskRuntimeImplementationAttempt(
          sequenceNumber = (existing.maxOfOrNull { it.sequenceNumber } ?: -1) + 1,
          phaseId = request.phaseId,
          attemptNumber = request.attemptCount,
          agentId = request.resolvedAgentId,
          status = attemptStatus,
          recordedAt = clock.instant().toString(),
          value = value,
          loopId = request.loopId,
          edgeIteration = request.edgeIteration,
          failureDisposition = request.failureDisposition,
          prompt = prompt,
        ),
    )
  val wire = implementationAttemptRecordWorkflowArtifact(appended)
  implementationAttemptValidator.validate(
    FeatureTaskRuntimeWireArtifactKind.IMPLEMENTATION_ATTEMPT,
    FeatureTaskRuntimeWorkflowArtifactMap.from(wire),
    DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_IMPLEMENTATION_ATTEMPTS.label(),
  )
  return mapOf(DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_IMPLEMENTATION_ATTEMPTS.entry(wire))
}

fun FeatureTaskRuntimePhaseStateRecorder.findingVerificationCheckpointPatch(
  request: FeatureTaskRuntimePhaseStateRequest,
): Map<String, Any?> {
  val output = request.normalizedOutput?.envelopeWireMap()
  if (
    request.finished &&
    request.status.workflowStepStatus() == WorkflowStepStatus.COMPLETED &&
    FeatureTaskRuntimeOutputVerification.carriesFindingDispositions(output)
  ) {
    val dispositions = FeatureTaskRuntimeOutputVerification.dispositionsFrom(output)
    return buildMap {
      DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_FINDING_VERIFICATION_CHECKPOINT.putInto(this, null)
      DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_FINDING_VERIFICATION_BOUNDARY_SELECTION.putInto(this, null)
      if (dispositions.isNotEmpty()) {
        DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_FINDING_VERIFICATION_DISPOSITIONS.putInto(
          this,
          dispositions.map { it.asWorkflowArtifactEntry() },
        )
      }
    }
  }
  val checkpoint = request.findingVerificationCheckpoint?.takeIf { it.isNotEmpty() } ?: return emptyMap()
  return mapOf(
    DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_FINDING_VERIFICATION_CHECKPOINT.entry(
      checkpoint.map { it.asWorkflowArtifactEntry() },
    ),
  )
}

fun FeatureTaskRuntimePhaseStateRecorder.recordCompletedPhaseWrite(
  request: FeatureTaskRuntimePhaseStateRequest,
): Boolean =
  runtimeOwnedPersistence.requiredWrite(
    seam = "FeatureTaskRuntimePhaseRecorder.recordCompletedPhase",
    expected = "runtime-owned completed phase state",
  ) { unitOfWork ->
    val record =
      unitOfWork.workflowStates.get(WorkflowFamily.TASK_RUNTIME, request.workflowId)
        ?: return@requiredWrite false
    val artifacts = record.artifacts
    val existingRecords = decodePhaseRecords(artifacts)
    val updatedRecords =
      LinkedHashMap(existingRecords).apply {
        put(request.phaseId, phaseRecordFor(request, existingRecords[request.phaseId], clock.instant()))
      }
    val ledger = decodePhaseLedger(artifacts)
    val completion =
      FeatureTaskRuntimePhaseLedgerEntry(
        action = COMPLETE,
        sequenceNumber = (ledger.maxOfOrNull { it.sequenceNumber } ?: -1) + 1,
        timestamp = clock.instant().toString(),
        phaseId = request.phaseId,
        attemptCount = request.attemptCount,
        resolvedAgentId = request.resolvedAgentId,
        loopId = request.loopId,
        edgeIteration = request.edgeIteration,
      )
    val updatedLedger =
      appendBoundedHistoryBySequence(
        workflowArtifactEntryMaps(ledger.map { it.asWorkflowArtifactEntry() }),
        workflowArtifactEntryMap(completion.asWorkflowArtifactEntry()),
        FEATURE_TASK_RUNTIME_PHASE_LEDGER_LIMIT,
      )
    workflowPersistence.persistArtifactsPatch(
      unitOfWork.workflowStates,
      record,
      mapOf(
        DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_PHASE_RECORDS.entry(
          updatedRecords.mapValues { (_, value) -> value.asWorkflowArtifactEntry() },
        ),
        DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_PHASE_LEDGER.entry(updatedLedger),
      ) + implementationAttemptPatch(artifacts, request, FeatureTaskRuntimeImplementationAttemptStatus.COMPLETED) +
        findingVerificationCheckpointPatch(request),
      WorkflowRowAdvance(request.phaseId, workflowStatusFor(request), stepUpdatesFrom(updatedRecords), clock.instant()),
    )
    true
  }

fun FeatureTaskRuntimePhaseStateRecorder.phaseRecordFor(
  request: FeatureTaskRuntimePhaseStateRequest,
  previous: FeatureTaskRuntimePhaseRecord?,
  now: Instant,
): FeatureTaskRuntimePhaseRecord = featureTaskRuntimePhaseRecordFor(request, previous, now)
