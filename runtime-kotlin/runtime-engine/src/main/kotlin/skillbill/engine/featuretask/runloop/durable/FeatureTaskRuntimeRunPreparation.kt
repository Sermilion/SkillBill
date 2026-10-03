package skillbill.engine.featuretask.runloop.durable

import me.tatarka.inject.annotations.Inject
import skillbill.engine.featuretask.lifecycle.continuation.FeatureTaskRuntimeGoalContinuationRecorder
import skillbill.engine.featuretask.lifecycle.continuation.GoalContinuationStateRecordRequest
import skillbill.engine.featuretask.lifecycle.continuation.continuation
import skillbill.engine.featuretask.lifecycle.continuation.goalContinuationConflict
import skillbill.engine.featuretask.lifecycle.continuation.goalContinuationPolicyBlockedReport
import skillbill.engine.featuretask.lifecycle.continuation.newGoalContinuationConflict
import skillbill.engine.featuretask.lifecycle.continuation.reviewState
import skillbill.engine.featuretask.model.core.ContinuationRead
import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeGoalContinuationContext
import skillbill.engine.featuretask.model.core.FeatureTaskRuntimePreparation
import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeRunRequest
import skillbill.engine.featuretask.phase.record.FeatureTaskRuntimePhaseRecorder
import skillbill.engine.featuretask.slot.PhaseStrategyLookup
import skillbill.error.shellcontent.InvalidWorkflowStateSchemaError
import skillbill.ports.workflow.gitops.model.GoalSubtaskReviewBaseline
import skillbill.review.context.model.execution.CodeReviewExecutionMode
import skillbill.workflow.model.ValidationDepth
import skillbill.workflow.model.WorkflowStepStatus
import skillbill.workflow.taskruntime.model.handoff.task.FeatureTaskRuntimeRunInvariants
import skillbill.workflow.taskruntime.model.persistence.FeatureTaskRuntimeGoalContinuationArtifact
import skillbill.workflow.taskruntime.model.persistence.FeatureTaskRuntimeGoalContinuationFieldAdoption
import skillbill.workflow.taskruntime.model.skeleton.FeatureTaskRuntimeQualityGateSelection

