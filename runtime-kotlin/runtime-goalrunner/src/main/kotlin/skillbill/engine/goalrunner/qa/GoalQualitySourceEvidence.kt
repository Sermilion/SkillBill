package skillbill.engine.goalrunner.qa

import skillbill.engine.featuretask.phaserun.GoalQualityValidationSourceRequest
import skillbill.engine.featuretask.phaserun.PhaseRunPlanResolver
import skillbill.engine.goal.GoalRunnerManifestState
import skillbill.engine.goalrunner.execution.support.goalRunnerRepositoryContentIdentity
import skillbill.engine.goalrunner.history.goalHistoryFingerprint
import skillbill.engine.goalrunner.model.GoalRunnerRunRequest
import skillbill.engine.goalrunner.persist.GoalRunnerWorkflowOutcomeStore
import skillbill.engine.goalrunner.persist.quality.GoalQualityExecutionRead
import skillbill.engine.goalrunner.persist.quality.GoalQualityValidationSourceProof
import skillbill.error.core.SkillBillRuntimeException
import skillbill.error.shellcontent.WorkflowFailureCode
import skillbill.goalrunner.model.history.GoalFinalRepairContext
import skillbill.goalrunner.model.history.GoalHistoryWriterInput
import skillbill.goalrunner.model.history.accumulateGoalFinalRepairWarnings
import skillbill.goalrunner.model.quality.GoalQualityAcceptedSourceObservation
import skillbill.goalrunner.model.quality.GoalQualityExecution
import skillbill.goalrunner.model.quality.GoalQualityExecutionInputs
import skillbill.goalrunner.model.quality.GoalQualityExecutionRecord
import skillbill.goalrunner.model.quality.GoalQualityExecutionStatus
import skillbill.goalrunner.model.quality.GoalQualityPhaseProgress
import skillbill.goalrunner.model.quality.GoalQualityValidationDisposition
import skillbill.ports.idestatus.model.StandaloneQaOutcome
import skillbill.ports.workflow.gitops.WorkflowGitOperations
import skillbill.ports.workflow.gitops.model.WorkflowGitOperationResult
import skillbill.workflow.model.WorkflowStepStatus
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimePhaseLedgerAction
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimePhaseLedgerEntry
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimePhaseRecord
import skillbill.workflow.taskruntime.model.skeleton.ResolvedPhaseExecutionPlan
import skillbill.workflow.taskruntime.model.skeleton.SkeletonDefinition
import java.nio.file.Path
import java.security.MessageDigest

internal fun acceptedValidationSourceProof(
  phasePlans: PhaseRunPlanResolver,
  record: GoalQualityExecutionRecord,
  plan: ResolvedPhaseExecutionPlan,
  currentFingerprint: String,
  acceptedWriterContents: Boolean,
): GoalQualityValidationSourceProof? {
  val execution = record.execution ?: return null
  if (execution.governingInputs == null || execution.progress == null) return null
  val progress = requireNotNull(execution.progress).validation
  if (!phasePlans.hasAcceptedValidationBoundary(progress, plan)) return null
  return proofForAcceptedBoundary(phasePlans, record, plan, currentFingerprint, acceptedWriterContents)
}

internal fun acceptedHistoricalValidationSourceProof(
  phasePlans: PhaseRunPlanResolver,
  retained: GoalQualityExecutionRead.Historical,
  plan: ResolvedPhaseExecutionPlan,
  currentFingerprint: String,
): GoalQualityValidationSourceProof? {
  val context = historicalValidationContext(phasePlans, retained, plan) ?: return null
  val terminal = historicalValidationTerminal(context.progress, plan) ?: return null
  val observation = historicalValidationObservation(context.progress, terminal, retained.validationOccurrence)
  val legacyFingerprint = retained.finalValidationContentFingerprint ?: retained.settledContentFingerprint
  val observedContent = observation?.contentFingerprint ?: legacyFingerprint ?: return null
  val oldFingerprint = retained.planFingerprint ?: historicalPlanFingerprint(retained.qaPlan, retained.validationPlan)
  return GoalQualityValidationSourceProof(
    sourceContractVersion = retained.sourceContractVersion,
    parentWorkflowId = requireNotNull(retained.parentWorkflowId),
    invocationId = requireNotNull(retained.invocationId),
    acceptedPlanFingerprint = oldFingerprint,
    validationOccurrence = retained.validationOccurrence,
    executionStatus = requireNotNull(retained.status),
    progressRevision = context.progress.revision,
    phaseId = terminal.record.phaseId,
    attempt = terminal.record.attemptCount,
    ledgerSequenceNumber = terminal.event.sequenceNumber,
    contentFingerprint = observedContent,
    sourceIsCurrent =
      historicalSourceIsCurrent(
        phasePlans,
        retained,
        plan,
        context,
        HistoricalObservedSource(currentFingerprint, legacyFingerprint, observedContent),
      ),
    acceptedOutputFingerprint = observation?.acceptedOutputFingerprint,
  )
}

