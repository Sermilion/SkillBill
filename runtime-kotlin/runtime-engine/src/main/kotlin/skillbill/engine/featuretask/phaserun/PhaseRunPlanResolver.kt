package skillbill.engine.featuretask.phaserun

import me.tatarka.inject.annotations.Inject
import skillbill.engine.featuretask.lifecycle.execution.FeatureTaskRuntimeExecutionPlanCodec
import skillbill.engine.featuretask.lifecycle.execution.FeatureTaskRuntimeExecutionPlanCompatibility
import skillbill.engine.featuretask.lifecycle.execution.FeatureTaskRuntimeExecutionPlanResolver
import skillbill.engine.featuretask.lifecycle.execution.FeatureTaskRuntimeStepLaunchAssignmentFactory
import skillbill.engine.featuretask.lifecycle.execution.StepLaunchAssignmentInputs
import skillbill.engine.featuretask.model.execution.ValidationGateCommandFamily
import skillbill.engine.featuretask.runloop.core.strategySelectionFacts
import skillbill.engine.featuretask.runloop.qualitygate.goalBuildDefinitionId
import skillbill.engine.featuretask.runloop.qualitygate.packBuildGateProgressKey
import skillbill.engine.featuretask.runloop.qualitygate.validationDefinitionId
import skillbill.engine.featuretask.slot.PhaseQualityGateOperation
import skillbill.engine.featuretask.slot.PhaseStrategyLookup
import skillbill.engine.featuretask.validation.interruptedInitialDiscovery
import skillbill.error.core.SkillBillRuntimeException
import skillbill.error.featuretask.FeatureTaskRuntimeExecutionPlanAdmissionError
import skillbill.error.shellcontent.WorkflowFailureCode
import skillbill.goalrunner.model.quality.GoalQualityAcceptedSourceObservation
import skillbill.goalrunner.model.quality.GoalQualityPhaseProgress
import skillbill.ports.agentrun.AgentRunLauncher
import skillbill.ports.workflow.gitops.WorkflowGitOperations
import skillbill.ports.workflow.gitops.model.WorkflowGitOperationResult
import skillbill.workflow.model.ValidationDepth
import skillbill.workflow.model.WorkflowStepStatus
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimeResolvedBranch
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimePhaseLedgerAction
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimePhaseLedgerEntry
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimePhaseRecord
import skillbill.workflow.taskruntime.model.skeleton.PhaseSlot
import skillbill.workflow.taskruntime.model.skeleton.ResolvedPhaseExecutionPlan
import skillbill.workflow.taskruntime.model.skeleton.SkeletonDefinition
import skillbill.workflow.taskruntime.model.validation.FeatureTaskRuntimeValidationGateProgress
import skillbill.workflow.taskruntime.model.validation.ValidationGateRunOutcome
import skillbill.workflow.taskruntime.validation.FeatureTaskRuntimeProviderLimitPause
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.coroutines.cancellation.CancellationException

