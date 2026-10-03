package skillbill.engine.goalrunner.persist

import skillbill.contracts.JsonCodec
import skillbill.ports.featuretask.model.FeatureTaskRuntimeWorkerLeaseState
import skillbill.ports.featuretask.model.FeatureTaskRuntimeWorkerOwnership
import skillbill.ports.taskruntime.FeatureTaskRuntimeWorkerSupervisor
import skillbill.ports.taskruntime.NoopFeatureTaskRuntimeHeartbeat
import skillbill.ports.taskruntime.model.FeatureTaskRuntimeHeartbeatPlan
import skillbill.ports.taskruntime.model.FeatureTaskRuntimeHeartbeatTick
import skillbill.ports.taskruntime.model.FeatureTaskRuntimeProcessIdentity
import skillbill.ports.taskruntime.model.FeatureTaskRuntimeProcessInspection
import skillbill.ports.workflow.gitops.NoopWorkflowGitOperations
import skillbill.ports.workflow.gitops.WorkflowGitOperations
import skillbill.ports.workflow.gitops.model.WorkflowGitOperationResult
import skillbill.ports.workflow.model.WorkflowFamily
import skillbill.ports.workflow.model.WorkflowStateRecord
import skillbill.ports.workflow.model.toSnapshot
import skillbill.ports.workflow.toRecord
import skillbill.review.context.model.execution.CodeReviewExecutionMode
import skillbill.workflow.engine.WorkflowEngine
import skillbill.workflow.engine.model.DurableWorkflowArtifactFamily
import skillbill.workflow.engine.model.WorkflowArtifactPatch
import skillbill.workflow.engine.model.WorkflowStepUpdates
import skillbill.workflow.engine.model.WorkflowUpdateInput
import skillbill.workflow.model.WorkflowStatus
import skillbill.workflow.model.goalobservability.GoalProgressEvent
import skillbill.workflow.model.goalobservability.GoalProgressEventKind
import skillbill.workflow.model.goalreview.GoalSubtaskReviewState
import skillbill.workflow.taskruntime.artifact.asWorkflowArtifactEntry
import skillbill.workflow.taskruntime.artifact.toWorkflowArtifactMap
import skillbill.workflow.taskruntime.model.persistence.FeatureTaskRuntimeGoalContinuationArtifact
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimePhaseRecord
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

private val GOAL_SUBTASK_REVIEW_RESULTS_ARTIFACT_KEY =
  DurableWorkflowArtifactFamily.GOAL_SUBTASK_REVIEW_RESULTS.label()
private val GOAL_SUBTASK_REVIEW_STATE_ARTIFACT_KEY =
  DurableWorkflowArtifactFamily.GOAL_SUBTASK_REVIEW_STATE.label()
private val FEATURE_TASK_RUNTIME_GOAL_CONTINUATION_ARTIFACT_KEY =
  DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_GOAL_CONTINUATION.label()
private val FEATURE_TASK_RUNTIME_PHASE_RECORDS_ARTIFACT_KEY =
  DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_PHASE_RECORDS.label()

internal fun outcomeStoreSqliteTimestamp(instant: Instant): String =
  DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
    .withZone(ZoneOffset.UTC)
    .format(instant.truncatedTo(ChronoUnit.SECONDS))

internal data class BlockedContinuationRecordFixture(
  val workflowId: String,
  val workflowStatus: String,
  val stepStatus: String,
  val blockedReasonArtifact: String?,
  val storedBlockedReason: String,
  val declaredProgressTimestamp: Instant? = null,
)