private data class HistoricalValidationContext(
  val inputs: GoalQualityExecutionInputs,
  val progress: GoalQualityPhaseProgress,
)

private data class HistoricalValidationTerminal(
  val record: FeatureTaskRuntimePhaseRecord,
  val event: FeatureTaskRuntimePhaseLedgerEntry,
)

private fun historicalValidationContext(
  phasePlans: PhaseRunPlanResolver,
  retained: GoalQualityExecutionRead.Historical,
  plan: ResolvedPhaseExecutionPlan,
): HistoricalValidationContext? {
  val plansReady = retained.hasAuthoritativePlansAndProgress && retained.status != null
  val authoritative = plansReady && retained.validation != null
  if (!authoritative) return null
  val inputs = retained.governingInputs
  val progress = retained.progress?.validation
  if (inputs == null || progress == null) return null
  if (!phasePlans.hasAcceptedValidationBoundary(progress, plan)) return null
  return HistoricalValidationContext(inputs, progress)
}

private fun historicalValidationTerminal(
  progress: GoalQualityPhaseProgress,
  plan: ResolvedPhaseExecutionPlan,
): HistoricalValidationTerminal? {
  val record =
    progress.phaseRecords.values.singleOrNull { it.status == WorkflowStepStatus.BLOCKED }
      ?: plan.selectedStepIds.lastOrNull()?.let(progress.phaseRecords::get)
      ?: return null
  val event = terminalQualityEvent(progress, record) ?: return null
  return HistoricalValidationTerminal(record, event)
}

private fun historicalValidationObservation(
  progress: GoalQualityPhaseProgress,
  terminal: HistoricalValidationTerminal,
  validationOccurrence: Int,
) = progress.acceptedSourceObservations.singleOrNull {
  val sameStep = it.phaseId == terminal.record.phaseId && it.attempt == terminal.record.attemptCount
  val sameEvent = it.completionSequenceNumber == terminal.event.sequenceNumber
  sameStep && sameEvent && it.validationOccurrence == validationOccurrence
}

private data class HistoricalObservedSource(
  val currentFingerprint: String,
  val legacyFingerprint: String?,
  val observedContent: String,
)

private fun historicalSourceIsCurrent(
  phasePlans: PhaseRunPlanResolver,
  retained: GoalQualityExecutionRead.Historical,
  plan: ResolvedPhaseExecutionPlan,
  context: HistoricalValidationContext,
  observed: HistoricalObservedSource,
): Boolean {
  if (retained.validation == GoalQualityValidationDisposition.NOT_REQUIRED) return true
  return observed.observedContent == observed.currentFingerprint &&
    phasePlans.acceptedValidationSourceIsCurrent(
      GoalQualityValidationSourceRequest(
        context.progress,
        plan,
        "${retained.parentWorkflowId}:${retained.invocationId}",
        Path.of(context.inputs.repoRoot),
        observed.currentFingerprint,
        observed.legacyFingerprint,
        retained.validationOccurrence,
      ),
    )
}

private fun historicalPlanFingerprint(
  qa: String?,
  validation: String?,
): String {
  require(!qa.isNullOrBlank() && !validation.isNullOrBlank())
  return MessageDigest.getInstance("SHA-256")
    .digest("$qa\u0000$validation".toByteArray(Charsets.UTF_8))
    .joinToString("") { "%02x".format(it) }
}

