package skillbill.engine.goalrunner.persist.quality

import skillbill.contracts.JsonCodec
import skillbill.contracts.workflow.goal.GOAL_QUALITY_EXECUTION_CONTRACT_VERSION
import skillbill.contracts.workflow.goal.GoalQualityExecutionPayloadKeys
import skillbill.goalrunner.model.quality.GoalQualityAcceptedSourceObservation
import skillbill.goalrunner.model.quality.GoalQualityAdoptionEvidence
import skillbill.goalrunner.model.quality.GoalQualityExecution
import skillbill.goalrunner.model.quality.GoalQualityExecutionInputs
import skillbill.goalrunner.model.quality.GoalQualityExecutionPlans
import skillbill.goalrunner.model.quality.GoalQualityExecutionRecord
import skillbill.goalrunner.model.quality.GoalQualityExecutionStatus
import skillbill.goalrunner.model.quality.GoalQualityPhaseProgress
import skillbill.goalrunner.model.quality.GoalQualityPreQaBuildOccurrence
import skillbill.goalrunner.model.quality.GoalQualityPreQaBuildState
import skillbill.goalrunner.model.quality.GoalQualityReadiness
import skillbill.goalrunner.model.quality.GoalQualityReadiness.CLEAN_UNSTARTED
import skillbill.goalrunner.model.quality.GoalQualityValidationDisposition
import skillbill.goalrunner.model.quality.GoalQualityValidationOccurrence
import skillbill.goalrunner.model.quality.validQaSourceWitnessIdentity
import skillbill.workflow.model.WorkflowStepStatus
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimePhaseLedgerAction
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimePhaseLedgerEntry
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimePhaseRecord
import skillbill.workflow.taskruntime.model.skeleton.SkeletonDefinition
import skillbill.workflow.taskruntime.model.validation.FeatureTaskRuntimeValidationGateProgress
import skillbill.workflow.taskruntime.model.validation.ValidationGateRunOutcome
import skillbill.workflow.taskruntime.phase.task.FeatureTaskRuntimePhaseWorkflowDefinition
import java.security.MessageDigest

internal fun validatePhaseProgress(progress: GoalQualityPhaseProgress) {
  val invalidRecords = progress.phaseRecords.any { (phase, record) -> phase != record.phaseId }
  val invalidLedger =
    progress.phaseLedger.map { it.sequenceNumber }.distinct().size != progress.phaseLedger.size ||
      progress.phaseLedger.zipWithNext().any { (left, right) -> right.sequenceNumber <= left.sequenceNumber }
  val invalidOutputs =
    progress.acceptedOutputs.any { it.phaseId.isBlank() || it.occurrence < 1 } ||
      progress.acceptedOutputs.map { it.phaseId to it.occurrence }.distinct().size != progress.acceptedOutputs.size
  val invalidEnvelopes =
    progress.acceptedEnvelopes.any {
      it.phaseId.isBlank() || it.attempt < 1 || !it.envelope.isObject
    } ||
      progress.acceptedEnvelopes.map { it.phaseId to it.attempt }.distinct().size != progress.acceptedEnvelopes.size
  if (listOf(
      invalidRecords,
      invalidLedger,
      invalidOutputs,
      invalidEnvelopes,
      invalidSourceObservations(progress),
      invalidGateKeys(progress),
      invalidTerminalProgress(progress),
    ).any { it }
  ) {
    invalid("Goal-quality phase progress has conflicting identities or terminal state.")
  }
}

