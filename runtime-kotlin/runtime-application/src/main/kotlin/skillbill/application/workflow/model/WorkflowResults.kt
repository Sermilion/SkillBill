package skillbill.application.workflow.model

import skillbill.workflow.engine.model.WorkflowContinueView
import skillbill.workflow.engine.model.WorkflowInputProjection
import skillbill.workflow.engine.model.WorkflowResumeView
import skillbill.workflow.engine.model.WorkflowSnapshotView
import skillbill.workflow.engine.model.WorkflowSummaryView
import skillbill.workflow.engine.model.WorkflowUpdateAcknowledgementView
import skillbill.workflow.model.goalobservability.GoalObservabilityEvent

sealed interface WorkflowOpenResult {
  data class Ok(
    val workflowId: String,
    val dbPath: String,
    val snapshot: WorkflowSnapshotView,
    val launchProjection: WorkflowInputProjection? = null,
    val goalObservability: GoalObservabilityEvent? = null,
  ) : WorkflowOpenResult

  data class Error(val workflowId: String, val error: String) : WorkflowOpenResult
}

sealed interface WorkflowUpdateResult {
  data class Ok(
    val workflowId: String,
    val dbPath: String,
    val acknowledgement: WorkflowUpdateAcknowledgementView,
    val launchProjection: WorkflowInputProjection? = null,
  ) : WorkflowUpdateResult

  data class Error(val workflowId: String, val error: String, val dbPath: String? = null) : WorkflowUpdateResult
}

sealed interface WorkflowGetResult {
  data class Ok(
    val workflowId: String,
    val dbPath: String,
    val snapshot: WorkflowSnapshotView,
    val goalObservability: GoalObservabilityEvent? = null,
  ) : WorkflowGetResult

  data class Error(val workflowId: String, val error: String, val dbPath: String) : WorkflowGetResult
}

data class WorkflowListResult(
  val dbPath: String,
  val workflowCount: Int,
  val workflows: List<WorkflowSummaryView>,
)

data class GoalContinuationOutcome(
  val issueKey: String,
  val subtaskId: Int,
  val status: String,
  val commitSha: String?,
  val workflowId: String,
  val blockedReason: String?,
  val lastResumableStep: String?,
)

sealed interface WorkflowLatestResult {
  data class Ok(val dbPath: String, val summary: WorkflowSummaryView) : WorkflowLatestResult

  data class Error(val dbPath: String, val error: String) : WorkflowLatestResult
}

sealed interface WorkflowResumeResult {
  data class Ok(
    val workflowId: String,
    val dbPath: String,
    val resume: WorkflowResumeView,
  ) : WorkflowResumeResult

  data class Error(val workflowId: String, val error: String, val dbPath: String) : WorkflowResumeResult
}

sealed interface WorkflowContinueResult {
  val dbPath: String

  /** Regular continue path with a typed view; covers ok / blocked / done / already_running / reopened. */
  data class Standard(
    override val dbPath: String,
    val view: WorkflowContinueView,
  ) : WorkflowContinueResult

  data class UnknownWorkflow(
    override val dbPath: String,
    val workflowId: String,
  ) : WorkflowContinueResult

  data class DecompositionMissingSubtaskWorkflow(
    override val dbPath: String,
    val subtaskId: Int,
    val blockedReason: String,
  ) : WorkflowContinueResult

  data class DecompositionBlockedSubtask(
    override val dbPath: String,
    val workflowId: String,
    val issueKey: String,
    val subtaskId: Int,
    val subtaskSpecPath: String,
    val blockedReason: String,
  ) : WorkflowContinueResult

  data class DecompositionBlockedBranchStart(
    override val dbPath: String,
    val workflowId: String,
    val issueKey: String,
    val blockedReason: String,
  ) : WorkflowContinueResult

  data class DecompositionDone(
    override val dbPath: String,
    val workflowId: String,
    val issueKey: String,
    val decompositionStatus: String,
  ) : WorkflowContinueResult

  data class DecompositionSubtaskOutcome(
    override val dbPath: String,
    val workflowId: String,
    val issueKey: String,
    val subtaskId: Int,
    val subtaskSpecPath: String,
    val outcome: GoalContinuationOutcome,
  ) : WorkflowContinueResult

  data class DecompositionBlockedGit(
    override val dbPath: String,
    val workflowId: String,
    val issueKey: String,
    val blockedReason: String,
  ) : WorkflowContinueResult

  /**
   * Decomposition decoration of [Standard]: a regular continuation that
   * was resolved through the decomposition parent. Carries the typed
   * continue view plus the extra `decomposition_subtask_id` /
   * `decomposition_subtask_spec_path` wire fields.
   */
  data class DecompositionStandard(
    override val dbPath: String,
    val view: WorkflowContinueView,
    val decompositionSubtaskId: Int,
    val decompositionSubtaskSpecPath: String,
    val issueKey: String = "",
    val outcome: GoalContinuationOutcome? = null,
  ) : WorkflowContinueResult

  /** Generic error wrapper (unused except for upstream framework errors). */
  data class Error(override val dbPath: String, val workflowId: String, val error: String) : WorkflowContinueResult
}
