package skillbill.engine.goalrunner.qa

import me.tatarka.inject.annotations.Inject
import skillbill.engine.diagnostics.RuntimeDiagnosticsBestEffortWarning
import skillbill.engine.diagnostics.containableFailure
import skillbill.engine.featuretask.lifecycle.assignment.WorkflowAgentAssignmentInspection
import skillbill.engine.featuretask.lifecycle.assignment.WorkflowAgentAssignmentStore
import skillbill.engine.featuretask.phaserun.PhaseRunEntry
import skillbill.engine.featuretask.phaserun.PhaseRunPlanResolver
import skillbill.engine.featuretask.phaserun.PhaseRunResult
import skillbill.engine.featuretask.qa.capability.QaCapabilityResolver
import skillbill.engine.featuretask.qa.report.FeatureTaskQaOutcomeRecorder
import skillbill.engine.goal.GoalRunnerManifestState
import skillbill.engine.goalrunner.execution.support.goalRunnerRepositoryContentIdentity
import skillbill.engine.goalrunner.model.GoalRunnerRunRequest
import skillbill.engine.goalrunner.persist.GoalRunnerWorkflowOutcomeStore
import skillbill.engine.goalrunner.persist.quality.GoalQualityAdmissionRequest
import skillbill.error.core.RuntimeFailureCode
import skillbill.error.core.SkillBillRuntimeException
import skillbill.error.core.catchingNonCancellation
import skillbill.error.core.failureCodeLabel
import skillbill.error.featuretask.isQualityRoutingRefusal
import skillbill.error.featuretask.qa.isQaFailure
import skillbill.error.shellcontent.WorkflowFailureCode
import skillbill.goalrunner.model.history.accumulateGoalFinalRepairWarnings
import skillbill.goalrunner.model.quality.GoalQualityExecution
import skillbill.goalrunner.model.quality.GoalQualityExecutionRecord
import skillbill.goalrunner.model.quality.GoalQualityExecutionStatus
import skillbill.goalrunner.model.quality.GoalQualityPreQaBuildState
import skillbill.goalrunner.model.quality.GoalQualityValidationDisposition
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.ports.featuretask.model.FeatureTaskQaFailureKind
import skillbill.ports.idestatus.model.StandaloneQaOutcome
import skillbill.ports.taskruntime.model.FeatureTaskRuntimeRunInvariantsRead
import skillbill.ports.workflow.gitops.WorkflowGitOperations
import skillbill.ports.workflow.gitops.model.WorkflowGitOperationResult
import skillbill.workflow.taskruntime.model.skeleton.FeatureTaskRuntimeQaSelection
import skillbill.workflow.taskruntime.model.skeleton.ResolvedPhaseExecutionPlan
import skillbill.workflow.taskruntime.model.skeleton.SkeletonDefinition
import skillbill.workflow.taskruntime.phase.task.FeatureTaskRuntimePhaseWorkflowDefinition
import java.util.concurrent.atomic.AtomicReference