@Inject
public class PhaseRunPlanResolver(
  private val strategies: PhaseStrategyLookup,
  private val gitOperations: WorkflowGitOperations,
  private val agentRunLauncher: AgentRunLauncher,
  private val intakeResolver: PhaseRunIntakeResolver,
  private val executionPlanCodec: FeatureTaskRuntimeExecutionPlanCodec,
  private val executionPlanCompatibility: FeatureTaskRuntimeExecutionPlanCompatibility,
  private val executionPlanResolver: FeatureTaskRuntimeExecutionPlanResolver,
) {
  public fun resolveGoalQualityExecutionPlan(
    request: PhaseRunRequest,
    workflowId: String,
  ): String =
    try {
      val plan = resolveExecutionPlan(request, workflowId)
      val inputs =
        requireNotNull(executionPlanResolver).resolveInputs(
          request.repoRoot,
          plan.qualityGateSelection,
          ValidationDepth.DEFAULT,
          request.timeout,
        )
      requireNotNull(executionPlanCodec).encodeExecution(plan, inputs).decodeToString()
    } catch (cancelled: CancellationException) {
      throw cancelled
    } catch (failure: FeatureTaskRuntimeExecutionPlanAdmissionError) {
      throw SkillBillRuntimeException(
        WorkflowFailureCode.INVALID_GOAL_QUALITY_EXECUTION,
        "Could not resolve the goal-quality execution plan for '${request.definitionId}'.",
        failure,
      )
    }

  public fun validateGoalQualityExecutionPlan(
    encoded: String,
    expectedDefinitionId: String,
    expectedAgentId: String,
  ): ResolvedPhaseExecutionPlan {
    val definition = SkeletonDefinition.byId(expectedDefinitionId)
    try {
      val plan =
        requireNotNull(executionPlanCompatibility)
          .requireSupportedComposition(encoded.toByteArray(Charsets.UTF_8))
      if (plan.definitionId != definition.id || plan.definitionSemanticRevision != definition.semanticRevision) {
        invalidQualityPlan()
      }
      if (strategies.qualityGateSelection(plan) != plan.qualityGateSelection) invalidQualityPlan()
      plan.selectedStrategies.forEach { identity ->
        identity.steps.forEach { step ->
          if (strategies.selectedOwnerOf(step, plan) == null) invalidQualityPlan()
        }
      }
      val requiredAssignments =
        plan.selectedStepIds.filter { step ->
          strategies.selectedOwnerOf(step, plan)?.slot !in setOf(PhaseSlot.INTAKE, PhaseSlot.COMMIT_PUSH)
        }.toSet()
      if (plan.stepLaunchAssignments.keys != requiredAssignments ||
        plan.stepLaunchAssignments.any { (step, assignment) ->
          assignment.stepId != step || assignment.agentId != expectedAgentId
        }
      ) {
        invalidQualityPlan()
      }
      return plan
    } catch (failure: SkillBillRuntimeException) {
      throw failure
    } catch (failure: FeatureTaskRuntimeExecutionPlanAdmissionError) {
      throw SkillBillRuntimeException(
        WorkflowFailureCode.INVALID_GOAL_QUALITY_EXECUTION,
        "The admitted '${definition.id}' execution plan is unsupported or corrupt.",
        failure,
      )
    }
  }

  public fun validateRetainedRunningPhases(
    progress: GoalQualityPhaseProgress,
    plan: ResolvedPhaseExecutionPlan,
    outcomeNamespace: String,
  ) {
    progress.phaseRecords.values.filter { record ->
      retainedRunningPhase(progress, plan, record) &&
        // Validate only has to make the build green on the current tree, so an interrupted attempt reruns.
        strategies.qualityGateOperation(plan, record.phaseId) != PhaseQualityGateOperation.AgentValidation
    }.forEach { record ->
      val envelopeAccepted =
        progress.acceptedEnvelopes.any {
          it.phaseId == record.phaseId && it.attempt == record.attemptCount
        }
      when (val operation = strategies.qualityGateOperation(plan, record.phaseId)) {
        is PhaseQualityGateOperation.PackGate -> {
          validateRunningGate(
            progress,
            record,
            operation.commandFamily,
            packBuildGateProgressKey(
              outcomeNamespace,
              plan.definitionId,
              record.phaseId,
              record.attemptCount,
              operation.commandFamily,
            ),
          )
        }
        else -> {
          val policy = strategies.selectedOwnerOf(record.phaseId, plan)?.policyFor(record.phaseId)
          val acceptedSource =
            progress.acceptedSourceObservations.any {
              it.phaseId == record.phaseId && it.attempt == record.attemptCount &&
                it.acceptedOutputFingerprint != null
            }
          if (mutatingWithoutAcceptedSource(
              envelopeAccepted,
              policy?.mutating == true,
              policy?.fileMutating == true,
              acceptedSource,
            )
          ) {
            unknownQualityStep(record)
          }
          if (!envelopeAccepted && !replayableReadOnlyStep(progress, record, plan)) unknownQualityStep(record)
        }
      }
    }
  }

  internal fun requiresTerminalQualitySourceEvidence(plan: ResolvedPhaseExecutionPlan): Boolean =
    plan.definitionId in setOf(goalBuildDefinitionId, validationDefinitionId) &&
      plan.selectedStepIds.any { step ->
        when (val operation = strategies.qualityGateOperation(plan, step)) {
          is PhaseQualityGateOperation.PackGate -> true
          PhaseQualityGateOperation.AgentValidation -> true
          else -> false
        }
      }

  public fun acceptedValidationSourceIsCurrent(request: GoalQualityValidationSourceRequest): Boolean =
    acceptedValidationSourceIsCurrent(request, strategies, gitOperations)

  public fun hasAcceptedValidationBoundary(
    progress: GoalQualityPhaseProgress,
    plan: ResolvedPhaseExecutionPlan,
  ): Boolean = hasAcceptedValidationBoundary(progress, plan.selectedStepIds)

  private fun retainedRunningPhase(
    progress: GoalQualityPhaseProgress,
    plan: ResolvedPhaseExecutionPlan,
    record: FeatureTaskRuntimePhaseRecord,
  ): Boolean {
    if (record.status == WorkflowStepStatus.RUNNING) return true
    val pausedGate =
      record.status == WorkflowStepStatus.PAUSED &&
        strategies.qualityGateOperation(plan, record.phaseId) is PhaseQualityGateOperation.PackGate
    return pausedGate && isProviderPausedAttempt(progress.phaseLedger, record)
  }

  private fun mutatingWithoutAcceptedSource(
    envelopeAccepted: Boolean,
    mutating: Boolean,
    fileMutating: Boolean,
    acceptedSource: Boolean,
  ): Boolean {
    val mutatingStep = mutating || fileMutating
    return envelopeAccepted && mutatingStep && !acceptedSource
  }

  private fun unstartedGate(
    envelopeAccepted: Boolean,
    nested: FeatureTaskRuntimeValidationGateProgress,
    resumableProviderPause: Boolean,
  ): Boolean {
    val idle = nested.gateRunCount == 0 && nested.terminalOutcome == null
    return !envelopeAccepted && idle && !resumableProviderPause
  }

  private fun replayableReadOnlyStep(
    progress: GoalQualityPhaseProgress,
    record: FeatureTaskRuntimePhaseRecord,
    plan: ResolvedPhaseExecutionPlan,
  ): Boolean {
    val policy = strategies.selectedOwnerOf(record.phaseId, plan)?.policyFor(record.phaseId) ?: return false
    if (policy.mutating || policy.fileMutating) return false
    validateAttemptStarts(progress.phaseLedger, record)
    return !hasTerminalAction(progress.phaseLedger, record)
  }

  internal fun validateRunningGate(
    progress: GoalQualityPhaseProgress,
    record: FeatureTaskRuntimePhaseRecord,
    commandFamily: ValidationGateCommandFamily,
    expectedKey: String,
  ) {
    val envelopeAccepted =
      progress.acceptedEnvelopes.any {
        it.phaseId == record.phaseId && it.attempt == record.attemptCount
      }
    validateAttemptStarts(progress.phaseLedger, record)
    val resumableProviderPause = isProviderPausedAttempt(progress.phaseLedger, record)
    val terminalActions = hasTerminalAction(progress.phaseLedger, record) && !resumableProviderPause
    if (terminalActions) unknownQualityStep(record)
    val (storedKey, nested) =
      when (commandFamily) {
        ValidationGateCommandFamily.BUILD -> progress.buildGateProgressKey to progress.buildGateProgress
        ValidationGateCommandFamily.VALIDATION ->
          progress.validationGateProgressKey to progress.validationGateProgress
      }
    if (storedKey != expectedKey || nested == null) unknownQualityStep(record)
    if (nested.interruptedInitialDiscovery(commandFamily)) return
    if (nested.pendingExternalOperation != null || nested.pendingExternalCheckpoint != null) {
      unknownQualityStep(
        record,
      )
    }
    if (unstartedGate(envelopeAccepted, nested, resumableProviderPause)) {
      unknownQualityStep(record)
    }
  }

  private fun isProviderPausedAttempt(
    ledger: List<FeatureTaskRuntimePhaseLedgerEntry>,
    record: FeatureTaskRuntimePhaseRecord,
  ): Boolean {
    if (record.status !in setOf(WorkflowStepStatus.RUNNING, WorkflowStepStatus.PAUSED) || record.finishedAt != null) {
      return false
    }
    val terminalActions =
      ledger.filter {
        it.phaseId == record.phaseId && it.attemptCount == record.attemptCount &&
          it.action in
          setOf(
            FeatureTaskRuntimePhaseLedgerAction.COMPLETE,
            FeatureTaskRuntimePhaseLedgerAction.BLOCKED,
            FeatureTaskRuntimePhaseLedgerAction.PAUSED,
          )
      }
    if (terminalActions.any {
        it.action in setOf(FeatureTaskRuntimePhaseLedgerAction.COMPLETE, FeatureTaskRuntimePhaseLedgerAction.BLOCKED)
      }
    ) {
      return false
    }
    val terminal = terminalActions.maxByOrNull { it.sequenceNumber } ?: return false
    val providerPause =
      FeatureTaskRuntimeProviderLimitPause.isProviderLimitPauseReason(terminal.blockedReason.orEmpty())
    return terminal.action == FeatureTaskRuntimePhaseLedgerAction.PAUSED && providerPause &&
      (
        record.status == WorkflowStepStatus.RUNNING ||
          FeatureTaskRuntimeProviderLimitPause.isProviderLimitPauseReason(record.blockedReason.orEmpty())
      )
  }

  private fun hasTerminalAction(
    ledger: List<FeatureTaskRuntimePhaseLedgerEntry>,
    record: FeatureTaskRuntimePhaseRecord,
  ): Boolean {
    return ledger.any {
      it.phaseId == record.phaseId && it.attemptCount == record.attemptCount &&
        it.action in
        setOf(
          FeatureTaskRuntimePhaseLedgerAction.COMPLETE,
          FeatureTaskRuntimePhaseLedgerAction.BLOCKED,
          FeatureTaskRuntimePhaseLedgerAction.PAUSED,
        )
    }
  }

  public fun validateNestedProgressKeys(progress: GoalQualityPhaseProgress) {
    val invalidBuild =
      (progress.buildGateProgress == null) != (progress.buildGateProgressKey == null) ||
        progress.buildGateProgressKey?.isBlank() == true
    val invalidValidation =
      (progress.validationGateProgress == null) != (progress.validationGateProgressKey == null) ||
        progress.validationGateProgressKey?.isBlank() == true
    if (invalidBuild || invalidValidation) {
      throw SkillBillRuntimeException(
        WorkflowFailureCode.INVALID_GOAL_QUALITY_EXECUTION,
        "Goal-quality nested gate progress is missing its exact progress key.",
      )
    }
  }

  private fun validateAttemptStarts(
    ledger: List<FeatureTaskRuntimePhaseLedgerEntry>,
    record: FeatureTaskRuntimePhaseRecord,
  ) {
    val starts =
      ledger.filter {
        it.phaseId == record.phaseId && it.attemptCount == record.attemptCount &&
          it.action == FeatureTaskRuntimePhaseLedgerAction.START
      }
    val first = starts.minByOrNull { it.sequenceNumber }
    val duplicateSequence = starts.map { it.sequenceNumber }.distinct().size != starts.size
    val conflicting =
      first != null &&
        starts.any {
          it.resolvedAgentId != record.resolvedAgentId || it.executionOrigin != record.executionOrigin ||
            it.copy(sequenceNumber = first.sequenceNumber, timestamp = first.timestamp) != first
        }
    if (first == null || duplicateSequence || conflicting) {
      throw SkillBillRuntimeException(
        WorkflowFailureCode.INVALID_GOAL_QUALITY_EXECUTION,
        "Goal-quality step '${record.phaseId}#${record.attemptCount}' has no consistent accepted start " +
          "for nested gate recovery.",
      )
    }
  }

  private fun unknownQualityStep(record: FeatureTaskRuntimePhaseRecord): Nothing =
    throw SkillBillRuntimeException(
      WorkflowFailureCode.INVALID_GOAL_QUALITY_EXECUTION,
      "The outcome of goal-quality step '${record.phaseId}#${record.attemptCount}' is unknown. " +
        "Inspect authoritative process or phase evidence, or explicitly retire " +
        "this quality execution and accept new cycles: " +
        "skill-bill goal fresh-quality <issue-key> --reason \"<why>\".",
    )

  internal fun invalidQualityPlan(detail: String? = null): Nothing =
    throw SkillBillRuntimeException(
      WorkflowFailureCode.INVALID_GOAL_QUALITY_EXECUTION,
      "The admitted goal-quality execution plan is unsupported or corrupt." + (detail?.let { " $it" } ?: ""),
    )

  internal fun resolveExecutionPlan(
    request: PhaseRunRequest,
    workflowId: String,
    branch: FeatureTaskRuntimeResolvedBranch? = null,
    preparedFacts: InMemoryPhaseRunFacts? = null,
  ): ResolvedPhaseExecutionPlan {
    val definition = SkeletonDefinition.byId(request.definitionId)
    val resolvedBranch = branch ?: currentBranch(request)
    val facts =
      preparedFacts ?: InMemoryPhaseRunFacts(
        request,
        definition,
        intakeResolver.resolve(definition, request, resolvedBranch?.branch),
        workflowId,
        null,
      )
    val selection = strategySelectionFacts(facts)
    return strategies.executionPlan(
      selection.copy(
        stepAssignments =
          FeatureTaskRuntimeStepLaunchAssignmentFactory.resolve(
            launcher = agentRunLauncher,
            lookup = strategies,
            facts = selection,
            inputs =
              StepLaunchAssignmentInputs(
                invokedAgentId = request.invokedAgentId,
                agentAssignment = facts.agentAssignment,
                modelAssignment = facts.modelAssignment,
              ),
          ),
      ),
    )
  }

  internal fun currentBranch(request: PhaseRunRequest): FeatureTaskRuntimeResolvedBranch? =
    (gitOperations.currentBranch(request.repoRoot) as? WorkflowGitOperationResult.Ok)
      ?.value
      ?.trim()
      ?.takeIf { branch -> branch.isNotBlank() && branch != "HEAD" }
      ?.let(::FeatureTaskRuntimeResolvedBranch)
}

