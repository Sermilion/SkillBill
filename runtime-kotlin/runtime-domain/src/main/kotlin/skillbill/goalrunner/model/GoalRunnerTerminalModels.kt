package skillbill.goalrunner.model

import skillbill.contracts.workflow.identity.blockincident.GoalRunnerAcceptedBlockReferenceKeys
import skillbill.error.shellcontent.invalidWorkflowStateSchemaError
import skillbill.workflow.decomposition.model.DecompositionSubtask
import skillbill.workflow.taskruntime.model.audit.FeatureTaskRuntimeNoChangePause
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimeWorkflowArtifactMap

enum class GoalRunnerTerminalStatus(val wireValue: String) {
  COMPLETE("complete"),
  FAILED("failed"),
  BLOCKED("blocked"),
  TIMEOUT("timeout"),
  NO_TERMINAL_STORE_OUTCOME("no_terminal_store_outcome"),

  RECONCILABLE("reconcilable"),

  PAUSED("paused"),

  COMPLETED_NO_CHANGE("completed_no_change"),

  ;

  companion object {
    fun fromWire(value: String): GoalRunnerTerminalStatus? =
      when (value) {
        "complete", "completed" -> COMPLETE
        "timeout", "timed_out" -> TIMEOUT
        else -> entries.firstOrNull { it.wireValue == value }
      }
  }
}

enum class GoalRunnerStopReason {
  FAILED,
  BLOCKED,
  POLICY_BLOCKED,
  INTERRUPTED,
  TIMEOUT,
  NO_TERMINAL_STORE_OUTCOME,
  PULL_REQUEST_FAILED,
  DEPENDENCIES_BLOCKED,

  RECONCILED_RESUMABLE,

  AWAITING_OPERATOR_DECISION,
  AWAITING_NO_CHANGE_DECISION,

  PAUSED,
  ;

  companion object {
    val RESUMABLE_STOP_REASONS =
      setOf(RECONCILED_RESUMABLE, AWAITING_OPERATOR_DECISION, AWAITING_NO_CHANGE_DECISION, PAUSED)
  }
}

data class GoalRunnerStoredOutcome(
  val status: GoalRunnerTerminalStatus,
  val workflowId: String,
  val commitSha: String? = null,
  val blockedReason: String? = null,
  val lastResumableStep: String? = null,
  val suppressPr: Boolean,
  val noChangePause: FeatureTaskRuntimeNoChangePause? = null,
  val acceptedBlockReference: GoalRunnerAcceptedBlockReference? = null,
)

