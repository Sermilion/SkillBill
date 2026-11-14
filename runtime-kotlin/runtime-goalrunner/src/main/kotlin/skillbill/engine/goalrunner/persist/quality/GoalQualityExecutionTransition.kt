package skillbill.engine.goalrunner.persist.quality

import skillbill.goalrunner.model.quality.GoalQualityAcceptedSourceObservation
import skillbill.goalrunner.model.quality.GoalQualityExecution
import skillbill.goalrunner.model.quality.GoalQualityExecutionInputs
import skillbill.goalrunner.model.quality.GoalQualityExecutionPlans
import skillbill.goalrunner.model.quality.GoalQualityExecutionStatus
import skillbill.goalrunner.model.quality.GoalQualityPhaseProgress
import skillbill.goalrunner.model.quality.GoalQualityPreQaBuildState
import skillbill.goalrunner.model.quality.GoalQualityValidationDisposition
import skillbill.goalrunner.model.quality.acceptedQaSourceWitness
import skillbill.goalrunner.model.quality.hasAcceptedQaSource
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

internal fun matchesAcceptedBuildBoundary(
  execution: GoalQualityExecution,
  proof: GoalQualityBuildSourceProof?,
  allowAcceptedQaSource: Boolean,
): Boolean {
  val source = proof ?: return false
  if (!buildBoundaryPresent(execution, source)) return false
  val identity = buildIdentityMatches(execution, source) && buildObservationMatches(execution, source)
  return identity && sourceAdmissionMatches(execution, source, allowAcceptedQaSource) &&
    terminalStateMatches(execution, source)
}

private fun buildBoundaryPresent(
  execution: GoalQualityExecution,
  proof: GoalQualityBuildSourceProof,
): Boolean {
  val progress = execution.progress?.build
  val record = progress?.phaseRecords?.get(proof.phaseId)
  val observation =
    progress?.acceptedSourceObservations?.singleOrNull {
      it.phaseId == proof.phaseId && it.attempt == proof.attempt
    }
  val gate = progress?.buildGateProgress
  val core = progress != null && record != null && observation != null
  return core && gate != null
}

private fun buildIdentityMatches(
  execution: GoalQualityExecution,
  proof: GoalQualityBuildSourceProof,
): Boolean {
  val progress = execution.progress?.build ?: return false
  val sameParent = execution.parentWorkflowId == proof.parentWorkflowId
  val samePlan = execution.executionPlans?.build == proof.acceptedPlan
  val sameFingerprint =
    execution.preQaBuildContentFingerprint == proof.acceptedContentFingerprint &&
      proof.observedCurrentContentFingerprint.isNotBlank()
  val sameProgress = progress.revision == proof.progressRevision
  return sameParent && samePlan && sameFingerprint && sameProgress
}

private fun buildObservationMatches(
  execution: GoalQualityExecution,
  proof: GoalQualityBuildSourceProof,
): Boolean {
  val progress = execution.progress?.build ?: return false
  val record = progress.phaseRecords[proof.phaseId] ?: return false
  val observation =
    progress.acceptedSourceObservations.singleOrNull {
      it.phaseId == proof.phaseId && it.attempt == proof.attempt
    } ?: return false
  val sameAttempt = record.attemptCount == proof.attempt
  val sameContent =
    observation.contentFingerprint == proof.acceptedContentFingerprint &&
      observation.completionSequenceNumber == proof.terminalSequenceNumber
  return sameAttempt && sameContent && acceptedBuildOutput(progress, proof, observation.acceptedOutputFingerprint) &&
    acceptedBuildLedger(progress, proof) && acceptedBuildGate(progress, proof)
}

private fun sourceAdmissionMatches(
  execution: GoalQualityExecution,
  proof: GoalQualityBuildSourceProof,
  allowAcceptedQaSource: Boolean,
): Boolean {
  val initial = proof.observedCurrentContentFingerprint == proof.acceptedContentFingerprint
  val acceptedQa =
    proof.acceptedQaSourceWitness?.let {
      execution.hasAcceptedQaSource(proof.observedCurrentContentFingerprint, it)
    } == true
  return if (allowAcceptedQaSource) initial || acceptedQa else freshBuildSource(execution, proof, initial, acceptedQa)
}

