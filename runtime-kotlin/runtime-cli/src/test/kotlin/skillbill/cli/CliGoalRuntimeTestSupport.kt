package skillbill.cli

import kotlinx.serialization.json.JsonElement
import skillbill.cli.model.CliRuntimeContext
import skillbill.contracts.JsonCodec
import skillbill.contracts.workflow.featuretask.FEATURE_TASK_RUNTIME_CONTRACT_VERSION
import skillbill.contracts.workflow.identity.task.FEATURE_TASK_RUNTIME_WORKER_OWNERSHIP_CONTRACT_VERSION
import skillbill.infrastructure.sqlite.ensureTestDatabase
import skillbill.install.model.SupportedAgent
import skillbill.ports.agentrun.AgentRunLauncher
import skillbill.ports.agentrun.ExecutableLookup
import skillbill.ports.agentrun.agentRunLaunchFacts
import skillbill.ports.agentrun.model.AgentRunLaunchOutcome
import skillbill.ports.agentrun.model.AgentRunLaunchRequest
import skillbill.ports.agentrun.model.AgentRunOutputStream
import skillbill.ports.agentrun.model.SkillRunRequest
import skillbill.ports.goalrunner.runner.GoalPullRequestPort
import skillbill.ports.goalrunner.runner.model.GoalPullRequestRequest
import skillbill.ports.goalrunner.runner.model.GoalPullRequestResult
import skillbill.ports.telemetry.transport.RemoteTransportPort
import skillbill.ports.time.NoopRuntimeTimingPort
import skillbill.ports.workflow.gitops.WorkflowGitOperations
import skillbill.ports.workflow.gitops.WorkflowGitOperationsTestBase
import skillbill.ports.workflow.gitops.model.GoalSubtaskReviewBaseline
import skillbill.ports.workflow.gitops.model.GoalSubtaskReviewBaselineRecoveryRequest
import skillbill.ports.workflow.gitops.model.GoalSubtaskReviewBaselineResult
import skillbill.ports.workflow.gitops.model.WorkflowGitCommitResult
import skillbill.ports.workflow.gitops.model.WorkflowGitIndexSnapshot
import skillbill.ports.workflow.gitops.model.WorkflowGitIndexSnapshotResult
import skillbill.ports.workflow.gitops.model.WorkflowGitNameListResult
import skillbill.ports.workflow.gitops.model.WorkflowGitOperationResult
import skillbill.ports.workflow.gitops.model.WorkflowGitOperationStatus
import skillbill.ports.workflow.gitops.model.WorkflowPathContentIdentitiesResult
import skillbill.ports.workflow.gitops.model.WorkflowSelectedDiffHunksRequest
import skillbill.ports.workflow.gitops.model.WorkflowSelectedDiffHunksResult
import skillbill.ports.workflow.gitops.model.WorkflowWorktreeActivityResult
import skillbill.workflow.model.WorkflowStatus
import skillbill.workflow.model.goalobservability.GoalObservabilityDiffStat
import skillbill.workflow.model.goalobservability.GoalObservabilitySelectedDiffHunk
import skillbill.workflow.model.goalobservability.GoalObservabilitySelectedDiffHunks
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import kotlin.test.assertTrue

internal fun startRunningRuntimeGoalChild(fixture: GoalCliFixture): String {
  val childWorkflowId = startRunningGoalChild(fixture)
  val runtimeWorkflow =
    RuntimeWorkflowTestSupport.open(
      fixture.dbPath,
      fixture.context(launcher = NoopGoalTestAgentRunLauncher),
    )
  ensureTestDatabase(fixture.dbPath).use { connection ->
    connection.prepareStatement(
      "UPDATE feature_task_workflows SET artifacts_json = replace(artifacts_json, ?, ?) " +
        "WHERE mode = 'runtime' AND instr(artifacts_json, ?) > 0",
    ).use { statement ->
      statement.setString(1, childWorkflowId)
      statement.setString(2, runtimeWorkflow["workflow_id"] as String)
      statement.setString(3, childWorkflowId)
      assertTrue(statement.executeUpdate() >= 1)
    }
  }
  return runtimeWorkflow["workflow_id"] as String
}