internal fun validateExecutionIdentity(execution: GoalQualityExecution) {
  val blankRequired =
    listOf(
      execution.parentWorkflowId,
      execution.invocationId,
      execution.baselineContentFingerprint,
    ).any(String::isBlank)
  val blankOptional =
    listOfNotNull(
      execution.warning,
      execution.selectionFingerprint,
      execution.settledContentFingerprint,
      execution.postQaContentFingerprint,
      execution.qaTestedTreeIdentity,
    ).any(String::isBlank)
  val missingFingerprint =
    execution.status != GoalQualityExecutionStatus.RETIRED &&
      execution.governingInputFingerprint.isNullOrBlank()
  if (blankRequired || blankOptional || missingFingerprint) {
    invalid("Stored goal quality execution has a blank identity, baseline, or warning.")
  }
  if (execution.status != GoalQualityExecutionStatus.RETIRED &&
    (execution.executionPlans == null || execution.progress == null)
  ) {
    invalid("An admitted quality execution has no accepted plans or phase progress.")
  }
  execution.progress?.let { progress ->
    val witnesses = progress.qa.acceptedQaSourceWitnesses
    val foreignWitness =
      progress.build.acceptedQaSourceWitnesses.isNotEmpty() ||
        progress.validation.acceptedQaSourceWitnesses.isNotEmpty()
    val conflictingWitness =
      witnesses.distinct().size != witnesses.size ||
        witnesses.any {
          !progress.qa.validQaSourceWitnessIdentity(
            it,
            "${execution.parentWorkflowId}:${execution.invocationId}",
            requireNotNull(execution.executionPlans).qa,
          )
        }
    if (foreignWitness || conflictingWitness) {
      invalid("Stored QA source provenance has conflicting plan, attempt or source identity.")
    }
  }
  validateOccurrenceHistory(execution)
}

private fun validateOccurrenceHistory(execution: GoalQualityExecution) {
  execution.preQaBuildOccurrences.forEach { occurrence -> validatePhaseProgress(occurrence.progress) }
  if (invalidValidationHistory(execution)) {
    invalid("Stored goal quality validation occurrence history is inconsistent with its active progress.")
  }
  if (invalidBuildHistory(execution)) {
    invalid("Stored initial-build occurrence history is inconsistent with its active progress.")
  }
}

private fun invalidValidationHistory(execution: GoalQualityExecution): Boolean {
  val active = execution.progress?.validation
  val countMismatch = execution.validationOccurrence != execution.validationOccurrences.size
  val indexMismatch =
    execution.validationOccurrences.map { it.occurrence } != execution.validationOccurrences.indices.toList()
  val staleArchive = execution.validationOccurrences.any { staleArchivedValidation(active, it) }
  val exceededAttempt =
    execution.validationOccurrences.flatMap { it.progress.attemptHighWaterMarks.entries }.any {
      (active?.attemptHighWaterMarks?.get(it.key) ?: 0) < it.value
    }
  return countMismatch || indexMismatch || staleArchive || exceededAttempt
}

private fun staleArchivedValidation(
  active: GoalQualityPhaseProgress?,
  archived: GoalQualityValidationOccurrence,
): Boolean {
  val newer = archived.progress.revision >= (active?.revision ?: 0)
  val recordMismatch = archived.progress.phaseRecords.any { (phase, record) -> phase != record.phaseId }
  val ledgerMismatch =
    archived.progress.phaseLedger.map { it.sequenceNumber }.distinct().size != archived.progress.phaseLedger.size
  return newer || recordMismatch || ledgerMismatch
}

private fun invalidBuildHistory(execution: GoalQualityExecution): Boolean =
  execution.preQaBuildOccurrences.any { invalidArchivedBuild(execution, it) }

private fun invalidArchivedBuild(
  execution: GoalQualityExecution,
  occurrence: GoalQualityPreQaBuildOccurrence,
): Boolean {
  val active = execution.progress?.build
  val unsettled =
    occurrence.state !in setOf(GoalQualityPreQaBuildState.PASSED, GoalQualityPreQaBuildState.WARNED) ||
      occurrence.contentFingerprint.isBlank()
  if (unsettled) return true
  if ((active?.revision ?: 0) <= occurrence.progress.revision) return true
  if (archivedBuildWatermarkExceeds(active, occurrence)) return true
  return archivedBuildEvidenceConflicts(execution, occurrence)
}

