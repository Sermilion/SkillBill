package skillbill.engine.goalrunner.qa

import me.tatarka.inject.annotations.Inject
import skillbill.agentaddon.model.HydratedAgentAddonSelection
import skillbill.agentaddon.model.HydratedAgentAddonSelectionEntry
import skillbill.application.decomposition.resolvedParentSpecPath
import skillbill.application.idestatus.AgentActivityStampWriter
import skillbill.engine.diagnostics.RuntimeDiagnosticsBestEffortWarning
import skillbill.engine.diagnostics.containableFailure
import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeModelAssignment
import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeRunEvent
import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeRunEventSink
import skillbill.engine.featuretask.phaserun.PhaseRunEntry
import skillbill.engine.featuretask.phaserun.PhaseRunPlanResolver
import skillbill.engine.featuretask.phaserun.PhaseRunRequest
import skillbill.engine.featuretask.phaserun.PhaseRunResult
import skillbill.engine.featuretask.qa.capability.QaCapabilityResolution
import skillbill.engine.featuretask.qa.capability.QaCapabilityResolver
import skillbill.engine.featuretask.qa.report.FeatureTaskQaOutcomeRecorder
import skillbill.engine.goalrunner.execution.support.goalRunnerRepositoryContentIdentity
import skillbill.engine.goalrunner.model.GoalProgressEventDraft
import skillbill.engine.goalrunner.model.GoalRunnerManifestState
import skillbill.engine.goalrunner.model.GoalRunnerProgressEventRecordRequest
import skillbill.engine.goalrunner.model.GoalRunnerRunRequest
import skillbill.engine.goalrunner.model.GoalSubtaskQaSummary
import skillbill.engine.goalrunner.monitoring.GOAL_FINALIZATION_OPERATION_KIND
import skillbill.engine.goalrunner.persist.GoalRunnerWorkflowOutcomeStore
import skillbill.error.core.SkillBillRuntimeException
import skillbill.error.core.catchingNonCancellation
import skillbill.error.featuretask.qa.isQaFailure
import skillbill.error.shellcontent.WorkflowFailureCode
import skillbill.goalrunner.model.history.GoalFinalRepairContext
import skillbill.goalrunner.model.quality.GoalQualityExecution
import skillbill.goalrunner.model.quality.GoalQualityExecutionInputs
import skillbill.goalrunner.model.quality.GoalQualityExecutionPlans
import skillbill.goalrunner.model.quality.GoalQualityExecutionRecord
import skillbill.goalrunner.model.quality.GoalQualityExecutionStatus
import skillbill.goalrunner.model.quality.GoalQualityHydratedAddonInput
import skillbill.goalrunner.model.quality.GoalQualityReadiness
import skillbill.goalrunner.model.quality.GoalQualityValidationDisposition
import skillbill.ports.agentrun.model.AgentRunOutputStream
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.ports.featuretask.model.FeatureTaskQaFailureKind
import skillbill.ports.idestatus.model.StandaloneQaOutcome
import skillbill.ports.projectstore.ProjectStorePort
import skillbill.ports.taskruntime.FeatureTaskRuntimeRunInvariantsSource
import skillbill.ports.taskruntime.model.FeatureTaskRuntimeRunInvariantsRead
import skillbill.ports.workflow.gitops.WorkflowGitOperations
import skillbill.ports.workflow.gitops.model.WorkflowGitOperationResult
import skillbill.workflow.decomposition.branchForFinalPullRequest
import skillbill.workflow.model.goalobservability.GoalProgressEventKind
import skillbill.workflow.model.goalobservability.GoalProgressOutcome
import skillbill.workflow.taskruntime.model.skeleton.FeatureTaskRuntimeQaSelection
import skillbill.workflow.taskruntime.model.skeleton.SkeletonDefinition
import java.nio.file.Path
import java.time.Clock
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration.Companion.milliseconds