private fun proofForAcceptedBoundary(
  phasePlans: PhaseRunPlanResolver,
  record: GoalQualityExecutionRecord,
  plan: ResolvedPhaseExecutionPlan,
  currentFingerprint: String,
  acceptedWriterContents: Boolean,
): GoalQualityValidationSourceProof? {
  val execution = requireNotNull(record.execution)
  val inputs = requireNotNull(execution.governingInputs)
  val progress = requireNotNull(execution.progress).validation
  val legacyContentFingerprint =
    execution.takeIf {
      record.contractVersion in setOf("0.4", "0.5") && record.adoptionEvidence == null
    }?.settledContentFingerprint
  val terminalRecord =
    progress.phaseRecords.values.singleOrNull { it.status == WorkflowStepStatus.BLOCKED }
      ?: plan.selectedStepIds.lastOrNull()?.let(progress.phaseRecords::get)
      ?: return null
  val event = terminalQualityEvent(progress, terminalRecord) ?: return null
  val observation =
    progress.acceptedSourceObservations.singleOrNull {
      it.phaseId == terminalRecord.phaseId && it.attempt == terminalRecord.attemptCount &&
        it.completionSequenceNumber == event.sequenceNumber &&
        it.validationOccurrence == execution.validationOccurrence
    }
  val observedContent =
    observation?.contentFingerprint ?: execution.finalValidationContentFingerprint ?: legacyContentFingerprint
  val sourceRequest =
    GoalQualityValidationSourceRequest(
      progress,
      plan,
      "${execution.parentWorkflowId}:${execution.invocationId}",
      Path.of(inputs.repoRoot),
      currentFingerprint,
      legacyContentFingerprint,
      execution.validationOccurrence,
    )
  val sourceIsCurrent =
    acceptedWriterContents ||
      execution.validation != GoalQualityValidationDisposition.NOT_REQUIRED &&
      observedContent == currentFingerprint && phasePlans.acceptedValidationSourceIsCurrent(sourceRequest)
  return GoalQualityValidationSourceProof(
    sourceContractVersion = record.contractVersion,
    parentWorkflowId = execution.parentWorkflowId,
    invocationId = execution.invocationId,
    acceptedPlanFingerprint = requireNotNull(execution.executionPlans).fingerprint,
    validationOccurrence = execution.validationOccurrence,
    executionStatus = execution.status,
    progressRevision = progress.revision,
    phaseId = terminalRecord.phaseId,
    attempt = terminalRecord.attemptCount,
    ledgerSequenceNumber = event.sequenceNumber,
    contentFingerprint = observedContent,
    sourceIsCurrent = sourceIsCurrent,
    acceptedOutputFingerprint = observation?.acceptedOutputFingerprint,
  )
}

internal fun historyWriterOwnsCurrentContents(
  outcomeStore: GoalRunnerWorkflowOutcomeStore,
  phasePlans: PhaseRunPlanResolver,
  state: GoalRunnerManifestState,
  execution: GoalQualityExecution,
  currentFingerprint: String,
): Boolean {
  val completion = outcomeStore.goalHistoryCompletion(state.parentWorkflowId) ?: return false
  val repairContext = outcomeStore.goalFinalRepairContext(state.parentWorkflowId) ?: return false
  val writerInput =
    GoalHistoryWriterInput(
      state.parentWorkflowId,
      outcomeStore.goalHistoryWriterEntries(
        state.parentWorkflowId,
        state.manifest.subtasks.map { it.id to it.workflowId },
      ),
      repairContext,
    )
  if (completion.inputFingerprint != goalHistoryFingerprint(writerInput, completion.preWriterContentFingerprint) ||
    completion.preWriterContentFingerprint != execution.finalValidationContentFingerprint ||
    completion.postWriterContentFingerprint != currentFingerprint
  ) {
    return false
  }
  return historyWriterValidationMatches(phasePlans, execution, completion.preWriterContentFingerprint)
}

private fun historyWriterValidationMatches(
  phasePlans: PhaseRunPlanResolver,
  execution: GoalQualityExecution,
  preWriterContentFingerprint: String,
): Boolean {
  if (execution.governingInputs == null || execution.executionPlans == null) return false
  val inputs = requireNotNull(execution.governingInputs)
  val plans = requireNotNull(execution.executionPlans)
  val plan =
    phasePlans.validateGoalQualityExecutionPlan(
      plans.validation,
      SkeletonDefinition.VALIDATION.id,
      inputs.effectiveAgentId,
    )
  val progress = execution.progress?.validation ?: return false
  val observation = progress.acceptedSourceObservations.lastOrNull()
  if (observation == null) return false
  val currentObservation =
    observation.contentFingerprint == preWriterContentFingerprint &&
      observation.validationOccurrence == execution.validationOccurrence &&
      observation.progressRevision <= progress.revision
  return phasePlans.hasAcceptedValidationBoundary(progress, plan) && currentObservation &&
    historyWriterTerminalMatches(progress, plan, observation)
}