internal fun seedLiveWorkerLease(
  fixture: GoalCliFixture,
  workflowId: String,
) {
  ensureTestDatabase(fixture.dbPath).use { connection ->
    connection.prepareStatement(
      """
      INSERT OR REPLACE INTO feature_task_runtime_worker_leases (
        workflow_id, contract_version, generation, owner_token, host_identity, boot_identity,
        pid, process_birth_token, lease_state, heartbeat_at, expires_at, phase_id, phase_attempt
      ) VALUES (?, ?, 1, ?, ?, ?, 1234, ?, 'active', ?, ?, 'implement', 1)
      """.trimIndent(),
    ).use { statement ->
      statement.setString(1, workflowId)
      statement.setString(2, FEATURE_TASK_RUNTIME_WORKER_OWNERSHIP_CONTRACT_VERSION)
      statement.setString(3, "owner-token-cli-watch")
      statement.setString(4, "test-host")
      statement.setString(5, "test-boot")
      statement.setString(6, "birth-1234")
      statement.setString(7, "2999-01-01T00:00:00Z")
      statement.setString(8, "2999-01-01T00:01:00Z")
      statement.executeUpdate()
    }
  }
}

internal fun clearWorkerLease(
  fixture: GoalCliFixture,
  workflowId: String,
) {
  ensureTestDatabase(fixture.dbPath).use { connection ->
    connection.prepareStatement(
      "DELETE FROM feature_task_runtime_worker_leases WHERE workflow_id = ?",
    ).use { statement ->
      statement.setString(1, workflowId)
      statement.executeUpdate()
    }
  }
}

internal fun seedExpiredWorkerLease(
  fixture: GoalCliFixture,
  workflowId: String,
) {
  ensureTestDatabase(fixture.dbPath).use { connection ->
    connection.prepareStatement(
      """
      INSERT OR REPLACE INTO feature_task_runtime_worker_leases (
        workflow_id, contract_version, generation, owner_token, host_identity, boot_identity,
        pid, process_birth_token, lease_state, heartbeat_at, expires_at, phase_id, phase_attempt
      ) VALUES (?, ?, 1, ?, ?, ?, 1234, ?, 'active', ?, ?, 'implement', 1)
      """.trimIndent(),
    ).use { statement ->
      statement.setString(1, workflowId)
      statement.setString(2, FEATURE_TASK_RUNTIME_WORKER_OWNERSHIP_CONTRACT_VERSION)
      statement.setString(3, "owner-token-expired-cli")
      statement.setString(4, "test-host")
      statement.setString(5, "test-boot")
      statement.setString(6, "birth-1234")
      statement.setString(7, "2000-01-01T00:00:00Z")
      statement.setString(8, "2000-01-01T00:00:30Z")
      statement.executeUpdate()
    }
  }
}

internal fun seedIssue342StaleParentControlState(fixture: GoalCliFixture) {
  DriverManager.getConnection("jdbc:sqlite:${fixture.dbPath}").use { connection ->
    val rows = mutableListOf<Pair<String, String>>()
    connection.prepareStatement(
      "SELECT parent_workflow_id, control_state_json FROM goal_runner_controls",
    ).use { statement ->
      statement.executeQuery().use { result ->
        while (result.next()) rows += result.getString(1) to result.getString(2)
      }
    }
    rows.forEach { (parentWorkflowId, json) ->
      val state =
        JsonCodec.anyToStringAnyMap(
          JsonCodec.jsonElementToValue(requireNotNull(JsonCodec.parseObjectOrNull(json))),
        ).orEmpty().toMutableMap()
      state["paused"] = true
      state["pause_requested"] = true
      state["pause_consumed"] = true
      state["pause_reason"] = "runner_interrupted"
      state["paused_at"] = "2000-01-01T00:00:00Z"
      state["execution_lease"] =
        mapOf(
          "generation" to 1,
          "owner_token" to "parent-owner-cli",
          "host_identity" to "test-host",
          "boot_identity" to "test-boot",
          "pid" to 1234,
          "process_birth_token" to "birth-1234",
          "heartbeat_at" to "2000-01-01T00:00:00Z",
          "expires_at" to "2000-01-01T00:00:30Z",
        )
      connection.prepareStatement(
        "UPDATE goal_runner_controls SET control_state_json = ? WHERE parent_workflow_id = ?",
      ).use { statement ->
        statement.setString(1, JsonCodec.mapToJsonString(state))
        statement.setString(2, parentWorkflowId)
        statement.executeUpdate()
      }
    }
  }
}