private fun freshBuildSource(
  execution: GoalQualityExecution,
  proof: GoalQualityBuildSourceProof,
  initial: Boolean,
  acceptedQa: Boolean,
): Boolean {
  val unmatched = !initial && !acceptedQa
  return unmatched && execution.acceptedQaSourceWitness(proof.observedCurrentContentFingerprint) == null
}

private fun terminalStateMatches(
  execution: GoalQualityExecution,
  proof: GoalQualityBuildSourceProof,
): Boolean {
  val progress = execution.progress?.build ?: return false
  val record = progress.phaseRecords[proof.phaseId] ?: return false
  val gate = progress.buildGateProgress ?: return false
  val lastRun = gate.gateRuns.lastOrNull()
  return when (execution.preQaBuild) {
    GoalQualityPreQaBuildState.PASSED -> passedBuildRecord(record.status, progress.terminalCompleted, lastRun)
    GoalQualityPreQaBuildState.WARNED ->
      warnedBuildRecord(record.status, progress.terminalBlockedStep, proof, gate, lastRun)
    else -> false
  }
}

internal fun validBuildTransition(
  prior: GoalQualityExecution,
  candidate: GoalQualityExecution,
): Boolean {
  if (!stableBuildEnvelope(prior, candidate)) return false
  val progress = candidate.progress?.build ?: return false
  if (candidate.preQaBuild == GoalQualityPreQaBuildState.RUNNING) {
    return unchangedRunningBuild(prior, candidate, progress)
  }
  return acceptedBuildAdvance(prior, candidate, progress)
}

private fun acceptedBuildOutput(
  progress: GoalQualityPhaseProgress,
  proof: GoalQualityBuildSourceProof,
  fingerprint: String?,
): Boolean {
  if (fingerprint == null) return true
  return progress.acceptedOutputs.any { output ->
    val sameStep = output.phaseId == proof.phaseId && output.occurrence == proof.attempt
    sameStep && sha256(output.output) == fingerprint
  }
}

private fun acceptedBuildLedger(
  progress: GoalQualityPhaseProgress,
  proof: GoalQualityBuildSourceProof,
): Boolean {
  val terminal = progress.phaseLedger.singleOrNull { it.sequenceNumber == proof.terminalSequenceNumber }
  val start = buildLedgerStarts(progress, proof.phaseId, proof.attempt).minByOrNull { it.sequenceNumber }
  val terminalAction =
    terminal?.phaseId == proof.phaseId && terminal.attemptCount == proof.attempt &&
      terminal.action in
      setOf(FeatureTaskRuntimePhaseLedgerAction.COMPLETE, FeatureTaskRuntimePhaseLedgerAction.BLOCKED)
  val started = start != null && start.sequenceNumber < proof.terminalSequenceNumber
  return terminalAction && started
}

private fun acceptedBuildGate(
  progress: GoalQualityPhaseProgress,
  proof: GoalQualityBuildSourceProof,
): Boolean {
  val gate = progress.buildGateProgress ?: return false
  val expectedKey =
    "${proof.parentWorkflowId}:${proof.invocationId}:${SkeletonDefinition.GOAL_BUILD.id}:" +
      "${proof.phaseId}:${proof.attempt}:build"
  val settled = gate.pendingExternalOperation == null && gate.pendingExternalCheckpoint == null
  return progress.buildGateProgressKey == expectedKey && settled && gate.terminalOutcome != "paused"
}

private fun passedBuildRecord(
  status: WorkflowStepStatus,
  terminalCompleted: Boolean,
  lastRun: FeatureTaskRuntimeValidationGateRunRecord?,
): Boolean {
  val completed = status == WorkflowStepStatus.COMPLETED && terminalCompleted
  val passed = lastRun?.outcome == ValidationGateRunOutcome.PASSED && lastRun.exitCode == 0
  return completed && passed
}

