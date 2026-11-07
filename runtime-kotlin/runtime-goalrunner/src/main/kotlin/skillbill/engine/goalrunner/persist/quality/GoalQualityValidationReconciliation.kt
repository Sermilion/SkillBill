package skillbill.engine.goalrunner.persist.quality

import skillbill.contracts.JsonCodec
import skillbill.contracts.workflow.goal.GOAL_QUALITY_EXECUTION_CONTRACT_VERSION
import skillbill.contracts.workflow.goal.GoalQualityExecutionPayloadKeys
import skillbill.error.core.SkillBillRuntimeException
import skillbill.error.shellcontent.WorkflowFailureCode
import skillbill.goalrunner.model.quality.GoalQualityAcceptedSourceObservation
import skillbill.goalrunner.model.quality.GoalQualityAdoptionEvidence
import skillbill.goalrunner.model.quality.GoalQualityExecution
import skillbill.goalrunner.model.quality.GoalQualityExecutionRecord
import skillbill.goalrunner.model.quality.GoalQualityExecutionStatus
import skillbill.goalrunner.model.quality.GoalQualityPhaseProgress
import skillbill.goalrunner.model.quality.GoalQualityPreQaBuildOccurrence
import skillbill.goalrunner.model.quality.GoalQualityPreQaBuildState
import skillbill.goalrunner.model.quality.GoalQualityValidationDisposition
import skillbill.workflow.model.WorkflowStepStatus
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimePhaseLedgerAction
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimePhaseLedgerEntry
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimePhaseRecord
import skillbill.workflow.taskruntime.model.skeleton.SkeletonDefinition
import skillbill.workflow.taskruntime.model.validation.FeatureTaskRuntimeValidationGateProgress
import skillbill.workflow.taskruntime.model.validation.FeatureTaskRuntimeValidationGateRunRecord
import skillbill.workflow.taskruntime.model.validation.ValidationGateRunOutcome
import skillbill.workflow.taskruntime.phase.task.FeatureTaskRuntimePhaseWorkflowDefinition
import java.security.MessageDigest

internal fun proofMatchesRecord(
  proof: GoalQualityValidationSourceProof,
  sourceVersion: String?,
  executionRecord: GoalQualityExecutionRecord,
): Boolean {
  val execution = executionRecord.execution ?: return false
  val progress = execution.progress?.validation ?: return false
  val phaseRecord = progress.phaseRecords[proof.phaseId] ?: return false
  val ledger =
    proof.ledgerSequenceNumber?.let { sequence ->
      progress.phaseLedger.singleOrNull { it.sequenceNumber == sequence }
    }
  return proofExecutionIdentityMatches(
    proof,
    sourceVersion,
    execution,
    retainedPlanFingerprint(executionRecord, sourceVersion),
  ) &&
    proofProgressIdentityMatches(proof, progress, phaseRecord, ledger) &&
    acceptedSourceMatchesRecord(proof, sourceVersion, execution, progress, ledger)
}

private fun retainedPlanFingerprint(
  record: GoalQualityExecutionRecord,
  sourceVersion: String?,
): String? =
  if (sourceVersion == GOAL_QUALITY_EXECUTION_CONTRACT_VERSION) {
    record.execution?.executionPlans?.fingerprint
  } else {
    historicalPlanFingerprint(record.adoptionEvidence, sourceVersion)
  }

private fun historicalPlanFingerprint(
  evidence: GoalQualityAdoptionEvidence?,
  sourceVersion: String?,
): String? {
  if (evidence == null || evidence.sourceContractVersion != sourceVersion) return null
  val plans = historicalExecutionPlans(evidence) ?: return null
  val qa = plans[GoalQualityExecutionPayloadKeys.QA_PLAN] as? String
  val validation = plans[GoalQualityExecutionPayloadKeys.VALIDATION_PLAN] as? String
  if (qa == null || validation == null) return null
  return (plans[GoalQualityExecutionPayloadKeys.PLAN_FINGERPRINT] as? String)
    ?: MessageDigest.getInstance("SHA-256")
      .digest("$qa\u0000$validation".toByteArray(Charsets.UTF_8))
      .joinToString("") { "%02x".format(it) }
}