private fun historyWriterTerminalMatches(
  progress: GoalQualityPhaseProgress,
  plan: ResolvedPhaseExecutionPlan,
  observation: GoalQualityAcceptedSourceObservation,
): Boolean {
  val terminalRecord =
    progress.phaseRecords.values.singleOrNull { it.status == WorkflowStepStatus.BLOCKED }
      ?: plan.selectedStepIds.lastOrNull()?.let(progress.phaseRecords::get)
      ?: return false
  val terminalEvent =
    progress.phaseLedger.filter { it.phaseId == terminalRecord.phaseId }
      .maxByOrNull { it.sequenceNumber }
  return observation.phaseId == terminalRecord.phaseId && observation.attempt == terminalRecord.attemptCount &&
    terminalEvent?.sequenceNumber == observation.completionSequenceNumber &&
    observation.acceptedOutputFingerprint?.let { fingerprint ->
      progress.acceptedOutputs.any {
        it.phaseId == observation.phaseId && it.occurrence == observation.attempt &&
          MessageDigest.getInstance("SHA-256").digest(it.output.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte) } == fingerprint
      }
    } != false
}

internal fun persistWarningAggregate(
  outcomeStore: GoalRunnerWorkflowOutcomeStore,
  state: GoalRunnerManifestState,
  invocationId: String,
  vararg additions: String?,
): String? {
  fun currentFacts(): Pair<GoalFinalRepairContext, GoalQualityExecution> {
    val context = outcomeStore.goalFinalRepairContext(state.parentWorkflowId) ?: GoalFinalRepairContext(emptyList())
    val execution = outcomeStore.goalQualityExecution(state.parentWorkflowId)?.execution
    if (execution == null || execution.invocationId != invocationId ||
      execution.status == GoalQualityExecutionStatus.RETIRED
    ) {
      throw SkillBillRuntimeException(
        WorkflowFailureCode.INVALID_GOAL_QUALITY_EXECUTION,
        "Final quality warning belongs to a different or retired invocation.",
      )
    }
    return context to execution
  }

  val (context, execution) = currentFacts()
  val candidate = accumulateGoalFinalRepairWarnings(context.warning, execution.warning, *additions)
  if (!outcomeStore.recordGoalFinalRepairWarning(state.parentWorkflowId, invocationId, candidate)) {
    throw SkillBillRuntimeException(
      WorkflowFailureCode.INVALID_GOAL_FINALIZATION_HISTORY,
      "Could not persist final quality warning for '${state.parentWorkflowId}'.",
    )
  }
  val (persistedContext, persistedExecution) = currentFacts()
  val reconciled =
    accumulateGoalFinalRepairWarnings(
      persistedContext.warning,
      persistedExecution.warning,
      *additions,
    )
  if (reconciled != persistedContext.warning &&
    !outcomeStore.recordGoalFinalRepairWarning(state.parentWorkflowId, invocationId, reconciled)
  ) {
    throw SkillBillRuntimeException(
      WorkflowFailureCode.INVALID_GOAL_FINALIZATION_HISTORY,
      "Could not persist reconciled final quality warning for '${state.parentWorkflowId}'.",
    )
  }
  val (finalContext, finalExecution) = currentFacts()
  return accumulateGoalFinalRepairWarnings(finalContext.warning, finalExecution.warning, *additions)
}

internal fun currentContentFingerprint(
  gitOperations: WorkflowGitOperations,
  state: GoalRunnerManifestState,
  request: GoalRunnerRunRequest,
): String =
  (goalRunnerRepositoryContentIdentity(gitOperations, request.repoRoot) as? WorkflowGitOperationResult.Ok)
    ?.value
    ?.takeIf(String::isNotBlank)
    ?: throw SkillBillRuntimeException(
      WorkflowFailureCode.INVALID_GOAL_QUALITY_EXECUTION,
      "Could not record settled repository contents for '${state.parentWorkflowId}'.",
    )