@Inject
class FeatureTaskRuntimeRunPreparation(
  private val recorder: FeatureTaskRuntimePhaseRecorder,
  private val continuationRecorder: FeatureTaskRuntimeGoalContinuationRecorder,
  private val runInvariantsStore: FeatureTaskRuntimeRunInvariantsStore,
  private val strategies: PhaseStrategyLookup,
) {
  fun prepare(request: FeatureTaskRuntimeRunRequest): FeatureTaskRuntimePreparation {
    requireNotNull(request.admittedExecution) { "Durable preparation requires transactional execution admission." }
    val persistedInvariants =
      try {
        runInvariantsStore.resolve(request.workflowId)
      } catch (error: InvalidWorkflowStateSchemaError) {
        val completedPhases =
          recorder.loadPhaseRecords(request.workflowId).orEmpty().values
            .filter { it.status == WorkflowStepStatus.COMPLETED }
            .map { it.phaseId }
        val plan = requireNotNull(request.admittedExecution).plan
        val transitions = plan.traversal
        val resumeRules = strategies.resumeRules(plan)
        val phase =
          transitions.forwardPhaseIds.firstOrNull {
            it !in completedPhases && it !in transitions.loopOnlyPhaseIds
          } ?: transitions.forwardPhaseIds.first { resumeRules(it).resumesPastCompletion }
        return FeatureTaskRuntimePreparation.PreparationBlocked(
          goalContinuationPolicyBlockedReport(
            request,
            request.runInvariants,
            "Planning inputs are unreadable: ${error.message}",
          ).copy(lastIncompletePhase = phase, completedPhaseIds = completedPhases),
        )
      }
    val reportInvariants = persistedInvariants ?: request.runInvariants
    return when (
      val initial = continuationRecorder.readContinuation(request, "Goal-continuation review persistence is malformed")
    ) {
      is ContinuationRead.Failure -> blocked(request, reportInvariants, initial.reason)
      ContinuationRead.None -> prepareWithContinuation(request, persistedInvariants, reportInvariants, null)
      is ContinuationRead.Available -> prepareWithContinuation(request, persistedInvariants, reportInvariants, initial)
      is ContinuationRead.AvailableWithoutReviewState ->
        prepareResumeWithoutReviewState(request, persistedInvariants, reportInvariants, initial)
    }
  }

  private fun prepareResumeWithoutReviewState(
    request: FeatureTaskRuntimeRunRequest,
    persistedInvariants: FeatureTaskRuntimeRunInvariants?,
    reportInvariants: FeatureTaskRuntimeRunInvariants,
    initial: ContinuationRead.AvailableWithoutReviewState,
  ): FeatureTaskRuntimePreparation {
    val suppliedBaseline =
      request.goalContinuation?.reviewBaseline
        ?: return blocked(
          request,
          reportInvariants,
          "Goal-continuation review state is missing; review_base_sha must be captured before " +
            "implementation and cannot be substituted.",
        )
    val selectedMode = selectedReviewMode(request, initial.continuation)
    recorder.ensureWorkflowOpen(request.workflowId, request.sessionId, request.issueKey)
    return freezeRunInvariants(
      request,
      persistedInvariants,
      selectedMode,
      ContinuationRead.Available(initial.continuation, suppliedBaseline),
    )
  }

  private fun prepareWithContinuation(
    request: FeatureTaskRuntimeRunRequest,
    persistedInvariants: FeatureTaskRuntimeRunInvariants?,
    reportInvariants: FeatureTaskRuntimeRunInvariants,
    initial: ContinuationRead.Available?,
  ): FeatureTaskRuntimePreparation {
    val selectedMode = selectedReviewMode(request, initial?.continuation)
    val policyConflict = continuationPolicyConflict(request, persistedInvariants, initial, selectedMode)
    if (policyConflict != null) {
      return blocked(request, reportInvariants, policyConflict)
    }
    recorder.ensureWorkflowOpen(request.workflowId, request.sessionId, request.issueKey)
    if (initial == null && request.goalContinuation != null && !recordContinuation(request, selectedMode)) {
      return blocked(
        request,
        reportInvariants,
        "Goal-continuation child state could not be persisted before freezing run invariants.",
      )
    }
    if (initial != null && !updateContinuationOnResume(request, initial)) {
      return blocked(
        request,
        reportInvariants,
        "Goal-continuation resume write-back could not be persisted before freezing run invariants.",
      )
    }
    return when (
      val resolved =
        continuationRecorder.readContinuation(
          request,
          "Goal-continuation review persistence is malformed after initialization",
        )
    ) {
      is ContinuationRead.Failure -> blocked(request, reportInvariants, resolved.reason)
      ContinuationRead.None -> freezeRunInvariants(request, persistedInvariants, selectedMode, null)
      is ContinuationRead.Available -> freezeRunInvariants(request, persistedInvariants, selectedMode, resolved)
      is ContinuationRead.AvailableWithoutReviewState ->
        blocked(
          request,
          reportInvariants,
          "Goal-continuation review state disappeared immediately after its write-back was persisted.",
        )
    }
  }

  private fun continuationPolicyConflict(
    request: FeatureTaskRuntimeRunRequest,
    persistedInvariants: FeatureTaskRuntimeRunInvariants?,
    initial: ContinuationRead.Available?,
    selectedMode: CodeReviewExecutionMode,
  ): String? =
    when {
      initial != null -> persistedContinuationConflict(request, persistedInvariants, initial, selectedMode)
      request.goalContinuation != null -> newGoalContinuationConflict(request, selectedMode)
      else -> requestedResumeModeConflict(request, persistedInvariants)
    } ?: goalContinuationInvariantConflict(request, persistedInvariants, initial, selectedMode)

  private fun updateContinuationOnResume(
    request: FeatureTaskRuntimeRunRequest,
    initial: ContinuationRead.Available,
  ): Boolean {
    val suppliedDepth = request.goalContinuation?.validationDepth
    val adoptedDepth = suppliedDepth.takeIf { initial.continuation.validationDepth == null }
    val legacySelectionHeal = initial.continuation.qualityGateSelection == null
    val healedSelection = initial.continuation.qualityGateSelection ?: LEGACY_QUALITY_GATE_SELECTION
    val fieldAdoption =
      when {
        adoptedDepth != null ->
          FeatureTaskRuntimeGoalContinuationFieldAdoption(
            field = "validation_depth",
            adoptedValue = adoptedDepth.wireValue,
            reason =
              "durable goal-continuation row predated the validation_depth contract; " +
                "adopted launcher-supplied depth",
          )
        legacySelectionHeal ->
          FeatureTaskRuntimeGoalContinuationFieldAdoption(
            field = "quality_gate_selection",
            adoptedValue = LEGACY_QUALITY_GATE_SELECTION.wireValue,
            reason =
              "durable goal-continuation row predated the quality_gate_selection contract; " +
                "resolved legacy selection to validate",
          )
        else -> null
      }
    return continuationRecorder.recordGoalContinuationState(
      request =
        GoalContinuationStateRecordRequest(
          workflowId = request.workflowId,
          continuation =
            initial.continuation.copy(
              validationDepth = adoptedDepth ?: initial.continuation.validationDepth,
              qualityGateSelection = healedSelection,
              agentAddonSelection =
                request.agentAddonSelection.persisted
                  .takeUnless { it.entries.isEmpty() }
                  ?: initial.continuation.agentAddonSelection,
            ),
          reviewBaseline = initial.baseline,
          fieldAdoption = fieldAdoption,
        ),
    )
  }

  private fun persistedContinuationConflict(
    request: FeatureTaskRuntimeRunRequest,
    persistedInvariants: FeatureTaskRuntimeRunInvariants?,
    initial: ContinuationRead.Available,
    selectedMode: CodeReviewExecutionMode,
  ): String? =
    goalContinuationConflict(request, initial.continuation, initial.baseline)
      ?: persistedInvariants
        ?.takeIf { it.codeReviewMode != selectedMode }
        ?.let { "Goal-continuation review policy does not match the workflow's durable code-review mode." }

  private fun requestedResumeModeConflict(
    request: FeatureTaskRuntimeRunRequest,
    persistedInvariants: FeatureTaskRuntimeRunInvariants?,
  ): String? =
    persistedInvariants
      ?.takeIf { request.requestedCodeReviewMode != null && it.codeReviewMode != request.requestedCodeReviewMode }
      ?.let {
        "Cannot change code-review mode on resume: workflow '${request.workflowId}' is pinned to " +
          "'${it.codeReviewMode.wireValue}', not '${request.requestedCodeReviewMode?.wireValue}'."
      }

  private fun goalContinuationInvariantConflict(
    request: FeatureTaskRuntimeRunRequest,
    persistedInvariants: FeatureTaskRuntimeRunInvariants?,
    initial: ContinuationRead.Available?,
    selectedMode: CodeReviewExecutionMode,
  ): String? =
    persistedInvariants
      ?.takeIf { hasGoalContinuation(initial, request) && it.codeReviewMode != selectedMode }
      ?.let { "Goal-continuation review policy does not match the workflow's durable code-review mode." }

  private fun recordContinuation(
    request: FeatureTaskRuntimeRunRequest,
    selectedMode: CodeReviewExecutionMode,
  ): Boolean {
    val context = requireNotNull(request.goalContinuation)
    return continuationRecorder.recordGoalContinuationState(
      request =
        GoalContinuationStateRecordRequest(
          workflowId = request.workflowId,
          continuation =
            FeatureTaskRuntimeGoalContinuationArtifact(
              issueKey = context.parentIssueKey,
              subtaskId = context.subtaskId,
              suppressPr = context.suppressPr,
              goalBranch = context.goalBranch,
              parentWorkflowId = context.parentWorkflowId,
              codeReviewMode = selectedMode,
              validationDepth = context.validationDepth,
              qualityGateSelection = context.qualityGateSelection,
              subtaskName = context.subtaskName,
              agentAddonSelection =
                context.agentAddonSelection.takeUnless { it.entries.isEmpty() }
                  ?: request.agentAddonSelection.persisted,
            ),
          reviewBaseline = requireNotNull(context.reviewBaseline),
        ),
    )
  }

  private fun freezeRunInvariants(
    request: FeatureTaskRuntimeRunRequest,
    persistedInvariants: FeatureTaskRuntimeRunInvariants?,
    selectedMode: CodeReviewExecutionMode,
    continuation: ContinuationRead.Available?,
  ): FeatureTaskRuntimePreparation {
    val proposed =
      (persistedInvariants ?: request.runInvariants).copy(
        codeReviewMode = continuation?.continuation?.codeReviewMode ?: selectedMode,
        agentAddonSelection = request.agentAddonSelection.persisted,
      )
    val durable = runInvariantsStore.resolve(request.workflowId, proposed) ?: proposed
    val durableContinuation = continuation?.continuation
    if (durableContinuation != null && durable.codeReviewMode != durableContinuation.codeReviewMode) {
      return blocked(
        request,
        durable,
        "Goal-continuation review policy does not match the workflow's durable code-review mode.",
      )
    }
    return FeatureTaskRuntimePreparation.Prepared(
      request.copy(
        runInvariants = durable,
        goalContinuation =
          durableContinuation?.let { continuationArtifact ->
            goalContinuationContext(continuationArtifact, requireNotNull(continuation).baseline, request)
          },
      ),
    )
  }
}