private fun historicalExecutionPlans(evidence: GoalQualityAdoptionEvidence): Map<String, Any?>? {
  val root = JsonCodec.parseObjectOrNull(evidence.originalPayloadJson) ?: return null
  val payload = JsonCodec.anyToStringAnyMap(JsonCodec.jsonElementToValue(root)) ?: return null
  val execution = JsonCodec.anyToStringAnyMap(payload[GoalQualityExecutionPayloadKeys.EXECUTION]) ?: return null
  return JsonCodec.anyToStringAnyMap(execution[GoalQualityExecutionPayloadKeys.EXECUTION_PLANS])
}

private fun proofExecutionIdentityMatches(
  proof: GoalQualityValidationSourceProof,
  sourceVersion: String?,
  execution: GoalQualityExecution,
  retainedFingerprint: String?,
): Boolean {
  val ownershipMatches =
    sourceVersion == proof.sourceContractVersion &&
      execution.parentWorkflowId == proof.parentWorkflowId && execution.invocationId == proof.invocationId
  val planMatches = retainedFingerprint == proof.acceptedPlanFingerprint
  val occurrenceMatches =
    execution.validationOccurrence == proof.validationOccurrence &&
      execution.status == proof.executionStatus
  return ownershipMatches && planMatches && occurrenceMatches
}

private fun proofProgressIdentityMatches(
  proof: GoalQualityValidationSourceProof,
  progress: GoalQualityPhaseProgress,
  record: FeatureTaskRuntimePhaseRecord,
  ledger: FeatureTaskRuntimePhaseLedgerEntry?,
): Boolean {
  if (progress.revision != proof.progressRevision || record.attemptCount != proof.attempt) return false
  if (ledger == null) {
    return record.status == WorkflowStepStatus.BLOCKED &&
      progress.phaseLedger.none {
        it.phaseId == proof.phaseId && it.attemptCount == proof.attempt
      }
  }
  if (ledger.phaseId != proof.phaseId || ledger.attemptCount != proof.attempt) return false
  return when (ledger.action) {
    FeatureTaskRuntimePhaseLedgerAction.COMPLETE -> record.status == WorkflowStepStatus.COMPLETED
    FeatureTaskRuntimePhaseLedgerAction.BLOCKED ->
      record.status == WorkflowStepStatus.BLOCKED &&
        progress.acceptedBlockedValidationEvent(proof.phaseId, proof.attempt)?.sequenceNumber ==
        proof.ledgerSequenceNumber
    else -> false
  }
}

private fun acceptedOutputMatches(
  proof: GoalQualityValidationSourceProof,
  progress: GoalQualityPhaseProgress,
): Boolean =
  proof.acceptedOutputFingerprint?.let { fingerprint ->
    progress.acceptedOutputs.any {
      it.phaseId == proof.phaseId && it.occurrence == proof.attempt && sha256(it.output) == fingerprint
    }
  } ?: (
    progress.acceptedEnvelopes.any { it.phaseId == proof.phaseId && it.attempt == proof.attempt } ||
      progress.acceptedOutputs.any { it.phaseId == proof.phaseId && it.occurrence == proof.attempt }
  )

private fun acceptedSourceMatchesRecord(
  proof: GoalQualityValidationSourceProof,
  sourceVersion: String?,
  current: GoalQualityExecution,
  progress: GoalQualityPhaseProgress,
  ledger: FeatureTaskRuntimePhaseLedgerEntry?,
): Boolean {
  if (!proof.sourceIsCurrent) return true
  if (ledger == null || proof.contentFingerprint == null) return false
  val outputMatches = acceptedOutputMatches(proof, progress)
  val blockedBoundary = ledger.action == FeatureTaskRuntimePhaseLedgerAction.BLOCKED
  val requiresOutput = proof.acceptedOutputFingerprint != null || !blockedBoundary
  if (requiresOutput && !outputMatches) return false
  return (!blockedBoundary || blockedSourceMatches(progress)) &&
    proofContentMatches(proof, sourceVersion, current, progress)
}

private fun blockedSourceMatches(progress: GoalQualityPhaseProgress): Boolean {
  val gate = progress.validationGateProgress
  val pending = gate?.pendingExternalOperation != null || gate?.pendingExternalCheckpoint != null
  if (pending || gate?.terminalOutcome == "paused") return false
  if (progress.validationGateProgressKey == null && gate == null) return true
  if (progress.validationGateProgressKey.isNullOrBlank() || gate == null) return false
  val knownRefusal =
    gate.terminalOutcome == "blocked" &&
      (gate.terminalFailureCode != null || gate.terminalFailureDisposition != null)
  return knownRefusal || gate.terminalOutcome == null && gate.gateRuns.lastOrNull()?.let(::verifiedFailedRun) == true
}