internal fun acceptedValidationSourceIsCurrent(
  request: GoalQualityValidationSourceRequest,
  strategies: PhaseStrategyLookup,
  gitOperations: WorkflowGitOperations,
): Boolean {
  val progress = request.progress
  val observations = progress.acceptedSourceObservations
  if (observations.isEmpty()) {
    return request.legacyContentFingerprint == request.currentContentFingerprint &&
      legacyGateSourceIsCurrent(request, strategies, gitOperations)
  }
  if (observations.last().contentFingerprint != request.currentContentFingerprint ||
    observations.any { !observationMatchesProgress(it, request) }
  ) {
    return false
  }
  val warningStep = progress.terminalBlockedStep ?: retainedBlockedBoundary(progress)
  return if (warningStep != null) {
    warningSourceIsCurrent(request, warningStep, strategies, gitOperations)
  } else {
    completedSourceIsCurrent(request, strategies, gitOperations)
  }
}

private fun observationMatchesProgress(
  observation: GoalQualityAcceptedSourceObservation,
  request: GoalQualityValidationSourceRequest,
): Boolean {
  val progress = request.progress
  val record = progress.phaseRecords[observation.phaseId]
  val event = record?.let { terminalEvent(progress, it) }
  val identityMatches =
    (
      request.commandFamily == ValidationGateCommandFamily.BUILD ||
        observation.validationOccurrence == request.validationOccurrence
    ) &&
      observation.progressRevision <= progress.revision && record?.attemptCount == observation.attempt &&
      event?.sequenceNumber == observation.completionSequenceNumber
  val outputMatches =
    observation.acceptedOutputFingerprint?.let { fingerprint ->
      progress.acceptedOutputs.any { output ->
        output.phaseId == observation.phaseId && output.occurrence == observation.attempt &&
          sha256(output.output) == fingerprint
      }
    } != false
  return identityMatches && outputMatches
}