private fun warnedBuildRecord(
  status: WorkflowStepStatus,
  terminalBlockedStep: String?,
  proof: GoalQualityBuildSourceProof,
  gate: FeatureTaskRuntimeValidationGateProgress,
  lastRun: FeatureTaskRuntimeValidationGateRunRecord?,
): Boolean {
  val blocked = status == WorkflowStepStatus.BLOCKED && terminalBlockedStep == proof.phaseId
  return blocked && knownBuildRefusal(gate, lastRun)
}

private fun knownBuildRefusal(
  gate: FeatureTaskRuntimeValidationGateProgress,
  lastRun: FeatureTaskRuntimeValidationGateRunRecord?,
): Boolean {
  val namedRefusal =
    gate.terminalOutcome == "blocked" &&
      (gate.terminalFailureCode != null || gate.terminalFailureDisposition != null)
  val failedRun = gate.terminalOutcome == null && lastRun != null && lastRun.outcome != ValidationGateRunOutcome.PASSED
  return namedRefusal || failedRun
}

private fun stableBuildEnvelope(
  prior: GoalQualityExecution,
  candidate: GoalQualityExecution,
): Boolean {
  val sameStatus = prior.status == candidate.status
  val sameBody =
    prior.copy(
      warning = candidate.warning,
      progress = candidate.progress,
      preQaBuild = candidate.preQaBuild,
      preQaBuildContentFingerprint = candidate.preQaBuildContentFingerprint,
    ) == candidate
  return sameStatus && sameBody
}

private fun unchangedRunningBuild(
  prior: GoalQualityExecution,
  candidate: GoalQualityExecution,
  progress: GoalQualityPhaseProgress,
): Boolean {
  val pending =
    prior.preQaBuild == GoalQualityPreQaBuildState.PENDING && prior.preQaBuildContentFingerprint == null
  val unchanged = candidate.preQaBuildContentFingerprint == null && progress == prior.progress?.build
  return pending && unchanged
}

private fun acceptedBuildAdvance(
  prior: GoalQualityExecution,
  candidate: GoalQualityExecution,
  progress: GoalQualityPhaseProgress,
): Boolean {
  if (candidate.preQaBuild !in setOf(GoalQualityPreQaBuildState.PASSED, GoalQualityPreQaBuildState.WARNED)) {
    return false
  }
  val fingerprint = candidate.preQaBuildContentFingerprint
  val observation = progress.acceptedSourceObservations.lastOrNull()
  val record = observation?.let { progress.phaseRecords[it.phaseId] }
  if (fingerprint == null || observation == null || record == null) return false
  val continued = prior.preQaBuild == GoalQualityPreQaBuildState.RUNNING
  return continued && prior.preQaBuildContentFingerprint == fingerprint &&
    advancedBuildObservation(prior, candidate, progress, observation, record)
}

private fun advancedBuildObservation(
  prior: GoalQualityExecution,
  candidate: GoalQualityExecution,
  progress: GoalQualityPhaseProgress,
  observation: GoalQualityAcceptedSourceObservation,
  record: FeatureTaskRuntimePhaseRecord,
): Boolean {
  val start = buildLedgerStart(progress, observation) ?: return false
  val terminal =
    progress.phaseLedger.singleOrNull { it.sequenceNumber == observation.completionSequenceNumber } ?: return false
  val identity =
    observation.phaseId == FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_BUILD &&
      observation.contentFingerprint == candidate.preQaBuildContentFingerprint &&
      observation.progressRevision <= progress.revision
  val sameAttempt = record.attemptCount == observation.attempt && record.resolvedAgentId == start.resolvedAgentId
  val ordered = start.sequenceNumber < observation.completionSequenceNumber
  val terminalAction = terminal.phaseId == observation.phaseId && terminal.attemptCount == observation.attempt
  return identity && sameAttempt && ordered && terminalAction &&
    terminal.action == expectedBuildAction(candidate) &&
    advancedBuildGate(prior, progress, observation) &&
    acceptedAdvanceTerminal(prior.warning, candidate, progress, observation)
}