private fun verifiedFailedRun(run: FeatureTaskRuntimeValidationGateRunRecord): Boolean {
  val commandKnown = !run.legacyUnverified && !run.command.isNullOrBlank() && run.exitCode != null
  val failedCheckpoint =
    run.repositoryCheckpoint?.isNotBlank() == true &&
      run.outcome != ValidationGateRunOutcome.PASSED
  return commandKnown && failedCheckpoint
}

private fun proofContentMatches(
  proof: GoalQualityValidationSourceProof,
  sourceVersion: String?,
  current: GoalQualityExecution,
  progress: GoalQualityPhaseProgress,
): Boolean {
  val matchingObservation = progress.acceptedSourceObservations.any { observationMatchesProof(it, proof) }
  val legacySource =
    sourceVersion != GOAL_QUALITY_EXECUTION_CONTRACT_VERSION &&
      (current.finalValidationContentFingerprint == null || progress.acceptedSourceObservations.isEmpty())
  return if (legacySource) {
    current.validation != GoalQualityValidationDisposition.NOT_REQUIRED &&
      (current.finalValidationContentFingerprint ?: current.settledContentFingerprint) == proof.contentFingerprint &&
      (progress.acceptedSourceObservations.isEmpty() || matchingObservation)
  } else {
    val contentMatches = current.finalValidationContentFingerprint == proof.contentFingerprint
    val runningWithoutProof =
      current.status == GoalQualityExecutionStatus.VALIDATION_RUNNING &&
        current.finalValidationContentFingerprint == null
    (contentMatches || runningWithoutProof) && matchingObservation
  }
}

private fun observationMatchesProof(
  observation: GoalQualityAcceptedSourceObservation,
  proof: GoalQualityValidationSourceProof,
): Boolean {
  val boundaryMatches =
    observation.phaseId == proof.phaseId && observation.attempt == proof.attempt &&
      observation.completionSequenceNumber == proof.ledgerSequenceNumber
  val sourceMatches =
    observation.contentFingerprint == proof.contentFingerprint &&
      observation.validationOccurrence == proof.validationOccurrence &&
      observation.acceptedOutputFingerprint == proof.acceptedOutputFingerprint
  return boundaryMatches && sourceMatches
}

internal fun bindLegacyValidationProof(
  record: GoalQualityExecutionRecord,
  proof: GoalQualityValidationSourceProof,
): GoalQualityExecutionRecord {
  if (proof.sourceContractVersion == GOAL_QUALITY_EXECUTION_CONTRACT_VERSION) return record
  val execution = record.execution ?: invalid("Accepted legacy validation proof has no execution.")
  val progress = execution.progress ?: invalid("Accepted legacy validation proof has no progress.")
  val existingObservation =
    progress.validation.acceptedSourceObservations.singleOrNull {
      it.phaseId == proof.phaseId && it.attempt == proof.attempt &&
        it.completionSequenceNumber == proof.ledgerSequenceNumber
    }
  if (existingObservation != null && existingObservation.contentFingerprint != proof.contentFingerprint) {
    invalid("Accepted legacy validation proof conflicts with its retained source observation.")
  }
  if (existingObservation?.acceptedOutputFingerprint != null &&
    existingObservation.acceptedOutputFingerprint != proof.acceptedOutputFingerprint
  ) {
    invalid("Accepted legacy validation proof conflicts with its retained output identity.")
  }
  val observation =
    existingObservation?.copy(
      acceptedOutputFingerprint = existingObservation.acceptedOutputFingerprint ?: proof.acceptedOutputFingerprint,
    ) ?: GoalQualityAcceptedSourceObservation(
      phaseId = proof.phaseId,
      attempt = proof.attempt,
      contentFingerprint = requireNotNull(proof.contentFingerprint),
      completionSequenceNumber = requireNotNull(proof.ledgerSequenceNumber),
      progressRevision = proof.progressRevision + 1,
      validationOccurrence = proof.validationOccurrence,
      acceptedOutputFingerprint = proof.acceptedOutputFingerprint,
    )
  return record.copy(
    execution =
      execution.copy(
        finalValidationContentFingerprint = proof.contentFingerprint,
        progress =
          progress.copy(
            validation =
              progress.validation.copy(
                acceptedSourceObservations =
                  if (existingObservation == null) {
                    progress.validation.acceptedSourceObservations + observation
                  } else {
                    progress.validation.acceptedSourceObservations.map {
                      if (it == existingObservation) observation else it
                    }
                  },
                revision = progress.validation.revision + 1,
              ),
          ),
      ),
  )
}