internal fun blockedContinuationRecord(fixture: BlockedContinuationRecordFixture): WorkflowStateRecord {
  val workflowId = fixture.workflowId
  val workflowStatus = fixture.workflowStatus
  val stepStatus = fixture.stepStatus
  val blockedReasonArtifact = fixture.blockedReasonArtifact
  val storedBlockedReason = fixture.storedBlockedReason
  val declaredProgressTimestamp = fixture.declaredProgressTimestamp
  val definition = WorkflowFamily.TASK_RUNTIME.definition
  val engine = WorkflowEngine()
  val opened = engine.openRecord(definition, workflowId, "fis-176", "preplan")
  val artifacts =
    linkedMapOf<String, Any?>(
      "goal_continuation" to
        mapOf(
          "issue_key" to "SKILL-176.4",
          "subtask_id" to 4,
          "suppress_pr" to true,
          "goal_branch" to "feat/SKILL-176",
          "code_review_mode" to "auto",
        ),
      "goal_continuation_outcome" to
        mapOf(
          "issue_key" to "SKILL-176.4",
          "subtask_id" to 4,
          "status" to "blocked",
          "workflow_id" to workflowId,
          "blocked_reason" to storedBlockedReason,
          "last_resumable_step" to "review",
        ),
    )
  if (blockedReasonArtifact != null) {
    artifacts["blocked_reason"] = blockedReasonArtifact
  }
  if (declaredProgressTimestamp != null) {
    artifacts["goal_progress_latest_event"] =
      GoalProgressEvent(
        eventKind = GoalProgressEventKind.OPERATION_HEARTBEAT,
        workflowId = workflowId,
        workflowPhase = "goal_runner_supervision",
        processAlive = true,
        sequenceNumber = 1,
        timestamp = declaredProgressTimestamp.toString(),
        operationName = "child_agent_run",
        operationKind = "long_child_run",
        expectedLong = true,
      ).toPersistenceWire()
  }
  return engine.updateRecord(
    definition,
    opened,
    WorkflowUpdateInput(
      terminalInstant = Instant.EPOCH,
      workflowStatus =
        WorkflowStatus.fromWire(workflowStatus)
          ?: error("Unknown workflow status '$workflowStatus'."),
      currentStepId = "review",
      stepUpdates =
        WorkflowStepUpdates.from(
          listOf(
            mapOf("step_id" to "review", "status" to stepStatus, "attempt_count" to 1),
          ),
        ),
      artifactsPatch = WorkflowArtifactPatch.from(artifacts),
      sessionId = "ftr-176",
    ),
  ).toRecord()
}

internal fun completeWithoutShaContinuationRecord(workflowId: String): WorkflowStateRecord {
  val definition = WorkflowFamily.TASK_RUNTIME.definition
  val engine = WorkflowEngine()
  val opened = engine.openRecord(definition, workflowId, "fis-176", "preplan")
  return engine.updateRecord(
    definition,
    opened,
    WorkflowUpdateInput(
      terminalInstant = Instant.EPOCH,
      workflowStatus = WorkflowStatus.RUNNING,
      currentStepId = "commit_push",
      stepUpdates =
        WorkflowStepUpdates.from(
          listOf(
            mapOf("step_id" to "commit_push", "status" to "completed", "attempt_count" to 1),
          ),
        ),
      artifactsPatch =
        WorkflowArtifactPatch.from(
          mapOf(
            "goal_continuation" to
              mapOf(
                "issue_key" to "SKILL-176.4",
                "subtask_id" to 4,
                "suppress_pr" to true,
                "goal_branch" to "feat/SKILL-176",
                "code_review_mode" to "auto",
              ),
            "goal_continuation_outcome" to
              mapOf(
                "issue_key" to "SKILL-176.4",
                "subtask_id" to 4,
                "status" to "complete",
                "workflow_id" to workflowId,
                "last_resumable_step" to "commit_push",
              ),
          ),
        ),
      sessionId = "ftr-176",
    ),
  ).toRecord()
}

internal fun runtimeCandidateRecordNoDeclaredEvent(
  workflowId: String,
  updatedAt: String?,
): WorkflowStateRecord {
  val definition = WorkflowFamily.TASK_RUNTIME.definition
  val engine = WorkflowEngine()
  val opened = engine.openRecord(definition, workflowId, "fis-001", "preplan")
  return engine.updateRecord(
    definition,
    opened,
    WorkflowUpdateInput(
      terminalInstant = Instant.EPOCH,
      workflowStatus = WorkflowStatus.RUNNING,
      currentStepId = "implement",
      stepUpdates =
        WorkflowStepUpdates.from(
          listOf(
            mapOf("step_id" to "implement", "status" to "running", "attempt_count" to 1),
          ),
        ),
      artifactsPatch =
        WorkflowArtifactPatch.from(
          mapOf(
            "goal_continuation" to
              mapOf(
                "issue_key" to "SKILL-87.1",
                "subtask_id" to 1,
                "suppress_pr" to true,
                "goal_branch" to "feat/SKILL-87",
                "code_review_mode" to "auto",
              ),
          ),
        ),
      sessionId = "ftr-001",
    ),
  ).toRecord().copy(updatedAt = updatedAt)
}