internal fun acceptedTerminalEvent(
  progress: GoalQualityPhaseProgress,
  phaseId: String,
  attempt: Int,
): FeatureTaskRuntimePhaseLedgerEntry? {
  val terminals =
    progress.phaseLedger.filter {
      it.phaseId == phaseId && it.attemptCount == attempt &&
        it.action in setOf(FeatureTaskRuntimePhaseLedgerAction.COMPLETE, FeatureTaskRuntimePhaseLedgerAction.BLOCKED)
    }
  if (terminals.map { it.action }.distinct().size > 1) return null
  return terminals.maxByOrNull { it.sequenceNumber }
}

internal fun validateExecutionDisposition(
  execution: GoalQualityExecution,
  currentContract: Boolean,
) {
  val hasFingerprint = execution.settledContentFingerprint != null
  val hasWarning = execution.warning != null
  val noValidation = execution.validation == GoalQualityValidationDisposition.NOT_REQUIRED
  val passedValidation = noValidation || execution.validation == GoalQualityValidationDisposition.COMPLETED
  val warnedValidation =
    noValidation ||
      execution.validation == GoalQualityValidationDisposition.DELIBERATELY_WARNED
  val valid =
    when (execution.status) {
      GoalQualityExecutionStatus.SETTLED_PASSED,
      GoalQualityExecutionStatus.SETTLED_SKIPPED,
      -> passedValidation && !hasWarning && hasFingerprint
      GoalQualityExecutionStatus.SETTLED_WARNED ->
        hasWarning && hasFingerprint &&
          (warnedValidation || execution.validation == GoalQualityValidationDisposition.COMPLETED)
      else -> {
        validatePendingDisposition(execution)
        true
      }
    }
  if (currentContract) validateTerminalEvidence(execution)
  if (currentContract) validateInitialBuildEvidence(execution)
  if (!valid) invalid("A settled quality execution has an invalid validation, warning or fingerprint.")
}

private fun validateInitialBuildEvidence(execution: GoalQualityExecution) {
  val fingerprint = execution.preQaBuildContentFingerprint
  val progress = execution.progress?.build ?: invalid("Current goal quality execution has no build progress.")
  when (execution.preQaBuild) {
    GoalQualityPreQaBuildState.PENDING -> rejectPendingBuild(fingerprint, progress)
    GoalQualityPreQaBuildState.RUNNING -> rejectRunningBuild(execution, fingerprint, progress)
    GoalQualityPreQaBuildState.PASSED,
    GoalQualityPreQaBuildState.WARNED,
    -> rejectSettledBuild(execution, fingerprint, progress)
  }
}

private fun rejectPendingBuild(
  fingerprint: String?,
  progress: GoalQualityPhaseProgress,
) {
  val records = progress.phaseRecords.isNotEmpty() || progress.phaseLedger.isNotEmpty()
  val terminal = progress.terminalCompleted || progress.terminalBlockedStep != null
  if (fingerprint != null || records || terminal) {
    invalid("Pending initial build carries an active attempt or terminal evidence.")
  }
}

private fun rejectRunningBuild(
  execution: GoalQualityExecution,
  fingerprint: String?,
  progress: GoalQualityPhaseProgress,
) {
  if (fingerprint == null || !runningBuildConflicts(execution, fingerprint, progress)) return
  invalid("Running initial build has incomplete or conflicting accepted terminal evidence.")
}

private fun runningBuildConflicts(
  execution: GoalQualityExecution,
  fingerprint: String,
  progress: GoalQualityPhaseProgress,
): Boolean {
  val observation = progress.acceptedSourceObservations.lastOrNull()
  val record = observation?.let { progress.phaseRecords[it.phaseId] }
  val terminal = observation?.let { acceptedTerminalEvent(progress, it.phaseId, it.attempt) }
  val gate = progress.buildGateProgress
  val missing = observation == null || record == null || terminal == null
  if (missing || gate == null) return true
  val identity =
    observation.phaseId != FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_BUILD ||
      observation.contentFingerprint != fingerprint || observation.progressRevision > progress.revision
  val attempt =
    record.attemptCount != observation.attempt || terminal.sequenceNumber != observation.completionSequenceNumber
  return identity || attempt || !runningTerminalAccepted(progress, observation, record, terminal, gate) ||
    runningGateConflicts(execution, progress, observation, gate)
}