internal fun workerLeaseRowCount(
  fixture: GoalCliFixture,
  workflowId: String,
): Int =
  ensureTestDatabase(fixture.dbPath).use { connection ->
    connection.prepareStatement(
      "SELECT COUNT(*) FROM feature_task_runtime_worker_leases WHERE workflow_id = ?",
    ).use { statement ->
      statement.setString(1, workflowId)
      statement.executeQuery().use { rows ->
        check(rows.next())
        rows.getInt(1)
      }
    }
  }

internal fun parentControlStateJson(fixture: GoalCliFixture): String =
  DriverManager.getConnection("jdbc:sqlite:${fixture.dbPath}").use { connection ->
    connection.prepareStatement(
      "SELECT control_state_json FROM goal_runner_controls LIMIT 1",
    ).use { statement ->
      statement.executeQuery().use { rows ->
        check(rows.next())
        rows.getString(1)
      }
    }
  }

internal fun startRunningGoalChild(fixture: GoalCliFixture): String =
  RuntimeWorkflowTestSupport.continueByIssueKey(
    dbPath = fixture.dbPath,
    issueKey = "SKILL-901",
    subtaskId = 1,
    context = fixture.context(launcher = NoopGoalTestAgentRunLauncher),
  )["workflow_id"] as String

internal fun recordRunningGoalChildProgress(
  fixture: GoalCliFixture,
  childWorkflowId: String,
  sequence: Int,
  message: String = "editing runtime files",
) {
  runtimeWorkflowUpdate(
    fixture,
    WorkflowUpdateFixture(
      dbPath = fixture.dbPath,
      workflowId = childWorkflowId,
      currentStep = "implement",
      stepUpdates = """[{"step_id":"implement","status":"running","attempt_count":1}]""",
      artifactsPatch =
        jsonString(
          mapOf(
            "preplan_digest" to mapOf("ready" to true),
            "plan" to mapOf("mode" to "implement", "task_count" to 1),
            "progress_event" to
              mapOf(
                "step_id" to "implement",
                "attempt_count" to 1,
                "source" to "phase_subagent",
                "kind" to "durable_progress",
                "message" to message,
                "sequence" to sequence,
                "timestamp" to "2026-06-01T00:00:00Z",
              ),
            "goal_observability_latest_event" to
              mapOf(
                "contract_version" to "0.2",
                "issue_key" to "SKILL-901",
                "subtask_id" to 1,
                "workflow_phase" to "implement",
                "worker_role" to "phase_subagent",
                "liveness_class" to "durable_progress",
                "activity_summary" to message,
                "sequence_number" to sequence,
                "timestamp" to "2026-06-01T00:00:00Z",
              ),
          ),
        ),
    ),
  )
}

internal fun advanceRunningGoalChildToReview(
  fixture: GoalCliFixture,
  childWorkflowId: String,
) {
  runtimeWorkflowUpdate(
    fixture,
    WorkflowUpdateFixture(
      dbPath = fixture.dbPath,
      workflowId = childWorkflowId,
      currentStep = "review",
      stepUpdates = """[{"step_id":"review","status":"running","attempt_count":1}]""",
      artifactsPatch = jsonString(emptyMap<String, Any?>()),
    ),
  )
}

internal fun completeRunningGoalChild(
  fixture: GoalCliFixture,
  childWorkflowId: String,
) {
  runtimeWorkflowUpdate(
    fixture,
    WorkflowUpdateFixture(
      dbPath = fixture.dbPath,
      workflowId = childWorkflowId,
      workflowStatus = WorkflowStatus.COMPLETED.wireValue,
      currentStep = "commit_push",
      stepUpdates = """[{"step_id":"commit_push","status":"completed","attempt_count":1}]""",
      artifactsPatch =
        jsonString(
          mapOf(
            "commit_push_result" to mapOf("commit_sha" to "sha-1"),
            "goal_continuation_outcome" to
              mapOf(
                "issue_key" to "SKILL-901",
                "subtask_id" to 1,
                "status" to "complete",
                "workflow_id" to childWorkflowId,
                "commit_sha" to "sha-1",
                "last_resumable_step" to "commit_push",
              ),
          ),
        ),
    ),
  )
}