internal fun goalReviewWorkflowRecord(
  workflowId: String,
  state: GoalSubtaskReviewState,
  rawReviewResult: String,
): WorkflowStateRecord {
  val definition = WorkflowFamily.TASK_RUNTIME.definition
  val engine = WorkflowEngine()
  val opened = engine.openRecord(definition, workflowId, "fis-001", "preplan")
  return engine.updateRecord(
    definition,
    opened,
    WorkflowUpdateInput(
      terminalInstant = Instant.EPOCH,
      workflowStatus = WorkflowStatus.RUNNING,
      currentStepId = "review",
      stepUpdates = null,
      artifactsPatch =
        WorkflowArtifactPatch.from(
          mapOf(
            FEATURE_TASK_RUNTIME_GOAL_CONTINUATION_ARTIFACT_KEY to
              FeatureTaskRuntimeGoalContinuationArtifact(
                issueKey = "SKILL-119",
                subtaskId = 2,
                suppressPr = true,
                goalBranch = "feat/SKILL-119",
                codeReviewMode = CodeReviewExecutionMode.AUTO,
              ).asWorkflowArtifactEntry().toWorkflowArtifactMap(),
            GOAL_SUBTASK_REVIEW_STATE_ARTIFACT_KEY to state.toPersistenceWire(),
            GOAL_SUBTASK_REVIEW_RESULTS_ARTIFACT_KEY to mapOf("1" to rawReviewResult),
          ),
        ),
      sessionId = "ftr-001",
    ),
  ).toRecord()
}

internal fun runtimeCandidateRecord(
  workflowId: String,
  declaredProgressTimestamp: Instant,
): WorkflowStateRecord {
  val definition = WorkflowFamily.TASK_RUNTIME.definition
  val engine = WorkflowEngine()
  val opened = engine.openRecord(definition, workflowId, "fis-001", "preplan")
  val declaredEvent =
    GoalProgressEvent(
      eventKind = GoalProgressEventKind.OPERATION_HEARTBEAT,
      workflowId = workflowId,
      workflowPhase = "goal_runner_supervision",
      processAlive = true,
      sequenceNumber = 1,
      timestamp = declaredProgressTimestamp.toString(),
      operationName = "child_agent_run",
      operationKind = "long_child_run",
      expectedLong = true,
    )
  return engine.updateRecord(
    definition,
    opened,
    WorkflowUpdateInput(
      terminalInstant = Instant.EPOCH,
      workflowStatus = WorkflowStatus.RUNNING,
      currentStepId = "implement",
      stepUpdates =
        WorkflowStepUpdates.from(
          listOf(
            mapOf("step_id" to "implement", "status" to "running", "attempt_count" to 1),
          ),
        ),
      artifactsPatch =
        WorkflowArtifactPatch.from(
          mapOf(
            "goal_continuation" to
              mapOf(
                "issue_key" to "SKILL-87.1",
                "subtask_id" to 1,
                "suppress_pr" to true,
                "goal_branch" to "feat/SKILL-87",
                "code_review_mode" to "auto",
              ),
            "goal_progress_latest_event" to declaredEvent.toPersistenceWire(),
          ),
        ),
      sessionId = "ftr-001",
    ),
  ).toRecord()
}

internal fun taskRuntimeWorkflowRecord(workflowId: String): WorkflowStateRecord {
  val definition = WorkflowFamily.TASK_RUNTIME.definition
  val engine = WorkflowEngine()
  val opened = engine.openRecord(definition, workflowId, "fis-001", "preplan")
  return engine.updateRecord(
    definition,
    opened,
    WorkflowUpdateInput(
      terminalInstant = Instant.EPOCH,
      workflowStatus = WorkflowStatus.RUNNING,
      currentStepId = "implement",
      stepUpdates = null,
      artifactsPatch = WorkflowArtifactPatch.from(emptyMap()),
      sessionId = "ftr-001",
    ),
  ).toRecord()
}

internal fun decodeWorkflowArtifactsForTest(artifactsJson: String): Map<String, Any?> {
  val element = JsonCodec.json.parseToJsonElement(artifactsJson)
  return requireNotNull(JsonCodec.anyToStringAnyMap(JsonCodec.jsonElementToValue(element)))
}

internal fun tornBlockedReviewRecord(workflowId: String): WorkflowStateRecord {
  val definition = WorkflowFamily.TASK_RUNTIME.definition
  val engine = WorkflowEngine()
  val opened = engine.openRecord(definition, workflowId, "fis-001", "preplan")
  val reviewRecord =
    FeatureTaskRuntimePhaseRecord(
      phaseId = "review",
      status = "running",
      attemptCount = 2,
      startedAt = "2026-08-15T09:17:42Z",
      resolvedAgentId = "cursor",
    )
  return engine.updateRecord(
    definition,
    opened,
    WorkflowUpdateInput(
      terminalInstant = Instant.EPOCH,
      workflowStatus = WorkflowStatus.BLOCKED,
      currentStepId = "review",
      stepUpdates =
        WorkflowStepUpdates.from(
          listOf(
            mapOf("step_id" to "review", "status" to "blocked", "attempt_count" to 2),
          ),
        ),
      artifactsPatch =
        WorkflowArtifactPatch.from(
          mapOf(
            FEATURE_TASK_RUNTIME_PHASE_RECORDS_ARTIFACT_KEY to
              mapOf(
                "review" to reviewRecord.asWorkflowArtifactEntry(),
              ),
            "goal_continuation" to
              mapOf(
                "issue_key" to "SKILL-191",
                "subtask_id" to 9,
                "suppress_pr" to true,
                "goal_branch" to "feat/SKILL-191",
                "code_review_mode" to "auto",
              ),
          ),
        ),
      sessionId = "ftr-001",
    ),
  ).toRecord()
}