internal fun currentRepositoryCheckpoint(
  gitOperations: WorkflowGitOperations,
  request: GoalRunnerRunRequest,
): String =
  (gitOperations.repositoryFingerprint(request.repoRoot) as? WorkflowGitOperationResult.Ok)
    ?.value?.takeIf(String::isNotBlank)
    ?: throw SkillBillRuntimeException(
      WorkflowFailureCode.INVALID_GOAL_QUALITY_EXECUTION,
      "Could not capture a repository checkpoint for retained QA source evidence.",
    )

internal fun codeChangedAfterQa(
  outcomeStore: GoalRunnerWorkflowOutcomeStore,
  gitOperations: WorkflowGitOperations,
  state: GoalRunnerManifestState,
  request: GoalRunnerRunRequest,
  execution: GoalQualityExecution? = outcomeStore.goalQualityExecution(state.parentWorkflowId)?.execution,
): String? {
  if (execution?.skipReason == GoalQaSkipReason.SUBTASKS_ALREADY_RAN_QA.wireValue) {
    val skippedAt = execution.postQaContentFingerprint?.takeIf(String::isNotBlank)
    val validatedAt = execution.finalValidationContentFingerprint?.takeIf(String::isNotBlank)
    return when {
      skippedAt == null || validatedAt == null ->
        "Could not establish whether final validation changed after per-subtask QA was skipped " +
          "because skip-boundary content evidence is unavailable."
      skippedAt == validatedAt -> null
      else ->
        "Final validation changed repository contents after per-subtask QA was skipped; " +
          "child QA did not test the assembled parent tree."
    }
  }
  val tested =
    execution?.qaTestedTreeIdentity?.takeIf(String::isNotBlank)
      ?: if (execution?.skipReason == GoalQaSkipReason.ALREADY_SETTLED_ON_TREE.wireValue) {
        outcomeStore.recordedGoalQaOutcome(qaOutcomeWorkflowId(state, execution.invocationId))
          ?.takeIf { it.outcome == StandaloneQaOutcome.PASSED }
          ?.testedTreeIdentity?.takeIf(String::isNotBlank)
      } else if (execution == null) {
        outcomeStore.recordedGoalQaOutcome(qaOutcomeWorkflowId(state, null))
          ?.takeIf { it.outcome != StandaloneQaOutcome.SKIPPED }
          ?.testedTreeIdentity?.takeIf(String::isNotBlank)
      } else {
        null
      }
  if (tested == null) {
    return if (execution?.skipReason == GoalQaSkipReason.ALREADY_SETTLED_ON_TREE.wireValue) {
      "QA tested-tree evidence is unavailable for the retained skip decision."
    } else {
      null
    }
  }
  val current = gitOperations.repositoryTreeIdentity(request.repoRoot)
  return when {
    current !is WorkflowGitOperationResult.Ok ->
      "QA tested tree $tested, but the current repository tree could not be observed."
    current.value.trim() == tested -> null
    else ->
      "validate changed code after QA settled; QA does not re-run, so the published change differs from the " +
        "tree QA tested ($tested)."
  }
}

internal fun requireCurrentValidationEvidence(
  gitOperations: WorkflowGitOperations,
  state: GoalRunnerManifestState,
  request: GoalRunnerRunRequest,
  execution: GoalQualityExecution,
) {
  if (execution.finalValidationContentFingerprint != currentContentFingerprint(gitOperations, state, request)) {
    throw SkillBillRuntimeException(
      WorkflowFailureCode.INVALID_GOAL_QUALITY_EXECUTION,
      "Final validation for '${execution.invocationId}' does not prove current repository contents.",
    )
  }
}

private fun terminalQualityEvent(
  progress: GoalQualityPhaseProgress,
  record: FeatureTaskRuntimePhaseRecord,
): FeatureTaskRuntimePhaseLedgerEntry? =
  if (record.status == WorkflowStepStatus.BLOCKED) {
    progress.acceptedBlockedValidationEvent(record.phaseId, record.attemptCount)
  } else {
    progress.phaseLedger.filter {
      it.phaseId == record.phaseId && it.attemptCount == record.attemptCount &&
        it.action == FeatureTaskRuntimePhaseLedgerAction.COMPLETE
    }.maxByOrNull { it.sequenceNumber }
  }