internal fun requiresValidationReentry(
  execution: GoalQualityExecution,
  acceptedBoundary: Boolean,
): Boolean =
  execution.status in
    setOf(
      GoalQualityExecutionStatus.VALIDATION_COMPLETED_PENDING_SETTLEMENT,
      GoalQualityExecutionStatus.SETTLED_PASSED,
      GoalQualityExecutionStatus.SETTLED_SKIPPED,
      GoalQualityExecutionStatus.SETTLED_WARNED,
    ) ||
    execution.status == GoalQualityExecutionStatus.VALIDATION_RUNNING &&
    execution.progress?.validation?.let { progress ->
      progress.hasAcceptedValidationBoundary() &&
        (progress.terminalCompleted || progress.terminalBlockedStep != null || acceptedBoundary)
    } == true

internal fun hasUnknownValidationOperation(execution: GoalQualityExecution): Boolean {
  val progress = execution.progress?.validation ?: return false
  val gate = progress.validationGateProgress
  return gate?.pendingExternalOperation != null || gate?.pendingExternalCheckpoint != null ||
    progress.phaseRecords.values.any { it.status == WorkflowStepStatus.RUNNING }
}

private fun sha256(value: String): String =
  MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(Charsets.UTF_8))
    .joinToString("") { "%02x".format(it) }

private fun invalid(reason: String): Nothing =
  throw SkillBillRuntimeException(WorkflowFailureCode.INVALID_GOAL_QUALITY_EXECUTION, reason)

internal fun archivedBuildEvidenceConflicts(
  execution: GoalQualityExecution,
  occurrence: GoalQualityPreQaBuildOccurrence,
): Boolean {
  val progress = occurrence.progress
  val observation = progress.acceptedSourceObservations.lastOrNull()
  val record = observation?.let { progress.phaseRecords[it.phaseId] }
  val terminal = observation?.let { acceptedTerminalEvent(progress, it.phaseId, it.attempt) }
  val start = observation?.let { archivedBuildStart(progress, it) }
  val gate = progress.buildGateProgress
  val missing = observation == null || record == null || terminal == null
  if (missing || start == null || gate == null) return true
  val identity = archivedIdentityMismatch(occurrence, observation, record, terminal, start)
  return identity || archivedGateMismatch(execution, progress, observation, gate, occurrence)
}

internal fun archivedBuildStart(
  progress: GoalQualityPhaseProgress,
  observation: GoalQualityAcceptedSourceObservation,
): FeatureTaskRuntimePhaseLedgerEntry? {
  val starts =
    progress.phaseLedger.filter {
      it.phaseId == observation.phaseId && it.attemptCount == observation.attempt &&
        it.action == FeatureTaskRuntimePhaseLedgerAction.START
    }
  val start = starts.minByOrNull { it.sequenceNumber } ?: return null
  return start.takeIf { starts.all { it.resolvedAgentId == start.resolvedAgentId } }
}

internal fun archivedIdentityMismatch(
  occurrence: GoalQualityPreQaBuildOccurrence,
  observation: GoalQualityAcceptedSourceObservation,
  record: FeatureTaskRuntimePhaseRecord,
  terminal: FeatureTaskRuntimePhaseLedgerEntry,
  start: FeatureTaskRuntimePhaseLedgerEntry,
): Boolean {
  val fingerprint = observation.contentFingerprint != occurrence.contentFingerprint
  val phase = observation.phaseId != FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_BUILD
  val attempt = record.attemptCount != observation.attempt
  val sequence = terminal.sequenceNumber != observation.completionSequenceNumber
  val action = terminal.action != expectedArchivedAction(occurrence)
  val lateStart = start.sequenceNumber >= observation.completionSequenceNumber
  val order = lateStart || start.resolvedAgentId != record.resolvedAgentId
  return fingerprint || phase || attempt || archivedStatusMismatch(occurrence, record) || sequence || action || order
}