private fun terminalEvent(
  progress: GoalQualityPhaseProgress,
  record: FeatureTaskRuntimePhaseRecord,
): FeatureTaskRuntimePhaseLedgerEntry? =
  when (record.status) {
    WorkflowStepStatus.BLOCKED -> progress.acceptedBlockedValidationEvent(record.phaseId, record.attemptCount)
    WorkflowStepStatus.COMPLETED ->
      progress.phaseLedger.filter {
        it.phaseId == record.phaseId && it.attemptCount == record.attemptCount &&
          it.action == FeatureTaskRuntimePhaseLedgerAction.COMPLETE
      }.maxByOrNull { it.sequenceNumber }
    else -> null
  }

private fun retainedBlockedBoundary(progress: GoalQualityPhaseProgress): String? =
  progress.acceptedSourceObservations.lastOrNull()?.let { observation ->
    progress.phaseRecords[observation.phaseId]?.takeIf { record ->
      record.status == WorkflowStepStatus.BLOCKED && record.attemptCount == observation.attempt &&
        progress.acceptedBlockedValidationEvent(record.phaseId, record.attemptCount)?.sequenceNumber ==
        observation.completionSequenceNumber
    }?.phaseId
  }

private fun warningSourceIsCurrent(
  request: GoalQualityValidationSourceRequest,
  warningStep: String,
  strategies: PhaseStrategyLookup,
  gitOperations: WorkflowGitOperations,
): Boolean {
  val progress = request.progress
  val observation = progress.acceptedSourceObservations.last()
  val record = progress.phaseRecords[warningStep] ?: return false
  val matchesObservation = observation.phaseId == warningStep && record.attemptCount == observation.attempt
  val matchesBoundary =
    record.status == WorkflowStepStatus.BLOCKED &&
      progress.acceptedBlockedValidationEvent(warningStep, observation.attempt)?.sequenceNumber ==
      observation.completionSequenceNumber
  if (!matchesObservation || !matchesBoundary) return false
  val operation = strategies.qualityGateOperation(request.plan, warningStep)
  return if (operation is PhaseQualityGateOperation.PackGate) {
    gateKeyMatches(request, record, operation.commandFamily) &&
      warningGateSourceIsCurrent(gateProgress(progress, operation.commandFamily), request.repoRoot, gitOperations)
  } else {
    operation == PhaseQualityGateOperation.AgentValidation
  }
}

