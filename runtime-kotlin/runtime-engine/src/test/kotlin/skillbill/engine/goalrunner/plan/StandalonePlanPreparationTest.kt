package skillbill.engine.goalrunner.plan

import skillbill.application.TestDecompositionManifestStore
import skillbill.application.TestRepositoryEnclosingRoot
import skillbill.engine.goalplanning.GoalPlanningMigrationAdmission
import skillbill.engine.goalplanning.GoalPlanningPreparationCheckpoint
import skillbill.engine.goalplanning.readStoredPlanningRecord
import skillbill.engine.goalrunner.InMemoryGoalManifestStore
import skillbill.engine.goalrunner.execution.core.GoalPlanningSweepPortsParams
import skillbill.engine.goalrunner.execution.core.testGoalPlanningSweepPorts
import skillbill.engine.goalrunner.manifest
import skillbill.engine.goalrunner.model.GoalRunnerChildWorkflowSetup
import skillbill.engine.goalrunner.model.GoalRunnerManifestState
import skillbill.engine.goalrunner.model.GoalRunnerRunRequest
import skillbill.engine.goalrunner.persist.planningMigrationForTest
import skillbill.engine.goalrunner.planning.attempt.GoalPlanningPhaseAttemptGate
import skillbill.engine.goalrunner.planning.attempt.NO_GOAL_PLANNING_ATTEMPT_RECORDER
import skillbill.engine.goalrunner.planning.context.GoalPlanningSharedPreplanProduction
import skillbill.engine.goalrunner.planning.hydration.GoalChildPlanningHydrationOutcome
import skillbill.engine.goalrunner.planning.hydration.GoalChildPlanningHydrator
import skillbill.engine.goalrunner.planning.model.GoalPlanningBurstSchedule
import skillbill.engine.goalrunner.planning.model.GoalPlanningSweepOutcome
import skillbill.engine.goalrunner.planning.outcome.proseRecordPayload
import skillbill.engine.goalrunner.planning.remedies.NO_GOAL_PLANNING_REJECTION_RECORDER
import skillbill.engine.goalrunner.planning.sweep.FakeInvariantsSource
import skillbill.engine.goalrunner.planning.sweep.SweepPlanningLauncher
import skillbill.engine.goalrunner.planning.sweep.fakeContextDiscovery
import skillbill.engine.goalrunner.planning.sweep.readySubSpec
import skillbill.error.core.SkillBillRuntimeException
import skillbill.infrastructure.contracts.FeatureTaskRuntimeWireArtifactValidator
import skillbill.infrastructure.sqlite.sqliteSessionFactoryForTests
import skillbill.ports.diagnostics.NoopRuntimeDiagnostics
import skillbill.ports.goalrunner.model.GoalPlanningIdentity
import skillbill.ports.goalrunner.runner.model.GoalRunnerReviewPolicy
import skillbill.ports.time.NoopRuntimeTimingPort
import skillbill.ports.workflow.gitops.model.GoalSubtaskReviewBaseline
import skillbill.ports.workflow.model.WorkflowFamily
import skillbill.review.context.model.execution.CodeReviewExecutionMode
import skillbill.workflow.engine.WorkflowEngine
import skillbill.workflow.engine.model.DurableWorkflowArtifactFamily
import skillbill.workflow.engine.model.WorkflowArtifactPatch
import skillbill.workflow.engine.model.WorkflowUpdateInput
import skillbill.workflow.model.WorkflowStatus
import skillbill.workflow.model.WorkflowStepStatus
import skillbill.workflow.taskruntime.artifact.asWorkflowArtifactEntry
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimePhaseRecord
import java.nio.file.Files
import java.sql.DriverManager
import java.time.Clock
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class StandalonePlanPreparationTest {
  private val root = Files.createTempDirectory("standalone-plan-handoff")
  private val home = Files.createTempDirectory("standalone-plan-handoff-db")
  private val database = sqliteSessionFactoryForTests(home, home.resolve("metrics.db").toString(), emptyMap())
  private val clock = Clock.systemUTC()
  private val validator = FeatureTaskRuntimeWireArtifactValidator()
  private val checkpoint = GoalPlanningPreparationCheckpoint(database, validator)
  private val manifest = manifest(2)
  private val manifestStore = InMemoryGoalManifestStore(manifest)
  private val sourceId = "wftr-standalone-plan"
  private val targetId = "wftr-goal"
  private val identity =
    GoalPlanningIdentity(sourceId, manifest.issueKey, TestRepositoryEnclosingRoot.repositoryIdentity(root))
  private val sharedProduction =
    GoalPlanningSharedPreplanProduction(
      checkpoint,
      FakeInvariantsSource(),
      TestDecompositionManifestStore,
      fakeContextDiscovery,
      TestRepositoryEnclosingRoot,
      GoalPlanningPhaseAttemptGate(
        manifestStore,
        NO_GOAL_PLANNING_ATTEMPT_RECORDER,
        NO_GOAL_PLANNING_REJECTION_RECORDER,
        NoopRuntimeTimingPort,
        GoalPlanningBurstSchedule(
          planFanOutCap = GoalPlanningBurstSchedule.DEFAULT_PLAN_FAN_OUT_CAP,
          emptyTurnBackoffBase = GoalPlanningBurstSchedule.DEFAULT_EMPTY_TURN_BACKOFF_BASE,
          emptyTurnBackoffFactor = GoalPlanningBurstSchedule.DEFAULT_EMPTY_TURN_BACKOFF_FACTOR,
          waitSlice = GoalPlanningBurstSchedule.DEFAULT_WAIT_SLICE,
        ),
        clock,
      ),
      GoalPlanningMigrationAdmission(database, planningMigrationForTest(), NoopRuntimeDiagnostics),
    )
  private val preparation =
    StandalonePlanPreparation(
      database,
      checkpoint,
      sharedProduction,
      manifestStore,
      TestDecompositionManifestStore,
      TestRepositoryEnclosingRoot,
    )

  init {
    val parent = root.resolve(manifest.parentSpecPath)
    Files.createDirectories(parent.parent)
    Files.writeString(parent, "# Requirements\n\n## Acceptance Criteria\n- Show standalone execution.\n")
    Files.writeString(parent.resolveSibling("decomposition-manifest.yaml"), "verified manifest")
    manifest.subtasks.forEach { Files.writeString(root.resolve(it.specPath), readySubSpec("subtask ${it.id}")) }
    val engine = WorkflowEngine()
    database.transaction { unit ->
      val source = engine.openRecord(WorkflowFamily.TASK_RUNTIME.definition, sourceId, "plan-session", "plan")
      val records =
        listOf("preplan", "plan").associateWith { phase ->
          FeatureTaskRuntimePhaseRecord(
            phaseId = phase,
            status = WorkflowStepStatus.COMPLETED,
            attemptCount = 1,
            startedAt = clock.instant(),
            resolvedAgentId = "codex",
            outputArtifact = proseRecordPayload(phase, "$phase authoritative standalone output"),
          ).asWorkflowArtifactEntry().toMap()
        }
      unit.workflowStates.save(
        WorkflowFamily.TASK_RUNTIME,
        engine.updateRecord(
          WorkflowFamily.TASK_RUNTIME.definition,
          source,
          WorkflowUpdateInput(
            WorkflowStatus.COMPLETED,
            "plan",
            null,
            WorkflowArtifactPatch.from(
              mapOf(DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_PHASE_RECORDS.entry(records)),
            ),
            "plan-session",
            terminalInstant = clock.instant(),
          ),
        ),
      )
      unit.workflowStates.save(
        WorkflowFamily.TASK_RUNTIME,
        engine.openRecord(WorkflowFamily.TASK_RUNTIME.definition, targetId, "goal-session", "plan"),
      )
    }
  }

  @AfterTest
  fun cleanup() {
    root.toFile().deleteRecursively()
    home.toFile().deleteRecursively()
  }

  @Test
  fun `completed standalone plan checkpoints import and goal preparation launches no planning agents`() {
    preparation.capture(sourceId, manifest.issueKey, root)
    val shared = assertNotNull(checkpoint.findSharedPreplan(identity))
    val plan = assertNotNull(checkpoint.findSubtaskPlan(identity, 1, manifest.subtasks.first().specPath))
    assertEquals(
      "preplan authoritative standalone output",
      readStoredPlanningRecord(shared.preplanPayload, "preplan", sourceId).output.value,
    )
    assertEquals(
      Files.readString(root.resolve(manifest.subtasks.first().specPath)),
      readStoredPlanningRecord(plan.planPayload, "plan", sourceId).output.value,
    )
    preparation.capture(sourceId, manifest.issueKey, root)
    import()
    import()
    assertEquals(shared.preplanPayload, checkpoint.findSharedPreplan(targetIdentity())?.preplanPayload)
    val (outcome, launcher) = sweep()
    val prepared = assertIs<GoalPlanningSweepOutcome.PreparedAll>(outcome)
    val request = assertNotNull(prepared.hydrationFor(1))
    val setup =
      GoalRunnerChildWorkflowSetup(
        subtaskId = 1,
        workflowId = "wftr-child",
        goalBranch = assertNotNull(manifest.featureBranch),
        normalizedIssueKey = manifest.issueKey,
        repositoryIdentity = identity.repositoryIdentity,
        governedSpecPath = manifest.subtasks.first().specPath,
        reviewBaseline = GoalSubtaskReviewBaseline("0".repeat(40), emptyList()),
        reviewPolicy = GoalRunnerReviewPolicy(CodeReviewExecutionMode.INLINE),
        planningHydration = request,
      )
    val hydration = database.read { GoalChildPlanningHydrator(clock).hydrate(it, setup, request) }
    assertEquals("implement", assertIs<GoalChildPlanningHydrationOutcome.Hydrated>(hydration).hydration.currentStepId)
    assertEquals(emptyList(), launcher.requests)
  }

  @Test
  fun `editing a subtask after standalone planning preserves saved hashes and refuses reuse`() {
    preparation.capture(sourceId, manifest.issueKey, root)
    Files.writeString(root.resolve(manifest.subtasks.first().specPath), readySubSpec("changed requirements"))
    preparation.capture(sourceId, manifest.issueKey, root)
    import()
    val (outcome, launcher) = sweep()
    assertContains(assertIs<GoalPlanningSweepOutcome.Stopped>(outcome).blockedReason, "sub-spec hash")
    assertEquals(emptyList(), launcher.requests)
  }

  @Test
  fun `missing standalone artifacts and incomplete specs never publish partial preparation`() {
    assertFailsWith<SkillBillRuntimeException> { import() }
    assertNull(checkpoint.findSharedPreplan(targetIdentity()))
    Files.writeString(
      root.resolve(manifest.subtasks.last().specPath),
      "# Incomplete\n\n## Acceptance Criteria\n- Do work.\n",
    )
    assertFailsWith<SkillBillRuntimeException> { preparation.capture(sourceId, manifest.issueKey, root) }
    assertNull(checkpoint.findSharedPreplan(identity))
    assertNull(checkpoint.findSubtaskPlan(identity, 1, manifest.subtasks.first().specPath))
  }

  @Test
  fun `a rejected goal checkpoint transfer rolls back the standalone records and can be retried`() {
    preparation.capture(sourceId, manifest.issueKey, root)
    val before = assertNotNull(checkpoint.findSharedPreplan(identity))
    sql(
      "CREATE TRIGGER reject_goal_plan BEFORE INSERT ON goal_subtask_plans " +
        "WHEN NEW.parent_goal_workflow_id = '$targetId' BEGIN SELECT RAISE(ABORT, 'injected'); END",
    )
    assertFailsWith<SkillBillRuntimeException> { import() }
    assertNull(checkpoint.findSharedPreplan(targetIdentity()))
    assertEquals(before, checkpoint.findSharedPreplan(identity))
    assertNotNull(checkpoint.findSubtaskPlan(identity, 2, manifest.subtasks.last().specPath))
    sql("DROP TRIGGER reject_goal_plan")
    import()
    assertNotNull(checkpoint.findSubtaskPlan(targetIdentity(), 2, manifest.subtasks.last().specPath))
  }

  private fun sql(statement: String) {
    DriverManager.getConnection("jdbc:sqlite:${home.resolve("metrics.db")}").use { connection ->
      connection.createStatement().use { it.execute(statement) }
    }
  }

  private fun sweep(): Pair<GoalPlanningSweepOutcome, SweepPlanningLauncher> {
    val launcher = SweepPlanningLauncher { _, _, _ -> error("Saved standalone planning must launch no planning agent") }
    val sweep =
      testGoalPlanningSweepPorts(
        GoalPlanningSweepPortsParams(
          checkpoint = checkpoint,
          subtaskLauncher = launcher,
          invariantsSource = FakeInvariantsSource(),
          manifestFileStore = TestDecompositionManifestStore,
          contextDiscovery = fakeContextDiscovery,
        ),
      )
    return sweep.prepare(
      GoalRunnerManifestState(targetId, "", manifest),
      GoalRunnerRunRequest(manifest.issueKey, root, "codex"),
    ) to launcher
  }

  private fun targetIdentity() = identity.copy(parentGoalWorkflowId = targetId)

  private fun import() =
    database.transaction {
      StandalonePlanCheckpointImport(validator).import(it, sourceId, targetIdentity(), manifest)
    }
}