internal fun seedAuthoritativeCompleteChild(fixture: GoalCliFixture) {
  val authoritativeChild =
    RuntimeWorkflowTestSupport.open(
      fixture.dbPath,
      fixture.context(launcher = NoopGoalTestAgentRunLauncher),
    )["workflow_id"] as String
  runtimeWorkflowUpdate(
    fixture,
    WorkflowUpdateFixture(
      dbPath = fixture.dbPath,
      workflowId = authoritativeChild,
      currentStep = "commit_push",
      stepUpdates = """[{"step_id":"commit_push","status":"completed","attempt_count":1}]""",
      artifactsPatch =
        jsonString(
          mapOf(
            "goal_continuation" to
              mapOf(
                "issue_key" to "SKILL-901",
                "subtask_id" to 1,
                "goal_branch" to "feat/SKILL-901-goal",
                "code_review_mode" to "auto",
                "suppress_pr" to true,
              ),
            "goal_continuation_outcome" to
              mapOf(
                "issue_key" to "SKILL-901",
                "subtask_id" to 1,
                "status" to "complete",
                "workflow_id" to authoritativeChild,
                "commit_sha" to "sha-1",
                "last_resumable_step" to "commit_push",
              ),
          ),
        ),
    ),
  )
}

internal data class GoalCliFixture(
  val tempDir: Path,
  val dbPath: Path,
  val parentSpec: Path,
  val subtaskSpecs: List<Path>,
  val pullRequests: RecordingGoalPullRequestPort = RecordingGoalPullRequestPort(),
) {
  fun context(
    launcher: AgentRunLauncher,
    liveStdout: (String) -> Unit = {},
    liveStderr: (String) -> Unit = {},
    workflowGitOperations: WorkflowGitOperations = GoalTestWorkflowGitOperations,
    requester: RemoteTransportPort? = null,
  ): CliRuntimeContext =
    CliRuntimeContext(
      userHome = tempDir.also { installFakeRuntimeMcpBin(it) },
      environment = isolatedCliEnvironment(tempDir),
      requester = requester,
      workflowGitOperations = workflowGitOperations,
      agentRunLauncher = launcher,
      goalPullRequestPort = pullRequests,
      liveStdout = liveStdout,
      liveStderr = liveStderr,
      executableLookup = ExecutableLookup { true },
      runtimeTimingPort = NoopRuntimeTimingPort,
    )

  fun materializeDatabaseWithTelemetry(
    level: String,
    requester: RemoteTransportPort,
  ) = materializeTelemetryDatabase(
    tempDir,
    dbPath,
    level,
    context(launcher = NoopGoalTestAgentRunLauncher, requester = requester),
  )

  fun goalCommand(
    dbPath: Path = this@GoalCliFixture.dbPath,
    extra: List<String> = emptyList(),
  ): List<String> =
    buildList {
      add("--db")
      add(dbPath.toString())
      add("goal")
      add("SKILL-901")
      add("--agent")
      add("codex")
      add("--repo-root")
      add(tempDir.toString())
      addAll(extra)
    }
}