private fun completedSourceIsCurrent(
  request: GoalQualityValidationSourceRequest,
  strategies: PhaseStrategyLookup,
  gitOperations: WorkflowGitOperations,
): Boolean {
  val progress = request.progress
  val completedRecords = progress.phaseRecords.values.filter { it.status == WorkflowStepStatus.COMPLETED }
  if (completedRecords.isEmpty() || completedRecords.any { !completedRecordIsCurrent(it, request) }) return false
  val observation = progress.acceptedSourceObservations.last()
  val record = progress.phaseRecords[observation.phaseId] ?: return false
  if (record.attemptCount != observation.attempt || record.status != WorkflowStepStatus.COMPLETED) return false
  val operation = strategies.qualityGateOperation(request.plan, observation.phaseId)
  return if (operation is PhaseQualityGateOperation.PackGate) {
    gateKeyMatches(request, record, operation.commandFamily) &&
      completedGateSourceIsCurrent(gateProgress(progress, operation.commandFamily), request.repoRoot, gitOperations)
  } else {
    progress.acceptedEnvelopes.any { it.phaseId == observation.phaseId && it.attempt == observation.attempt } ||
      progress.acceptedOutputs.any { it.phaseId == observation.phaseId && it.occurrence == observation.attempt }
  }
}

private fun completedRecordIsCurrent(
  record: FeatureTaskRuntimePhaseRecord,
  request: GoalQualityValidationSourceRequest,
): Boolean {
  val progress = request.progress
  val observation =
    progress.acceptedSourceObservations.singleOrNull {
      it.phaseId == record.phaseId && it.attempt == record.attemptCount
    } ?: return false
  return observation.contentFingerprint == request.currentContentFingerprint &&
    terminalEvent(progress, record)?.action == FeatureTaskRuntimePhaseLedgerAction.COMPLETE
}