private fun runningTerminalAccepted(
  progress: GoalQualityPhaseProgress,
  observation: GoalQualityAcceptedSourceObservation,
  record: FeatureTaskRuntimePhaseRecord,
  terminal: FeatureTaskRuntimePhaseLedgerEntry,
  gate: FeatureTaskRuntimeValidationGateProgress,
): Boolean =
  when (terminal.action) {
    FeatureTaskRuntimePhaseLedgerAction.COMPLETE -> passedRunningTerminal(progress, record, gate)
    FeatureTaskRuntimePhaseLedgerAction.BLOCKED -> blockedRunningTerminal(progress, observation, record, gate)
    else -> false
  }

private fun passedRunningTerminal(
  progress: GoalQualityPhaseProgress,
  record: FeatureTaskRuntimePhaseRecord,
  gate: FeatureTaskRuntimeValidationGateProgress,
): Boolean {
  val run = gate.gateRuns.lastOrNull()
  val completed = record.status == WorkflowStepStatus.COMPLETED && progress.terminalCompleted
  val passed = run?.outcome == ValidationGateRunOutcome.PASSED && run.exitCode == 0
  return completed && passed
}

private fun blockedRunningTerminal(
  progress: GoalQualityPhaseProgress,
  observation: GoalQualityAcceptedSourceObservation,
  record: FeatureTaskRuntimePhaseRecord,
  gate: FeatureTaskRuntimeValidationGateProgress,
): Boolean {
  val blocked = record.status == WorkflowStepStatus.BLOCKED && progress.terminalBlockedStep == observation.phaseId
  return blocked && archivedRefusal(gate, gate.gateRuns.lastOrNull())
}

private fun runningGateConflicts(
  execution: GoalQualityExecution,
  progress: GoalQualityPhaseProgress,
  observation: GoalQualityAcceptedSourceObservation,
  gate: FeatureTaskRuntimeValidationGateProgress,
): Boolean {
  val pending = gate.pendingExternalOperation != null || gate.pendingExternalCheckpoint != null
  val paused = gate.terminalOutcome == "paused"
  return pending || paused || progress.buildGateProgressKey != archivedBuildKey(execution, observation)
}

private fun rejectSettledBuild(
  execution: GoalQualityExecution,
  fingerprint: String?,
  progress: GoalQualityPhaseProgress,
) {
  if (fingerprint.isNullOrBlank()) invalid("Settled initial build has no source fingerprint.")
  val observation =
    progress.acceptedSourceObservations.lastOrNull()
      ?: invalid("Settled initial build has no accepted source observation.")
  val record =
    progress.phaseRecords[observation.phaseId]
      ?: invalid("Settled initial build source observation has no phase record.")
  val terminal =
    progress.phaseLedger.singleOrNull { it.sequenceNumber == observation.completionSequenceNumber }
      ?: invalid("Settled initial build source observation has no terminal ledger event.")
  val start = archivedBuildStart(progress, observation)
  val gate = progress.buildGateProgress ?: invalid("Settled initial build has no accepted pack gate evidence.")
  val identity =
    observation.phaseId != FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_BUILD ||
      observation.contentFingerprint != fingerprint || record.attemptCount != observation.attempt
  val attempt =
    start == null || start.sequenceNumber >= observation.completionSequenceNumber ||
      start.resolvedAgentId != record.resolvedAgentId
  val gateState = settledGateState(execution, progress, observation, gate)
  val evidence = identity || !settledResult(execution, progress, gate)
  if (evidence || attempt || gateState) {
    invalid("Settled initial build terminal evidence conflicts with its source, attempt or gate result.")
  }
}

private fun settledGateState(
  execution: GoalQualityExecution,
  progress: GoalQualityPhaseProgress,
  observation: GoalQualityAcceptedSourceObservation,
  gate: FeatureTaskRuntimeValidationGateProgress,
): Boolean {
  val pending = gate.pendingExternalOperation != null || gate.pendingExternalCheckpoint != null
  val paused = gate.terminalOutcome == "paused"
  val wrongKey = progress.buildGateProgressKey != archivedBuildKey(execution, observation)
  val gateProblem = pending || paused || wrongKey
  return !archivedOutputMatches(progress, observation) || gateProblem
}