internal class GoalFixtureAgentRunLauncher(
  private val fixture: GoalCliFixture,
  private val dbPath: Path = fixture.dbPath,
  private val failSubtask: Int? = null,
  private val noTerminalSubtask: Int? = null,
  private val childDiagnosticChatterCount: Int = 1,
) : AgentRunLauncher {
  val requests: MutableList<AgentRunLaunchRequest> = mutableListOf()
  val childLaunches: MutableList<AgentRunLaunchRequest> = mutableListOf()

  override fun launch(request: AgentRunLaunchRequest): AgentRunLaunchOutcome {
    requests += request
    val skillRequest = request.skillRunRequest
    if (skillRequest.goalContinuation == null && skillRequest.promptOverride != null) {
      return planningLaunchOutcome(skillRequest)
    }
    childLaunches += request
    val subtaskId = requireNotNull(skillRequest.subtaskId)
    skillRequest.outputSink.write(AgentRunOutputStream.STDOUT, "child-$subtaskId-stdout\n")
    skillRequest.outputSink.write(AgentRunOutputStream.STDERR, "child-$subtaskId-stderr\n")
    skillRequest.outputSink.write(
      AgentRunOutputStream.STDERR,
      "skill-bill: workflow progress: subtask $subtaskId " +
        "workflow wftr-$subtaskId step implement durable_progress step=implement\n",
    )
    repeat(childDiagnosticChatterCount) {
      skillRequest.outputSink.write(
        AgentRunOutputStream.STDERR,
        "skill-bill: status heartbeat (90s): child run still active; workflow: " +
          "subtask $subtaskId workflow wftr-$subtaskId step implement durable_progress\n",
      )
    }
    val selectedDbPath = dbPath.toString()
    val workflowId = startSubtaskWorkflow(skillRequest, selectedDbPath)
    if (subtaskId == failSubtask) {
      failSubtaskWorkflow(workflowId, Path.of(selectedDbPath))
    } else if (subtaskId == noTerminalSubtask) {
      stampImplementRunning(workflowId, Path.of(selectedDbPath))
    } else {
      completeSubtaskWorkflow(workflowId, subtaskId, Path.of(selectedDbPath))
    }
    return agentRunLaunchFacts(
      agent = SupportedAgent.CODEX,
      stdout = "captured child $subtaskId",
      stderr = "",
    )
  }

  private fun planningLaunchOutcome(skillRequest: SkillRunRequest): AgentRunLaunchOutcome {
    val phaseId =
      Regex("""Phase: (\w+) \(""")
        .find(skillRequest.promptOverride.orEmpty())
        ?.groupValues?.get(1)
        ?: "preplan"
    return agentRunLaunchFacts(
      agent = SupportedAgent.CODEX,
      stdout = phasePlanningPayload(phaseId),
      stderr = "",
    )
  }

  private fun startSubtaskWorkflow(
    skillRequest: SkillRunRequest,
    dbPath: String,
  ): String {
    val continuation =
      requireNotNull(skillRequest.goalContinuation) {
        "Goal child launch requires goalContinuation with a pre-opened workflow id."
      }
    val workflowId =
      continuation.assignedWorkflowId?.takeIf(String::isNotBlank)
        ?: continuation.childWorkflowId?.takeIf(String::isNotBlank)
        ?: error("Goal child launch requires assignedWorkflowId or childWorkflowId.")
    RuntimeWorkflowTestSupport.get(
      Path.of(dbPath),
      workflowId,
      fixture.context(launcher = this),
    )
    return workflowId
  }

  private fun stampImplementRunning(
    workflowId: String,
    dbPath: Path,
  ) {
    runtimeWorkflowUpdate(
      fixture,
      WorkflowUpdateFixture(
        dbPath = dbPath,
        workflowId = workflowId,
        currentStep = "implement",
        stepUpdates = """[{"step_id":"implement","status":"running","attempt_count":1}]""",
        artifactsPatch = jsonString(emptyMap<String, Any?>()),
      ),
      launcher = this,
    )
  }

  private fun completeSubtaskWorkflow(
    workflowId: String,
    subtaskId: Int,
    dbPath: Path,
  ) {
    runtimeWorkflowUpdate(
      fixture,
      WorkflowUpdateFixture(
        dbPath = dbPath,
        workflowId = workflowId,
        workflowStatus = WorkflowStatus.COMPLETED.wireValue,
        currentStep = "commit_push",
        stepUpdates = """[{"step_id":"commit_push","status":"completed","attempt_count":1}]""",
        artifactsPatch = jsonString(mapOf("commit_push_result" to mapOf("commit_sha" to "sha-$subtaskId"))),
      ),
      launcher = this,
    )
  }

  private fun failSubtaskWorkflow(
    workflowId: String,
    dbPath: Path,
  ) {
    runtimeWorkflowUpdate(
      fixture,
      WorkflowUpdateFixture(
        dbPath = dbPath,
        workflowId = workflowId,
        workflowStatus = WorkflowStatus.FAILED.wireValue,
        currentStep = "implement",
        stepUpdates = """[{"step_id":"implement","status":"failed","attempt_count":1}]""",
        artifactsPatch = jsonString(mapOf("blocked_reason" to "forced failure")),
      ),
      launcher = this,
    )
  }
}

internal class RecordingGoalPullRequestPort : GoalPullRequestPort {
  val requests: MutableList<GoalPullRequestRequest> = mutableListOf()