@Inject
public class DefaultGoalRunnerQaGate(
  private val phaseRunEntry: PhaseRunEntry,
  private val phasePlans: PhaseRunPlanResolver,
  private val outcomeStore: GoalRunnerWorkflowOutcomeStore,
  private val gitOperations: WorkflowGitOperations,
  private val preparation: GoalQualityPreparation,
  private val phaseRequests: GoalQualityPhaseRequests,
  private val buildBoundary: GoalQualityBuildBoundary,
  private val qaOutcomes: FeatureTaskQaOutcomeRecorder,
  private val capabilities: QaCapabilityResolver,
  private val diagnostics: RuntimeDiagnostics,
  private val workflowAgentAssignments: WorkflowAgentAssignmentStore,
) : GoalRunnerQaGate {
  private val repairOutputs = GoalQualityRepairOutputs(outcomeStore)
  private val currentStage = ThreadLocal<String>()

  override fun run(
    state: GoalRunnerManifestState,
    request: GoalRunnerRunRequest,
  ): GoalQaGateResult {
    enterStage(FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_BUILD)
    return try {
      runContained(state, qualityRoutingRequest(state, request))
    } catch (failure: SkillBillRuntimeException) {
      val boundaryFailure = qualityRoutingRefusal(failure)
        ?: if (failure.code in INTEGRITY_FAILURES) failure else throw failure
      blockedAtCurrentStage(boundaryFailure)
    } finally {
      currentStage.remove()
    }
  }

  private fun qualityRoutingRequest(
    state: GoalRunnerManifestState,
    request: GoalRunnerRunRequest,
  ): GoalRunnerRunRequest {
    val suppliedAssignments = request.workflowAgentAssignments
    val suppliedFingerprint = request.workflowAgentAssignmentsFingerprint
    return when (val inspection = workflowAgentAssignments.inspect(state.parentWorkflowId)) {
      is WorkflowAgentAssignmentInspection.Present -> {
        if ((suppliedAssignments != null || suppliedFingerprint != null) &&
          (suppliedAssignments != inspection.assignments || suppliedFingerprint != inspection.fingerprint)
        ) {
          throw SkillBillRuntimeException(
            WorkflowFailureCode.INVALID_GOAL_QUALITY_EXECUTION,
            "The supplied goal-quality routing reference conflicts with the accepted parent artifact.",
          )
        }
        request.copy(
          workflowAgentAssignments = inspection.assignments,
          workflowAgentAssignmentsFingerprint = inspection.fingerprint,
        )
      }
      WorkflowAgentAssignmentInspection.Legacy -> {
        if (suppliedAssignments != null || suppliedFingerprint != null) {
          throw SkillBillRuntimeException(
            WorkflowFailureCode.INVALID_GOAL_QUALITY_EXECUTION,
            "The goal-quality request supplies routing that the accepted parent does not contain.",
          )
        }
        request
      }
      is WorkflowAgentAssignmentInspection.Problem ->
        throw SkillBillRuntimeException(
          WorkflowFailureCode.INVALID_GOAL_QUALITY_EXECUTION,
          inspection.recoveryMessage,
        )
    }
  }

  private fun runContained(
    state: GoalRunnerManifestState,
    request: GoalRunnerRunRequest,
  ): GoalQaGateResult {
    var invocationId: String? = null
    return catchingNonCancellation { runQa(state, request) { invocationId = it } }.getOrElse { failure ->
      qualityRoutingRefusal(failure)?.let { throw it }
      if ((failure as? SkillBillRuntimeException)?.code in INTEGRITY_FAILURES) throw failure
      containFailure(state, request, containableFailure(failure), invocationId)
    }
  }

  private fun enterStage(stepId: String) = currentStage.set(stepId)

  private fun blockedAtCurrentStage(failure: SkillBillRuntimeException): GoalQaGateResult.Blocked {
    val stepId = currentStage.get() ?: FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_BUILD
    RuntimeDiagnosticsBestEffortWarning.record(
      diagnostics,
      "goal_qa_gate: quality integrity failed at '$stepId'",
      failure,
    )
    return GoalQaGateResult.Blocked(
      reason = failure.message?.takeIf(String::isNotBlank) ?: "Final goal quality state could not be verified.",
      stepId = stepId,
      failureCode = failure.failureCodeLabel(),
    )
  }

  private fun containFailure(
    state: GoalRunnerManifestState,
    request: GoalRunnerRunRequest,
    failure: Exception,
    expectedInvocationId: String?,
  ): GoalQaGateResult {
    val environment = (failure as? SkillBillRuntimeException)?.isQaFailure == true
    val message = failure.message?.takeIf(String::isNotBlank) ?: failure.javaClass.simpleName
    val reason =
      if (environment) {
        "QA could not complete: $message"
      } else {
        "QA could not complete because of a runtime defect: ${failure.javaClass.simpleName}: $message"
      }
    val quality = outcomeStore.goalQualityExecution(state.parentWorkflowId)
    val invocationId = expectedInvocationId
    requireContainedInvocation(quality, invocationId, failure)
    requireResolvedActiveGateOutcome(outcomeStore, quality?.execution, failure)
    if (quality?.execution?.preQaBuild == GoalQualityPreQaBuildState.RUNNING) {
      throw failure
    }
    persistContainedWarning(state, invocationId, reason)
    RuntimeDiagnosticsBestEffortWarning.record(diagnostics, "goal_qa_gate: $reason", failure)
    recordBestEffort(state) {
      qaOutcomes.recordContainedFailure(
        qaOutcomeWorkflowId(state, invocationId),
        if (environment) FeatureTaskQaFailureKind.ENVIRONMENT else FeatureTaskQaFailureKind.DEFECT,
        reason,
      )
    }
    if (quality?.execution?.status == GoalQualityExecutionStatus.QA_PENDING) {
      return settleQualityWithWarning(state, request, quality, reason)
    }
    if (quality?.execution?.status == GoalQualityExecutionStatus.VALIDATION_DECISION_PENDING) {
      return settleQualityWithWarning(state, request, quality, reason)
    }
    val qualityStatus = quality?.execution?.status
    if (quality != null && qualityStatus != null && qualityStatus !in
      setOf(
        GoalQualityExecutionStatus.SETTLED_PASSED,
        GoalQualityExecutionStatus.SETTLED_SKIPPED,
        GoalQualityExecutionStatus.SETTLED_WARNED,
        GoalQualityExecutionStatus.RETIRED,
      )
    ) {
      return settleQualityWithWarning(state, request, quality, reason)
    }
    return GoalQaGateResult.Warned(reason)
  }

  private fun requireContainedInvocation(
    quality: GoalQualityExecutionRecord?,
    invocationId: String?,
    failure: Exception,
  ) {
    if (quality?.execution?.invocationId == invocationId) return
    throw SkillBillRuntimeException(
      WorkflowFailureCode.INVALID_GOAL_QUALITY_EXECUTION,
      "Final QA failure belongs to a different or retired quality invocation.",
      failure,
    )
  }

  private fun persistContainedWarning(
    state: GoalRunnerManifestState,
    invocationId: String?,
    reason: String,
  ) {
    if (invocationId == null) return
    if (outcomeStore.recordGoalFinalRepairWarning(state.parentWorkflowId, invocationId, reason)) return
    throw SkillBillRuntimeException(
      WorkflowFailureCode.INVALID_GOAL_FINALIZATION_HISTORY,
      "Could not persist final QA warning for '${state.parentWorkflowId}'.",
    )
  }

  private fun recordBestEffort(
    state: GoalRunnerManifestState,
    record: () -> Unit,
  ) {
    catchingNonCancellation(record).onFailure { recordingFailure ->
      RuntimeDiagnosticsBestEffortWarning.record(
        diagnostics,
        "goal_qa_gate: could not record the QA warning for '${state.parentWorkflowId}'.",
        containableFailure(recordingFailure),
      )
    }
  }

  private fun runQa(
    state: GoalRunnerManifestState,
    request: GoalRunnerRunRequest,
    admitted: (String) -> Unit,
  ): GoalQaGateResult {
    val (quality, parentInvariants) = preparation.prepare(state, request)
    val execution = requireNotNull(quality.execution)
    admitted(execution.invocationId)
    val paused = buildBoundary.run(state, request, quality)
    if (paused != null) return paused
    enterStage(FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_QA)
    return continueAfterInitialBuild(state, request, quality, parentInvariants, execution.invocationId)
  }

  private fun continueAfterInitialBuild(
    state: GoalRunnerManifestState,
    request: GoalRunnerRunRequest,
    quality: GoalQualityExecutionRecord,
    parentInvariants: FeatureTaskRuntimeRunInvariantsRead,
    invocationId: String,
  ): GoalQaGateResult {
    val currentQuality = requireLatestQuality(outcomeStore, state, quality)
    val currentExecution = requireNotNull(currentQuality.execution)
    val currentTree = goalRunnerRepositoryContentIdentity(gitOperations, request.repoRoot)
    val unusableBuild =
      currentExecution.preQaBuild == GoalQualityPreQaBuildState.WARNED &&
        currentExecution.status == GoalQualityExecutionStatus.QA_PENDING
    if (unusableBuild) return continueAfterUnusableBuild(state, request, currentQuality)
    return when (currentExecution.status) {
      GoalQualityExecutionStatus.SETTLED_PASSED,
      GoalQualityExecutionStatus.SETTLED_SKIPPED,
      GoalQualityExecutionStatus.SETTLED_WARNED,
      -> reuseSettledQuality(state, request, currentQuality, buildBoundary.currentBaseline(currentTree))
      GoalQualityExecutionStatus.VALIDATION_DECISION_PENDING,
      GoalQualityExecutionStatus.REQUIRED_VALIDATION_PENDING,
      GoalQualityExecutionStatus.VALIDATION_RUNNING,
      GoalQualityExecutionStatus.VALIDATION_COMPLETED_PENDING_SETTLEMENT,
      -> resumeValidation(state, request, currentQuality, parentInvariants)
      GoalQualityExecutionStatus.RETIRED ->
        throw SkillBillRuntimeException(
          WorkflowFailureCode.INVALID_GOAL_QUALITY_EXECUTION,
          "Final quality execution '$invocationId' was retired by a goal reset or replan.",
        )
      GoalQualityExecutionStatus.QA_PENDING ->
        runPendingQa(state, request, currentQuality, parentInvariants, currentTree)
    }
  }

  private fun continueAfterUnusableBuild(
    state: GoalRunnerManifestState,
    request: GoalRunnerRunRequest,
    quality: GoalQualityExecutionRecord,
  ): GoalQaGateResult {
    val execution = requireNotNull(quality.execution)
    val warning = execution.warning ?: "The initial build was unusable."
    val current =
      transitionQuality(
        outcomeStore,
        state,
        quality,
        quality.acceptQa(currentContentFingerprint(gitOperations, state, request), null, warning),
      )
    return when (
      val validation =
        validateAfterQa(
          state,
          request,
          requireNotNull(execution.governingInputs).branch,
          current,
        )
    ) {
      is ValidationAfterQaStep.Paused -> validation.pause
      is ValidationAfterQaStep.Settled -> validation.result
      is ValidationAfterQaStep.Advance ->
        settleAfterQaOutcome(state, request, validation.quality, listOfNotNull(warning) + validation.warnings)
    }
  }

  private fun reuseSettledQuality(
    state: GoalRunnerManifestState,
    request: GoalRunnerRunRequest,
    quality: GoalQualityExecutionRecord,
    baseline: String,
  ): GoalQaGateResult {
    enterStage(FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_VALIDATE)
    staleValidationContinuation(state, request, quality)?.let { return it }
    val execution = requireNotNull(quality.execution)
    val settledFingerprint =
      execution.settledContentFingerprint
        ?: throw SkillBillRuntimeException(
          WorkflowFailureCode.INVALID_GOAL_QUALITY_EXECUTION,
          "The settled quality execution '${execution.invocationId}' has no content observation.",
        )
    val retainedContext = requiredFinalRepairContext(outcomeStore, state)
    val retainedWarning = accumulateGoalFinalRepairWarnings(retainedContext.warning, execution.warning)
    val acceptedWriterOwnsCurrentContents =
      historyWriterOwnsCurrentContents(outcomeStore, phasePlans, state, request, execution, baseline)
    val finalValidationIsCurrent = execution.finalValidationContentFingerprint == baseline
    if (!finalValidationIsCurrent && !acceptedWriterOwnsCurrentContents) {
      throw SkillBillRuntimeException(
        WorkflowFailureCode.INVALID_GOAL_QUALITY_EXECUTION,
        "Settled final validation for '${execution.invocationId}' does not prove current repository contents.",
      )
    }
    val changedContentsWarning =
      if (baseline == settledFingerprint || acceptedWriterOwnsCurrentContents) {
        if (acceptedWriterOwnsCurrentContents) {
          null
        } else {
          codeChangedAfterQa(outcomeStore, gitOperations, state, request, execution)
        }
      } else {
        val testedTree =
          execution.qaTestedTreeIdentity?.takeIf(String::isNotBlank)
            ?: outcomeStore.recordedGoalQaOutcome(
              qaOutcomeWorkflowId(state, execution.invocationId),
            )?.takeIf { it.outcome == StandaloneQaOutcome.PASSED }
              ?.testedTreeIdentity?.takeIf(String::isNotBlank)
        val retainedSourceWarning = codeChangedAfterQa(outcomeStore, gitOperations, state, request, execution)
        retainedSourceWarning ?: if (testedTree == null) {
          "Repository contents changed after final quality settled, and QA tested-tree evidence is unavailable."
        } else {
          "Repository contents changed after final quality settled; QA tested tree $testedTree."
        }
      }
    val warning = accumulateGoalFinalRepairWarnings(retainedWarning, changedContentsWarning)
    val durableWarning = persistWarningAggregate(outcomeStore, state, execution.invocationId, warning)
    if (durableWarning != null) {
      recordBestEffort(state) {
        qaOutcomes.recordWarning(
          qaOutcomeWorkflowId(
            state,
            execution.invocationId,
          ),
          durableWarning,
        )
      }
      return GoalQaGateResult.Warned(durableWarning)
    }
    return if (execution.status == GoalQualityExecutionStatus.SETTLED_SKIPPED) {
      GoalQaGateResult.Skipped(
        GoalQaSkipReason.fromWire(execution.skipReason) ?: GoalQaSkipReason.ALREADY_SETTLED_ON_TREE,
      )
    } else {
      GoalQaGateResult.Passed
    }
  }

  private fun runPendingQa(
    state: GoalRunnerManifestState,
    request: GoalRunnerRunRequest,
    quality: GoalQualityExecutionRecord,
    parentInvariants: FeatureTaskRuntimeRunInvariantsRead,
    currentTree: WorkflowGitOperationResult,
  ): GoalQaGateResult {
    val execution = requireNotNull(quality.execution)
    val hasAcceptedQaProgress = quality.execution?.progress?.qa?.let(::hasAcceptedPhaseProgress) == true
    if (!hasAcceptedQaProgress) {
      if (requireNotNull(execution.governingInputs).qaSelection == FeatureTaskRuntimeQaSelection.OFF) {
        return settleSkipped(state, request, quality, GoalQaSkipReason.DISABLED_POLICY)
      }
      earlySkipReason(outcomeStore, gitOperations, capabilities, state, request)?.let {
        return settleSkipped(state, request, quality, it.reason, it.testedTreeIdentity)
      }
    }
    when (val read = parentInvariants) {
      is FeatureTaskRuntimeRunInvariantsRead.Read -> Unit
      is FeatureTaskRuntimeRunInvariantsRead.Rejected ->
        return containedWarning(
          state,
          request,
          "QA did not run: could not read parent spec acceptance criteria: ${read.reason}",
        )
    }
    val branch = requireNotNull(quality.execution?.governingInputs).branch
    val repairPersistenceFailure = AtomicReference<SkillBillRuntimeException?>()
    val goalQaRequest =
      phaseRequests.create(
        GoalQualityPhaseInput(
          SkeletonDefinition.GOAL_QA,
          requireNotNull(quality.execution?.governingInputs),
          requireNotNull(quality.execution),
        ),
        state,
        request,
        repairOutputs.sink(state, SkeletonDefinition.GOAL_QA, repairPersistenceFailure),
      )
    val qaResult =
      phaseRunEntry.runForGoalQuality(
        goalQaRequest,
        state.parentWorkflowId,
        execution.invocationId,
        branch,
        requireNotNull(requireNotNull(quality.execution).executionPlans).qa,
      )
    requireResolvedNestedGateOutcome(
      outcomeStore,
      execution,
      SkeletonDefinition.GOAL_QA,
      requireNotNull(requireNotNull(quality.execution).executionPlans).qa,
    )
    repairPersistenceFailure.get()?.let { throw it }
    repairOutputs.reconcile(state, SkeletonDefinition.GOAL_QA, qaResult)
    return settleAfterQa(state, request, GoalQaRun(goalQaRequest, branch, currentTree, qaResult), quality)
  }

  private fun settleSkipped(
    state: GoalRunnerManifestState,
    request: GoalRunnerRunRequest,
    quality: GoalQualityExecutionRecord,
    reason: GoalQaSkipReason,
    testedTreeIdentity: String? = null,
  ): GoalQaGateResult {
    var current = quality
    if (current.execution?.status == GoalQualityExecutionStatus.QA_PENDING) {
      val observation = currentContentFingerprint(gitOperations, state, request)
      val contextWarning = outcomeStore.goalFinalRepairContext(state.parentWorkflowId)?.warning
      current =
        transitionQuality(
          outcomeStore,
          state,
          current,
          current.acceptQa(
            observation,
            testedTreeIdentity,
            contextWarning,
            skipReason = reason.wireValue,
          ),
        )
    }
    var context = outcomeStore.goalFinalRepairContext(state.parentWorkflowId)
    if (context == null) {
      if (!outcomeStore.recordGoalFinalRepairWarning(
          state.parentWorkflowId,
          requireNotNull(current.execution).invocationId,
          null,
        )
      ) {
        throw SkillBillRuntimeException(
          WorkflowFailureCode.INVALID_GOAL_FINALIZATION_HISTORY,
          "Could not persist empty final repair context for '${state.parentWorkflowId}'.",
        )
      }
      context = outcomeStore.goalFinalRepairContext(state.parentWorkflowId)
    }
    val branch = requireNotNull(current.execution?.governingInputs).branch
    return when (val validation = validateAfterQa(state, request, branch, current)) {
      is ValidationAfterQaStep.Paused -> validation.pause
      is ValidationAfterQaStep.Settled -> validation.result
      is ValidationAfterQaStep.Advance -> {
        current = validation.quality
        settleAfterQaOutcome(
          state,
          request,
          current,
          listOfNotNull(context?.warning, current.execution?.warning) + validation.warnings,
        )
      }
    }
  }

  private fun settleAfterQa(
    state: GoalRunnerManifestState,
    request: GoalRunnerRunRequest,
    run: GoalQaRun,
    quality: GoalQualityExecutionRecord,
  ): GoalQaGateResult {
    goalQaProviderLimitPause(run.result, nestedQaRepairBuild = true)?.let { return it }
    val buildWarning = buildAfterQaFixWarning(run.result)
    val qaWarning =
      buildWarning ?: (run.result as? PhaseRunResult.Blocked)?.let { "QA stopped at '${it.stepId}': ${it.reason}" }
    val treeAfterQa = goalRunnerRepositoryContentIdentity(gitOperations, request.repoRoot)
    if (treeAfterQa !is WorkflowGitOperationResult.Ok) {
      throw SkillBillRuntimeException(
        WorkflowFailureCode.INVALID_GOAL_QUALITY_EXECUTION,
        "Could not observe repository contents after QA for '${state.parentWorkflowId}'.",
      )
    }
    val qaOutcome = outcomeStore.recordedGoalQaOutcome(qaOutcomeWorkflowId(state, quality.execution?.invocationId))
    val testedTreeIdentity = qaOutcome?.testedTreeIdentity?.takeIf(String::isNotBlank)
    val effectiveQaWarning = finalQaWarning(qaWarning, qaOutcome, testedTreeIdentity)
    var current =
      transitionQuality(
        outcomeStore,
        state,
        quality,
        quality.acceptQa(
          treeAfterQa.value,
          testedTreeIdentity,
          effectiveQaWarning,
        ),
      )
    qaWarning?.let { recordContainedEnvironmentFailure(state, it) }
    val validationWarnings =
      when (
        val validationStep = validateAfterQa(state, request, run.branch, current)
      ) {
        is ValidationAfterQaStep.Paused -> return validationStep.pause
        is ValidationAfterQaStep.Settled -> return validationStep.result
        is ValidationAfterQaStep.Advance -> {
          current = validationStep.quality
          validationStep.warnings
        }
      }
    val warnings = listOfNotNull(current.execution?.warning, effectiveQaWarning) + validationWarnings
    return settleAfterQaOutcome(state, request, current, warnings)
  }

  private fun settleAfterQaOutcome(
    state: GoalRunnerManifestState,
    request: GoalRunnerRunRequest,
    quality: GoalQualityExecutionRecord,
    warnings: List<String>,
  ): GoalQaGateResult {
    staleValidationContinuation(state, request, quality)?.let { return it }
    var current = requireLatestQuality(outcomeStore, state, quality)
    requireCurrentValidationEvidence(gitOperations, state, request, requireNotNull(current.execution))
    val retainedWarning =
      persistWarningAggregate(
        outcomeStore,
        state,
        requireNotNull(current.execution).invocationId,
        codeChangedAfterQa(outcomeStore, gitOperations, state, request, current.execution),
        accumulateGoalFinalRepairWarnings(warnings),
      )
    current = requireLatestQuality(outcomeStore, state, current)
    val retainedWarnings = listOfNotNull(retainedWarning)
    val skipped = current.execution?.skipReason != null
    val terminalStatus =
      if (retainedWarnings.isEmpty() && skipped) {
        GoalQualityExecutionStatus.SETTLED_SKIPPED
      } else if (retainedWarnings.isEmpty()) {
        GoalQualityExecutionStatus.SETTLED_PASSED
      } else {
        GoalQualityExecutionStatus.SETTLED_WARNED
      }
    val settled =
      transitionQuality(
        outcomeStore,
        state,
        current,
        current.settle(
          terminalStatus,
          retainedWarning,
          currentContentFingerprint(
            gitOperations,
            state,
            request,
          ),
        ),
      )
    return if (retainedWarnings.isEmpty() && skipped) {
      GoalQaGateResult.Skipped(
        GoalQaSkipReason.fromWire(current.execution?.skipReason) ?: GoalQaSkipReason.ALREADY_SETTLED_ON_TREE,
      )
    } else if (retainedWarnings.isEmpty()) {
      GoalQaGateResult.Passed
    } else {
      GoalQaGateResult.Warned(requireNotNull(settled.execution).warning.orEmpty())
    }
  }

  private fun runValidationPhase(
    state: GoalRunnerManifestState,
    request: GoalRunnerRunRequest,
    quality: GoalQualityExecutionRecord,
    branch: String,
  ): ValidationPhaseAttempt {
    val execution = requireNotNull(quality.execution)
    val persistenceFailure = AtomicReference<SkillBillRuntimeException?>()
    return catchingNonCancellation {
      val result =
        phaseRunEntry.runForGoalQuality(
          phaseRequests.create(
            GoalQualityPhaseInput(
              SkeletonDefinition.VALIDATION,
              requireNotNull(execution.governingInputs),
              execution,
            ),
            state,
            request,
            repairOutputs.sink(state, SkeletonDefinition.VALIDATION, persistenceFailure),
          ),
          state.parentWorkflowId,
          execution.invocationId,
          branch,
          requireNotNull(execution.executionPlans).validation,
        )
      requireResolvedNestedGateOutcome(
        outcomeStore,
        execution,
        SkeletonDefinition.VALIDATION,
        requireNotNull(execution.executionPlans).validation,
      )
      persistenceFailure.get()?.let { throw it }
      repairOutputs.reconcile(state, SkeletonDefinition.VALIDATION, result)
      ValidationPhaseAttempt.Completed(result)
    }.getOrElse { failure ->
      qualityRoutingRefusal(failure)?.let { throw it }
      if ((failure as? SkillBillRuntimeException)?.code in
        setOf(
          WorkflowFailureCode.INVALID_GOAL_FINALIZATION_HISTORY,
          WorkflowFailureCode.INVALID_GOAL_QUALITY_EXECUTION,
        )
      ) {
        throw failure
      }
      ValidationPhaseAttempt.Contained(
        containValidationFailure(state, request, containableFailure(failure), execution.invocationId),
      )
    }
  }

  private fun containValidationFailure(
    state: GoalRunnerManifestState,
    request: GoalRunnerRunRequest,
    failure: Exception,
    invocationId: String,
  ): GoalQaGateResult {
    val reason =
      if ((failure as? SkillBillRuntimeException)?.isQaFailure == true) {
        "QA could not complete: ${failure.message?.takeIf(String::isNotBlank) ?: failure.javaClass.simpleName}"
      } else {
        "QA could not complete because of a runtime defect: ${failure.javaClass.simpleName}: " +
          (failure.message?.takeIf(String::isNotBlank) ?: failure.javaClass.simpleName)
      }
    var quality = outcomeStore.goalQualityExecution(state.parentWorkflowId)
    if (quality?.execution?.invocationId != invocationId) {
      throw SkillBillRuntimeException(
        WorkflowFailureCode.INVALID_GOAL_QUALITY_EXECUTION,
        "Final validation failure belongs to a different or retired quality invocation.",
        failure,
      )
    }
    quality = requireNotNull(quality)
    requireResolvedActiveGateOutcome(outcomeStore, quality.execution, failure)
    if (quality.execution?.preQaBuild == GoalQualityPreQaBuildState.RUNNING) {
      throw failure
    }
    var execution = requireNotNull(quality.execution)
    val warning =
      accumulateGoalFinalRepairWarnings(
        persistWarningAggregate(outcomeStore, state, invocationId, reason),
        codeChangedAfterQa(outcomeStore, gitOperations, state, request, execution),
      ).orEmpty()
    quality = requireLatestQuality(outcomeStore, state, quality)
    execution = requireNotNull(quality.execution)
    quality = acceptValidationWarning(state, request, quality, warning)
    val terminalWarning = persistWarningAggregate(outcomeStore, state, invocationId, warning).orEmpty()
    val settled =
      transitionQuality(
        outcomeStore,
        state,
        quality,
        quality.settle(
          GoalQualityExecutionStatus.SETTLED_WARNED,
          terminalWarning,
          currentContentFingerprint(gitOperations, state, request),
        ),
      )
    RuntimeDiagnosticsBestEffortWarning.record(diagnostics, "goal_qa_gate: $reason", failure)
    recordBestEffort(state) {
      qaOutcomes.recordContainedFailure(
        qaOutcomeWorkflowId(state, invocationId),
        if ((failure as? SkillBillRuntimeException)?.isQaFailure == true) {
          FeatureTaskQaFailureKind.ENVIRONMENT
        } else {
          FeatureTaskQaFailureKind.DEFECT
        },
        reason,
      )
    }
    return GoalQaGateResult.Warned(requireNotNull(settled.execution).warning.orEmpty())
  }

  private fun acceptValidationWarning(
    state: GoalRunnerManifestState,
    request: GoalRunnerRunRequest,
    quality: GoalQualityExecutionRecord,
    warning: String,
  ): GoalQualityExecutionRecord {
    val execution = requireNotNull(quality.execution)
    if (execution.status != GoalQualityExecutionStatus.VALIDATION_RUNNING) {
      if (execution.status != GoalQualityExecutionStatus.VALIDATION_COMPLETED_PENDING_SETTLEMENT) {
        throw SkillBillRuntimeException(
          WorkflowFailureCode.INVALID_GOAL_QUALITY_EXECUTION,
          "Failed validation '${execution.invocationId}' has no active or accepted terminal boundary.",
        )
      }
      requireCurrentValidationEvidence(gitOperations, state, request, execution)
      return quality
    }
    val observedContent = currentContentFingerprint(gitOperations, state, request)
    if (execution.finalValidationContentFingerprint != null &&
      execution.finalValidationContentFingerprint != observedContent
    ) {
      throw SkillBillRuntimeException(
        WorkflowFailureCode.INVALID_GOAL_QUALITY_EXECUTION,
        "Accepted final validation for '${execution.invocationId}' no longer proves current repository contents.",
      )
    }
    val acceptedCompletion =
      execution.progress?.validation?.terminalCompleted == true &&
        execution.finalValidationContentFingerprint != null
    return transitionQuality(
      outcomeStore,
      state,
      quality,
      if (acceptedCompletion) quality.completeValidation() else quality.warnValidation(warning, observedContent),
    )
  }

  private fun validateAfterQa(
    state: GoalRunnerManifestState,
    request: GoalRunnerRunRequest,
    branch: String,
    quality: GoalQualityExecutionRecord,
  ): ValidationAfterQaStep {
    enterStage(FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_VALIDATE)
    var current = transitionQuality(outcomeStore, state, quality, quality.startValidation())
    val attempt = runValidationPhase(state, request, current, branch)
    if (attempt is ValidationPhaseAttempt.Contained) return ValidationAfterQaStep.Settled(attempt.result)
    val validation = (attempt as ValidationPhaseAttempt.Completed).result
    goalQaProviderLimitPause(validation)?.let { return ValidationAfterQaStep.Paused(it) }
    current = requireLatestQuality(outcomeStore, state, current)
    val validationWarning =
      (validation as? PhaseRunResult.Blocked)?.let { blocked ->
        "Validation after QA stopped at '${blocked.stepId}': ${blocked.reason}"
      }
    current =
      if (validationWarning == null) {
        transitionQuality(outcomeStore, state, current, current.completeValidation())
      } else {
        transitionQuality(
          outcomeStore,
          state,
          current,
          current.warnValidation(
            validationWarning,
            current.execution?.finalValidationContentFingerprint ?: currentContentFingerprint(
              gitOperations,
              state,
              request,
            ),
          ),
        )
      }
    validationWarning?.let { recordContainedEnvironmentFailure(state, it) }
    val changedWarning = codeChangedAfterQa(outcomeStore, gitOperations, state, request)
    changedWarning?.let {
      recordBestEffort(state) {
        qaOutcomes.recordWarning(
          qaOutcomeWorkflowId(
            state,
            current.execution?.invocationId,
          ),
          it,
        )
      }
    }
    return ValidationAfterQaStep.Advance(listOfNotNull(validationWarning, changedWarning), current)
  }

  private fun resumeValidation(
    state: GoalRunnerManifestState,
    request: GoalRunnerRunRequest,
    quality: GoalQualityExecutionRecord,
    parentInvariants: FeatureTaskRuntimeRunInvariantsRead,
  ): GoalQaGateResult {
    enterStage(FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_VALIDATE)
    var current = quality
    resumeAcceptedValidation(state, request, current)?.let { return it }
    current = requireLatestQuality(outcomeStore, state, current)
    if (parentInvariants is FeatureTaskRuntimeRunInvariantsRead.Rejected) {
      return settleQualityWithWarning(
        state,
        request,
        current,
        "Final validation did not run because parent acceptance criteria could not be read: ${parentInvariants.reason}",
      )
    }
    return runResumedValidation(state, request, current)
  }

  private fun runResumedValidation(
    state: GoalRunnerManifestState,
    request: GoalRunnerRunRequest,
    quality: GoalQualityExecutionRecord,
  ): GoalQaGateResult {
    var current = quality
    val branch = requireNotNull(current.execution?.governingInputs).branch
    if (current.execution?.status == GoalQualityExecutionStatus.REQUIRED_VALIDATION_PENDING) {
      current = transitionQuality(outcomeStore, state, current, current.startValidation())
    }
    val attempt = runValidationPhase(state, request, current, branch)
    if (attempt is ValidationPhaseAttempt.Contained) return attempt.result
    val validation = (attempt as ValidationPhaseAttempt.Completed).result
    goalQaProviderLimitPause(validation)?.let { return it }
    current = requireLatestQuality(outcomeStore, state, current)
    val validationWarning =
      (validation as? PhaseRunResult.Blocked)?.let { "Validation after QA stopped at '${it.stepId}': ${it.reason}" }
    current =
      if (validationWarning == null) {
        transitionQuality(outcomeStore, state, current, current.completeValidation())
      } else {
        transitionQuality(
          outcomeStore,
          state,
          current,
          current.warnValidation(
            validationWarning,
            current.execution?.finalValidationContentFingerprint ?: currentContentFingerprint(
              gitOperations,
              state,
              request,
            ),
          ),
        )
      }
    return settleResumedValidation(state, request, current, validationWarning)
  }

  private fun resumeAcceptedValidation(
    state: GoalRunnerManifestState,
    request: GoalRunnerRunRequest,
    current: GoalQualityExecutionRecord,
  ): GoalQaGateResult? {
    val execution = requireNotNull(current.execution)
    if (execution.status == GoalQualityExecutionStatus.VALIDATION_COMPLETED_PENDING_SETTLEMENT) {
      staleValidationContinuation(state, request, current)?.let { return it }
      requireCurrentValidationEvidence(gitOperations, state, request, execution)
      val staleWarning = codeChangedAfterQa(outcomeStore, gitOperations, state, request)
      val warning = persistWarningAggregate(outcomeStore, state, execution.invocationId, staleWarning)
      return settleQualityFromRetainedContext(
        state,
        request,
        current,
        when {
          warning != null -> GoalQualityExecutionStatus.SETTLED_WARNED
          execution.skipReason != null -> GoalQualityExecutionStatus.SETTLED_SKIPPED
          else -> GoalQualityExecutionStatus.SETTLED_PASSED
        },
      )
    }
    if (execution.status == GoalQualityExecutionStatus.VALIDATION_DECISION_PENDING) {
      if (execution.validation == GoalQualityValidationDisposition.NOT_REQUIRED) {
        transitionQuality(outcomeStore, state, current, current.requireFinalValidation())
        return null
      }
      throw SkillBillRuntimeException(
        WorkflowFailureCode.INVALID_GOAL_QUALITY_EXECUTION,
        "Quality execution '${execution.invocationId}' has no retained post-QA validation decision.",
      )
    }
    return null
  }

  private fun settleResumedValidation(
    state: GoalRunnerManifestState,
    request: GoalRunnerRunRequest,
    current: GoalQualityExecutionRecord,
    validationWarning: String?,
  ): GoalQaGateResult {
    val staleWarning = codeChangedAfterQa(outcomeStore, gitOperations, state, request)
    val warning =
      persistWarningAggregate(
        outcomeStore,
        state,
        requireNotNull(current.execution).invocationId,
        validationWarning,
        staleWarning,
      )
    return settleQualityFromRetainedContext(
      state,
      request,
      current,
      when {
        warning != null -> GoalQualityExecutionStatus.SETTLED_WARNED
        current.execution?.skipReason != null -> GoalQualityExecutionStatus.SETTLED_SKIPPED
        else -> GoalQualityExecutionStatus.SETTLED_PASSED
      },
    )
  }

  private fun settleQualityWithWarning(
    state: GoalRunnerManifestState,
    request: GoalRunnerRunRequest,
    quality: GoalQualityExecutionRecord,
    warning: String,
  ): GoalQaGateResult {
    val execution = requireNotNull(quality.execution)
    var current = acceptContainedQaWarning(state, request, quality, warning)
    var settlementWarning = persistWarningAggregate(outcomeStore, state, execution.invocationId, warning).orEmpty()
    var latest = requireNotNull(current.execution)
    if (latest.status == GoalQualityExecutionStatus.VALIDATION_DECISION_PENDING &&
      latest.validation in
      setOf(
        GoalQualityValidationDisposition.DECISION_PENDING,
        GoalQualityValidationDisposition.NOT_REQUIRED,
      )
    ) {
      current = transitionQuality(outcomeStore, state, current, current.requireFinalValidation())
      latest = requireNotNull(current.execution)
    }
    current = requireLatestQuality(outcomeStore, state, current)
    latest = requireNotNull(current.execution)
    if (latest.status == GoalQualityExecutionStatus.REQUIRED_VALIDATION_PENDING) {
      return when (
        val validation =
          validateAfterQa(
            state,
            request,
            requireNotNull(latest.governingInputs).branch,
            current,
          )
      ) {
        is ValidationAfterQaStep.Paused -> validation.pause
        is ValidationAfterQaStep.Settled -> validation.result
        is ValidationAfterQaStep.Advance ->
          settleAfterQaOutcome(
            state,
            request,
            validation.quality,
            listOfNotNull(settlementWarning) + validation.warnings,
          )
      }
    }
    if (latest.status == GoalQualityExecutionStatus.VALIDATION_RUNNING) {
      val changedWarning = codeChangedAfterQa(outcomeStore, gitOperations, state, request, latest)
      settlementWarning = persistWarningAggregate(outcomeStore, state, latest.invocationId, changedWarning).orEmpty()
      current = requireLatestQuality(outcomeStore, state, current)
      latest = requireNotNull(current.execution)
      current = acceptValidationWarning(state, request, current, settlementWarning)
    } else if (latest.status == GoalQualityExecutionStatus.VALIDATION_COMPLETED_PENDING_SETTLEMENT) {
      requireCurrentValidationEvidence(gitOperations, state, request, latest)
      settlementWarning =
        persistWarningAggregate(
          outcomeStore,
          state,
          latest.invocationId,
          latest.warning,
          codeChangedAfterQa(outcomeStore, gitOperations, state, request, latest),
        ).orEmpty()
      current = requireLatestQuality(outcomeStore, state, current)
      latest = requireNotNull(current.execution)
    } else if (latest.validation == GoalQualityValidationDisposition.NOT_REQUIRED) {
      throw SkillBillRuntimeException(
        WorkflowFailureCode.INVALID_GOAL_QUALITY_EXECUTION,
        "Quality execution '${latest.invocationId}' requires explicit adoption before final validation.",
      )
    }
    return settleWarnedQuality(state, request, current, settlementWarning)
  }

  private fun settleWarnedQuality(
    state: GoalRunnerManifestState,
    request: GoalRunnerRunRequest,
    current: GoalQualityExecutionRecord,
    settlementWarning: String,
  ): GoalQaGateResult.Warned {
    val settled =
      transitionQuality(
        outcomeStore,
        state,
        current,
        current.settle(
          GoalQualityExecutionStatus.SETTLED_WARNED,
          accumulateGoalFinalRepairWarnings(settlementWarning, current.execution?.warning),
          currentContentFingerprint(
            gitOperations,
            state,
            request,
          ),
        ),
      )
    return GoalQaGateResult.Warned(requireNotNull(settled.execution).warning.orEmpty())
  }

  private fun acceptContainedQaWarning(
    state: GoalRunnerManifestState,
    request: GoalRunnerRunRequest,
    quality: GoalQualityExecutionRecord,
    warning: String,
  ): GoalQualityExecutionRecord {
    val execution = requireNotNull(quality.execution)
    var current = quality
    if (execution.status == GoalQualityExecutionStatus.QA_PENDING) {
      val observation = currentContentFingerprint(gitOperations, state, request)
      val qaOutcome =
        catchingNonCancellation {
          outcomeStore.recordedGoalQaOutcome(qaOutcomeWorkflowId(state, execution.invocationId))
        }.getOrElse { failure ->
          RuntimeDiagnosticsBestEffortWarning.record(
            diagnostics,
            "goal_qa_gate: could not recover the QA outcome",
            failure,
          )
          null
        }
      val testedTreeIdentity =
        qaOutcome?.takeIf { it.outcome != StandaloneQaOutcome.SKIPPED }
          ?.testedTreeIdentity?.takeIf(String::isNotBlank)
      val retainedWarning =
        accumulateGoalFinalRepairWarnings(
          outcomeStore.goalFinalRepairContext(state.parentWorkflowId)?.warning,
          execution.warning,
          warning,
          qaOutcome?.reason,
        )
      current =
        transitionQuality(
          outcomeStore,
          state,
          current,
          current.acceptQa(
            observation,
            testedTreeIdentity,
            retainedWarning,
          ),
        )
    }
    return current
  }

  private fun settleQualityFromRetainedContext(
    state: GoalRunnerManifestState,
    request: GoalRunnerRunRequest,
    quality: GoalQualityExecutionRecord,
    status: GoalQualityExecutionStatus,
  ): GoalQaGateResult {
    staleValidationContinuation(state, request, quality)?.let { return it }
    requireCurrentValidationEvidence(gitOperations, state, request, requireNotNull(quality.execution))
    requiredFinalRepairContext(outcomeStore, state)
    val warning =
      persistWarningAggregate(
        outcomeStore,
        state,
        requireNotNull(quality.execution).invocationId,
        codeChangedAfterQa(outcomeStore, gitOperations, state, request, quality.execution),
      )
    val current = requireLatestQuality(outcomeStore, state, quality)
    requireCurrentValidationEvidence(gitOperations, state, request, requireNotNull(current.execution))
    val terminal = if (warning != null) GoalQualityExecutionStatus.SETTLED_WARNED else status
    val settled =
      transitionQuality(
        outcomeStore,
        state,
        current,
        current.settle(terminal, warning, currentContentFingerprint(gitOperations, state, request)),
      )
    return if (terminal == GoalQualityExecutionStatus.SETTLED_WARNED) {
      GoalQaGateResult.Warned(warning.orEmpty())
    } else if (terminal == GoalQualityExecutionStatus.SETTLED_SKIPPED) {
      GoalQaGateResult.Skipped(
        GoalQaSkipReason.fromWire(settled.execution?.skipReason) ?: GoalQaSkipReason.ALREADY_SETTLED_ON_TREE,
      )
    } else {
      GoalQaGateResult.Passed
    }
  }

  private fun containedWarning(
    state: GoalRunnerManifestState,
    request: GoalRunnerRunRequest,
    warning: String,
  ): GoalQaGateResult {
    val quality = outcomeStore.goalQualityExecution(state.parentWorkflowId)
    val invocationId = quality?.execution?.invocationId ?: return GoalQaGateResult.Warned(warning)
    repairOutputs.warning(state.parentWorkflowId, invocationId, warning)
    recordContainedEnvironmentFailure(state, warning)
    if (quality.execution?.status == GoalQualityExecutionStatus.QA_PENDING) {
      return settleQualityWithWarning(state, request, quality, warning)
    }
    return GoalQaGateResult.Warned(warning)
  }

  private fun recordContainedEnvironmentFailure(
    state: GoalRunnerManifestState,
    reason: String,
  ) = recordBestEffort(state) {
    qaOutcomes.recordContainedFailure(
      qaOutcomeWorkflowId(state, outcomeStore.goalQualityExecution(state.parentWorkflowId)?.execution?.invocationId),
      FeatureTaskQaFailureKind.ENVIRONMENT,
      reason,
    )
  }

  private fun staleValidationContinuation(
    state: GoalRunnerManifestState,
    request: GoalRunnerRunRequest,
    quality: GoalQualityExecutionRecord,
  ): GoalQaGateResult? {
    val execution = quality.execution ?: return null
    val validationPlan = validationReentryPlan(state, request, execution) ?: return null
    val inputs = requireNotNull(execution.governingInputs)
    val plans = requireNotNull(execution.executionPlans)
    val currentFingerprint = currentContentFingerprint(gitOperations, state, request)
    val writerOwnsContents =
      historyWriterOwnsCurrentContents(outcomeStore, phasePlans, state, request, execution, currentFingerprint)
    val sourceProof =
      acceptedValidationSourceProof(
        phasePlans,
        quality,
        validationPlan,
        currentFingerprint,
        writerOwnsContents,
      )
    val currentBoundary =
      execution.status == GoalQualityExecutionStatus.VALIDATION_RUNNING ||
        execution.finalValidationContentFingerprint == currentFingerprint
    if (sourceProof?.sourceIsCurrent == true && currentBoundary || writerOwnsContents) return null
    val reconciled =
      outcomeStore.admitGoalQualityExecution(
        state.parentWorkflowId,
        GoalQualityAdmissionRequest(
          currentFingerprint,
          inputs,
          plans,
          validationSourceProof = sourceProof,
        ),
      )
    val next = requireNotNull(reconciled.execution)
    if (next.validationOccurrence <= execution.validationOccurrence ||
      next.status != GoalQualityExecutionStatus.REQUIRED_VALIDATION_PENDING
    ) {
      throw SkillBillRuntimeException(
        WorkflowFailureCode.INVALID_GOAL_QUALITY_EXECUTION,
        "Stale final validation for '${execution.invocationId}' did not enter a new validation occurrence.",
      )
    }
    return resumeValidation(state, request, reconciled, retainedParentInvariants(inputs))
  }

  private fun validationReentryPlan(
    state: GoalRunnerManifestState,
    request: GoalRunnerRunRequest,
    execution: GoalQualityExecution,
  ): ResolvedPhaseExecutionPlan? {
    if (execution.progress == null || execution.governingInputs == null || execution.executionPlans == null) return null
    val validationProgress = requireNotNull(execution.progress).validation
    val inputs = requireNotNull(execution.governingInputs)
    val plans = requireNotNull(execution.executionPlans)
    val validationPlan =
      phasePlans.validateGoalQualityExecutionPlan(
        plans.validation,
        SkeletonDefinition.VALIDATION.id,
        inputs.effectiveAgentId,
        qualityWorkflowAgentAssignments(inputs, request, state.parentWorkflowId),
      )
    val acceptedRunningBoundary = phasePlans.hasAcceptedValidationBoundary(validationProgress, validationPlan)
    val terminalProgress = validationProgress.terminalCompleted || validationProgress.terminalBlockedStep != null
    if (execution.status == GoalQualityExecutionStatus.VALIDATION_RUNNING &&
      terminalProgress && !acceptedRunningBoundary
    ) {
      throw SkillBillRuntimeException(
        WorkflowFailureCode.INVALID_GOAL_QUALITY_EXECUTION,
        "Retained validation terminal state for '${execution.invocationId}' has no accepted plan boundary.",
      )
    }
    val reentryCandidate =
      execution.status in
        setOf(
          GoalQualityExecutionStatus.VALIDATION_COMPLETED_PENDING_SETTLEMENT,
          GoalQualityExecutionStatus.SETTLED_PASSED,
          GoalQualityExecutionStatus.SETTLED_SKIPPED,
          GoalQualityExecutionStatus.SETTLED_WARNED,
        ) || execution.status == GoalQualityExecutionStatus.VALIDATION_RUNNING &&
        acceptedRunningBoundary
    return validationPlan.takeIf { reentryCandidate }
  }

  private companion object {
    val INTEGRITY_FAILURES: Set<RuntimeFailureCode> =
      setOf(
        WorkflowFailureCode.INVALID_GOAL_FINALIZATION_HISTORY,
        WorkflowFailureCode.INVALID_GOAL_QUALITY_EXECUTION,
      )
  }

  private fun qualityRoutingRefusal(failure: Throwable): SkillBillRuntimeException? {
    val runtimeFailure = failure as? SkillBillRuntimeException ?: return null
    if (!runtimeFailure.isQualityRoutingRefusal) return null
    if (runtimeFailure.code == WorkflowFailureCode.INVALID_GOAL_QUALITY_EXECUTION) return runtimeFailure
    return SkillBillRuntimeException(
      WorkflowFailureCode.INVALID_GOAL_QUALITY_EXECUTION,
      "Selected quality provider could not launch; retained quality progress remains pending.",
      runtimeFailure,
    )
  }
}