internal val LEGACY_QUALITY_GATE_SELECTION = FeatureTaskRuntimeQualityGateSelection.VALIDATE

private fun hasGoalContinuation(
  initial: ContinuationRead.Available?,
  request: FeatureTaskRuntimeRunRequest,
): Boolean = initial != null || request.goalContinuation != null

internal fun selectedReviewMode(
  request: FeatureTaskRuntimeRunRequest,
  continuation: FeatureTaskRuntimeGoalContinuationArtifact?,
): CodeReviewExecutionMode =
  continuation?.codeReviewMode
    ?: request.goalContinuation?.codeReviewMode
    ?: request.requestedCodeReviewMode
    ?: request.runInvariants.codeReviewMode

private fun FeatureTaskRuntimeGoalContinuationRecorder.readContinuation(
  request: FeatureTaskRuntimeRunRequest,
  persistencePrefix: String,
): ContinuationRead =
  runCatching { continuation(request.workflowId) }.fold(
    onSuccess = { continuation ->
      continuation?.let { readReviewBaseline(request, it) } ?: ContinuationRead.None
    },
    onFailure = { error -> ContinuationRead.Failure("$persistencePrefix: ${error.message.orEmpty()}") },
  )

private fun FeatureTaskRuntimeGoalContinuationRecorder.readReviewBaseline(
  request: FeatureTaskRuntimeRunRequest,
  continuation: FeatureTaskRuntimeGoalContinuationArtifact,
): ContinuationRead =
  runCatching { reviewState(request.workflowId) }.fold(
    onSuccess = { state ->
      state?.let {
        ContinuationRead.Available(
          continuation,
          GoalSubtaskReviewBaseline(it.reviewBaseSha, it.baselineUntrackedPaths),
        )
      } ?: ContinuationRead.AvailableWithoutReviewState(continuation)
    },
    onFailure = { error ->
      ContinuationRead.Failure(
        "Goal-continuation review state or durable raw evidence is malformed: ${error.message.orEmpty()}",
      )
    },
  )