  override fun open(request: GoalPullRequestRequest): GoalPullRequestResult {
    requests += request
    return GoalPullRequestResult.Opened("https://github.com/example/skill-bill/pull/901")
  }
}

internal fun goalControlCommand(
  fixture: GoalCliFixture,
  subcommand: String,
): List<String> =
  listOf(
    "--db",
    fixture.dbPath.toString(),
    "goal",
    subcommand,
    "SKILL-901",
    "--repo-root",
    fixture.tempDir.toString(),
  )

internal fun forcePendingPauseRequest(dbPath: Path) {
  DriverManager.getConnection("jdbc:sqlite:$dbPath").use { connection ->
    val rows = mutableListOf<Pair<String, String>>()
    connection.prepareStatement(
      "SELECT parent_workflow_id, control_state_json FROM goal_runner_controls",
    ).use { statement ->
      statement.executeQuery().use { result ->
        while (result.next()) rows += result.getString(1) to result.getString(2)
      }
    }
    rows.forEach { (parentWorkflowId, json) ->
      val state =
        JsonCodec.anyToStringAnyMap(
          JsonCodec.jsonElementToValue(requireNotNull(JsonCodec.parseObjectOrNull(json))),
        ).orEmpty().toMutableMap()
      state["paused"] = false
      state["pause_requested"] = true
      state["pause_consumed"] = false
      state["pause_reason"] = "operator_request"
      connection.prepareStatement(
        "UPDATE goal_runner_controls SET control_state_json = ? WHERE parent_workflow_id = ?",
      ).use { statement ->
        statement.setString(1, JsonCodec.mapToJsonString(state))
        statement.setString(2, parentWorkflowId)
        statement.executeUpdate()
      }
    }
  }
}

internal fun goalFixture(
  subtaskCount: Int,
  seedWorkflow: Boolean = true,
): GoalCliFixture {
  val tempDir = Files.createTempDirectory("skillbill-cli-goal")
  installGoalBuildPack(tempDir)
  val parentSpec = tempDir.resolve(".feature-specs/SKILL-901-goal/spec.md")
  Files.createDirectories(parentSpec.parent)
  Files.writeString(
    parentSpec,
    """
    # Parent

    ## Acceptance Criteria

    1. The decomposed goal completes every governed subtask.
    """.trimIndent(),
  )
  val subtaskSpecs =
    (1..subtaskCount).map { id ->
      parentSpec.parent.resolve("spec_subtask_${id}_part.md").also { path ->
        Files.writeString(path, subtaskSpecText(id))
      }
    }
  val fixture =
    GoalCliFixture(
      tempDir = tempDir,
      dbPath = tempDir.resolve("metrics.db"),
      parentSpec = parentSpec,
      subtaskSpecs = subtaskSpecs,
    )
  if (seedWorkflow) {
    seedParentWorkflow(fixture)
  }
  return fixture
}

internal fun seedParentWorkflow(fixture: GoalCliFixture) {
  val opened =
    RuntimeWorkflowTestSupport.open(
      fixture.dbPath,
      fixture.context(launcher = NoopGoalTestAgentRunLauncher),
    )
  val workflowId = opened["workflow_id"] as String
  runtimeWorkflowUpdate(
    fixture,
    WorkflowUpdateFixture(
      dbPath = fixture.dbPath,
      workflowId = workflowId,
      currentStep = "plan",
      stepUpdates = """[{"step_id":"plan","status":"completed","attempt_count":1}]""",
      artifactsPatch = parentArtifactsPatch(fixture),
    ),
  )
}

internal fun parentArtifactsPatch(fixture: GoalCliFixture): String =
  jsonString(
    mapOf(
      "branch" to mapOf("branch" to "feat/SKILL-901-goal"),
      "plan" to
        mapOf(
          "mode" to "decompose",
          "parent_spec_path" to fixture.parentSpec.toString(),
          "recommended_first_subtask_id" to 1,
          "subtasks" to
            fixture.subtaskSpecs.mapIndexed { index, path ->
              mapOf(
                "id" to index + 1,
                "name" to "Part ${index + 1}",
                "spec_path" to path.toString(),
                "depends_on" to if (index == 0) emptyList<Int>() else listOf(index),
              )
            },
        ),
    ),
  )