private fun buildLedgerStart(
  progress: GoalQualityPhaseProgress,
  observation: GoalQualityAcceptedSourceObservation,
): FeatureTaskRuntimePhaseLedgerEntry? {
  val starts = buildLedgerStarts(progress, observation.phaseId, observation.attempt)
  val start = starts.minByOrNull { it.sequenceNumber } ?: return null
  return start.takeIf { starts.all { it.resolvedAgentId == start.resolvedAgentId } }
}

private fun buildLedgerStarts(
  progress: GoalQualityPhaseProgress,
  phaseId: String,
  attempt: Int,
): List<FeatureTaskRuntimePhaseLedgerEntry> =
  progress.phaseLedger.filter {
    it.phaseId == phaseId && it.attemptCount == attempt &&
      it.action == FeatureTaskRuntimePhaseLedgerAction.START
  }

private fun expectedBuildAction(candidate: GoalQualityExecution): FeatureTaskRuntimePhaseLedgerAction =
  if (candidate.preQaBuild == GoalQualityPreQaBuildState.PASSED) {
    FeatureTaskRuntimePhaseLedgerAction.COMPLETE
  } else {
    FeatureTaskRuntimePhaseLedgerAction.BLOCKED
  }

private fun advancedBuildGate(
  prior: GoalQualityExecution,
  progress: GoalQualityPhaseProgress,
  observation: GoalQualityAcceptedSourceObservation,
): Boolean {
  val expectedKey =
    "${prior.parentWorkflowId}:${prior.invocationId}:${SkeletonDefinition.GOAL_BUILD.id}:" +
      "${observation.phaseId}:${observation.attempt}:build"
  return progress.buildGateProgressKey == expectedKey
}

private fun acceptedAdvanceTerminal(
  priorWarning: String?,
  candidate: GoalQualityExecution,
  progress: GoalQualityPhaseProgress,
  observation: GoalQualityAcceptedSourceObservation,
): Boolean {
  val gate = progress.buildGateProgress ?: return false
  return if (candidate.preQaBuild == GoalQualityPreQaBuildState.PASSED) {
    passedAdvance(progress, gate, candidate, priorWarning)
  } else {
    warnedAdvance(progress, gate, observation, candidate)
  }
}

private fun passedAdvance(
  progress: GoalQualityPhaseProgress,
  gate: FeatureTaskRuntimeValidationGateProgress,
  candidate: GoalQualityExecution,
  priorWarning: String?,
): Boolean {
  val run = gate.gateRuns.lastOrNull() ?: return false
  val completed = progress.terminalCompleted && progress.terminalBlockedStep == null
  val passed = run.outcome == ValidationGateRunOutcome.PASSED && run.exitCode == 0
  return completed && passed && candidate.warning == priorWarning
}

private fun warnedAdvance(
  progress: GoalQualityPhaseProgress,
  gate: FeatureTaskRuntimeValidationGateProgress,
  observation: GoalQualityAcceptedSourceObservation,
  candidate: GoalQualityExecution,
): Boolean {
  val blockedStep = progress.terminalBlockedStep ?: return false
  val blocked = progress.phaseRecords[blockedStep] ?: return false
  val event = progress.acceptedBlockedValidationEvent(blocked.phaseId, blocked.attemptCount)
  val sameEvent = event?.sequenceNumber == observation.completionSequenceNumber
  val quiet = gate.pendingExternalOperation == null && gate.pendingExternalCheckpoint == null
  return sameEvent && candidate.warning?.isNotBlank() == true && quiet &&
    knownBuildRefusal(gate, gate.gateRuns.lastOrNull())
}

internal fun allowsQualityProgressWrite(
  execution: GoalQualityExecution,
  write: GoalQualityPhaseProgressWrite,
  selectedPlan: String,
): Boolean {
  val sameAttempt = execution.invocationId == write.invocationId && selectedPlan == write.acceptedPlan
  if (!sameAttempt) return false
  return when (write.definitionId) {
    SkeletonDefinition.GOAL_BUILD.id -> buildProgressAllowed(execution)
    SkeletonDefinition.GOAL_QA.id -> qaProgressAllowed(execution, write)
    SkeletonDefinition.VALIDATION.id -> validationProgressAllowed(execution)
    else -> false
  }
}

