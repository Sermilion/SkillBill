package skillbill.engine.goalrunner.persist.quality

import skillbill.contracts.JsonCodec
import skillbill.contracts.workflow.goal.GOAL_QUALITY_EXECUTION_CONTRACT_VERSION
import skillbill.error.core.SkillBillRuntimeException
import skillbill.error.shellcontent.WorkflowFailureCode
import skillbill.goalrunner.model.quality.GoalQualityAcceptedSourceObservation
import skillbill.goalrunner.model.quality.GoalQualityAdoptionEvidence
import skillbill.goalrunner.model.quality.GoalQualityExecution
import skillbill.goalrunner.model.quality.GoalQualityExecutionInputs
import skillbill.goalrunner.model.quality.GoalQualityExecutionPlans
import skillbill.goalrunner.model.quality.GoalQualityExecutionRecord
import skillbill.goalrunner.model.quality.GoalQualityExecutionStatus
import skillbill.goalrunner.model.quality.GoalQualityPhaseProgress
import skillbill.goalrunner.model.quality.GoalQualityReadiness
import skillbill.goalrunner.model.quality.GoalQualityReadiness.CLEAN_UNSTARTED
import skillbill.goalrunner.model.quality.GoalQualityValidationDisposition
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimePhaseLedgerAction
import skillbill.workflow.taskruntime.model.skeleton.SkeletonDefinition
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
  validateOccurrenceHistory(execution)
}

private fun validateOccurrenceHistory(execution: GoalQualityExecution) {
  val activeValidation = execution.progress?.validation
  val invalidOccurrenceHistory =
    execution.validationOccurrence != execution.validationOccurrences.size ||
      execution.validationOccurrences.map { it.occurrence } != execution.validationOccurrences.indices.toList() ||
      execution.validationOccurrences.any { archived ->
        archived.progress.revision >= (activeValidation?.revision ?: 0) ||
          archived.progress.phaseRecords.any { (phase, record) -> phase != record.phaseId } ||
          archived.progress.phaseLedger.map { it.sequenceNumber }.distinct().size !=
          archived.progress.phaseLedger.size
      } ||
      execution.validationOccurrences.flatMap { archived ->
        archived.progress.attemptHighWaterMarks.entries
      }.any { (phase, attempt) -> (activeValidation?.attemptHighWaterMarks?.get(phase) ?: 0) < attempt }
  if (invalidOccurrenceHistory) {
    invalid("Stored goal quality validation occurrence history is inconsistent with its active progress.")
  }
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
  if (!valid) invalid("A settled quality execution has an invalid validation, warning or fingerprint.")
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
  val json = JsonCodec.mapToJsonString(inputs.toPersistenceWire())
  return MessageDigest.getInstance("SHA-256")
    .digest(json.toByteArray(Charsets.UTF_8))
    .joinToString("") { "%02x".format(it) }
}

private fun invalid(reason: String): Nothing =
  throw SkillBillRuntimeException(WorkflowFailureCode.INVALID_GOAL_QUALITY_EXECUTION, reason)

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
): Boolean {
  val progress = write.progress
  val terminalUpdate = progress.terminalCompleted || progress.terminalBlockedStep != null
  val isValidation = write.definitionId == SkeletonDefinition.VALIDATION.id
  val fingerprint = write.finalValidationContentFingerprint
  if (!isValidation || !terminalUpdate) return fingerprint == null
  if (fingerprint.isNullOrBlank()) return false
  val observation = progress.acceptedSourceObservations.lastOrNull() ?: return false
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
  if (!proofMatchesRecord(proof, sourceVersion, record.execution)) {
    invalid("Accepted final-validation evidence changed before transactional admission; reload the retained execution.")
  }
  return true
}

internal fun matchesExistingProgressSource(
  write: GoalQualityPhaseProgressWrite,
  execution: GoalQualityExecution,
): Boolean {
  val terminalUpdate = write.progress.terminalCompleted || write.progress.terminalBlockedStep != null
  return write.definitionId != SkeletonDefinition.VALIDATION.id || !terminalUpdate ||
    execution.finalValidationContentFingerprint == write.finalValidationContentFingerprint
}