private fun gateKeyMatches(
  request: GoalQualityValidationSourceRequest,
  record: FeatureTaskRuntimePhaseRecord,
  family: ValidationGateCommandFamily,
): Boolean =
  gateProgressKey(request.progress, family) ==
    packBuildGateProgressKey(
      request.outcomeNamespace,
      request.plan.definitionId,
      record.phaseId,
      record.attemptCount,
      family,
    )

private fun warningGateSourceIsCurrent(
  gate: FeatureTaskRuntimeValidationGateProgress?,
  repoRoot: Path,
  gitOperations: WorkflowGitOperations,
): Boolean {
  if (gate == null || gate.pendingExternalOperation != null || gate.pendingExternalCheckpoint != null) return false
  if (gate.terminalOutcome == "paused") return false
  val knownRefusal =
    gate.terminalOutcome == "blocked" &&
      (gate.terminalFailureCode != null || gate.terminalFailureDisposition != null)
  return when {
    knownRefusal -> true
    gate.terminalOutcome != null -> false
    else ->
      gateRunSourceIsCurrent(gate, repoRoot, gitOperations) &&
        gate.gateRuns.last().outcome != ValidationGateRunOutcome.PASSED
  }
}

private fun gateProgress(
  progress: GoalQualityPhaseProgress,
  family: ValidationGateCommandFamily,
): FeatureTaskRuntimeValidationGateProgress? =
  when (family) {
    ValidationGateCommandFamily.BUILD -> progress.buildGateProgress
    ValidationGateCommandFamily.VALIDATION -> progress.validationGateProgress
  }