private fun settledResult(
  execution: GoalQualityExecution,
  progress: GoalQualityPhaseProgress,
  gate: FeatureTaskRuntimeValidationGateProgress,
): Boolean {
  val observation = progress.acceptedSourceObservations.lastOrNull() ?: return false
  val record = progress.phaseRecords[observation.phaseId] ?: return false
  val terminal =
    progress.phaseLedger.singleOrNull { it.sequenceNumber == observation.completionSequenceNumber } ?: return false
  return if (execution.preQaBuild == GoalQualityPreQaBuildState.PASSED) {
    passedSettledResult(progress, record, terminal, gate)
  } else {
    warnedSettledResult(execution, observation, record, terminal, gate)
  }
}

private fun passedSettledResult(
  progress: GoalQualityPhaseProgress,
  record: FeatureTaskRuntimePhaseRecord,
  terminal: FeatureTaskRuntimePhaseLedgerEntry,
  gate: FeatureTaskRuntimeValidationGateProgress,
): Boolean {
  val run = gate.gateRuns.lastOrNull()
  val completed = progress.terminalCompleted && progress.terminalBlockedStep == null
  val recordMatches =
    record.status == WorkflowStepStatus.COMPLETED && terminal.action == FeatureTaskRuntimePhaseLedgerAction.COMPLETE
  val passed = run?.outcome == ValidationGateRunOutcome.PASSED && run.exitCode == 0
  return completed && recordMatches && passed
}

private fun warnedSettledResult(
  execution: GoalQualityExecution,
  observation: GoalQualityAcceptedSourceObservation,
  record: FeatureTaskRuntimePhaseRecord,
  terminal: FeatureTaskRuntimePhaseLedgerEntry,
  gate: FeatureTaskRuntimeValidationGateProgress,
): Boolean {
  val blocked =
    execution.progress?.build?.terminalBlockedStep == observation.phaseId &&
      record.status == WorkflowStepStatus.BLOCKED
  val recorded = terminal.action == FeatureTaskRuntimePhaseLedgerAction.BLOCKED && execution.warning != null
  return blocked && recorded && archivedRefusal(gate, gate.gateRuns.lastOrNull())
}

internal fun validatePendingDisposition(execution: GoalQualityExecution) {
  val validation = execution.validation
  val noValidation = validation == GoalQualityValidationDisposition.NOT_REQUIRED
  val decisionPending = validation == GoalQualityValidationDisposition.DECISION_PENDING
  val validationRequired = validation == GoalQualityValidationDisposition.REQUIRED_PENDING
  val running = validation == GoalQualityValidationDisposition.RUNNING
  val completed = validation == GoalQualityValidationDisposition.COMPLETED
  val warned = validation == GoalQualityValidationDisposition.DELIBERATELY_WARNED
  val valid =
    when (execution.status) {
      GoalQualityExecutionStatus.QA_PENDING -> decisionPending
      GoalQualityExecutionStatus.VALIDATION_DECISION_PENDING -> decisionPending || noValidation
      GoalQualityExecutionStatus.REQUIRED_VALIDATION_PENDING -> validationRequired
      GoalQualityExecutionStatus.VALIDATION_RUNNING -> running
      GoalQualityExecutionStatus.VALIDATION_COMPLETED_PENDING_SETTLEMENT -> completed || warned
      else -> true
    }
  if (!valid) invalid("A pending quality execution has an invalid validation disposition.")
}

private fun invalidGateKeys(progress: GoalQualityPhaseProgress): Boolean {
  val build =
    (progress.buildGateProgress == null) != (progress.buildGateProgressKey == null) ||
      progress.buildGateProgressKey?.isBlank() == true
  val validation =
    (progress.validationGateProgress == null) != (progress.validationGateProgressKey == null) ||
      progress.validationGateProgressKey?.isBlank() == true
  return build || validation
}