private fun buildProgressAllowed(execution: GoalQualityExecution): Boolean =
  execution.status != GoalQualityExecutionStatus.RETIRED &&
    execution.preQaBuild == GoalQualityPreQaBuildState.RUNNING

private fun qaProgressAllowed(
  execution: GoalQualityExecution,
  write: GoalQualityPhaseProgressWrite,
): Boolean {
  val pending =
    execution.status == GoalQualityExecutionStatus.QA_PENDING &&
      execution.preQaBuild == GoalQualityPreQaBuildState.PASSED
  return pending && matchesAcceptedBuildBoundary(execution, write.preQaBuildSourceProof, allowAcceptedQaSource = true)
}

private fun validationProgressAllowed(execution: GoalQualityExecution): Boolean {
  val running = execution.status == GoalQualityExecutionStatus.VALIDATION_RUNNING
  val built =
    execution.preQaBuild in setOf(GoalQualityPreQaBuildState.PASSED, GoalQualityPreQaBuildState.WARNED)
  return running && built
}

internal fun acceptsInitialBuildReentry(
  execution: GoalQualityExecution,
  proof: GoalQualityBuildSourceProof,
): Boolean {
  val sameInvocation =
    execution.invocationId == proof.invocationId && execution.status == GoalQualityExecutionStatus.QA_PENDING
  val settled =
    execution.preQaBuild in setOf(GoalQualityPreQaBuildState.PASSED, GoalQualityPreQaBuildState.WARNED)
  return sameInvocation && settled && matchesAcceptedBuildBoundary(execution, proof, allowAcceptedQaSource = false)
}

internal fun blocksQualityTransition(
  buildTransition: Boolean,
  validOuter: Boolean,
  sameRecordEnvelope: Boolean,
  settledBuild: Boolean,
): Boolean {
  val ordinary = !buildTransition && !validOuter
  val envelope = buildTransition && !sameRecordEnvelope
  val unsettled = !buildTransition && !settledBuild
  return ordinary || envelope || unsettled
}

internal fun conflictingHistoricalPlans(
  oldFingerprint: String?,
  qaPlan: String?,
  validationPlan: String?,
  expectedFingerprint: String,
): Boolean {
  val present = oldFingerprint != null && qaPlan != null && validationPlan != null
  return present && oldFingerprint != expectedFingerprint
}

internal fun historicalAdmissionMatches(
  retained: GoalQualityExecutionRead.Historical,
  parentWorkflowId: String,
  selectionFingerprint: String,
  governingInputs: GoalQualityExecutionInputs,
  executionPlans: GoalQualityExecutionPlans,
): Boolean {
  val retainedInputs = retained.governingInputs ?: return false
  val identity = retained.parentWorkflowId == parentWorkflowId && !retained.invocationId.isNullOrBlank()
  val baseline =
    !retained.baselineContentFingerprint.isNullOrBlank() && !retained.selectionFingerprint.isNullOrBlank()
  val selection = retained.selectionFingerprint == selectionFingerprint
  val inputs =
    retained.governingInputFingerprint ==
      historicalGoverningInputFingerprint(retainedInputs, retained.sourceContractVersion) &&
      retainedInputs == governingInputs
  val plans = retained.qaPlan == executionPlans.qa && retained.validationPlan == executionPlans.validation
  return identity && baseline && selection && inputs && plans
}

