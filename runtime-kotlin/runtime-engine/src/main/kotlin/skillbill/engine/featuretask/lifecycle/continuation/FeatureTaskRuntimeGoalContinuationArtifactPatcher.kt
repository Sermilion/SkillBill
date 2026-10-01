package skillbill.engine.featuretask.lifecycle.continuation

import skillbill.agentaddon.model.AgentAddonSelection
import skillbill.contracts.SharedPayloadKeys
import skillbill.engine.featuretask.runloop.observability.continuation
import skillbill.error.shellcontent.InvalidGoalSubtaskReviewStateSchemaError
import skillbill.ports.workflow.WorkflowStateRepository
import skillbill.ports.workflow.gitops.model.GoalSubtaskReviewBaseline
import skillbill.ports.workflow.model.WorkflowFamily
import skillbill.workflow.engine.WorkflowEngine
import skillbill.workflow.engine.model.DurableWorkflowArtifactFamily
import skillbill.workflow.engine.model.WorkflowArtifactPatch
import skillbill.workflow.engine.model.WorkflowStateSnapshot
import skillbill.workflow.engine.model.WorkflowUpdateInput
import skillbill.workflow.model.goalreview.GoalSubtaskReviewDisposition
import skillbill.workflow.model.goalreview.GoalSubtaskReviewState
import skillbill.workflow.taskruntime.artifact.asWorkflowArtifactEntry
import skillbill.workflow.taskruntime.model.persistence.FeatureTaskRuntimeGoalContinuationArtifact
import skillbill.workflow.taskruntime.model.persistence.GoalSubtaskReviewArtifactDecoder
import skillbill.workflow.taskruntime.model.skeleton.FeatureTaskRuntimeQualityGateSelection

class FeatureTaskRuntimeGoalContinuationArtifactPatcher(
  private val engine: WorkflowEngine,
) {
  internal fun save(
    record: WorkflowStateSnapshot,
    workflowStates: WorkflowStateRepository,
    patch: Map<String, Any?>,
  ) {
    val updated =
      engine.updateRecord(
        WorkflowFamily.TASK_RUNTIME.definition,
        record,
        WorkflowUpdateInput(
          workflowStatus = record.workflowStatus,
          currentStepId = record.currentStepId,
          stepUpdates = null,
          artifactsPatch = WorkflowArtifactPatch.from(patch),
          sessionId = record.sessionId.orEmpty(),
        ),
      )
    workflowStates.save(WorkflowFamily.TASK_RUNTIME, updated)
  }
}

internal fun continuationFromArtifacts(artifacts: Map<String, Any?>): FeatureTaskRuntimeGoalContinuationArtifact? =
  GoalSubtaskReviewArtifactDecoder.decodeContinuationOnly(artifacts)

internal fun reviewStateFromArtifacts(artifacts: Map<String, Any?>): GoalSubtaskReviewState? =
  GoalSubtaskReviewArtifactDecoder.decodeReviewStateOnly(artifacts)

fun GoalSubtaskReviewState.canRecoverReviewBase(): Boolean = disposition == GoalSubtaskReviewDisposition.PENDING

fun GoalSubtaskReviewState.matches(
  baseline: GoalSubtaskReviewBaseline,
  continuation: FeatureTaskRuntimeGoalContinuationArtifact,
): Boolean =
  reviewBaseSha == baseline.reviewBaseSha &&
    baselineUntrackedPaths == baseline.baselineUntrackedPaths.distinct().sorted() &&
    codeReviewMode == continuation.codeReviewMode

internal fun rawReviewResultsFromArtifacts(
  artifacts: Map<String, Any?>,
  state: GoalSubtaskReviewState,
): Map<String, String> {
  val decoded =
    GoalSubtaskReviewArtifactDecoder.decode(artifacts)
      ?: rawReviewResultError(
        DurableWorkflowArtifactFamily.GOAL_SUBTASK_REVIEW_STATE.label(),
        "must be present whenever raw goal-subtask review results are read.",
      )
  if (decoded.state != state) {
    rawReviewResultError(
      DurableWorkflowArtifactFamily.GOAL_SUBTASK_REVIEW_STATE.label(),
      "changed while reading its durable raw review results.",
    )
  }
  return decoded.rawResults
}