data class GoalRunnerAcceptedBlockReference(
  val sourceWorkflowId: String,
  val blockedStepId: String,
  val attempt: Int?,
  val executionGeneration: Long?,
  val aliasKind: String,
  val aliasValue: String,
  val incidentToken: String,
  val childIncidentToken: String?,
  val sourceAliasKind: String? = null,
  val sourceAliasValue: String? = null,
  val blockCause: String? = null,
) {
  init {
    require(sourceWorkflowId.isNotBlank() && blockedStepId.isNotBlank() && (attempt == null || attempt > 0))
    require(blockCause == null || blockCause.isNotBlank())
    require(aliasKind.isNotBlank() && aliasValue.isNotBlank())
    require(incidentToken.isNotBlank())
    require((sourceAliasKind == null) == (sourceAliasValue == null))
  }

  fun toArtifactMap(): FeatureTaskRuntimeWorkflowArtifactMap =
    FeatureTaskRuntimeWorkflowArtifactMap.from(
      linkedMapOf(
        GoalRunnerAcceptedBlockReferenceKeys.WORKFLOW_ID to sourceWorkflowId,
        GoalRunnerAcceptedBlockReferenceKeys.STEP_ID to blockedStepId,
        GoalRunnerAcceptedBlockReferenceKeys.ATTEMPT to attempt,
        GoalRunnerAcceptedBlockReferenceKeys.EXECUTION_GENERATION to executionGeneration,
        GoalRunnerAcceptedBlockReferenceKeys.ALIAS_KIND to aliasKind,
        GoalRunnerAcceptedBlockReferenceKeys.ALIAS_VALUE to aliasValue,
        GoalRunnerAcceptedBlockReferenceKeys.INCIDENT_TOKEN to incidentToken,
        GoalRunnerAcceptedBlockReferenceKeys.CHILD_INCIDENT_TOKEN to childIncidentToken,
        GoalRunnerAcceptedBlockReferenceKeys.SOURCE_ALIAS_KIND to sourceAliasKind,
        GoalRunnerAcceptedBlockReferenceKeys.SOURCE_ALIAS_VALUE to sourceAliasValue,
      ).apply { blockCause?.let { put(GoalRunnerAcceptedBlockReferenceKeys.BLOCK_CAUSE, it) } },
    )

  fun matchesIdentity(
    workflowId: String,
    stepId: String,
    acceptedAttempt: Int?,
    generation: Long?,
  ): Boolean =
    sourceWorkflowId == workflowId && blockedStepId == stepId && attempt == acceptedAttempt &&
      executionGeneration == generation

  companion object {
    fun fromArtifactMap(raw: Any?): GoalRunnerAcceptedBlockReference? {
      if (raw == null) return null
      val value = raw as? Map<*, *> ?: invalid()
      val attempt = value.optionalNumber(GoalRunnerAcceptedBlockReferenceKeys.ATTEMPT)?.toInt()
      val generation = value.optionalNumber(GoalRunnerAcceptedBlockReferenceKeys.EXECUTION_GENERATION)?.toLong()
      if (attempt?.let { it <= 0 } == true || generation?.let { it < 0 } == true) invalid()
      return GoalRunnerAcceptedBlockReference(
        sourceWorkflowId = value.requiredString(GoalRunnerAcceptedBlockReferenceKeys.WORKFLOW_ID),
        blockedStepId = value.requiredString(GoalRunnerAcceptedBlockReferenceKeys.STEP_ID),
        attempt = attempt,
        executionGeneration = generation,
        aliasKind = value.requiredString(GoalRunnerAcceptedBlockReferenceKeys.ALIAS_KIND),
        aliasValue = value.requiredString(GoalRunnerAcceptedBlockReferenceKeys.ALIAS_VALUE),
        incidentToken = value.requiredString(GoalRunnerAcceptedBlockReferenceKeys.INCIDENT_TOKEN),
        childIncidentToken = value.optionalString(GoalRunnerAcceptedBlockReferenceKeys.CHILD_INCIDENT_TOKEN),
        sourceAliasKind = value.optionalString(GoalRunnerAcceptedBlockReferenceKeys.SOURCE_ALIAS_KIND),
        sourceAliasValue = value.optionalString(GoalRunnerAcceptedBlockReferenceKeys.SOURCE_ALIAS_VALUE),
        blockCause = value.optionalString(GoalRunnerAcceptedBlockReferenceKeys.BLOCK_CAUSE),
      )
    }

    private fun Map<*, *>.requiredString(key: String): String =
      (this[key] as? String)?.takeIf(String::isNotBlank) ?: invalid()

    private fun Map<*, *>.optionalString(key: String): String? =
      when (val item = this[key]) {
        null -> null
        is String -> item.takeIf(String::isNotBlank) ?: invalid()
        else -> invalid()
      }

    private fun Map<*, *>.optionalNumber(key: String): Number? = this[key]?.let { it as? Number ?: invalid() }

    private fun invalid(): Nothing =
      throw invalidWorkflowStateSchemaError("Goal continuation accepted block reference is invalid.")
  }
}

sealed interface GoalRunnerReconciledOutcome {
  data class Complete(
    val workflowId: String,
    val commitSha: String,
    val lastResumableStep: String,
  ) : GoalRunnerReconciledOutcome

  data class Stop(
    val reason: GoalRunnerStopReason,
    val blockedReason: String,
    val workflowId: String?,
    val commitSha: String?,
    val lastResumableStep: String,
    val liveness: GoalRunnerLivenessSnapshot? = null,
    val noChangeReason: String? = null,
    val incidentToken: String? = null,
    val acceptedBlockReference: GoalRunnerAcceptedBlockReference? = null,
  ) : GoalRunnerReconciledOutcome
}

data class GoalRunnerSubtaskDecision(
  val subtask: DecompositionSubtask,
  val action: GoalRunnerSubtaskAction,
)

enum class GoalRunnerSubtaskAction {
  START,
  RESUME,
}

sealed interface GoalRunnerSelection {
  data class Run(val decision: GoalRunnerSubtaskDecision) : GoalRunnerSelection

  data class Blocked(val subtask: DecompositionSubtask, val reason: String) : GoalRunnerSelection

  data object Done : GoalRunnerSelection
}

data class GoalRunnerStopReport(
  val issueKey: String,
  val subtaskId: Int,
  val reason: GoalRunnerStopReason,
  val blockedReason: String,
  val workflowId: String?,
  val lastResumableStep: String,
  val noChangeReason: String? = null,
  val incidentToken: String? = null,
  val acceptedBlockReference: GoalRunnerAcceptedBlockReference? = null,
  val historicalBlockReference: GoalRunnerAcceptedBlockReference? = null,
  val failureCode: String? = null,
  val recoveryCommand: String? = null,
)

enum class GoalPullRequestStatus(val wireValue: String) {
  OPENED("opened"),
  EXISTING("existing"),
  ;

  companion object {
    fun fromWire(value: String): GoalPullRequestStatus? = entries.firstOrNull { it.wireValue == value }
  }
}

sealed interface GoalRunnerRunReport {
  val issueKey: String
  val attemptedSubtasks: List<Int>
  val parentWorkflowId: String?
    get() = null