internal fun validCandidateDisposition(
  prior: GoalQualityExecution,
  candidate: GoalQualityExecution,
): Boolean {
  return when (candidate.status) {
    GoalQualityExecutionStatus.QA_PENDING,
    GoalQualityExecutionStatus.RETIRED,
    -> false
    GoalQualityExecutionStatus.VALIDATION_DECISION_PENDING ->
      candidate.validation in
        setOf(
          GoalQualityValidationDisposition.DECISION_PENDING,
          GoalQualityValidationDisposition.NOT_REQUIRED,
        ) && (prior.status == GoalQualityExecutionStatus.QA_PENDING || candidate.warning == prior.warning)
    GoalQualityExecutionStatus.REQUIRED_VALIDATION_PENDING ->
      candidate.validation == GoalQualityValidationDisposition.REQUIRED_PENDING &&
        (prior.status == GoalQualityExecutionStatus.QA_PENDING || candidate.warning == prior.warning)
    GoalQualityExecutionStatus.VALIDATION_RUNNING ->
      candidate.validation == GoalQualityValidationDisposition.RUNNING && candidate.warning == prior.warning
    GoalQualityExecutionStatus.VALIDATION_COMPLETED_PENDING_SETTLEMENT ->
      candidate.validation in
        setOf(
          GoalQualityValidationDisposition.COMPLETED,
          GoalQualityValidationDisposition.DELIBERATELY_WARNED,
        ) &&
        if (candidate.validation == GoalQualityValidationDisposition.DELIBERATELY_WARNED) {
          candidate.warning != null
        } else {
          candidate.warning == prior.warning
        }
    else -> validTerminalDisposition(candidate)
  }
}

internal fun sameExecutionIdentity(
  prior: GoalQualityExecution,
  candidate: GoalQualityExecution,
): Boolean =
  prior.copy(
    status = candidate.status,
    validation = candidate.validation,
    warning = candidate.warning,
    settledContentFingerprint = candidate.settledContentFingerprint,
    finalValidationContentFingerprint = candidate.finalValidationContentFingerprint,
    postQaContentFingerprint = candidate.postQaContentFingerprint,
    qaTestedTreeIdentity = candidate.qaTestedTreeIdentity,
    skipReason = candidate.skipReason,
    progress = candidate.progress,
    preQaBuild = candidate.preQaBuild,
    preQaBuildContentFingerprint = candidate.preQaBuildContentFingerprint,
  ) == candidate

internal fun validQaObservation(
  prior: GoalQualityExecution,
  candidate: GoalQualityExecution,
): Boolean =
  if (prior.status == GoalQualityExecutionStatus.QA_PENDING) {
    val hasObservation = !candidate.postQaContentFingerprint.isNullOrBlank()
    val hasTestedTree = !candidate.qaTestedTreeIdentity.isNullOrBlank()
    val justified = hasTestedTree || !candidate.warning.isNullOrBlank() || candidate.skipReason != null
    val requiresObservation =
      hasTestedTree || candidate.status == GoalQualityExecutionStatus.REQUIRED_VALIDATION_PENDING
    justified && (!requiresObservation || hasObservation)
  } else {
    candidate.skipReason == prior.skipReason &&
      candidate.postQaContentFingerprint == prior.postQaContentFingerprint &&
      candidate.qaTestedTreeIdentity == prior.qaTestedTreeIdentity
  }

internal fun validSettledFingerprint(
  prior: GoalQualityExecution,
  candidate: GoalQualityExecution,
): Boolean {
  val terminal =
    candidate.status in
      setOf(
        GoalQualityExecutionStatus.SETTLED_PASSED,
        GoalQualityExecutionStatus.SETTLED_SKIPPED,
        GoalQualityExecutionStatus.SETTLED_WARNED,
      )
  return terminal == (candidate.settledContentFingerprint != null) &&
    (terminal || candidate.settledContentFingerprint == prior.settledContentFingerprint)
}

internal fun validFinalValidationObservation(
  prior: GoalQualityExecution,
  candidate: GoalQualityExecution,
): Boolean {
  val terminalValidation =
    candidate.status in
      setOf(
        GoalQualityExecutionStatus.VALIDATION_COMPLETED_PENDING_SETTLEMENT,
        GoalQualityExecutionStatus.SETTLED_PASSED,
        GoalQualityExecutionStatus.SETTLED_SKIPPED,
        GoalQualityExecutionStatus.SETTLED_WARNED,
      )
  return if (terminalValidation) {
    !candidate.finalValidationContentFingerprint.isNullOrBlank() &&
      (
        prior.finalValidationContentFingerprint == null ||
          prior.finalValidationContentFingerprint == candidate.finalValidationContentFingerprint
      )
  } else {
    candidate.finalValidationContentFingerprint == prior.finalValidationContentFingerprint
  }
}

private fun sha256(value: String): String =
  MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