fun rawReviewResultError(
  fieldPath: String,
  reason: String,
): Nothing =
  throw InvalidGoalSubtaskReviewStateSchemaError(
    sourceLabel = DurableWorkflowArtifactFamily.GOAL_SUBTASK_REVIEW_STATE.label(),
    fieldPath = fieldPath,
    reason = reason,
  )

internal fun continuationPatch(
  continuation: FeatureTaskRuntimeGoalContinuationArtifact?,
  existing: FeatureTaskRuntimeGoalContinuationArtifact?,
): Map<String, Any?> =
  when {
    continuation == null || continuation == existing -> emptyMap()
    existing == null ->
      mapOf(
        DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_GOAL_CONTINUATION.entry(
          continuation.asWorkflowArtifactEntry(),
        ),
        "install_sync_result" to
          mapOf(
            SharedPayloadKeys.STATUS to "deferred",
            "reason" to
              "goal-continuation defers installer, uninstall, and install-sync flows until the parent goal exits; " +
              "deferred install sync must not block subtask completion",
          ),
      )
    else ->
      mapOf(
        DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_GOAL_CONTINUATION.entry(
          continuation.asWorkflowArtifactEntry(),
        ),
      )
  }

fun FeatureTaskRuntimeGoalContinuationArtifact?.compatibleWith(
  supplied: FeatureTaskRuntimeGoalContinuationArtifact?,
): Boolean {
  if (this == null || supplied == null) return true
  val healed =
    copy(
      agentAddonSelection = AgentAddonSelection(),
      validationDepth = validationDepth ?: supplied.validationDepth,
      qualityGateSelection = qualityGateSelection ?: FeatureTaskRuntimeQualityGateSelection.VALIDATE,
      parallelReviewAgent = null,
    )
  return healed ==
    supplied.copy(
      agentAddonSelection = AgentAddonSelection(),
      parallelReviewAgent = null,
    )
}

internal enum class GoalReviewBaseField(val wireValue: String) {
  REVIEW_BASE("review_base_sha"),
  REMEDIATION_BASE("remediation_base_sha"),
}

internal fun reviewStatePatch(
  request: GoalContinuationStateRecordRequest,
  artifacts: Map<String, Any?>,
  existingContinuation: FeatureTaskRuntimeGoalContinuationArtifact?,
): Map<String, Any?> {
  val continuation = request.continuation ?: return emptyMap()
  val state = reviewStateFromArtifacts(artifacts)
  val baseline = request.reviewBaseline
  if (state != null) {
    check(baseline == null || state.matches(baseline, continuation)) {
      "Goal-subtask review baseline and execution mode are immutable on resume."
    }
    return emptyMap()
  }
  check(existingContinuation == null) {
    "Goal-subtask review state is missing for an existing child workflow; " +
      "refusing to capture a replacement baseline."
  }
  requireNotNull(baseline) {
    "Goal-subtask review baseline is required when opening a child workflow; " +
      "refusing to create an unpinned review scope."
  }
  if (DurableWorkflowArtifactFamily.GOAL_SUBTASK_REVIEW_RESULTS.contains(artifacts)) {
    rawReviewResultError(
      DurableWorkflowArtifactFamily.GOAL_SUBTASK_REVIEW_RESULTS.label(),
      "must be absent before the goal-subtask review state exists.",
    )
  }
  return mapOf(
    DurableWorkflowArtifactFamily.GOAL_SUBTASK_REVIEW_STATE.entry(
      GoalSubtaskReviewState.initial(
        reviewBaseSha = baseline.reviewBaseSha,
        baselineUntrackedPaths = baseline.baselineUntrackedPaths,
        codeReviewMode = continuation.codeReviewMode,
      ).toPersistenceWire(),
    ),
  )
}