internal data class WorkflowUpdateFixture(
  val dbPath: Path,
  val workflowId: String,
  val workflowStatus: String = "running",
  val currentStep: String,
  val stepUpdates: String,
  val artifactsPatch: String,
)

internal fun runtimeWorkflowUpdate(
  fixture: GoalCliFixture,
  update: WorkflowUpdateFixture,
  launcher: AgentRunLauncher = NoopGoalTestAgentRunLauncher,
): Map<String, Any?> =
  RuntimeWorkflowTestSupport.update(
    RuntimeWorkflowTestSupport.UpdateArgs(
      dbPath = update.dbPath,
      workflowId = update.workflowId,
      workflowStatus = update.workflowStatus,
      currentStepId = update.currentStep,
      stepUpdates = RuntimeWorkflowTestSupport.parseStepUpdates(update.stepUpdates),
      artifactsPatch = RuntimeWorkflowTestSupport.parseArtifactsPatch(update.artifactsPatch),
      context = fixture.context(launcher = launcher),
    ),
  )

internal fun jsonString(value: Any?): String =
  JsonCodec.json.encodeToString(
    JsonElement.serializer(),
    JsonCodec.valueToJsonElement(value),
  )

internal fun phasePlanningPayload(phaseId: String): String =
  """{"contract_version":"$FEATURE_TASK_RUNTIME_CONTRACT_VERSION","phase_id":"$phaseId",""" +
    """"status":"completed","summary":"$phaseId","produced_outputs":""" +
    (planningProjectionOutputs(phaseId) ?: """{"result":"$phaseId"}""") + "}"

internal fun planningProjectionOutputs(phaseId: String): String? =
  when (phaseId) {
    "preplan" ->
      """{"value":"Fixture preplan prose for downstream plan."}"""
    "plan" ->
      """{"value":"Fixture plan prose for downstream implement and audit."}"""
    else -> null
  }

internal fun subtaskSpecText(id: Int): String =
  "---\nstatus: Pending\n---\n\n# Subtask $id\n\n## Acceptance Criteria\n\n1. Subtask $id delivers its part.\n\n" +
    "## Implementation Details\n\nPlanned implementation details for subtask $id.\n"

internal object NoopGoalTestAgentRunLauncher : AgentRunLauncher {
  override fun launch(request: AgentRunLaunchRequest): AgentRunLaunchOutcome = error("Unexpected launch")
}

internal object GoalTestWorkflowGitOperations : WorkflowGitOperationsTestBase() {
  override fun repositoryOwnedPaths(repoRoot: Path): WorkflowGitNameListResult =
    WorkflowGitNameListResult.Listed(listOf("Main.kt"))

  override fun repositoryFingerprint(repoRoot: Path): WorkflowGitOperationResult =
    WorkflowGitOperationResult.Ok(value = "test-repository-fingerprint")

  override fun repositoryCheckpointFingerprint(
    repoRoot: Path,
    baseCommit: String?,
    headCommit: String,
    ownedPaths: List<String>,
  ): WorkflowGitOperationResult = repositoryFingerprint(repoRoot)

  override fun stagePaths(
    repoRoot: Path,
    paths: List<String>,
  ): WorkflowGitOperationResult = WorkflowGitOperationResult.Ok(value = "")

  override fun captureIndexState(
    repoRoot: Path,
    paths: List<String>,
  ): WorkflowGitIndexSnapshotResult = WorkflowGitIndexSnapshotResult.Captured(WorkflowGitIndexSnapshot.EMPTY)

  override fun restoreIndexState(
    repoRoot: Path,
    paths: List<String>,
    snapshot: WorkflowGitIndexSnapshot,
  ): WorkflowGitOperationResult = WorkflowGitOperationResult.Ok(value = "")

  override fun stagedPaths(repoRoot: Path): WorkflowGitNameListResult = WorkflowGitNameListResult.Listed(emptyList())

  override fun pathContentIdentities(
    repoRoot: Path,
    paths: List<String>,
  ): WorkflowPathContentIdentitiesResult =
    WorkflowPathContentIdentitiesResult.Resolved(identities = paths.associateWith { "identity" })

  override fun checkoutBranch(
    repoRoot: Path,
    branch: String,
    baseBranch: String?,
  ): WorkflowGitOperationResult = WorkflowGitOperationResult.Ok(value = branch)