internal fun archivedStatusMismatch(
  occurrence: GoalQualityPreQaBuildOccurrence,
  record: FeatureTaskRuntimePhaseRecord,
): Boolean {
  val passed = occurrence.state == GoalQualityPreQaBuildState.PASSED && record.status != WorkflowStepStatus.COMPLETED
  val warned = occurrence.state == GoalQualityPreQaBuildState.WARNED && record.status != WorkflowStepStatus.BLOCKED
  return passed || warned
}

internal fun expectedArchivedAction(occurrence: GoalQualityPreQaBuildOccurrence): FeatureTaskRuntimePhaseLedgerAction =
  if (occurrence.state == GoalQualityPreQaBuildState.PASSED) {
    FeatureTaskRuntimePhaseLedgerAction.COMPLETE
  } else {
    FeatureTaskRuntimePhaseLedgerAction.BLOCKED
  }

internal fun archivedGateMismatch(
  execution: GoalQualityExecution,
  progress: GoalQualityPhaseProgress,
  observation: GoalQualityAcceptedSourceObservation,
  gate: FeatureTaskRuntimeValidationGateProgress,
  occurrence: GoalQualityPreQaBuildOccurrence,
): Boolean {
  val pending = gate.pendingExternalOperation != null || gate.pendingExternalCheckpoint != null
  val paused = gate.terminalOutcome == "paused"
  val key = progress.buildGateProgressKey != archivedBuildKey(execution, observation)
  val result = !archivedGateResult(occurrence, progress, observation, gate)
  return !archivedOutputMatches(progress, observation) || pending || paused || key || result
}

internal fun archivedBuildKey(
  execution: GoalQualityExecution,
  observation: GoalQualityAcceptedSourceObservation,
): String =
  "${execution.parentWorkflowId}:${execution.invocationId}:${SkeletonDefinition.GOAL_BUILD.id}:" +
    "${observation.phaseId}:${observation.attempt}:build"

internal fun archivedOutputMatches(
  progress: GoalQualityPhaseProgress,
  observation: GoalQualityAcceptedSourceObservation,
): Boolean {
  val fingerprint = observation.acceptedOutputFingerprint ?: return true
  return progress.acceptedOutputs.any { output ->
    val sameOutput = output.phaseId == observation.phaseId && output.occurrence == observation.attempt
    sameOutput && sha256(output.output) == fingerprint
  }
}

internal fun archivedGateResult(
  occurrence: GoalQualityPreQaBuildOccurrence,
  progress: GoalQualityPhaseProgress,
  observation: GoalQualityAcceptedSourceObservation,
  gate: FeatureTaskRuntimeValidationGateProgress,
): Boolean {
  val run = gate.gateRuns.lastOrNull()
  return if (occurrence.state == GoalQualityPreQaBuildState.PASSED) {
    progress.terminalCompleted && run?.outcome == ValidationGateRunOutcome.PASSED && run.exitCode == 0
  } else {
    progress.terminalBlockedStep == observation.phaseId && archivedRefusal(gate, run)
  }
}

internal fun archivedRefusal(
  gate: FeatureTaskRuntimeValidationGateProgress,
  run: FeatureTaskRuntimeValidationGateRunRecord?,
): Boolean {
  val refusal = gate.terminalFailureCode != null || gate.terminalFailureDisposition != null
  val named = gate.terminalOutcome == "blocked" && refusal
  val failed = gate.terminalOutcome == null && run != null && run.outcome != ValidationGateRunOutcome.PASSED
  return named || failed
}

internal fun archivedBuildWatermarkExceeds(
  active: GoalQualityPhaseProgress?,
  occurrence: GoalQualityPreQaBuildOccurrence,
): Boolean {
  val attemptExceeds =
    occurrence.progress.attemptHighWaterMarks.entries.any { (phase, attempt) ->
      (active?.attemptHighWaterMarks?.get(phase) ?: 0) < attempt
    }
  val ledgerExceeds = (active?.ledgerSequenceHighWaterMark ?: 0) < occurrence.progress.ledgerSequenceHighWaterMark
  return attemptExceeds || ledgerExceeds
}