private fun goalContinuationContext(
  continuation: FeatureTaskRuntimeGoalContinuationArtifact,
  baseline: GoalSubtaskReviewBaseline,
  request: FeatureTaskRuntimeRunRequest,
): FeatureTaskRuntimeGoalContinuationContext =
  FeatureTaskRuntimeGoalContinuationContext(
    parentIssueKey = continuation.issueKey,
    subtaskId = continuation.subtaskId,
    goalBranch = continuation.goalBranch,
    suppressPr = continuation.suppressPr,
    parentWorkflowId = continuation.parentWorkflowId,
    lastResumableStep = request.goalContinuation?.lastResumableStep,
    codeReviewMode = continuation.codeReviewMode,
    validationDepth =
      continuation.validationDepth
        ?: request.goalContinuation?.validationDepth
        ?: ValidationDepth.DEFAULT,
    qualityGateSelection = continuation.qualityGateSelection ?: LEGACY_QUALITY_GATE_SELECTION,
    subtaskName = continuation.subtaskName,
    reviewBaseline = baseline,
    agentAddonSelection = continuation.agentAddonSelection,
  )

private fun blocked(
  request: FeatureTaskRuntimeRunRequest,
  invariants: FeatureTaskRuntimeRunInvariants,
  reason: String,
): FeatureTaskRuntimePreparation.PreparationBlocked =
  FeatureTaskRuntimePreparation.PreparationBlocked(
    goalContinuationPolicyBlockedReport(request, invariants, reason),
  )