private fun invalidTerminalProgress(progress: GoalQualityPhaseProgress): Boolean {
  val invalidIdentity =
    progress.revision < 0 || progress.terminalBlockedStep?.isBlank() == true ||
      progress.terminalBlockedReason?.isBlank() == true
  val hasBlock = progress.terminalBlockedStep != null || progress.terminalBlockedReason != null
  val incompleteBlock = (progress.terminalBlockedStep == null) != (progress.terminalBlockedReason == null)
  return invalidIdentity || if (progress.terminalCompleted) hasBlock else incompleteBlock
}

internal fun validTerminalDisposition(candidate: GoalQualityExecution): Boolean =
  when (candidate.status) {
    GoalQualityExecutionStatus.SETTLED_PASSED ->
      candidate.validation == GoalQualityValidationDisposition.COMPLETED && candidate.warning == null
    GoalQualityExecutionStatus.SETTLED_SKIPPED ->
      candidate.validation == GoalQualityValidationDisposition.COMPLETED && candidate.warning == null
    GoalQualityExecutionStatus.SETTLED_WARNED ->
      candidate.validation in
        setOf(
          GoalQualityValidationDisposition.COMPLETED,
          GoalQualityValidationDisposition.DELIBERATELY_WARNED,
        ) && candidate.warning != null
    else -> false
  }

internal fun adoptLegacyExecution(
  initial: GoalQualityExecutionRecord,
  sourceVersion: String,
  originalPayload: Map<String, Any?>,
  validationSourceProof: GoalQualityValidationSourceProof?,
  proofMatchesStored: Boolean,
): GoalQualityExecutionRecord {
  var record = initial
  val sourceProofCurrent = proofMatchesStored && validationSourceProof?.sourceIsCurrent == true
  if (record.execution?.status == GoalQualityExecutionStatus.RETIRED) {
    record =
      GoalQualityExecutionRecord(
        contractVersion = GOAL_QUALITY_EXECUTION_CONTRACT_VERSION,
        readiness = GoalQualityReadiness.CLEAN_UNSTARTED,
        adoptionEvidence =
          record.adoptionEvidence ?: GoalQualityAdoptionEvidence(
            sourceVersion,
            JsonCodec.mapToJsonString(requireNotNull(originalPayload)),
          ),
      )
  } else {
    val execution = record.execution
    if (execution?.status == GoalQualityExecutionStatus.VALIDATION_DECISION_PENDING &&
      execution.validation == GoalQualityValidationDisposition.NOT_REQUIRED
    ) {
      record = record.requireFinalValidation()
    }
    record =
      record.copy(
        contractVersion = GOAL_QUALITY_EXECUTION_CONTRACT_VERSION,
        adoptionEvidence =
          record.adoptionEvidence ?: GoalQualityAdoptionEvidence(
            sourceVersion,
            JsonCodec.mapToJsonString(requireNotNull(originalPayload)),
          ),
      )
    if (sourceProofCurrent) {
      record = bindLegacyValidationProof(record, requireNotNull(validationSourceProof))
    }
    val legacyActive = record.execution
    val staleValidation =
      legacyActive?.let {
        it.status != GoalQualityExecutionStatus.RETIRED && requiresValidationReentry(it, proofMatchesStored)
      } == true && !sourceProofCurrent
    if (legacyActive != null && staleValidation) {
      if (hasUnknownValidationOperation(legacyActive)) {
        invalid("Legacy final validation has an unresolved external operation; inspect its retained evidence first.")
      }
      record = record.startNewValidationOccurrence()
    }
  }
  return record
}

internal fun validateRetainedAdmission(
  record: GoalQualityExecutionRecord,
  selectionFingerprint: String,
  governingInputs: GoalQualityExecutionInputs,
  executionPlans: GoalQualityExecutionPlans,
) {
  record.execution?.takeIf { it.status != GoalQualityExecutionStatus.RETIRED }?.let { active ->
    val expectedFingerprints = selectionFingerprint to governingInputFingerprint(governingInputs)
    val expectedInputs = governingInputs to executionPlans
    if ((active.selectionFingerprint to active.governingInputFingerprint) != expectedFingerprints ||
      (active.governingInputs to active.executionPlans) != expectedInputs
    ) {
      invalid(
        "The admitted goal quality execution inputs or accepted plans changed; " +
          "a new invocation requires an explicit reset.",
      )
    }
  }
}

