package skillbill.di.core

import skillbill.contracts.JsonCodec
import skillbill.contracts.projectstore.ProjectStoreLayout
import skillbill.engine.work.control.WorkCancellation
import skillbill.engine.work.control.model.WorkControlAction
import skillbill.engine.work.control.model.WorkControlRejection
import skillbill.engine.work.control.model.WorkControlResult
import skillbill.engine.work.control.model.WorkRunTarget
import skillbill.goalrunner.model.GoalRunnerControlState
import skillbill.idestatus.model.IdeStatusDisplayGroup
import skillbill.idestatus.model.IdeStatusLaunchScope
import skillbill.idestatus.model.IdeStatusLifecycleState
import skillbill.infrastructure.sqlite.ensureTestDatabase
import skillbill.infrastructure.sqlite.establishTemporarySchemaReadiness
import skillbill.model.EnvironmentContext
import skillbill.ports.featuretask.model.FeatureTaskRuntimeWorkerOwnership
import skillbill.ports.idestatus.model.IdeStatusWorkflowRegistration
import skillbill.ports.idestatus.model.StandaloneMonitorTarget
import skillbill.ports.idestatus.model.StandalonePhaseStatusRegistration
import skillbill.ports.idestatus.model.StandalonePhaseStatusUpdate
import skillbill.ports.idestatus.model.StandalonePhaseStatusUpdateResult
import skillbill.ports.idestatus.model.WorkBoardRequest
import skillbill.ports.idestatus.model.WorkDetailRequest
import skillbill.ports.idestatus.model.WorkLiveSection
import skillbill.ports.taskruntime.FeatureTaskRuntimeWorkerSupervisor
import skillbill.ports.taskruntime.NoopFeatureTaskRuntimeWorkerSupervisor
import skillbill.ports.taskruntime.model.FeatureTaskRuntimeProcessIdentity
import skillbill.ports.taskruntime.model.FeatureTaskRuntimeProcessInspection
import skillbill.ports.work.WorkRunLauncher
import skillbill.ports.work.model.WorkRunLaunchKind
import skillbill.ports.work.model.WorkRunLaunchRequest
import skillbill.ports.work.model.WorkRunLaunchResult
import skillbill.ports.work.model.WorkRunStopRequest
import skillbill.ports.work.model.WorkRunStopResult
import skillbill.ports.workflow.model.WorkflowFamily
import skillbill.ports.workflow.model.WorkflowStateRecord
import skillbill.workflow.decomposition.encodeDecompositionManifestWireMap
import skillbill.workflow.decomposition.intentFor
import skillbill.workflow.decomposition.model.DecompositionManifest
import skillbill.workflow.decomposition.model.DecompositionSubtask
import skillbill.workflow.decomposition.runtime.goalParentArtifactProjection
import skillbill.workflow.decomposition.withParentStatus
import skillbill.workflow.engine.model.DurableWorkflowArtifacts
import skillbill.workflow.model.WorkflowStatus
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimeWorkflowArtifactMap
import skillbill.workflow.taskruntime.model.qa.QaRunDetails
import skillbill.workflow.verify.FeatureVerifyWorkflowDefinition
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WorkRunControlsTest {
  @Test
  fun `detail run options use the supplied snapshot and refuse a changed store revision`() {
    establishTemporarySchemaReadiness("detail-run-options").use { fixture ->
      val component = component(fixture.tempDir, fixture.dbPath)
      component.databaseSession.transaction { it.standalonePhaseStatuses.register(registration(fixture.tempDir)) }
      val detail = assertNotNull(component.ideStatusService.detail(WorkDetailRequest("interrupted")).item)
      assertEquals(
        component.workControlService.options(target(component)),
        component.workControlService.options(detail),
      )
      component.databaseSession.transaction {
        it.standalonePhaseStatuses.register(
          registration(fixture.tempDir).copy(invocationId = "unrelated", executionId = "execution-unrelated"),
        )
      }
      assertNull(component.workControlService.options(detail))
    }
  }

  @Test
  fun `canceling an interrupted phase moves it into history and rejects late owner updates`() {
    establishTemporarySchemaReadiness("canceled-phase").use { fixture ->
      val launched = mutableListOf<WorkRunLaunchRequest>()
      val stopped = mutableListOf<WorkRunStopRequest>()
      val launcher =
        object : WorkRunLauncher {
          override fun stop(request: WorkRunStopRequest): WorkRunStopResult {
            stopped += request
            return WorkRunStopResult.NotRunning
          }

          override fun start(request: WorkRunLaunchRequest): WorkRunLaunchResult {
            launched += request
            return WorkRunLaunchResult.Started(fixture.tempDir.resolve("retry.log"))
          }
        }
      val component = component(fixture.tempDir, fixture.dbPath, launcher)
      val registered =
        component.databaseSession.transaction {
          it.standalonePhaseStatuses.register(registration(fixture.tempDir).copy(branchCorrelation = "HEAD"))
        }
      val target = target(component)
      assertEquals(WorkControlResult.Accepted, component.cancelRun(target))
      assertEquals(fixture.dbPath, stopped.single().databasePath)
      assertEquals("${registered.repositoryIdentity}:HEAD:monitor", stopped.single().ownershipKey)
      val canceled = assertNotNull(component.ideStatusService.detail(WorkDetailRequest("interrupted")).item)
      assertEquals(IdeStatusLifecycleState.CANCELED, canceled.snapshot.lifecycleState)
      assertNotNull(canceled.finishedAt)
      assertNull(canceled.liveSection)
      assertFalse(canceled.requiresAttention)
      val options = assertNotNull(component.workControlService.options(target(component)))
      assertTrue(options.canLaunch)
      assertFalse(options.canCancel)
      assertEquals("Run again", options.launchLabel)
      assertEquals(WorkControlResult.Accepted, component.launchRun(target(component), "codex"))
      assertEquals(1, launched.size)
      assertEquals(
        IdeStatusLifecycleState.CANCELED,
        component.ideStatusService.detail(WorkDetailRequest("interrupted")).item?.snapshot?.lifecycleState,
      )
      val late =
        component.databaseSession.transaction {
          it.standalonePhaseStatuses.update(
            StandalonePhaseStatusUpdate(
              registered.executionId,
              registered.statusRevision,
              registered.leaseOwner,
              registered.leaseGeneration,
              "active",
              "monitor",
              null,
              Instant.now(),
            ),
          )
        }
      assertEquals(StandalonePhaseStatusUpdateResult.TERMINAL_REGRESSION, late)
      val board = component.ideStatusService.projectBoardSelection(listOf("interrupted"), WorkBoardRequest())
      assertEquals(IdeStatusLifecycleState.CANCELED, board.items.single().snapshot.lifecycleState)
    }
  }

  @Test
  fun `keyless phase retries retain older blocked attempts in needs you and execution history`() {
    establishTemporarySchemaReadiness("phase-retry-group").use { fixture ->
      val component = component(fixture.tempDir, fixture.dbPath)
      component.databaseSession.transaction { unit ->
        val first = registration(fixture.tempDir).copy(lifecycleState = "blocked")
        unit.standalonePhaseStatuses.register(first)
        unit.standalonePhaseStatuses.register(
          first.copy(
            invocationId = "newest",
            executionId = "newest-execution",
            startedAt = first.startedAt.plusSeconds(1),
            lifecycleState = "paused",
          ),
        )
        unit.standalonePhaseStatuses.register(
          first.copy(
            invocationId = "other-branch",
            executionId = "other-execution",
            branchCorrelation = "other",
          ),
        )
      }
      val board =
        component.ideStatusService.projectBoardSelection(
          listOf("interrupted", "newest", "other-branch"),
          WorkBoardRequest(),
        )
      val old = board.items.single { it.snapshot.execution?.invocationId == "interrupted" }
      assertEquals(WorkLiveSection.NEEDS_YOU, old.liveSection)
      assertTrue(old.requiresAttention)
      assertNull(old.finishedAt)
      assertEquals(IdeStatusLifecycleState.BLOCKED, old.snapshot.lifecycleState)
      assertEquals(3, board.items.count { it.liveSection != null })
      val history = assertNotNull(component.ideStatusService.detail(WorkDetailRequest("newest")).item)
      assertEquals(setOf("newest", "interrupted"), history.executionHistory.map { it.invocationId }.toSet())
    }
  }

  @Test
  fun `completed QA retains details and finalization after a newer attempt starts`() {
    establishTemporarySchemaReadiness("phase-qa-details").use { fixture ->
      val component = component(fixture.tempDir, fixture.dbPath)
      val details = QaRunDetails(reportPath = "/qa/report.md", skipReason = "empty_scope")
      component.databaseSession.transaction {
        val registered =
          it.standalonePhaseStatuses.register(
            registration(fixture.tempDir).copy(
              phaseId = "qa",
              lifecycleState = "active",
              leaseExpiresAt = Instant.now().plusSeconds(3600),
              launchAgentId = "codex",
              launchArguments = listOf("phase", "qa", "--agent", "codex"),
            ),
          )
        it.standalonePhaseStatuses.update(
          StandalonePhaseStatusUpdate(
            executionId = registered.executionId,
            expectedRevision = registered.statusRevision,
            leaseOwner = registered.leaseOwner,
            leaseGeneration = registered.leaseGeneration,
            lifecycleState = "terminal",
            currentStep = "qa",
            currentActivity = null,
            updatedAt = Instant.now(),
            finishedAt = Instant.now(),
            terminalResult = "done",
            qaDetails = details,
          ),
        )
        it.standalonePhaseStatuses.register(
          registration(fixture.tempDir).copy(
            invocationId = "newer-qa",
            executionId = "newer-qa-execution",
            phaseId = "qa",
            lifecycleState = "active",
            startedAt = registered.startedAt.plusSeconds(1),
            leaseExpiresAt = Instant.now().plusSeconds(3600),
          ),
        )
      }

      val item = assertNotNull(component.ideStatusService.detail(WorkDetailRequest("interrupted")).item)

      assertEquals(details, item.executionHistory.single { it.invocationId == "interrupted" }.qaDetails)
      assertEquals(WorkLiveSection.NEEDS_YOU, item.liveSection)
      assertTrue(item.requiresAttention)
      assertNotNull(item.finishedAt)
      val options = assertNotNull(component.workControlService.options(target(component)))
      assertTrue(options.canFinalize)
      assertTrue(options.canLaunch)
    }
  }

  @Test
  fun `finalizing a completed standalone run retires its relaunch`() {
    establishTemporarySchemaReadiness("finalized-phase").use { fixture ->
      val component = component(fixture.tempDir, fixture.dbPath)
      component.databaseSession.transaction { unit ->
        val registered =
          unit.standalonePhaseStatuses.register(
            registration(fixture.tempDir).copy(
              phaseId = "validation",
              lifecycleState = "active",
              leaseExpiresAt = Instant.now().plusSeconds(3600),
              launchAgentId = "codex",
              launchArguments = listOf("phase", "validation", "--agent", "codex"),
            ),
          )
        unit.standalonePhaseStatuses.update(
          StandalonePhaseStatusUpdate(
            executionId = registered.executionId,
            expectedRevision = registered.statusRevision,
            leaseOwner = registered.leaseOwner,
            leaseGeneration = registered.leaseGeneration,
            lifecycleState = "terminal",
            currentStep = "validation",
            currentActivity = null,
            updatedAt = Instant.now(),
            finishedAt = Instant.now(),
            terminalResult = "passed",
          ),
        )
      }
      val target = target(component)
      val offered = assertNotNull(component.workControlService.options(target))
      assertTrue(offered.canLaunch)
      assertTrue(offered.canFinalize)

      assertEquals(
        WorkControlResult.Accepted,
        component.workControlService.request(target, WorkControlAction.FINALIZE),
      )

      val settled = assertNotNull(component.workControlService.options(target))
      assertFalse(settled.canLaunch)
      assertFalse(settled.canFinalize)
      assertEquals(
        WorkControlResult.Rejected(WorkControlRejection.INELIGIBLE_TARGET),
        component.launchRun(target, "codex"),
      )
    }
  }

  @Test
  fun `canceling an older historical attempt does not stop the harness for a newer invocation`() {
    establishTemporarySchemaReadiness("cancel-old-phase").use { fixture ->
      val stopped = mutableListOf<WorkRunStopRequest>()
      val launcher =
        object : WorkRunLauncher {
          override fun start(request: WorkRunLaunchRequest) = WorkRunLaunchResult.Refused("Unused")

          override fun stop(request: WorkRunStopRequest): WorkRunStopResult {
            stopped += request
            return WorkRunStopResult.Stopped
          }
        }
      val component = component(fixture.tempDir, fixture.dbPath, launcher)
      component.databaseSession.transaction { unit ->
        val old = registration(fixture.tempDir).copy(lifecycleState = "blocked")
        unit.standalonePhaseStatuses.register(old)
        unit.standalonePhaseStatuses.register(
          old.copy(
            invocationId = "newer",
            executionId = "newer-execution",
            startedAt = old.startedAt.plusSeconds(1),
            lifecycleState = "active",
            leaseExpiresAt = Instant.now().plusSeconds(60),
          ),
        )
      }
      assertEquals(WorkControlResult.Accepted, component.cancelRun(target(component)))
      assertTrue(stopped.isEmpty())
      assertEquals(
        IdeStatusLifecycleState.ACTIVE,
        component.ideStatusService.detail(WorkDetailRequest("newer")).item?.snapshot?.lifecycleState,
      )
    }
  }

  @Test
  fun `run again keeps recorded intake allows another harness and refuses stale targets or a changed branch`() {
    establishTemporarySchemaReadiness("repeat-phase").use { fixture ->
      Files.createDirectory(fixture.tempDir.resolve(".git"))
      Files.writeString(fixture.tempDir.resolve(".git/HEAD"), "ref: refs/heads/main\n")
      val launched = mutableListOf<WorkRunLaunchRequest>()
      val launcher =
        object : WorkRunLauncher {
          override fun stop(request: WorkRunStopRequest) = WorkRunStopResult.NotRunning

          override fun start(request: WorkRunLaunchRequest): WorkRunLaunchResult {
            launched += request
            return WorkRunLaunchResult.Started(fixture.tempDir.resolve("run.log"))
          }
        }
      val component = component(fixture.tempDir, fixture.dbPath, launcher)
      val arguments = listOf("phase", "monitor", "https://example.test/pr/42", "--agent", "claude")
      component.databaseSession.transaction {
        it.standalonePhaseStatuses.register(
          registration(fixture.tempDir).copy(
            launchAgentId = "claude",
            launchArguments = arguments,
            agentEnvironment = mapOf("CLAUDE_CONFIG_DIR" to "/home/dev/.claude-work"),
          ),
        )
      }
      val target = target(component)
      val options = assertNotNull(component.workControlService.options(target))
      assertTrue(options.canLaunch)
      assertEquals("claude", options.agentId)
      assertEquals("Run again", options.launchLabel)
      assertEquals(WorkControlResult.Accepted, component.launchRun(target, "codex"))
      assertEquals(
        WorkControlResult.Rejected(WorkControlRejection.CHANGED_TARGET),
        component.launchRun(
          target.copy(repositoryRoot = fixture.tempDir.resolve("other-repository")),
          null,
        ),
      )
      val command = assertIs<WorkRunLaunchKind.RuntimeCommand>(launched.single().kind)
      assertEquals(listOf("phase", "monitor", "https://example.test/pr/42", "--agent", "codex"), command.arguments)
      assertEquals(fixture.dbPath, launched.single().databasePath)
      assertEquals(mapOf("CLAUDE_CONFIG_DIR" to "/home/dev/.claude-work"), launched.single().agentEnvironment)
      Files.writeString(fixture.tempDir.resolve(".git/HEAD"), "ref: refs/heads/other\n")
      assertEquals(
        WorkControlResult.Rejected(WorkControlRejection.BRANCH_CHANGED),
        component.launchRun(target, null),
      )
      assertEquals(
        WorkControlResult.Rejected(WorkControlRejection.CHANGED_TARGET),
        component.launchRun(target.copy(statusRevision = "0"), null),
      )
      assertNotNull(component.workControlService.options(target.copy(updatedAt = target.updatedAt.minusSeconds(10))))
      Files.delete(fixture.tempDir.resolve(".git/HEAD"))
      assertEquals(
        WorkControlResult.Rejected(WorkControlRejection.BRANCH_UNAVAILABLE),
        component.launchRun(target, null),
      )
      assertEquals(1, launched.size)
      Files.writeString(fixture.tempDir.resolve(".git/HEAD"), "ref: refs/heads/main\n")
      assertEquals(WorkControlResult.Accepted, component.launchRun(target, null))
      assertEquals(2, launched.size)
    }
  }

  @Test
  fun `a standalone plan resumes through the runtime without a chat approval channel`() {
    establishTemporarySchemaReadiness("standalone-plan-resume").use { fixture ->
      Files.createDirectory(fixture.tempDir.resolve(".git"))
      Files.writeString(fixture.tempDir.resolve(".git/HEAD"), "ref: refs/heads/main\n")
      val launched = mutableListOf<WorkRunLaunchRequest>()
      val launcher =
        object : WorkRunLauncher {
          override fun stop(request: WorkRunStopRequest) = WorkRunStopResult.NotRunning

          override fun start(request: WorkRunLaunchRequest): WorkRunLaunchResult {
            launched += request
            return WorkRunLaunchResult.Started(fixture.tempDir.resolve("run.log"))
          }
        }
      val component = component(fixture.tempDir, fixture.dbPath, launcher)
      component.databaseSession.transaction {
        it.standalonePhaseStatuses.register(
          registration(fixture.tempDir).copy(
            issueKey = "SKILL-9",
            phaseId = "plan",
            launchAgentId = "claude",
            launchArguments = listOf("phase", "plan", "SKILL-9", "--agent", "claude"),
          ),
        )
      }

      assertEquals(WorkControlResult.Accepted, component.launchRun(target(component), "codex"))
      val command = assertIs<WorkRunLaunchKind.RuntimeCommand>(launched.single().kind)
      assertEquals(listOf("phase", "plan", "SKILL-9", "--agent", "codex"), command.arguments)
      assertEquals(fixture.dbPath, launched.single().databasePath)
    }
  }

  @Test
  fun `a plan the goal command started shows with goals and relaunches the goal`() {
    establishTemporarySchemaReadiness("goal-plan").use { fixture ->
      Files.createDirectory(fixture.tempDir.resolve(".git"))
      Files.writeString(fixture.tempDir.resolve(".git/HEAD"), "ref: refs/heads/main\n")
      val launched = mutableListOf<WorkRunLaunchRequest>()
      val launcher =
        object : WorkRunLauncher {
          override fun stop(request: WorkRunStopRequest) = WorkRunStopResult.NotRunning

          override fun start(request: WorkRunLaunchRequest): WorkRunLaunchResult {
            launched += request
            return WorkRunLaunchResult.Started(fixture.tempDir.resolve("run.log"))
          }
        }
      val component = component(fixture.tempDir, fixture.dbPath, launcher)
      component.databaseSession.transaction {
        it.standalonePhaseStatuses.register(
          registration(fixture.tempDir).copy(
            issueKey = "SKILL-9",
            phaseId = "plan",
            launchAgentId = "claude",
            launchArguments = listOf("goal", "SKILL-9 Render answers", "--agent", "claude"),
            launchScope = IdeStatusLaunchScope.GOAL,
          ),
        )
      }
      val item = assertNotNull(component.ideStatusService.detail(WorkDetailRequest("interrupted")).item)
      assertEquals(IdeStatusLaunchScope.GOAL, item.snapshot.execution?.launchScope)
      assertEquals(IdeStatusDisplayGroup.GOALS, item.displayGroup)

      assertEquals(WorkControlResult.Accepted, component.launchRun(target(component), "codex"))
      val command = assertIs<WorkRunLaunchKind.RuntimeCommand>(launched.single().kind)
      assertEquals(
        listOf(
          "goal",
          "SKILL-9 Render answers",
          "--agent",
          "codex",
          "--repo-root",
          fixture.tempDir.toString(),
          "--no-live-output",
        ),
        command.arguments,
      )
    }
  }

  @Test
  fun `a standalone plan reports a goal started for its issue in the same repository`() {
    establishTemporarySchemaReadiness("plan-goal-started").use { fixture ->
      val component = component(fixture.tempDir, fixture.dbPath)
      val identity = "repo-root-realpath-v1:${fixture.tempDir.toRealPath()}"
      component.databaseSession.transaction {
        it.standalonePhaseStatuses.register(registration(fixture.tempDir).copy(issueKey = "SKILL-9", phaseId = "plan"))
      }
      assertFalse(assertNotNull(component.workControlService.options(target(component))).goalStarted)

      insertGoalParent(fixture.dbPath, "other-repo-goal", "SKILL-9")
      component.databaseSession.transaction { unit ->
        unit.goalRunnerControls.persistControlState(
          "other-repo-goal",
          GoalRunnerControlState(repositoryIdentity = "repo-root-realpath-v1:/elsewhere"),
        )
      }
      assertFalse(assertNotNull(component.workControlService.options(target(component))).goalStarted)

      insertGoalParent(fixture.dbPath, "plan-goal", "SKILL-9")
      component.databaseSession.transaction { unit ->
        unit.goalRunnerControls.persistControlState("plan-goal", GoalRunnerControlState(repositoryIdentity = identity))
      }
      assertTrue(assertNotNull(component.workControlService.options(target(component))).goalStarted)
    }
  }

  private fun insertGoalParent(
    database: Path,
    workflowId: String,
    issueKey: String,
  ) {
    val spec = "${ProjectStoreLayout.SPECS_PREFIX}$issueKey"
    val subtask = DecompositionSubtask(id = 1, name = "Phase", specPath = "$spec/spec_subtask_1.md", status = "pending")
    val manifest =
      DecompositionManifest(
        issueKey = issueKey,
        featureName = "Goal for $issueKey",
        parentSpecPath = "$spec/spec.md",
        baseBranch = "main",
        featureBranch = "feat/$issueKey",
        currentSubtaskIntent = intentFor(subtask.id, subtask.status),
        subtasks = listOf(subtask),
      ).withParentStatus()
    val artifacts =
      goalParentArtifactProjection(
        DurableWorkflowArtifacts.EMPTY,
        FeatureTaskRuntimeWorkflowArtifactMap.from(encodeDecompositionManifestWireMap(manifest)),
      )
    ensureTestDatabase(database).use { connection ->
      connection.prepareStatement(
        """
        INSERT INTO feature_task_workflows (workflow_id, mode, contract_version, issue_key, steps_json, artifacts_json)
        VALUES (?, 'runtime', '1', ?, '[]', ?)
        """.trimIndent(),
      ).use { statement ->
        statement.setString(1, workflowId)
        statement.setString(2, issueKey)
        statement.setString(3, JsonCodec.mapToJsonString(artifacts))
        statement.executeUpdate()
      }
    }
  }

  @Test
  fun `older monitor entries require an explicit agent choice`() {
    establishTemporarySchemaReadiness("legacy-phase").use { fixture ->
      val component = component(fixture.tempDir, fixture.dbPath)
      component.databaseSession.transaction {
        it.standalonePhaseStatuses.register(registration(fixture.tempDir).copy(branchCorrelation = "HEAD"))
      }
      val target = target(component)
      assertNull(component.workControlService.options(target)?.agentId)
      assertEquals(
        WorkControlResult.Rejected(WorkControlRejection.AGENT_REQUIRED),
        component.launchRun(target, null),
      )
    }
  }

  @Test
  fun `canceling a failed historical goal removes unfinished subtasks from the live board`() {
    establishTemporarySchemaReadiness("canceled-goal").use { fixture ->
      ensureTestDatabase(fixture.dbPath).use { connection ->
        connection.prepareStatement(
          """
          INSERT INTO goal_issue_progress (parent_workflow_id, issue_key, first_started_at, status, last_activity_at)
          VALUES ('old-goal', 'SKILL-777', ?, 'failed', ?)
          """.trimIndent(),
        ).use { statement ->
          val time = Instant.now().minusSeconds(60).toString()
          statement.setString(1, time)
          statement.setString(2, time)
          statement.executeUpdate()
        }
      }
      val launches = mutableListOf<WorkRunLaunchRequest>()
      val launcher =
        object : WorkRunLauncher {
          override fun stop(request: WorkRunStopRequest) = WorkRunStopResult.NotRunning

          override fun start(request: WorkRunLaunchRequest): WorkRunLaunchResult {
            launches += request
            return WorkRunLaunchResult.Started(fixture.tempDir.resolve("goal.log"))
          }
        }
      val component = component(fixture.tempDir, fixture.dbPath, launcher)
      component.databaseSession.transaction { unit ->
        unit.goalRunnerControls.persistControlState(
          "old-goal",
          GoalRunnerControlState(
            repositoryIdentity = "repo-root-realpath-v1:${fixture.tempDir.toRealPath()}",
          ),
        )
      }
      val item = assertNotNull(component.ideStatusService.detail(WorkDetailRequest("old-goal")).item)
      val target =
        WorkRunTarget(
          "old-goal",
          item.snapshot.repositoryIdentity,
          Path.of(item.repositoryRoot),
          item.snapshot.execution?.executionId,
          item.snapshot.execution?.statusRevision,
          item.snapshot.updatedAt,
          assertNotNull(item.databaseFileObservation),
        )
      assertEquals(WorkControlResult.Accepted, component.cancelRun(target))
      val canceled = assertNotNull(component.ideStatusService.detail(WorkDetailRequest("old-goal")).item)
      assertEquals(IdeStatusLifecycleState.CANCELED, canceled.snapshot.lifecycleState)
      assertNull(canceled.liveSection)
      assertNotNull(canceled.finishedAt)
      assertFalse(canceled.requiresAttention)
      val restart =
        target.copy(
          updatedAt = canceled.snapshot.updatedAt,
          statusRevision = canceled.snapshot.execution?.statusRevision,
        )
      assertTrue(assertNotNull(component.workControlService.options(restart)).canLaunch)
      assertEquals(WorkControlResult.Accepted, component.launchRun(restart, "codex"))
      assertEquals(1, launches.size)
      assertTrue(
        component.ideStatusService.detail(WorkDetailRequest("old-goal")).item?.snapshot?.lifecycleState !=
          IdeStatusLifecycleState.CANCELED,
      )
    }
  }

  @Test
  fun `cancel accepts a target the runner heartbeat made older but refuses one for another execution`() {
    establishTemporarySchemaReadiness("heartbeat-cancel").use { fixture ->
      ensureTestDatabase(fixture.dbPath).use { connection ->
        connection.prepareStatement(
          """
          INSERT INTO goal_issue_progress (parent_workflow_id, issue_key, first_started_at, status, last_activity_at)
          VALUES ('beating-goal', 'SKILL-778', ?, 'failed', ?)
          """.trimIndent(),
        ).use { statement ->
          val time = Instant.now().minusSeconds(60).toString()
          statement.setString(1, time)
          statement.setString(2, time)
          statement.executeUpdate()
        }
      }
      val launcher =
        object : WorkRunLauncher {
          override fun stop(request: WorkRunStopRequest) = WorkRunStopResult.NotRunning

          override fun start(request: WorkRunLaunchRequest): WorkRunLaunchResult =
            WorkRunLaunchResult.Started(fixture.tempDir.resolve("goal.log"))
        }
      val component = component(fixture.tempDir, fixture.dbPath, launcher)
      component.databaseSession.transaction { unit ->
        unit.goalRunnerControls.persistControlState(
          "beating-goal",
          GoalRunnerControlState(
            repositoryIdentity = "repo-root-realpath-v1:${fixture.tempDir.toRealPath()}",
          ),
        )
      }
      val item = assertNotNull(component.ideStatusService.detail(WorkDetailRequest("beating-goal")).item)
      val rendered =
        WorkRunTarget(
          "beating-goal",
          item.snapshot.repositoryIdentity,
          Path.of(item.repositoryRoot),
          item.snapshot.execution?.executionId,
          item.snapshot.execution?.statusRevision,
          item.snapshot.updatedAt.minusSeconds(10),
          assertNotNull(item.databaseFileObservation),
        )
      assertEquals(
        WorkControlResult.Rejected(WorkControlRejection.CHANGED_TARGET),
        component.cancelRun(rendered.copy(executionId = "another-execution")),
      )
      assertEquals(WorkControlResult.Accepted, component.cancelRun(rendered))
      assertEquals(
        IdeStatusLifecycleState.CANCELED,
        component.ideStatusService.detail(WorkDetailRequest("beating-goal")).item?.snapshot?.lifecycleState,
      )
    }
  }

  @Test
  fun `canceling an active verify operation abandons it although agent-driven verify runs hold no worker lease`() {
    establishTemporarySchemaReadiness("verify-cancel").use { fixture ->
      val launcher =
        object : WorkRunLauncher {
          override fun stop(request: WorkRunStopRequest) = WorkRunStopResult.NotRunning

          override fun start(request: WorkRunLaunchRequest): WorkRunLaunchResult =
            WorkRunLaunchResult.Started(fixture.tempDir.resolve("verify.log"))
        }
      val component = component(fixture.tempDir, fixture.dbPath, launcher)
      val identity = "repo-root-realpath-v1:${fixture.tempDir.toRealPath()}"
      val now = Instant.now().minusSeconds(60)
      component.databaseSession.transaction { unit ->
        unit.workflowStates.saveRecord(WorkflowFamily.VERIFY, runningVerifyRecord("wfv-open", now))
        unit.standalonePhaseStatuses.registerWorkflow(
          IdeStatusWorkflowRegistration(
            identity,
            "feat/SKILL-779-verify",
            "SKILL-779",
            "wfv-open",
            "verify-invocation",
            "verify-execution",
            "active",
            now,
          ),
        )
      }
      val item = assertNotNull(component.ideStatusService.detail(WorkDetailRequest("wfv-open")).item)
      assertEquals(IdeStatusLifecycleState.ACTIVE, item.snapshot.lifecycleState)
      val target =
        WorkRunTarget(
          "wfv-open",
          item.snapshot.repositoryIdentity,
          Path.of(item.repositoryRoot),
          item.snapshot.execution?.executionId,
          item.snapshot.execution?.statusRevision,
          item.snapshot.updatedAt,
          assertNotNull(item.databaseFileObservation),
        )
      assertTrue(assertNotNull(component.workControlService.options(target)).canCancel)
      assertEquals(WorkControlResult.Accepted, component.cancelRun(target))
      val canceled = assertNotNull(component.ideStatusService.detail(WorkDetailRequest("wfv-open")).item)
      assertEquals(IdeStatusLifecycleState.CANCELED, canceled.snapshot.lifecycleState)
      assertNull(canceled.liveSection)
      assertEquals(
        WorkflowStatus.ABANDONED,
        component.databaseSession.read { it.workflowStates.get(WorkflowFamily.VERIFY, "wfv-open") }?.workflowStatus,
      )
    }
  }

  @Test
  fun `an existing goal resumes through the runtime command and ignores the branch its execution was registered on`() {
    establishTemporarySchemaReadiness("goal-other-branch").use { fixture ->
      Files.createDirectory(fixture.tempDir.resolve(".git"))
      Files.writeString(fixture.tempDir.resolve(".git/HEAD"), "ref: refs/heads/feat/SKILL-777-goal-branch\n")
      ensureTestDatabase(fixture.dbPath).use { connection ->
        connection.prepareStatement(
          """
          INSERT INTO goal_issue_progress (parent_workflow_id, issue_key, first_started_at, status, last_activity_at)
          VALUES ('stacked-goal', 'SKILL-777', ?, 'failed', ?)
          """.trimIndent(),
        ).use { statement ->
          val time = Instant.now().minusSeconds(60).toString()
          statement.setString(1, time)
          statement.setString(2, time)
          statement.executeUpdate()
        }
      }
      val launches = mutableListOf<WorkRunLaunchRequest>()
      val launcher =
        object : WorkRunLauncher {
          override fun stop(request: WorkRunStopRequest) = WorkRunStopResult.NotRunning

          override fun start(request: WorkRunLaunchRequest): WorkRunLaunchResult {
            launches += request
            return WorkRunLaunchResult.Started(fixture.tempDir.resolve("goal.log"))
          }
        }
      val component = component(fixture.tempDir, fixture.dbPath, launcher)
      val identity = "repo-root-realpath-v1:${fixture.tempDir.toRealPath()}"
      component.databaseSession.transaction { unit ->
        unit.goalRunnerControls.persistControlState(
          "stacked-goal",
          GoalRunnerControlState(repositoryIdentity = identity),
        )
        unit.standalonePhaseStatuses.registerWorkflow(
          IdeStatusWorkflowRegistration(
            identity,
            "feat/OTHER-1-launched-from-here",
            "SKILL-777",
            "stacked-goal",
            "goal-invocation",
            "goal-execution",
            "active",
            Instant.now().minusSeconds(120),
          ),
        )
      }
      val item = assertNotNull(component.ideStatusService.detail(WorkDetailRequest("stacked-goal")).item)
      assertEquals("goal-execution", item.snapshot.execution?.executionId)
      val target =
        WorkRunTarget(
          "stacked-goal",
          item.snapshot.repositoryIdentity,
          Path.of(item.repositoryRoot),
          item.snapshot.execution?.executionId,
          item.snapshot.execution?.statusRevision,
          item.snapshot.updatedAt,
          assertNotNull(item.databaseFileObservation),
        )
      assertEquals(WorkControlResult.Accepted, component.launchRun(target, "codex"))
      assertEquals(
        WorkRunLaunchKind.RuntimeCommand(
          listOf("goal", "SKILL-777", "--agent", "codex", "--repo-root", item.repositoryRoot, "--no-live-output"),
        ),
        launches.single().kind,
      )
    }
  }

  @Test
  fun `goal relaunch carries the agent home recorded by the latest parent workflow execution`() {
    establishTemporarySchemaReadiness("goal-agent-environment").use { fixture ->
      ensureTestDatabase(fixture.dbPath).use { connection ->
        connection.prepareStatement(
          """
          INSERT INTO goal_issue_progress (parent_workflow_id, issue_key, first_started_at, status, last_activity_at)
          VALUES ('env-goal', 'SKILL-778', ?, 'failed', ?)
          """.trimIndent(),
        ).use { statement ->
          val time = Instant.now().minusSeconds(60).toString()
          statement.setString(1, time)
          statement.setString(2, time)
          statement.executeUpdate()
        }
      }
      val launches = mutableListOf<WorkRunLaunchRequest>()
      val launcher =
        object : WorkRunLauncher {
          override fun stop(request: WorkRunStopRequest) = WorkRunStopResult.NotRunning

          override fun start(request: WorkRunLaunchRequest): WorkRunLaunchResult {
            launches += request
            return WorkRunLaunchResult.Started(fixture.tempDir.resolve("goal.log"))
          }
        }
      val component = component(fixture.tempDir, fixture.dbPath, launcher)
      val repository = "repo-root-realpath-v1:${fixture.tempDir.toRealPath()}"
      val started = Instant.now().minusSeconds(50)
      val first =
        IdeStatusWorkflowRegistration(
          repository, "HEAD", "SKILL-778", "env-goal", "goal-run-1", "goal-execution-1", "active", started,
          mapOf("CLAUDE_CONFIG_DIR" to "/home/dev/.claude-old"),
        )
      component.databaseSession.transaction { unit ->
        unit.goalRunnerControls.persistControlState("env-goal", GoalRunnerControlState(repositoryIdentity = repository))
        unit.standalonePhaseStatuses.registerWorkflow(first)
        unit.standalonePhaseStatuses.registerWorkflow(
          first.copy(
            invocationId = "goal-run-2",
            executionId = "goal-execution-2",
            startedAt = started.plusSeconds(1),
            agentEnvironment = mapOf("CLAUDE_CONFIG_DIR" to "/home/dev/.claude-work"),
          ),
        )
      }
      val item = assertNotNull(component.ideStatusService.detail(WorkDetailRequest("env-goal")).item)
      val target =
        WorkRunTarget(
          "env-goal",
          item.snapshot.repositoryIdentity,
          Path.of(item.repositoryRoot),
          item.snapshot.execution?.executionId,
          item.snapshot.execution?.statusRevision,
          item.snapshot.updatedAt,
          assertNotNull(item.databaseFileObservation),
        )
      assertEquals(WorkControlResult.Accepted, component.launchRun(target, "claude"))
      assertEquals(mapOf("CLAUDE_CONFIG_DIR" to "/home/dev/.claude-work"), launches.single().agentEnvironment)
    }
  }

  @Test
  fun `cancel terminates only the exact recorded owner and refuses an identity mismatch`() {
    listOf(
      FeatureTaskRuntimeProcessInspection.ExactLive,
      FeatureTaskRuntimeProcessInspection.PidReused,
    ).forEach { initial ->
      establishTemporarySchemaReadiness("owned-phase-cancel").use { fixture ->
        val component = component(fixture.tempDir, fixture.dbPath)
        component.databaseSession.transaction { unit ->
          unit.standalonePhaseStatuses.register(
            registration(fixture.tempDir).copy(
              processOwner = FeatureTaskRuntimeProcessIdentity("fixture-host", "fixture-boot", 999999, "fixture-birth"),
            ),
          )
        }
        var inspection: FeatureTaskRuntimeProcessInspection = initial
        var stopped = 0
        val supervisor =
          object : FeatureTaskRuntimeWorkerSupervisor by NoopFeatureTaskRuntimeWorkerSupervisor {
            override fun inspect(ownership: FeatureTaskRuntimeWorkerOwnership) = inspection

            override fun terminateGracefully(ownership: FeatureTaskRuntimeWorkerOwnership): Boolean {
              assertEquals("owner", ownership.ownerToken)
              assertEquals("fixture-birth", ownership.processBirthToken)
              stopped++
              inspection = FeatureTaskRuntimeProcessInspection.NotRunning
              return true
            }
          }
        val cancellation =
          WorkCancellation(
            component.databaseSession,
            component.ideStatusService,
            component.goalRunnerStatusService,
            supervisor,
            Clock.systemUTC(),
            object : WorkRunLauncher {
              override fun start(request: WorkRunLaunchRequest) = WorkRunLaunchResult.Refused("Unused")

              override fun stop(request: WorkRunStopRequest) = WorkRunStopResult.NotRunning
            },
          )
        val result = cancellation.cancel(target(component))
        if (initial == FeatureTaskRuntimeProcessInspection.ExactLive) {
          assertEquals(WorkControlResult.Accepted, result)
          assertEquals(1, stopped)
          assertEquals(
            IdeStatusLifecycleState.CANCELED,
            component.ideStatusService.detail(WorkDetailRequest("interrupted")).item?.snapshot?.lifecycleState,
          )
        } else {
          assertEquals(WorkControlResult.Rejected(WorkControlRejection.PID_REUSED), result)
          assertEquals(0, stopped)
          assertEquals(
            IdeStatusLifecycleState.PAUSED,
            component.ideStatusService.detail(WorkDetailRequest("interrupted")).item?.snapshot?.lifecycleState,
          )
        }
      }
    }
  }

  @Test
  fun `pull request monitors replay their exact url from any checkout and cancel only their own invocation`() {
    establishTemporarySchemaReadiness("monitor-target-controls").use { fixture ->
      Files.createDirectory(fixture.tempDir.resolve(".git"))
      Files.writeString(fixture.tempDir.resolve(".git/HEAD"), "ref: refs/heads/feat/OTHER-1-unrelated\n")
      val launched = mutableListOf<WorkRunLaunchRequest>()
      val stopped = mutableListOf<WorkRunStopRequest>()
      val launcher =
        object : WorkRunLauncher {
          override fun stop(request: WorkRunStopRequest): WorkRunStopResult {
            stopped += request
            return WorkRunStopResult.Stopped
          }

          override fun start(request: WorkRunLaunchRequest): WorkRunLaunchResult {
            launched += request
            return WorkRunLaunchResult.Started(fixture.tempDir.resolve("monitor.log"))
          }
        }
      val component = component(fixture.tempDir, fixture.dbPath, launcher)
      val repository = "repo-root-realpath-v1:${fixture.tempDir.toRealPath()}"
      component.databaseSession.transaction { unit ->
        listOf(7, 8).forEach { number ->
          unit.standalonePhaseStatuses.register(
            registration(fixture.tempDir).copy(
              branchCorrelation = "feat/SKILL-427-shared",
              invocationId = "monitor-$number",
              executionId = "execution-$number",
              launchAgentId = "claude",
              launchArguments = listOf("phase", "monitor", "https://example.test/stale", "--agent", "claude"),
              monitorTarget =
                StandaloneMonitorTarget(
                  pullRequestKey = "github.com/acme/repo#$number",
                  pullRequestUrl = "https://github.com/acme/repo/pull/$number",
                  headRepository = "github.com/fork-$number/repo",
                  headRef = "feat/SKILL-427-shared",
                  callerRoot = fixture.tempDir.toString(),
                  worktreePath = "/wt/$number",
                ),
            ),
          )
        }
      }

      fun targetFor(invocation: String): WorkRunTarget {
        val item = assertNotNull(component.ideStatusService.detail(WorkDetailRequest(invocation)).item)
        return WorkRunTarget(
          invocation,
          item.snapshot.repositoryIdentity,
          Path.of(item.repositoryRoot),
          item.snapshot.execution?.executionId,
          item.snapshot.execution?.statusRevision,
          item.snapshot.updatedAt,
          assertNotNull(item.databaseFileObservation),
        )
      }

      assertEquals(WorkControlResult.Accepted, component.launchRun(targetFor("monitor-7"), "codex"))
      val command = assertIs<WorkRunLaunchKind.RuntimeCommand>(launched.single().kind)
      assertEquals(
        listOf("phase", "monitor", "https://github.com/acme/repo/pull/7", "--agent", "codex"),
        command.arguments,
      )

      assertEquals(WorkControlResult.Accepted, component.cancelRun(targetFor("monitor-8")))
      assertEquals("$repository:github.com/acme/repo#8:monitor", stopped.single().ownershipKey)
      assertEquals(
        IdeStatusLifecycleState.CANCELED,
        component.ideStatusService.detail(WorkDetailRequest("monitor-8")).item?.snapshot?.lifecycleState,
      )
      assertEquals(
        IdeStatusLifecycleState.PAUSED,
        component.ideStatusService.detail(WorkDetailRequest("monitor-7")).item?.snapshot?.lifecycleState,
      )
    }
  }

  @Test
  fun `a branch monitor rerun launches its branch selector instead of an unparsable tree url`() {
    establishTemporarySchemaReadiness("monitor-branch-target-controls").use { fixture ->
      Files.createDirectory(fixture.tempDir.resolve(".git"))
      Files.writeString(fixture.tempDir.resolve(".git/HEAD"), "ref: refs/heads/feat/OTHER-1-unrelated\n")
      val launched = mutableListOf<WorkRunLaunchRequest>()
      val launcher =
        object : WorkRunLauncher {
          override fun stop(request: WorkRunStopRequest): WorkRunStopResult = WorkRunStopResult.Stopped

          override fun start(request: WorkRunLaunchRequest): WorkRunLaunchResult {
            launched += request
            return WorkRunLaunchResult.Started(fixture.tempDir.resolve("monitor.log"))
          }
        }
      val component = component(fixture.tempDir, fixture.dbPath, launcher)
      component.databaseSession.transaction { unit ->
        unit.standalonePhaseStatuses.register(
          registration(fixture.tempDir).copy(
            branchCorrelation = "main",
            invocationId = "monitor-main",
            executionId = "execution-main",
            launchAgentId = "claude",
            monitorTarget =
              StandaloneMonitorTarget(
                pullRequestKey = "github.com/acme/repo@main",
                pullRequestUrl = "https://github.com/acme/repo/tree/main",
                headRepository = "github.com/acme/repo",
                headRef = "main",
                callerRoot = fixture.tempDir.toString(),
                worktreePath = null,
              ),
          ),
        )
      }
      val item = assertNotNull(component.ideStatusService.detail(WorkDetailRequest("monitor-main")).item)
      val target =
        WorkRunTarget(
          "monitor-main",
          item.snapshot.repositoryIdentity,
          Path.of(item.repositoryRoot),
          item.snapshot.execution?.executionId,
          item.snapshot.execution?.statusRevision,
          item.snapshot.updatedAt,
          assertNotNull(item.databaseFileObservation),
        )

      assertEquals(WorkControlResult.Accepted, component.launchRun(target, "codex"))

      val command = assertIs<WorkRunLaunchKind.RuntimeCommand>(launched.single().kind)
      assertEquals(listOf("phase", "monitor", "branch:main", "--agent", "codex"), command.arguments)
    }
  }

  private fun RuntimeComponent.launchRun(
    target: WorkRunTarget,
    agent: String?,
  ) = workControlService.request(target, WorkControlAction.LAUNCH, agent)

  private fun RuntimeComponent.cancelRun(target: WorkRunTarget) =
    workControlService.request(target, WorkControlAction.CANCEL)

  private fun component(
    home: Path,
    database: Path,
    launcher: WorkRunLauncher? = null,
  ): RuntimeComponent =
    RuntimeComponent::class.create(
      RuntimeContext(
        EnvironmentContext(database.toString(), environment = emptyMap(), userHome = home, repositoryRoot = home),
        TransportContext(),
        WorkflowOpsContext(),
        OptionalCallbacks(workRunLauncher = launcher),
      ),
    )

  private fun runningVerifyRecord(
    workflowId: String,
    at: Instant,
  ): WorkflowStateRecord {
    val steps = FeatureVerifyWorkflowDefinition.definition.stepIds
    val stepsJson =
      steps.mapIndexed { index, step ->
        val status =
          when (index) {
            0 -> "completed"
            1 -> "running"
            else -> "pending"
          }
        """{"step_id":"$step","status":"$status","attempt_count":1}"""
      }.joinToString(prefix = "[", postfix = "]")
    return WorkflowStateRecord(
      workflowId = workflowId,
      sessionId = "session-$workflowId",
      workflowName = "bill-feature-verify",
      contractVersion = "0.1",
      workflowStatus = WorkflowStatus.RUNNING.wireValue,
      currentStepId = steps[1],
      stepsJson = stepsJson,
      artifactsJson = "{}",
      startedAt = at.toString(),
      updatedAt = at.toString(),
      finishedAt = null,
      issueKey = "SKILL-779",
    )
  }

  private fun registration(root: Path): StandalonePhaseStatusRegistration {
    val now = Instant.now().minusSeconds(30)
    return StandalonePhaseStatusRegistration(
      "repo-root-realpath-v1:${root.toRealPath()}", "main", null, null,
      "interrupted", "monitor", "execution-interrupted", "paused", "runner_interrupted", now, "owner", 1, now,
    )
  }

  private fun target(component: RuntimeComponent): WorkRunTarget {
    val item = assertNotNull(component.ideStatusService.detail(WorkDetailRequest("interrupted")).item)
    return WorkRunTarget(
      "interrupted",
      item.snapshot.repositoryIdentity,
      Path.of(item.repositoryRoot),
      item.snapshot.execution?.executionId,
      item.snapshot.execution?.statusRevision,
      item.snapshot.updatedAt,
      assertNotNull(item.databaseFileObservation),
    )
  }
}