@Inject
public class DefaultGoalRunnerQaGate(
  private val phaseRunEntry: PhaseRunEntry,
  private val phasePlans: PhaseRunPlanResolver,
  private val outcomeStore: GoalRunnerWorkflowOutcomeStore,
  private val gitOperations: WorkflowGitOperations,
  private val invariantsSource: FeatureTaskRuntimeRunInvariantsSource,
  private val clock: Clock,
  private val activityStampWriter: AgentActivityStampWriter,
  private val qaOutcomes: FeatureTaskQaOutcomeRecorder,
  private val capabilities: QaCapabilityResolver,
  private val projectStore: ProjectStorePort,
  private val diagnostics: RuntimeDiagnostics,
) : GoalRunnerQaGate {
  private val repairOutputs = GoalQualityRepairOutputs(outcomeStore)

  override fun run(
    state: GoalRunnerManifestState,
    request: GoalRunnerRunRequest,
  ): GoalQaGateResult {
    var invocationId: String? = null
    return catchingNonCancellation { runQa(state, request) { invocationId = it } }.getOrElse { failure ->
      if ((failure as? SkillBillRuntimeException)?.code in
        setOf(
          WorkflowFailureCode.INVALID_GOAL_FINALIZATION_HISTORY,
          WorkflowFailureCode.INVALID_GOAL_QUALITY_EXECUTION,
        )
      ) {
        throw failure
      }
      containFailure(state, request, containableFailure(failure), invocationId)
    }
  }

  private fun containFailure(
    state: GoalRunnerManifestState,
    request: GoalRunnerRunRequest,
    failure: Exception,
    expectedInvocationId: String?,
  ): GoalQaGateResult.Warned {
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
    if (quality?.execution?.invocationId != invocationId) {
      throw SkillBillRuntimeException(
        WorkflowFailureCode.INVALID_GOAL_QUALITY_EXECUTION,
        "Final QA failure belongs to a different or retired quality invocation.",
        failure,
      )
    }
    requireResolvedActiveGateOutcome(state.parentWorkflowId, quality?.execution)
    if (invocationId != null &&
      !outcomeStore.recordGoalFinalRepairWarning(
        state.parentWorkflowId,
        invocationId,
        reason,
      )
    ) {
      throw SkillBillRuntimeException(
        WorkflowFailureCode.INVALID_GOAL_FINALIZATION_HISTORY,
        "Could not persist final QA warning for '${state.parentWorkflowId}'.",
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
    RuntimeDiagnosticsBestEffortWarning.record(diagnostics, "goal_qa_gate: $reason", failure)
    recordBestEffort(state) {
      qaOutcomes.recordContainedFailure(
        qaOutcomeWorkflowId(state, invocationId),
        if (environment) FeatureTaskQaFailureKind.ENVIRONMENT else FeatureTaskQaFailureKind.DEFECT,
        reason,
      )
    }
    return GoalQaGateResult.Warned(reason)
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
    val (quality, parentInvariants, currentTree) = prepareQuality(state, request)
    val baseline = (currentTree as WorkflowGitOperationResult.Ok).value
    val execution = requireNotNull(quality.execution)
    admitted(execution.invocationId)
    when (execution.status) {
      GoalQualityExecutionStatus.SETTLED_PASSED,
      GoalQualityExecutionStatus.SETTLED_SKIPPED,
      GoalQualityExecutionStatus.SETTLED_WARNED,
      -> return reuseSettledQuality(state, execution, baseline)
      GoalQualityExecutionStatus.VALIDATION_DECISION_PENDING,
      GoalQualityExecutionStatus.REQUIRED_VALIDATION_PENDING,
      GoalQualityExecutionStatus.VALIDATION_RUNNING,
      GoalQualityExecutionStatus.VALIDATION_COMPLETED_PENDING_SETTLEMENT,
      -> return resumeValidation(state, request, quality, parentInvariants)
      GoalQualityExecutionStatus.RETIRED ->
        throw SkillBillRuntimeException(
          WorkflowFailureCode.INVALID_GOAL_QUALITY_EXECUTION,
          "Final quality execution '${execution.invocationId}' was retired by a goal reset or replan.",
        )
      GoalQualityExecutionStatus.QA_PENDING -> Unit
    }
    return runPendingQa(state, request, quality, parentInvariants, currentTree)
  }

  private fun prepareQuality(
    state: GoalRunnerManifestState,
    request: GoalRunnerRunRequest,
  ): Triple<GoalQualityExecutionRecord, FeatureTaskRuntimeRunInvariantsRead, WorkflowGitOperationResult> {
    val currentTree = goalRunnerRepositoryContentIdentity(gitOperations, request.repoRoot)
    val baseline =
      (currentTree as? WorkflowGitOperationResult.Ok)?.value?.takeIf(String::isNotBlank)
        ?: throw SkillBillRuntimeException(
          WorkflowFailureCode.INVALID_GOAL_QUALITY_EXECUTION,
          "Could not capture a repository baseline for final quality execution '${state.parentWorkflowId}'.",
        )
    val retainedRecord = outcomeStore.goalQualityExecution(state.parentWorkflowId)
    if (retainedRecord?.readiness == GoalQualityReadiness.HISTORICAL_AMBIGUITY) {
      throw SkillBillRuntimeException(
        WorkflowFailureCode.INVALID_GOAL_QUALITY_EXECUTION,
        "This parent has ambiguous historical quality state. Provide authoritative original evidence or explicitly " +
          "choose a fresh quality execution with new cycles: " +
          "skill-bill goal fresh-quality ${request.issueKey} --reason \"<why>\".",
      )
    }
    val retainedExecution =
      retainedRecord?.execution
        ?.takeUnless { it.status == GoalQualityExecutionStatus.RETIRED }
    val parentInvariants =
      retainedExecution?.governingInputs?.let(::retainedParentInvariants)
        ?: if (earlySkipReason(state, request) != null) {
          FeatureTaskRuntimeRunInvariantsRead.Rejected("QA is already settled or unsupported for this goal.")
        } else {
          readParentInvariants(state, request)
        }
    val inputs = retainedExecution?.governingInputs ?: governingInputs(state, request, parentInvariants)
    val retainedPlans =
      retainedExecution
        ?.takeUnless { it.status == GoalQualityExecutionStatus.RETIRED }
        ?.executionPlans
    retainedPlans?.let {
      val acceptedAgentId = requireNotNull(retainedExecution.governingInputs).effectiveAgentId
      phasePlans.validateGoalQualityExecutionPlan(it.qa, SkeletonDefinition.GOAL_QA.id, acceptedAgentId)
      phasePlans.validateGoalQualityExecutionPlan(it.validation, SkeletonDefinition.VALIDATION.id, acceptedAgentId)
    }
    val executionPlans =
      retainedPlans ?: GoalQualityExecutionPlans(
        phasePlans.resolveGoalQualityExecutionPlan(
          phaseRequest(GoalQualityPhaseInput(SkeletonDefinition.GOAL_QA, inputs), state, request) { _, _, _, _ -> },
          state.parentWorkflowId,
        ),
        phasePlans.resolveGoalQualityExecutionPlan(
          phaseRequest(GoalQualityPhaseInput(SkeletonDefinition.VALIDATION, inputs), state, request) { _, _, _, _ -> },
          state.parentWorkflowId,
        ),
      )
    val quality =
      outcomeStore.admitGoalQualityExecution(
        state.parentWorkflowId,
        baseline,
        inputs,
        executionPlans,
      )
    return Triple(quality, parentInvariants, currentTree)
  }

  private fun reuseSettledQuality(
    state: GoalRunnerManifestState,
    execution: GoalQualityExecution,
    baseline: String,
  ): GoalQaGateResult {
    val settledFingerprint =
      execution.settledContentFingerprint
        ?: throw SkillBillRuntimeException(
          WorkflowFailureCode.INVALID_GOAL_QUALITY_EXECUTION,
          "The settled quality execution '${execution.invocationId}' has no content observation.",
        )
    val retainedContext = requiredFinalRepairContext(state)
    val retainedWarning = retainedContext.warning ?: execution.warning
    val acceptedWriterOwnsCurrentContents =
      outcomeStore.goalHistoryCompletion(state.parentWorkflowId)?.postWriterContentFingerprint == baseline
    val changedContentsWarning =
      if (baseline == settledFingerprint || acceptedWriterOwnsCurrentContents) {
        null
      } else {
        val testedTree =
          outcomeStore.recordedGoalQaOutcome(
            qaOutcomeWorkflowId(
              state,
              execution.invocationId,
            ),
          )?.testedTreeIdentity
        if (testedTree == null) {
          "Repository contents changed after final quality settled, and QA has no recorded tested tree."
        } else {
          "Repository contents changed after final quality settled; QA tested tree $testedTree."
        }
      }
    val warning =
      listOfNotNull(
        retainedWarning,
        changedContentsWarning,
      ).distinct().joinToString(" ").takeIf(String::isNotBlank)
    if (warning != retainedContext.warning &&
      !outcomeStore.recordGoalFinalRepairWarning(state.parentWorkflowId, execution.invocationId, warning)
    ) {
      throw SkillBillRuntimeException(
        WorkflowFailureCode.INVALID_GOAL_FINALIZATION_HISTORY,
        "Could not persist the post-quality content warning for '${state.parentWorkflowId}'.",
      )
    }
    if (warning != null) {
      recordBestEffort(state) {
        qaOutcomes.recordWarning(
          qaOutcomeWorkflowId(
            state,
            execution.invocationId,
          ),
          warning,
        )
      }
      return GoalQaGateResult.Warned(warning)
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
      earlySkipReason(state, request)?.let { return settleSkipped(state, request, quality, it) }
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
      phaseRequest(
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
      state.parentWorkflowId,
      execution.invocationId,
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
  ): GoalQaGateResult {
    var current = quality
    if (current.execution?.status == GoalQualityExecutionStatus.QA_PENDING) {
      val observation = currentContentFingerprint(state, request)
      val contextWarning = outcomeStore.goalFinalRepairContext(state.parentWorkflowId)?.warning
      current =
        transitionQuality(
          state,
          current,
          current.acceptQa(
            observation,
            null,
            contextWarning,
            validationRequired = false,
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
    val warning = context?.warning
    val terminalStatus =
      if (warning == null) {
        GoalQualityExecutionStatus.SETTLED_SKIPPED
      } else {
        GoalQualityExecutionStatus.SETTLED_WARNED
      }
    val settled =
      transitionQuality(
        state,
        current,
        current.settle(
          terminalStatus,
          warning,
          currentContentFingerprint(
            state,
            request,
          ),
        ),
      )
    return if (settled.execution?.status == GoalQualityExecutionStatus.SETTLED_WARNED) {
      GoalQaGateResult.Warned(warning.orEmpty())
    } else {
      GoalQaGateResult.Skipped(reason)
    }
  }

  private fun settleAfterQa(
    state: GoalRunnerManifestState,
    request: GoalRunnerRunRequest,
    run: GoalQaRun,
    quality: GoalQualityExecutionRecord,
  ): GoalQaGateResult {
    val buildWarning = buildAfterQaFixWarning(run.result)
    val qaWarning =
      buildWarning ?: (run.result as? PhaseRunResult.Blocked)?.let { "QA stopped at '${it.stepId}': ${it.reason}" }
    val treeAfterQa = goalRunnerRepositoryContentIdentity(gitOperations, request.repoRoot)
    if (treeAfterQa !is WorkflowGitOperationResult.Ok) {
      throw SkillBillRuntimeException(
        WorkflowFailureCode.INVALID_GOAL_QUALITY_EXECUTION,
        "Could not compare the retained quality baseline after QA for '${state.parentWorkflowId}'.",
      )
    }
    val qaOutcome = outcomeStore.recordedGoalQaOutcome(qaOutcomeWorkflowId(state, quality.execution?.invocationId))
    val testedTreeIdentity = qaOutcome?.testedTreeIdentity?.takeIf(String::isNotBlank)
    val effectiveQaWarning = finalQaWarning(qaWarning, qaOutcome, testedTreeIdentity)
    val validationRequired =
      !sameTree(
        WorkflowGitOperationResult.Ok(requireNotNull(quality.execution).baselineContentFingerprint),
        treeAfterQa,
      )
    var current =
      transitionQuality(
        state,
        quality,
        quality.acceptQa(
          treeAfterQa.value,
          testedTreeIdentity,
          effectiveQaWarning,
          validationRequired,
        ),
      )
    qaWarning?.let { recordContainedEnvironmentFailure(state, it) }
    val validationWarnings =
      if (!validationRequired) {
        emptyList()
      } else {
        validateAfterQa(state, request, run, buildWarning, current).also { current = it.second }.first
      }
    val warnings = listOfNotNull(current.execution?.warning, effectiveQaWarning) + validationWarnings
    repairOutputs.warning(
      state.parentWorkflowId,
      requireNotNull(current.execution).invocationId,
      warnings.joinToString(" ").takeIf(String::isNotBlank),
    )
    val terminalStatus =
      if (warnings.isEmpty()) {
        GoalQualityExecutionStatus.SETTLED_PASSED
      } else {
        GoalQualityExecutionStatus.SETTLED_WARNED
      }
    current =
      transitionQuality(
        state,
        current,
        current.settle(
          terminalStatus,
          warnings.joinToString(" ").takeIf(String::isNotBlank),
          currentContentFingerprint(
            state,
            request,
          ),
        ),
      )
    return if (warnings.isEmpty()) GoalQaGateResult.Passed else GoalQaGateResult.Warned(warnings.joinToString(" "))
  }

  private fun validateAfterQa(
    state: GoalRunnerManifestState,
    request: GoalRunnerRunRequest,
    run: GoalQaRun,
    buildWarning: String?,
    quality: GoalQualityExecutionRecord,
  ): Pair<List<String>, GoalQualityExecutionRecord> {
    var current = transitionQuality(state, quality, quality.startValidation())
    val repairPersistenceFailure = AtomicReference<SkillBillRuntimeException?>()
    val validation =
      phaseRunEntry.runForGoalQuality(
        phaseRequest(
          GoalQualityPhaseInput(
            SkeletonDefinition.VALIDATION,
            requireNotNull(quality.execution?.governingInputs),
            requireNotNull(current.execution),
          ),
          state,
          request,
          repairOutputs.sink(state, SkeletonDefinition.VALIDATION, repairPersistenceFailure),
        ),
        state.parentWorkflowId,
        requireNotNull(quality.execution).invocationId,
        run.branch,
        requireNotNull(requireNotNull(quality.execution).executionPlans).validation,
      )
    requireResolvedNestedGateOutcome(
      state.parentWorkflowId,
      requireNotNull(current.execution).invocationId,
      SkeletonDefinition.VALIDATION,
      requireNotNull(requireNotNull(current.execution).executionPlans).validation,
    )
    repairPersistenceFailure.get()?.let { throw it }
    repairOutputs.reconcile(state, SkeletonDefinition.VALIDATION, validation)
    val validationWarning =
      (validation as? PhaseRunResult.Blocked)?.let { blocked ->
        "Validation after QA stopped at '${blocked.stepId}': ${blocked.reason}"
      }
    current =
      if (validationWarning == null) {
        transitionQuality(state, current, current.completeValidation())
      } else {
        transitionQuality(state, current, current.warnValidation(validationWarning))
      }
    validationWarning?.let { recordContainedEnvironmentFailure(state, it) }
    val changedWarning = codeChangedAfterQa(state, request).takeIf { buildWarning == null }
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
    return listOfNotNull(validationWarning, changedWarning) to current
  }

  private fun resumeValidation(
    state: GoalRunnerManifestState,
    request: GoalRunnerRunRequest,
    quality: GoalQualityExecutionRecord,
    parentInvariants: FeatureTaskRuntimeRunInvariantsRead,
  ): GoalQaGateResult {
    var current = quality
    val execution = requireNotNull(current.execution)
    resumeAcceptedValidation(state, request, current)?.let { return it }
    if (parentInvariants is FeatureTaskRuntimeRunInvariantsRead.Rejected) {
      return settleQualityWithWarning(
        state,
        request,
        current,
        "Final validation did not run because parent acceptance criteria could not be read: ${parentInvariants.reason}",
      )
    }
    val branch = requireNotNull(current.execution?.governingInputs).branch
    if (current.execution?.status == GoalQualityExecutionStatus.REQUIRED_VALIDATION_PENDING) {
      current = transitionQuality(state, current, current.startValidation())
    }
    val persistenceFailure = AtomicReference<SkillBillRuntimeException?>()
    val validationRequest =
      phaseRequest(
        GoalQualityPhaseInput(
          SkeletonDefinition.VALIDATION,
          requireNotNull(current.execution?.governingInputs),
          requireNotNull(current.execution),
        ),
        state,
        request,
        repairOutputs.sink(state, SkeletonDefinition.VALIDATION, persistenceFailure),
      )
    val validation =
      phaseRunEntry.runForGoalQuality(
        validationRequest,
        state.parentWorkflowId,
        requireNotNull(current.execution).invocationId,
        branch,
        requireNotNull(requireNotNull(current.execution).executionPlans).validation,
      )
    requireResolvedNestedGateOutcome(
      state.parentWorkflowId,
      execution.invocationId,
      SkeletonDefinition.VALIDATION,
      requireNotNull(requireNotNull(current.execution).executionPlans).validation,
    )
    persistenceFailure.get()?.let { throw it }
    repairOutputs.reconcile(state, SkeletonDefinition.VALIDATION, validation)
    val validationWarning =
      (validation as? PhaseRunResult.Blocked)?.let { "Validation after QA stopped at '${it.stepId}': ${it.reason}" }
    current =
      if (validationWarning == null) {
        transitionQuality(state, current, current.completeValidation())
      } else {
        transitionQuality(state, current, current.warnValidation(validationWarning))
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
      val staleWarning = codeChangedAfterQa(state, request)
      val warning =
        listOfNotNull(
          outcomeStore.goalFinalRepairContext(state.parentWorkflowId)?.warning,
          execution.warning,
          staleWarning,
        )
          .distinct()
          .joinToString(" ")
          .takeIf(String::isNotBlank)
      if (!outcomeStore.recordGoalFinalRepairWarning(state.parentWorkflowId, execution.invocationId, warning)) {
        throw SkillBillRuntimeException(
          WorkflowFailureCode.INVALID_GOAL_FINALIZATION_HISTORY,
          "Could not persist resumed quality settlement for '${state.parentWorkflowId}'.",
        )
      }
      return settleQualityFromRetainedContext(
        state,
        request,
        current,
        if (warning == null) GoalQualityExecutionStatus.SETTLED_PASSED else GoalQualityExecutionStatus.SETTLED_WARNED,
      )
    }
    if (execution.status == GoalQualityExecutionStatus.VALIDATION_DECISION_PENDING) {
      if (execution.validation == GoalQualityValidationDisposition.NOT_REQUIRED) {
        val warning = execution.warning ?: outcomeStore.goalFinalRepairContext(state.parentWorkflowId)?.warning
        if (warning != null) return settleQualityWithWarning(state, request, current, warning)
        return settleQualityFromRetainedContext(
          state,
          request,
          current,
          if (execution.skipReason != null) {
            GoalQualityExecutionStatus.SETTLED_SKIPPED
          } else {
            GoalQualityExecutionStatus.SETTLED_PASSED
          },
        )
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
    val staleWarning = codeChangedAfterQa(state, request)
    val warnings =
      listOfNotNull(
        outcomeStore.goalFinalRepairContext(state.parentWorkflowId)?.warning,
        validationWarning,
        current.execution?.warning,
        staleWarning,
      ).distinct()
    val warning = warnings.joinToString(" ").takeIf(String::isNotBlank)
    if (!outcomeStore.recordGoalFinalRepairWarning(
        state.parentWorkflowId,
        requireNotNull(current.execution).invocationId,
        warning,
      )
    ) {
      throw SkillBillRuntimeException(
        WorkflowFailureCode.INVALID_GOAL_FINALIZATION_HISTORY,
        "Could not persist resumed final validation settlement for '${state.parentWorkflowId}'.",
      )
    }
    return settleQualityFromRetainedContext(
      state,
      request,
      current,
      if (warning == null) GoalQualityExecutionStatus.SETTLED_PASSED else GoalQualityExecutionStatus.SETTLED_WARNED,
    )
  }

  private fun settleQualityWithWarning(
    state: GoalRunnerManifestState,
    request: GoalRunnerRunRequest,
    quality: GoalQualityExecutionRecord,
    warning: String,
  ): GoalQaGateResult.Warned {
    val execution = requireNotNull(quality.execution)
    var current = quality
    if (execution.status == GoalQualityExecutionStatus.QA_PENDING) {
      val observation = currentContentFingerprint(state, request)
      current =
        transitionQuality(
          state,
          current,
          current.acceptQa(
            observation,
            null,
            warning,
            validationRequired = false,
          ),
        )
    } else if (execution.status == GoalQualityExecutionStatus.VALIDATION_DECISION_PENDING &&
      execution.validation == GoalQualityValidationDisposition.DECISION_PENDING
    ) {
      current = transitionQuality(state, current, current.warnValidation(warning))
    }
    val contextWarning =
      listOfNotNull(outcomeStore.goalFinalRepairContext(state.parentWorkflowId)?.warning, warning)
        .distinct()
        .joinToString(" ")
    if (!outcomeStore.recordGoalFinalRepairWarning(state.parentWorkflowId, execution.invocationId, contextWarning)) {
      throw SkillBillRuntimeException(
        WorkflowFailureCode.INVALID_GOAL_FINALIZATION_HISTORY,
        "Could not persist validation warning.",
      )
    }
    if (current.execution?.validation in
      setOf(
        GoalQualityValidationDisposition.REQUIRED_PENDING,
        GoalQualityValidationDisposition.RUNNING,
      )
    ) {
      current = transitionQuality(state, current, current.warnValidation(contextWarning))
    }
    val settled =
      transitionQuality(
        state,
        current,
        current.settle(
          GoalQualityExecutionStatus.SETTLED_WARNED,
          contextWarning,
          currentContentFingerprint(
            state,
            request,
          ),
        ),
      )
    return GoalQaGateResult.Warned(requireNotNull(settled.execution).warning.orEmpty())
  }

  private fun settleQualityFromRetainedContext(
    state: GoalRunnerManifestState,
    request: GoalRunnerRunRequest,
    quality: GoalQualityExecutionRecord,
    status: GoalQualityExecutionStatus,
  ): GoalQaGateResult {
    val context = requiredFinalRepairContext(state)
    val warning =
      listOfNotNull(
        context.warning,
        quality.execution?.warning,
      ).distinct().joinToString(" ").takeIf(String::isNotBlank)
    val terminal = if (warning != null) GoalQualityExecutionStatus.SETTLED_WARNED else status
    val settled =
      transitionQuality(
        state,
        quality,
        quality.settle(terminal, warning, currentContentFingerprint(state, request)),
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

  private fun requiredFinalRepairContext(state: GoalRunnerManifestState): GoalFinalRepairContext =
    outcomeStore.goalFinalRepairContext(state.parentWorkflowId)
      ?: throw SkillBillRuntimeException(
        WorkflowFailureCode.INVALID_GOAL_FINALIZATION_HISTORY,
        "Final repair context is missing for '${state.parentWorkflowId}'.",
      )

  private fun transitionQuality(
    state: GoalRunnerManifestState,
    current: GoalQualityExecutionRecord,
    next: GoalQualityExecutionRecord,
  ): GoalQualityExecutionRecord {
    val execution = requireNotNull(current.execution)
    if (!outcomeStore.transitionGoalQualityExecution(
        state.parentWorkflowId,
        execution.invocationId,
        current,
        next,
      )
    ) {
      throw SkillBillRuntimeException(
        WorkflowFailureCode.INVALID_GOAL_QUALITY_EXECUTION,
        "Goal quality execution '${execution.invocationId}' changed before its transition was accepted.",
      )
    }
    return next
  }

  private fun phaseRequest(
    phase: GoalQualityPhaseInput,
    state: GoalRunnerManifestState,
    request: GoalRunnerRunRequest,
    repairOutput: (String, String, Int, String) -> Unit,
  ): PhaseRunRequest {
    val (definition, inputs, qualityExecution) = phase
    val acceptedPlan =
      qualityExecution?.executionPlans?.let { plans ->
        if (definition == SkeletonDefinition.GOAL_QA) plans.qa else plans.validation
      }
    val progress =
      if (qualityExecution == null || acceptedPlan == null) {
        null
      } else {
        outcomeStore.goalQualityPhaseProgress(
          state.parentWorkflowId,
          qualityExecution.invocationId,
          definition.id,
          acceptedPlan,
        )
      }
    var latestProgress = progress
    return PhaseRunRequest(
      definitionId = definition.id,
      repoRoot = Path.of(inputs.repoRoot),
      invokedAgentId = inputs.effectiveAgentId,
      intake = inputs.issueKey,
      timeout = inputs.timeoutMillis?.milliseconds,
      agentAddonSelection =
        HydratedAgentAddonSelection(
          inputs.addons.map { HydratedAgentAddonSelectionEntry(it.persisted, it.description, it.content) },
        ),
      modelAssignment = FeatureTaskRuntimeModelAssignment(inputs.modelDirectives, inputs.modelMatrix),
      qaSelection = inputs.qaSelection,
      acceptanceCriteria = inputs.acceptanceCriteria,
      eventSink = FeatureTaskRuntimeRunEventSink { event -> record(state, request, event) },
      activityOutput = { stepName, activity -> recordActivity(state, request, stepName, activity) },
      activityStampSink = activityStampWriter.sink(state.parentWorkflowId, parentWorkflowId = null),
      recordedGoalQaOutcome =
        qualityExecution?.let { outcomeStore.recordedGoalQaOutcome(qaOutcomeWorkflowId(state, it.invocationId)) },
      goalQualityInvocationId = qualityExecution?.invocationId,
      acceptedGoalRepairOutput = repairOutput,
      goalQualityPhaseProgress = progress,
      persistGoalQualityPhaseProgress =
        if (qualityExecution == null || acceptedPlan == null) {
          null
        } else {
          { next ->
            val accepted =
              outcomeStore.persistGoalQualityPhaseProgress(
                state.parentWorkflowId,
                qualityExecution.invocationId,
                definition.id,
                acceptedPlan,
                next,
              )
            if (accepted) latestProgress = next
            accepted
          }
        },
      latestGoalQualityPhaseProgress =
        if (qualityExecution == null || acceptedPlan == null) {
          null
        } else {
          { requireNotNull(latestProgress) }
        },
    )
  }

  private fun containedWarning(
    state: GoalRunnerManifestState,
    request: GoalRunnerRunRequest,
    warning: String,
  ): GoalQaGateResult.Warned {
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

  private fun requireResolvedNestedGateOutcome(
    parentWorkflowId: String,
    invocationId: String,
    definition: SkeletonDefinition,
    acceptedPlan: String,
  ) {
    val progress =
      outcomeStore.goalQualityPhaseProgress(
        parentWorkflowId,
        invocationId,
        definition.id,
        acceptedPlan,
      )
    val pending =
      listOfNotNull(
        progress.buildGateProgress?.let { it.pendingExternalOperation to it.pendingExternalCheckpoint },
        progress.validationGateProgress?.let { it.pendingExternalOperation to it.pendingExternalCheckpoint },
      ).firstOrNull { it.first != null }
    if (pending != null) {
      throw SkillBillRuntimeException(
        WorkflowFailureCode.INVALID_GOAL_QUALITY_EXECUTION,
        "The '${pending.first}' outcome is unknown after interruption at checkpoint '${pending.second}'. " +
          "Inspect authoritative command or agent evidence, or explicitly retire " +
          "this quality execution and accept new cycles.",
      )
    }
  }

  private fun requireResolvedActiveGateOutcome(
    parentWorkflowId: String,
    execution: GoalQualityExecution?,
  ) {
    if (execution == null) return
    val definition =
      when (execution.status) {
        GoalQualityExecutionStatus.QA_PENDING -> SkeletonDefinition.GOAL_QA
        GoalQualityExecutionStatus.VALIDATION_RUNNING -> SkeletonDefinition.VALIDATION
        else -> return
      }
    val plans = requireNotNull(execution.executionPlans)
    requireResolvedNestedGateOutcome(
      parentWorkflowId,
      execution.invocationId,
      definition,
      if (definition == SkeletonDefinition.GOAL_QA) plans.qa else plans.validation,
    )
  }

  private fun currentContentFingerprint(
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

  private fun governingInputs(
    state: GoalRunnerManifestState,
    request: GoalRunnerRunRequest,
    parentInvariants: FeatureTaskRuntimeRunInvariantsRead,
  ): GoalQualityExecutionInputs {
    val read = parentInvariants as? FeatureTaskRuntimeRunInvariantsRead.Read
    val rejected = parentInvariants as? FeatureTaskRuntimeRunInvariantsRead.Rejected
    return GoalQualityExecutionInputs(
      issueKey = request.issueKey,
      intake = request.intake,
      repoRoot = request.repoRoot.toAbsolutePath().normalize().toString(),
      invokedAgentId = request.invokedAgentId,
      configuredAgentOverrideId = request.configuredAgentOverrideId,
      timeoutMillis = request.timeout?.inWholeMilliseconds?.coerceAtLeast(1),
      qaSelection = request.qaSelection ?: FeatureTaskRuntimeQaSelection.AUTO,
      parentSpecPath = state.manifest.parentSpecPath,
      acceptanceCriteria = read?.invariants?.acceptanceCriteria,
      acceptanceCriteriaReadFailure = rejected?.reason,
      branch = state.manifest.featureBranch.orEmpty().ifBlank { state.manifest.branchForFinalPullRequest() },
      modelDirectives = request.modelAssignment.perPhaseDirectives,
      modelMatrix = request.modelAssignment.matrix,
      addons =
        request.agentAddonSelection.entries.map { entry ->
          GoalQualityHydratedAddonInput(entry.persisted, entry.description, entry.content)
        },
    )
  }

  private fun earlySkipReason(
    state: GoalRunnerManifestState,
    request: GoalRunnerRunRequest,
  ): GoalQaSkipReason? {
    if (childrenAlreadyRanQa(state)) return GoalQaSkipReason.SUBTASKS_ALREADY_RAN_QA
    val prior = outcomeStore.recordedGoalQaOutcome(state.parentWorkflowId)
    if (prior?.outcome == StandaloneQaOutcome.PASSED) {
      val current = gitOperations.repositoryTreeIdentity(request.repoRoot)
      if (current is WorkflowGitOperationResult.Ok && current.value.trim() == prior.testedTreeIdentity) {
        return GoalQaSkipReason.ALREADY_SETTLED_ON_TREE
      }
    }
    return if (capabilities.resolve(request.repoRoot) is QaCapabilityResolution.Absent) {
      GoalQaSkipReason.UNSUPPORTED_TARGET
    } else {
      null
    }
  }

  private fun readParentInvariants(
    state: GoalRunnerManifestState,
    request: GoalRunnerRunRequest,
  ): FeatureTaskRuntimeRunInvariantsRead =
    invariantsSource.read(
      resolvedParentSpecPath(
        request.repoRoot,
        Path.of(state.manifest.parentSpecPath),
        projectStore.resolve(request.repoRoot),
      ),
    )

  private fun codeChangedAfterQa(
    state: GoalRunnerManifestState,
    request: GoalRunnerRunRequest,
  ): String? {
    val settled =
      outcomeStore.recordedGoalQaOutcome(
        qaOutcomeWorkflowId(state, outcomeStore.goalQualityExecution(state.parentWorkflowId)?.execution?.invocationId),
      ) ?: return null
    val tested = settled.testedTreeIdentity?.takeIf { settled.outcome != StandaloneQaOutcome.SKIPPED } ?: return null
    val current = gitOperations.repositoryTreeIdentity(request.repoRoot)
    if (current is WorkflowGitOperationResult.Ok && current.value.trim() == tested) return null
    return "validate changed code after QA settled; QA does not re-run, so the published change differs from the " +
      "tree QA tested ($tested)."
  }

  private fun childrenAlreadyRanQa(state: GoalRunnerManifestState): Boolean {
    val workflows =
      outcomeStore.resolvedGoalQualityChildIds(
        state.parentWorkflowId,
        state.manifest.subtasks.map { it.id to it.workflowId },
      )
    return workflows.isNotEmpty() &&
      workflows.all { workflowId ->
        outcomeStore.hasQaComposedPlan(workflowId) &&
          outcomeStore.recordedQaOutcome(workflowId) is GoalSubtaskQaSummary.Recorded
      }
  }

  private fun record(
    state: GoalRunnerManifestState,
    request: GoalRunnerRunRequest,
    event: FeatureTaskRuntimeRunEvent,
  ) {
    val phaseId: String
    val kind: GoalProgressEventKind
    val outcome: GoalProgressOutcome
    when (event) {
      is FeatureTaskRuntimeRunEvent.PhaseStarted -> {
        phaseId = event.phaseId
        kind = GoalProgressEventKind.PHASE_STARTED
        outcome = GoalProgressOutcome.NONE
      }
      is FeatureTaskRuntimeRunEvent.PhaseCompleted -> {
        phaseId = event.phaseId
        kind = GoalProgressEventKind.PHASE_COMPLETED
        outcome = GoalProgressOutcome.SUCCEEDED
      }
      is FeatureTaskRuntimeRunEvent.PhaseBlocked -> {
        phaseId = event.phaseId
        kind = GoalProgressEventKind.PHASE_COMPLETED
        outcome = GoalProgressOutcome.FAILED
      }
      is FeatureTaskRuntimeRunEvent.PhasePaused -> {
        phaseId = event.phaseId
        kind = GoalProgressEventKind.PHASE_COMPLETED
        outcome = GoalProgressOutcome.CANCELLED
      }
      else -> return
    }
    val started = event as? FeatureTaskRuntimeRunEvent.PhaseStarted
    recordProgress(
      state,
      request,
      progressDraft(state, request, phaseId, kind, outcome).copy(
        launchedModel = started?.model,
        effort = started?.effort,
      ),
    )
    request.outputSink.write(AgentRunOutputStream.STDERR, "$phaseId: ${kind.wireValue}\n")
  }

  private fun recordActivity(
    state: GoalRunnerManifestState,
    request: GoalRunnerRunRequest,
    stepName: String,
    activity: String,
  ) {
    val boundedActivity = activity.filterNot(Char::isISOControl).take(MAX_ACTIVITY_LENGTH)
    recordProgress(
      state,
      request,
      progressDraft(state, request, stepName, GoalProgressEventKind.OPERATION_HEARTBEAT, GoalProgressOutcome.NONE).copy(
        operationName = boundedActivity.take(MAX_OPERATION_NAME_LENGTH),
        expectedLong = true,
      ),
    )
    request.outputSink.write(AgentRunOutputStream.STDERR, "$boundedActivity\n")
  }

  private fun recordProgress(
    state: GoalRunnerManifestState,
    request: GoalRunnerRunRequest,
    draft: GoalProgressEventDraft,
  ) {
    check(
      outcomeStore.recordProgressEvent(
        GoalRunnerProgressEventRecordRequest(
          workflowId = state.parentWorkflowId,
          issueKey = request.issueKey,
          draft = draft,
        ),
      ),
    ) { "Goal finalization could not record progress for '${state.parentWorkflowId}'." }
  }

  private fun progressDraft(
    state: GoalRunnerManifestState,
    request: GoalRunnerRunRequest,
    phaseId: String,
    kind: GoalProgressEventKind,
    outcome: GoalProgressOutcome,
  ): GoalProgressEventDraft =
    GoalProgressEventDraft(
      eventKind = kind,
      workflowId = state.parentWorkflowId,
      workflowPhase = phaseId,
      processAlive = true,
      timestamp = clock.instant(),
      stepId = phaseId,
      operationKind = GOAL_FINALIZATION_OPERATION_KIND,
      outcome = outcome,
      agentId = request.configuredAgentOverrideId ?: request.invokedAgentId,
    )
}

private data class GoalQaRun(
  val request: PhaseRunRequest,
  val branch: String,
  val startTree: WorkflowGitOperationResult,
  val result: PhaseRunResult,
)

private const val MAX_ACTIVITY_LENGTH = 500
private const val MAX_OPERATION_NAME_LENGTH = 160

private data class GoalQualityPhaseInput(
  val definition: SkeletonDefinition,
  val inputs: GoalQualityExecutionInputs,
  val execution: GoalQualityExecution? = null,
)