  override fun branchExists(
    repoRoot: Path,
    branch: String,
  ): WorkflowGitOperationResult = WorkflowGitOperationResult.Ok(value = "true")

  override fun currentBranch(repoRoot: Path): WorkflowGitOperationResult = WorkflowGitOperationResult.Ok(value = "")

  override fun captureGoalSubtaskReviewBaseline(
    repoRoot: Path,
    expectedBranch: String,
  ): GoalSubtaskReviewBaselineResult =
    GoalSubtaskReviewBaselineResult(
      status = WorkflowGitOperationStatus.OK,
      baseline = GoalSubtaskReviewBaseline("0".repeat(40), emptyList()),
    )

  override fun buildGoalSubtaskReviewInput(
    repoRoot: Path,
    baseline: GoalSubtaskReviewBaseline,
    expectedBranch: String,
  ): Nothing = error("Goal review input is not used by this goal CLI fixture.")

  override fun recoverGoalSubtaskReviewBaseline(
    repoRoot: Path,
    request: GoalSubtaskReviewBaselineRecoveryRequest,
    expectedBranch: String,
  ): GoalSubtaskReviewBaselineResult =
    GoalSubtaskReviewBaselineResult(
      status = WorkflowGitOperationStatus.ERROR,
      error = "Goal review baseline recovery is not used by this goal CLI fixture.",
    )

  override fun createCommit(
    repoRoot: Path,
    message: String,
  ): WorkflowGitCommitResult = WorkflowGitCommitResult.Committed(commitSha = "test-commit")

  override fun headCommitSha(repoRoot: Path): WorkflowGitOperationResult =
    WorkflowGitOperationResult.Ok(value = "test-commit")

  override fun isCommitAncestor(
    repoRoot: Path,
    ancestorSha: String,
    descendantSha: String,
  ): WorkflowGitOperationResult = WorkflowGitOperationResult.Ok(value = "true")

  override fun validateBranchBase(
    repoRoot: Path,
    branch: String,
    expectedBaseBranch: String,
  ): WorkflowGitOperationResult = WorkflowGitOperationResult.Ok(value = expectedBaseBranch)

  override fun worktreeStatus(repoRoot: Path): WorkflowGitOperationResult = WorkflowGitOperationResult.Ok(value = "")

  override fun worktreeActivity(repoRoot: Path): WorkflowWorktreeActivityResult =
    WorkflowWorktreeActivityResult(
      status = WorkflowGitOperationStatus.OK,
      diffStat = GoalObservabilityDiffStat(filesChanged = 1, insertions = 2, deletions = 1),
    )

  override fun selectedDiffHunks(
    repoRoot: Path,
    request: WorkflowSelectedDiffHunksRequest,
  ): WorkflowSelectedDiffHunksResult =
    WorkflowSelectedDiffHunksResult(
      status = WorkflowGitOperationStatus.OK,
      selectedDiffHunks =
        GoalObservabilitySelectedDiffHunks(
          hunks =
            listOf(
              GoalObservabilitySelectedDiffHunk(
                path = request.paths.firstOrNull().orEmpty(),
                staged = false,
                header = "@@ -1 +1 @@",
                lines = listOf("-old", "+new"),
                truncated = false,
              ),
            ),
          truncated = false,
        ),
    )
}

internal class RecordingGoalTestWorkflowGitOperations : WorkflowGitOperations by GoalTestWorkflowGitOperations {
  val worktreeActivityRequests: MutableList<Path> = mutableListOf()
  val selectedDiffRequests: MutableList<WorkflowSelectedDiffHunksRequest> = mutableListOf()

  override fun worktreeActivity(repoRoot: Path): WorkflowWorktreeActivityResult {
    worktreeActivityRequests.add(repoRoot)
    return GoalTestWorkflowGitOperations.worktreeActivity(repoRoot)
  }

  override fun selectedDiffHunks(
    repoRoot: Path,
    request: WorkflowSelectedDiffHunksRequest,
  ): WorkflowSelectedDiffHunksResult {
    selectedDiffRequests += request
    return GoalTestWorkflowGitOperations.selectedDiffHunks(repoRoot, request)
  }
}

internal fun WorkflowSelectedDiffHunksRequest.limits(): Triple<Int, Int, Int> = Triple(maxHunks, maxLines, maxBytes)