private fun gateProgressKey(
  progress: GoalQualityPhaseProgress,
  family: ValidationGateCommandFamily,
): String? =
  when (family) {
    ValidationGateCommandFamily.BUILD -> progress.buildGateProgressKey
    ValidationGateCommandFamily.VALIDATION -> progress.validationGateProgressKey
  }

private fun completedGateSourceIsCurrent(
  gate: FeatureTaskRuntimeValidationGateProgress?,
  repoRoot: Path,
  gitOperations: WorkflowGitOperations,
): Boolean =
  gate != null && gate.terminalOutcome == null &&
    gateRunSourceIsCurrent(gate, repoRoot, gitOperations) &&
    gate.gateRuns.last().let { it.outcome == ValidationGateRunOutcome.PASSED && it.exitCode == 0 }

private fun gateRunSourceIsCurrent(
  gate: FeatureTaskRuntimeValidationGateProgress,
  repoRoot: Path,
  gitOperations: WorkflowGitOperations,
): Boolean {
  val run = gate.gateRuns.lastOrNull() ?: return false
  val checkpoint = gitOperations.repositoryFingerprint(repoRoot)
  val sourceMatches =
    checkpoint is WorkflowGitOperationResult.Ok && checkpoint.value.isNotBlank() &&
      run.repositoryCheckpoint == checkpoint.value
  val verifiedCommand = !run.legacyUnverified && !run.command.isNullOrBlank() && run.exitCode != null
  return sourceMatches && verifiedCommand
}