  data class Completed(
    override val issueKey: String,
    override val attemptedSubtasks: List<Int>,
    val pullRequestUrl: String?,
    val pullRequestStatus: GoalPullRequestStatus,
    val subtasksCompleted: Int,
    val subtasksPending: Int,
    val subtasksBlocked: Int,
    val unaddressedFindingCount: Int? = 0,
    val unaddressedSeverityBreakdown: Map<String, Int> = emptyMap(),
    val featureName: String? = null,
    override val parentWorkflowId: String? = null,
    val monitoringSummary: String? = null,
  ) : GoalRunnerRunReport

  /** The goal finished with no subtask commit: every subtask was accepted as no-change, so there is no PR. */
  data class CompletedNoChange(
    override val issueKey: String,
    override val attemptedSubtasks: List<Int>,
    /** Null when the accepted pause cannot be read back; telemetry then omits the reason instead of inventing one. */
    val noChangeReason: String?,
    val subtaskIds: List<Int>,
    override val parentWorkflowId: String? = null,
  ) : GoalRunnerRunReport

  data class Stopped(
    override val issueKey: String,
    override val attemptedSubtasks: List<Int>,
    val stop: GoalRunnerStopReport,
    override val parentWorkflowId: String? = null,
  ) : GoalRunnerRunReport

  /** A previous run already finalized this goal, so the runner stopped without planning, committing or opening a PR. */
  data class AlreadyComplete(
    override val issueKey: String,
    val completedAt: String,
    val pullRequestUrl: String?,
    override val parentWorkflowId: String? = null,
  ) : GoalRunnerRunReport {
    override val attemptedSubtasks: List<Int>
      get() = emptyList()
  }
}

fun GoalRunnerStopReason.toLedgerAction(): GoalAttemptLedgerAction =
  when (this) {
    GoalRunnerStopReason.TIMEOUT -> GoalAttemptLedgerAction.TIMEOUT
    GoalRunnerStopReason.INTERRUPTED -> GoalAttemptLedgerAction.INTERRUPTION
    GoalRunnerStopReason.NO_TERMINAL_STORE_OUTCOME -> GoalAttemptLedgerAction.RETRY
    GoalRunnerStopReason.FAILED,
    GoalRunnerStopReason.BLOCKED,
    GoalRunnerStopReason.POLICY_BLOCKED,
    GoalRunnerStopReason.PULL_REQUEST_FAILED,
    GoalRunnerStopReason.DEPENDENCIES_BLOCKED,
    GoalRunnerStopReason.RECONCILED_RESUMABLE,
    GoalRunnerStopReason.AWAITING_OPERATOR_DECISION,
    GoalRunnerStopReason.AWAITING_NO_CHANGE_DECISION,
    GoalRunnerStopReason.PAUSED,
    -> GoalAttemptLedgerAction.FINAL_RECONCILED_OUTCOME
  }

fun GoalRunnerStopReason.toDiagnosticClass(): String =
  when (this) {
    GoalRunnerStopReason.NO_TERMINAL_STORE_OUTCOME -> "no_terminal_workflow_state"
    GoalRunnerStopReason.FAILED -> "malformed_result_json"
    GoalRunnerStopReason.TIMEOUT,
    GoalRunnerStopReason.INTERRUPTED,
    GoalRunnerStopReason.BLOCKED,
    -> "child_process_failed"
    GoalRunnerStopReason.POLICY_BLOCKED,
    GoalRunnerStopReason.PULL_REQUEST_FAILED,
    GoalRunnerStopReason.DEPENDENCIES_BLOCKED,
    GoalRunnerStopReason.RECONCILED_RESUMABLE,
    GoalRunnerStopReason.AWAITING_OPERATOR_DECISION,
    GoalRunnerStopReason.AWAITING_NO_CHANGE_DECISION,
    GoalRunnerStopReason.PAUSED,
    -> name.lowercase()
  }

fun GoalRunnerStopReason.nextSafeAction(): String =
  when (this) {
    GoalRunnerStopReason.NO_TERMINAL_STORE_OUTCOME,
    GoalRunnerStopReason.TIMEOUT,
    GoalRunnerStopReason.INTERRUPTED,
    GoalRunnerStopReason.RECONCILED_RESUMABLE,
    GoalRunnerStopReason.AWAITING_OPERATOR_DECISION,
    GoalRunnerStopReason.AWAITING_NO_CHANGE_DECISION,
    GoalRunnerStopReason.PAUSED,
    -> "resume_from_last_resumable_step"
    GoalRunnerStopReason.FAILED -> "inspect_child_output_then_resume"
    GoalRunnerStopReason.BLOCKED,
    GoalRunnerStopReason.POLICY_BLOCKED,
    GoalRunnerStopReason.PULL_REQUEST_FAILED,
    GoalRunnerStopReason.DEPENDENCIES_BLOCKED,
    -> "inspect_blocked_reason"
  }