internal fun governingInputFingerprint(inputs: GoalQualityExecutionInputs): String {
  return fingerprintInputsWire(inputs.toPersistenceWire())
}

internal fun historicalGoverningInputFingerprint(
  inputs: GoalQualityExecutionInputs,
  sourceVersion: String,
): String {
  val wire = inputs.toPersistenceWire().toMutableMap()
  if (sourceVersion != GOAL_QUALITY_EXECUTION_CONTRACT_VERSION) {
    wire.remove(GoalQualityExecutionPayloadKeys.AGENT_ASSIGNMENTS_WORKFLOW_ID)
    wire.remove(GoalQualityExecutionPayloadKeys.AGENT_ASSIGNMENTS_FINGERPRINT)
  }
  return fingerprintInputsWire(wire)
}

private fun fingerprintInputsWire(inputs: Map<String, Any?>): String {
  val json = JsonCodec.mapToJsonString(inputs)
  return MessageDigest.getInstance("SHA-256")
    .digest(json.toByteArray(Charsets.UTF_8))
    .joinToString("") { "%02x".format(it) }
}

private fun invalidSourceObservations(progress: GoalQualityPhaseProgress): Boolean =
  progress.acceptedSourceObservations.any { !validSourceObservation(it, progress) } ||
    progress.acceptedSourceObservations.map { it.phaseId to it.attempt }.distinct().size !=
    progress.acceptedSourceObservations.size

private fun validSourceObservation(
  observation: GoalQualityAcceptedSourceObservation,
  progress: GoalQualityPhaseProgress,
): Boolean {
  val identityValid =
    observation.phaseId.isNotBlank() && observation.attempt >= 1 &&
      observation.contentFingerprint.isNotBlank()
  val revisionValid =
    observation.completionSequenceNumber >= 1 && observation.progressRevision >= 1 &&
      observation.progressRevision <= progress.revision && observation.validationOccurrence >= 0
  val ledgerValid =
    progress.phaseLedger.any {
      it.phaseId == observation.phaseId && it.attemptCount == observation.attempt &&
        it.sequenceNumber == observation.completionSequenceNumber &&
        it.action in setOf(FeatureTaskRuntimePhaseLedgerAction.COMPLETE, FeatureTaskRuntimePhaseLedgerAction.BLOCKED)
    }
  return identityValid && revisionValid && ledgerValid
}

private fun validateTerminalEvidence(execution: GoalQualityExecution) {
  val noValidation = execution.validation == GoalQualityValidationDisposition.NOT_REQUIRED
  val terminalValidation =
    execution.status in
      setOf(
        GoalQualityExecutionStatus.VALIDATION_COMPLETED_PENDING_SETTLEMENT,
        GoalQualityExecutionStatus.SETTLED_PASSED,
        GoalQualityExecutionStatus.SETTLED_SKIPPED,
        GoalQualityExecutionStatus.SETTLED_WARNED,
      ) || execution.status == GoalQualityExecutionStatus.RETIRED &&
      execution.finalValidationContentFingerprint != null || execution.progress?.validation?.let {
        it.terminalCompleted || it.terminalBlockedStep != null
      } == true
  if (noValidation || terminalValidation != !execution.finalValidationContentFingerprint.isNullOrBlank()) {
    invalid("Current goal quality validation evidence is missing or premature.")
  }
}

internal fun validProgressSourceWrite(
  write: GoalQualityPhaseProgressWrite,
  execution: GoalQualityExecution,
): Boolean =
  if (write.definitionId == SkeletonDefinition.GOAL_BUILD.id) {
    validBuildSourceWrite(write)
  } else if (write.preQaBuildContentFingerprint != null) {
    false
  } else {
    validValidationSourceWrite(write, execution)
  }