internal fun crashedChildRecord(workflowId: String): WorkflowStateRecord {
  val definition = WorkflowFamily.TASK_RUNTIME.definition
  val engine = WorkflowEngine()
  val opened = engine.openRecord(definition, workflowId, "fis-001", "preplan")
  return engine.updateRecord(
    definition,
    opened,
    WorkflowUpdateInput(
      terminalInstant = Instant.EPOCH,
      workflowStatus = WorkflowStatus.RUNNING,
      currentStepId = "implement",
      stepUpdates = null,
      artifactsPatch =
        WorkflowArtifactPatch.from(
          mapOf(
            "goal_continuation" to
              mapOf(
                "issue_key" to "SKILL-87.1",
                "subtask_id" to 1,
                "suppress_pr" to true,
                "goal_branch" to "feat/SKILL-87",
                "code_review_mode" to "auto",
              ),
          ),
        ),
      sessionId = "ftr-001",
    ),
  ).toRecord()
}

internal fun expiredLeaseOwnership(
  workflowId: String,
  expiresAt: String = "2000-01-01T00:00:30Z",
) = FeatureTaskRuntimeWorkerOwnership(
  workflowId = workflowId,
  generation = 1,
  ownerToken = "crashed-owner-$workflowId",
  hostIdentity = "host",
  bootIdentity = "boot",
  pid = 9,
  processBirthToken = "birth-9",
  leaseState = FeatureTaskRuntimeWorkerLeaseState.ACTIVE,
  heartbeatAt = "2000-01-01T00:00:00Z",
  expiresAt = expiresAt,
  phaseId = "implement",
  phaseAttempt = 1,
)

internal object DeadProcessSupervisor : FeatureTaskRuntimeWorkerSupervisor {
  override fun currentProcess() = FeatureTaskRuntimeProcessIdentity("host", "boot", 9, "birth-9")

  override fun inspect(ownership: FeatureTaskRuntimeWorkerOwnership) = FeatureTaskRuntimeProcessInspection.NotRunning

  override fun awaitExit(
    ownership: FeatureTaskRuntimeWorkerOwnership,
    timeout: Duration,
  ) = Unit

  override fun terminateGracefully(ownership: FeatureTaskRuntimeWorkerOwnership) = true

  override fun terminateForcibly(ownership: FeatureTaskRuntimeWorkerOwnership) = true

  override fun startHeartbeat(
    plan: FeatureTaskRuntimeHeartbeatPlan,
    heartbeat: () -> FeatureTaskRuntimeHeartbeatTick,
  ) = NoopFeatureTaskRuntimeHeartbeat

  override fun pause(durationMillis: Long) = Unit
}

internal object LiveProcessSupervisor : FeatureTaskRuntimeWorkerSupervisor {
  override fun currentProcess() = FeatureTaskRuntimeProcessIdentity("host", "boot", 9, "birth-9")

  override fun inspect(ownership: FeatureTaskRuntimeWorkerOwnership) = FeatureTaskRuntimeProcessInspection.ExactLive

  override fun awaitExit(
    ownership: FeatureTaskRuntimeWorkerOwnership,
    timeout: Duration,
  ) = Unit

  override fun terminateGracefully(ownership: FeatureTaskRuntimeWorkerOwnership) = true

  override fun terminateForcibly(ownership: FeatureTaskRuntimeWorkerOwnership) = true

  override fun startHeartbeat(
    plan: FeatureTaskRuntimeHeartbeatPlan,
    heartbeat: () -> FeatureTaskRuntimeHeartbeatTick,
  ) = NoopFeatureTaskRuntimeHeartbeat

  override fun pause(durationMillis: Long) = Unit
}

internal object MeasuringHeadShaGitOperations : WorkflowGitOperations by NoopWorkflowGitOperations {
  override fun headCommitSha(repoRoot: Path): WorkflowGitOperationResult =
    WorkflowGitOperationResult.Ok(value = "measured-head-sha")
}

internal fun blockedBuildStepsJson(record: WorkflowStateRecord): String =
  JsonCodec.valueToJsonString(
    record.toSnapshot().steps.map { step ->
      mapOf(
        "step_id" to step.stepId,
        "status" to if (step.stepId == "build") "blocked" else step.status.wireValue,
        "attempt_count" to step.attemptCount,
      )
    },
  )