internal fun hasAcceptedValidationBoundary(
  progress: GoalQualityPhaseProgress,
  selected: Collection<String>,
): Boolean {
  if (selected.isEmpty() || progress.phaseRecords.keys.any { it !in selected }) return false
  val blocked = progress.phaseRecords.values.singleOrNull { it.status == WorkflowStepStatus.BLOCKED }
  return if (blocked != null && acceptedBlockedBoundary(progress, selected, blocked)) {
    true
  } else {
    progress.phaseRecords.keys == selected.toSet() &&
      selected.all { phase ->
        completedPhaseHasEvent(progress, phase)
      }
  }
}

private fun acceptedBlockedBoundary(
  progress: GoalQualityPhaseProgress,
  selected: Collection<String>,
  record: FeatureTaskRuntimePhaseRecord,
): Boolean {
  val index = selected.indexOf(record.phaseId)
  val shapeMatches =
    index >= 0 && progress.phaseRecords.keys == selected.take(index + 1).toSet() &&
      selected.drop(index + 1).none { it in progress.phaseRecords }
  val statusesMatch =
    progress.phaseRecords.values.all {
      val blockedMatch = it.phaseId == record.phaseId && it.status == WorkflowStepStatus.BLOCKED
      it.status == WorkflowStepStatus.COMPLETED || blockedMatch
    }
  val evidenceMatches =
    selected.take(index).all { completedPhaseHasEvent(progress, it) } &&
      progress.acceptedBlockedValidationEvent(record.phaseId, record.attemptCount) != null
  return shapeMatches && statusesMatch && evidenceMatches
}

private fun completedPhaseHasEvent(
  progress: GoalQualityPhaseProgress,
  phase: String,
): Boolean {
  val record = progress.phaseRecords[phase] ?: return false
  return record.status == WorkflowStepStatus.COMPLETED &&
    terminalEvent(progress, record)?.action == FeatureTaskRuntimePhaseLedgerAction.COMPLETE
}

private fun legacyGateSourceIsCurrent(
  request: GoalQualityValidationSourceRequest,
  strategies: PhaseStrategyLookup,
  gitOperations: WorkflowGitOperations,
): Boolean {
  val progress = request.progress
  val records =
    progress.phaseRecords.values.filter {
      it.status in setOf(WorkflowStepStatus.COMPLETED, WorkflowStepStatus.BLOCKED)
    }
  val record = records.singleOrNull() ?: return false
  val operation =
    strategies.qualityGateOperation(request.plan, record.phaseId) as? PhaseQualityGateOperation.PackGate
      ?: return false
  val action =
    if (record.status == WorkflowStepStatus.BLOCKED) {
      FeatureTaskRuntimePhaseLedgerAction.BLOCKED
    } else {
      FeatureTaskRuntimePhaseLedgerAction.COMPLETE
    }
  if (terminalEvent(progress, record)?.action != action || !gateKeyMatches(request, record, operation.commandFamily)) {
    return false
  }
  return if (record.status == WorkflowStepStatus.BLOCKED) {
    warningGateSourceIsCurrent(
      gateProgress(progress, operation.commandFamily),
      request.repoRoot,
      gitOperations,
    )
  } else {
    progress.acceptedEnvelopes.any { it.phaseId == record.phaseId && it.attempt == record.attemptCount } &&
      completedGateSourceIsCurrent(
        gateProgress(progress, operation.commandFamily),
        request.repoRoot,
        gitOperations,
      )
  }
}

private fun sha256(value: String): String =
  MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

public data class GoalQualityValidationSourceRequest(
  val progress: GoalQualityPhaseProgress,
  val plan: ResolvedPhaseExecutionPlan,
  val outcomeNamespace: String,
  val repoRoot: Path,
  val currentContentFingerprint: String,
  val legacyContentFingerprint: String? = null,
  val validationOccurrence: Int = 0,
  val commandFamily: ValidationGateCommandFamily =
    if (plan.definitionId == goalBuildDefinitionId) {
      ValidationGateCommandFamily.BUILD
    } else {
      ValidationGateCommandFamily.VALIDATION
    },
)