private fun validBuildSourceWrite(write: GoalQualityPhaseProgressWrite): Boolean {
  val progress = write.progress
  val terminalUpdate = progress.terminalCompleted || progress.terminalBlockedStep != null
  if (write.finalValidationContentFingerprint != null || !terminalUpdate) {
    return write.preQaBuildContentFingerprint == null
  }
  val fingerprint = write.preQaBuildContentFingerprint
  val observation = progress.acceptedSourceObservations.lastOrNull()
  val record = observation?.let { progress.phaseRecords[it.phaseId] }
  if (fingerprint.isNullOrBlank() || observation == null || record == null) return false
  return buildSourceObservationMatches(progress, observation, record, fingerprint)
}

private fun buildSourceObservationMatches(
  progress: GoalQualityPhaseProgress,
  observation: GoalQualityAcceptedSourceObservation,
  record: FeatureTaskRuntimePhaseRecord,
  fingerprint: String,
): Boolean {
  val terminalEvent =
    if (record.status == WorkflowStepStatus.BLOCKED) {
      progress.acceptedBlockedValidationEvent(record.phaseId, record.attemptCount)
    } else {
      progress.phaseLedger.filter {
        it.phaseId == record.phaseId && it.attemptCount == record.attemptCount &&
          it.action == FeatureTaskRuntimePhaseLedgerAction.COMPLETE
      }.maxByOrNull { it.sequenceNumber }
    }
  val start =
    progress.phaseLedger.firstOrNull {
      it.phaseId == record.phaseId && it.attemptCount == record.attemptCount &&
        it.action == FeatureTaskRuntimePhaseLedgerAction.START
    }
  val identity =
    observation.phaseId == FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_BUILD &&
      !progress.buildGateProgressKey.isNullOrBlank() && observation.contentFingerprint == fingerprint
  val attempt =
    observation.progressRevision <= progress.revision && record.attemptCount == observation.attempt &&
      start != null && start.sequenceNumber < observation.completionSequenceNumber &&
      start.resolvedAgentId == record.resolvedAgentId
  return identity && attempt && terminalEvent?.sequenceNumber == observation.completionSequenceNumber
}

private fun validValidationSourceWrite(
  write: GoalQualityPhaseProgressWrite,
  execution: GoalQualityExecution,
): Boolean {
  val progress = write.progress
  val terminalUpdate = progress.terminalCompleted || progress.terminalBlockedStep != null
  val validation = write.definitionId == SkeletonDefinition.VALIDATION.id
  if (!validation || !terminalUpdate) return write.finalValidationContentFingerprint == null
  val fingerprint = write.finalValidationContentFingerprint
  val observation = progress.acceptedSourceObservations.lastOrNull()
  if (fingerprint.isNullOrBlank() || observation == null) return false
  val matchesRevision =
    observation.progressRevision <= progress.revision &&
      observation.validationOccurrence == execution.validationOccurrence
  return observation.contentFingerprint == fingerprint && matchesRevision
}

internal fun matchesRetainedProof(
  proof: GoalQualityValidationSourceProof?,
  sourceVersion: String?,
  record: GoalQualityExecutionRecord,
): Boolean {
  if (proof == null) return false
  if (!proofMatchesRecord(proof, sourceVersion, record)) {
    invalid("Accepted final-validation evidence changed before transactional admission; reload the retained execution.")
  }
  return true
}

internal fun matchesExistingProgressSource(
  write: GoalQualityPhaseProgressWrite,
  execution: GoalQualityExecution,
): Boolean {
  val terminalUpdate = write.progress.terminalCompleted || write.progress.terminalBlockedStep != null
  return when (write.definitionId) {
    SkeletonDefinition.GOAL_BUILD.id ->
      !terminalUpdate || execution.preQaBuildContentFingerprint == write.preQaBuildContentFingerprint
    SkeletonDefinition.VALIDATION.id ->
      !terminalUpdate || execution.finalValidationContentFingerprint == write.finalValidationContentFingerprint
    else -> write.finalValidationContentFingerprint == null && write.preQaBuildContentFingerprint == null
  }
}
