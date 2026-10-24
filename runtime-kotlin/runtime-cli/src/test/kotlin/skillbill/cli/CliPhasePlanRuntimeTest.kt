package skillbill.cli

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import skillbill.cli.core.CliRuntime
import skillbill.contracts.JsonCodec
import skillbill.contracts.workflow.featuretask.DECOMPOSITION_MANIFEST_CONTRACT_VERSION
import skillbill.install.model.SupportedAgent
import skillbill.ports.agentrun.AgentRunLauncher
import skillbill.ports.agentrun.agentRunLaunchFacts
import skillbill.ports.agentrun.model.AgentRunLaunchModelRequest
import skillbill.ports.agentrun.model.AgentRunLaunchOutcome
import skillbill.ports.agentrun.model.AgentRunLaunchRequest
import skillbill.ports.agentrun.model.AgentRunTermination
import skillbill.ports.agentrun.passThroughResolvedLaunchModel
import skillbill.ports.workflow.gitops.WorkflowGitOperations
import skillbill.ports.workflow.gitops.model.WorkflowGitOperationResult
import skillbill.workflow.engine.model.DurableWorkflowArtifactFamily
import skillbill.workflow.engine.model.DurableWorkflowArtifacts
import skillbill.workflow.engine.model.ExecutionPlanArtifactView
import skillbill.workflow.taskruntime.model.skeleton.EffectiveLaunchModel
import skillbill.workflow.taskruntime.model.skeleton.SkeletonDefinition
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

class CliPhasePlanRuntimeTest {
  private val tempDir: Path = Files.createTempDirectory("skillbill-cli-phase-plan")
  private val fixture =
    GoalCliFixture(
      tempDir = tempDir,
      dbPath = tempDir.resolve("metrics.db"),
      parentSpec = tempDir.resolve(".feature-specs/$ISSUE_KEY-phase-plan/spec.md"),
      subtaskSpecs = emptyList(),
    )

  @AfterTest
  fun cleanUp() {
    tempDir.toFile().deleteRecursively()
  }

  @Test
  fun `blocked planning resumes its own authored manifest without rerunning preplan`() {
    installGoalBuildPack(tempDir)
    val launcher = PhasePlanLauncher(tempDir, incompleteFirstBundle = true)
    val arguments = listOf("--db", fixture.dbPath.toString(), "phase", "plan", ISSUE_KEY)
    val context = fixture.context(launcher = launcher).copy(repositoryRoot = tempDir)
    val first = CliRuntime.run(arguments + listOf("split the work", "--agent", "codex"), context)
    assertEquals(1, first.exitCode, first.stdout + first.stderr)
    assertContains(first.stdout, "Readiness problem")

    val resumed = CliRuntime.run(arguments + listOf("--agent", "codex"), context)
    assertEquals(0, resumed.exitCode, resumed.stdout + resumed.stderr)
    assertEquals(RESUMED_PLAN_LAUNCHES, launcher.phaseIds)
    assertEquals(1, rowCount("feature_task_workflows"))
    assertEquals(PLANNED_UNITS, completedPlanUnitIds())
    assertEquals(mapOf("status" to "completed", "attempt_count" to 1), settledIntakeStep())
    val board = CliRuntime.run(listOf("--db", fixture.dbPath.toString(), "work", "board", "--format", "json"), context)
    val boardPayload = requireNotNull(JsonCodec.parseObjectOrNull(board.stdout))
    val items = boardPayload["items"] as JsonArray
    assertEquals(1, items.size, board.stdout)
    val detail =
      CliRuntime.run(
        listOf("--db", fixture.dbPath.toString(), "work", "detail", resumedWorkflowId(), "--format", "json"),
        context,
      )
    assertEquals(0, detail.exitCode, detail.stdout + detail.stderr)
    val detailPayload = requireNotNull(JsonCodec.parseObjectOrNull(detail.stdout))
    val item = detailPayload["item"] as JsonObject
    assertEquals(2, (item["execution_history"] as JsonArray).size, detail.stdout)
  }

  @Test
  fun `phase plan completes despite concurrent edits outside its bundle`() {
    installGoalBuildPack(tempDir)
    val launcher = PhasePlanLauncher(tempDir, dirtyOutsideBundle = true)
    val git =
      object : WorkflowGitOperations by GoalTestWorkflowGitOperations {
        override fun worktreeStatus(repoRoot: Path): WorkflowGitOperationResult =
          WorkflowGitOperationResult.Ok(
            if (Files.exists(repoRoot.resolve("outside-plan.txt"))) "?? outside-plan.txt" else "",
          )
      }
    val result =
      CliRuntime.run(
        listOf("--db", fixture.dbPath.toString(), "phase", "plan", ISSUE_KEY, "split the work", "--agent", "codex"),
        fixture.context(launcher = launcher, workflowGitOperations = git).copy(repositoryRoot = tempDir),
      )
    assertEquals(0, result.exitCode, result.stdout + result.stderr)
    assertEquals(PLAN_LAUNCHES, launcher.phaseIds)
    assertEquals("Concurrent edit", Files.readString(tempDir.resolve("outside-plan.txt")))
    val report = requireNotNull(JsonCodec.parseObjectOrNull(requireNotNull(planReport())))
    val subtasks = (report["subtasks"] as JsonArray).map { it as JsonObject }
    assertEquals(listOf("first part", "second part"), subtasks.map { it["name"].toString().trim('"') })
    assertEquals("[1]", subtasks[1]["depends_on"].toString())
    assertEquals(
      listOf("standalone" to listOf("phase", "plan", "$ISSUE_KEY split the work", "--agent", "codex")),
      planLaunches(),
    )
  }

  @Test
  fun `a plan blocked while planning subtasks already reports the bundle it authored`() {
    installGoalBuildPack(tempDir)
    val launcher = PhasePlanLauncher(tempDir, failPlanSubtasks = true)
    val result =
      CliRuntime.run(
        listOf("--db", fixture.dbPath.toString(), "phase", "plan", ISSUE_KEY, "split the work", "--agent", "codex"),
        fixture.context(launcher = launcher).copy(repositoryRoot = tempDir),
      )
    assertEquals(1, result.exitCode, result.stdout + result.stderr)
    assertTrue("plan_subtasks" in launcher.phaseIds, launcher.phaseIds.toString())
    val report = requireNotNull(JsonCodec.parseObjectOrNull(requireNotNull(planReport())))
    val subtasks = (report["subtasks"] as JsonArray).map { it as JsonObject }
    assertEquals(listOf("first part", "second part"), subtasks.map { it["name"].toString().trim('"') })
  }

  @Test
  fun `full run directory intake executes a completed plan without another planning handoff`() {
    installGoalBuildPack(tempDir)
    val launcher = PhasePlanLauncher(tempDir)

    val plan =
      CliRuntime.run(
        listOf(
          "--db",
          fixture.dbPath.toString(),
          "phase",
          "plan",
          ISSUE_KEY,
          "split",
          "the",
          "work",
          "--agent",
          "codex",
        ),
        fixture.context(launcher = launcher).copy(repositoryRoot = tempDir),
      )

    assertEquals(0, plan.exitCode, plan.stdout)
    assertContains(plan.stdout, "Manifest: ")
    assertContains(plan.stdout, "specs/$ISSUE_KEY-")
    assertTrue(Files.notExists(tempDir.resolve(".feature-specs")))
    assertEquals(PLAN_LAUNCHES, launcher.phaseIds)
    assertEquals(1, rowCount("feature_task_workflows"))
    assertEquals(PLANNED_UNITS, completedPlanUnitIds())
    val preflight =
      CliRuntime.run(
        listOf(
          "--db",
          fixture.dbPath.toString(),
          "goal",
          "preflight",
          ISSUE_KEY,
          "--agent",
          "codex",
          "--repo-root",
          tempDir.toString(),
          "--format",
          "json",
        ),
        fixture.context(launcher = launcher),
      )
    assertEquals(0, preflight.exitCode, preflight.stdout)
    val payload =
      requireNotNull(
        JsonCodec.anyToStringAnyMap(
          JsonCodec.jsonElementToValue(requireNotNull(JsonCodec.parseObjectOrNull(preflight.stdout))),
        ),
      ) { "Expected preflight JSON object but got: ${preflight.stdout}" }
    assertEquals(ISSUE_KEY, payload["issue_key"])
    assertEquals("new_work", payload["verdict"], preflight.stdout)
    assertEquals(false, payload["manifest_missing"], preflight.stdout)
    assertEquals(1, rowCount("feature_task_workflows"))
    assertGoalStartsAtImplementation(launcher)
  }

  @Test
  fun `full run on a new key plans first then imports the bundle and runs the goal linked to the plan`() {
    installGoalBuildPack(tempDir)
    val planner = PhasePlanLauncher(tempDir)
    val goal = GoalFixtureAgentRunLauncher(fixture, noTerminalSubtask = 1)

    val result = runFull(PlanThenGoalLauncher(planner, goal))

    assertEquals(3, result.exitCode, result.stdout + result.stderr)
    assertEquals(PLAN_LAUNCHES, planner.phaseIds)
    assertTrue(planner.timeouts.all { it == 30.minutes }, planner.timeouts.toString())
    assertEquals(1, goal.childLaunches.size)
    assertEquals(1, rowCount("goal_runner_controls"))
    assertTrue(workflowIdsMentioning(planWorkflowId()).isNotEmpty())
    assertEquals(
      listOf("goal" to listOf("goal", "$ISSUE_KEY Split the work into two parts.", "--agent", "codex")),
      planLaunches(),
    )
  }

  @Test
  fun `full run stops with the plan block reason and workflow id when planning blocks`() {
    installGoalBuildPack(tempDir)
    val goal = GoalFixtureAgentRunLauncher(fixture)

    val result = runFull(PlanThenGoalLauncher(PhasePlanLauncher(tempDir, incompleteFirstBundle = true), goal))

    assertEquals(1, result.exitCode, result.stdout + result.stderr)
    assertContains(result.stdout + result.stderr, "Readiness problem")
    assertContains(result.stdout + result.stderr, "Plan workflow ID: ${planWorkflowId()}")
    assertTrue(goal.requests.isEmpty())
    assertEquals(0, rowCount("goal_runner_controls"))
  }

  @Test
  fun `full run on a key with an incomplete plan workflow resumes that workflow`() {
    installGoalBuildPack(tempDir)
    val planner = PhasePlanLauncher(tempDir, authorNothingFirst = true)
    val launcher = PlanThenGoalLauncher(planner, GoalFixtureAgentRunLauncher(fixture, noTerminalSubtask = 1))

    val blocked = runFull(launcher)
    assertEquals(1, blocked.exitCode, blocked.stdout + blocked.stderr)
    val incompletePlan = planWorkflowId()

    val resumed = runFull(launcher)

    assertEquals(3, resumed.exitCode, resumed.stdout + resumed.stderr)
    assertEquals(RESUMED_PLAN_LAUNCHES, planner.phaseIds)
    assertEquals(incompletePlan, planWorkflowId())
  }

  @Test
  fun `full run on a reused key plans the new spec instead of resuming the earlier spec's plan`() {
    installGoalBuildPack(tempDir)
    val planner = PhasePlanLauncher(tempDir, authorNothingFirst = true)
    val launcher = PlanThenGoalLauncher(planner, GoalFixtureAgentRunLauncher(fixture, noTerminalSubtask = 1))
    assertEquals(1, runFull(launcher).exitCode)
    val earlierPlan = planWorkflowId()
    val specs = storeSpecsRoot(tempDir)
    val earlierBundle = specs.resolve(planner.bundleNames().single())
    Files.move(earlierBundle, specs.resolve(earlierBundle.fileName.toString().replace(ISSUE_KEY, "SKILL-905")))

    val result = runFull(launcher, "$ISSUE_KEY Unrelated work in two parts.")

    assertEquals(3, result.exitCode, result.stdout + result.stderr)
    assertEquals(listOf("preplan", "plan") + PLAN_LAUNCHES, planner.phaseIds)
    val plans = planWorkflowIds()
    assertEquals(2, plans.size, plans.toString())
    assertTrue(earlierPlan in plans, plans.toString())
    assertEquals(listOf("$ISSUE_KEY-unrelated-work-in-two-parts"), planner.bundleNames())
  }

  @Test
  fun `full run on a reused key with an authored bundle plans it instead of reopening the earlier spec's plan`() {
    installGoalBuildPack(tempDir)
    val planner = PhasePlanLauncher(tempDir)
    val planned =
      CliRuntime.run(
        listOf("--db", fixture.dbPath.toString(), "phase", "plan", ISSUE_KEY, PLAN_INTAKE, "--agent", "codex"),
        fixture.context(launcher = planner).copy(repositoryRoot = tempDir),
      )
    assertEquals(0, planned.exitCode, planned.stdout + planned.stderr)
    val earlierPlan = planWorkflowId()
    val specs = storeSpecsRoot(tempDir)
    val earlierBundle = specs.resolve(planner.bundleNames().single())
    Files.move(earlierBundle, specs.resolve(earlierBundle.fileName.toString().replace(ISSUE_KEY, "SKILL-905")))
    val reusedBundle = Files.createDirectories(specs.resolve("$ISSUE_KEY-unrelated-work"))
    planner.authorBundle()

    val goal = GoalFixtureAgentRunLauncher(fixture, noTerminalSubtask = 1)
    val result = runFull(PlanThenGoalLauncher(planner, goal), reusedBundle.toString())

    assertEquals(3, result.exitCode, result.stdout + result.stderr)
    val plans = planWorkflowIds()
    assertEquals(2, plans.size, plans.toString())
    assertTrue(earlierPlan in plans, plans.toString())
    assertEquals(1, goal.childLaunches.size)
  }

  @Test
  fun `full run resumes an unfinished standalone plan without replacing its unbounded timeout`() {
    installGoalBuildPack(tempDir)
    val planner = PhasePlanLauncher(tempDir, authorNothingFirst = true)
    val context = fixture.context(launcher = planner).copy(repositoryRoot = tempDir)
    val first =
      CliRuntime.run(
        listOf("--db", fixture.dbPath.toString(), "phase", "plan", ISSUE_KEY, PLAN_INTAKE, "--agent", "codex"),
        context,
      )
    assertEquals(1, first.exitCode, first.stdout + first.stderr)
    val workflowId = planWorkflowId()
    val descriptor = DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_EXECUTION_PLAN.value(planArtifacts())
    val goal = GoalFixtureAgentRunLauncher(fixture, noTerminalSubtask = 1)

    val resumed = runFull(PlanThenGoalLauncher(planner, goal))

    assertEquals(3, resumed.exitCode, resumed.stdout + resumed.stderr)
    assertEquals(workflowId, planWorkflowId())
    assertEquals(descriptor, DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_EXECUTION_PLAN.value(planArtifacts()))
    assertEquals(RESUMED_PLAN_LAUNCHES, planner.phaseIds)
    assertTrue(planner.timeouts.all { it == null }, planner.timeouts.toString())
    assertEquals(PLANNED_UNITS, completedPlanUnitIds())
    assertEquals(1, goal.childLaunches.size)
  }

  @Test
  fun `key-only standalone retry retains the bounded timeout of an unfinished goal plan`() {
    installGoalBuildPack(tempDir)
    val planner = PhasePlanLauncher(tempDir, authorNothingFirst = true)
    val first = runFull(PlanThenGoalLauncher(planner, GoalFixtureAgentRunLauncher(fixture)))
    assertEquals(1, first.exitCode, first.stdout + first.stderr)
    val workflowId = planWorkflowId()
    val descriptor = DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_EXECUTION_PLAN.value(planArtifacts())

    val resumed =
      CliRuntime.run(
        listOf("--db", fixture.dbPath.toString(), "phase", "plan", ISSUE_KEY, "--agent", "codex"),
        fixture.context(launcher = planner).copy(repositoryRoot = tempDir),
      )

    assertEquals(0, resumed.exitCode, resumed.stdout + resumed.stderr)
    assertEquals(workflowId, planWorkflowId())
    assertEquals(descriptor, DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_EXECUTION_PLAN.value(planArtifacts()))
    assertEquals(RESUMED_PLAN_LAUNCHES, planner.phaseIds)
    assertTrue(planner.timeouts.all { it == 30.minutes }, planner.timeouts.toString())
    assertEquals(PLANNED_UNITS, completedPlanUnitIds())
  }

  @Test
  fun `goal spec drift reopens the standalone plan with its saved timeout and retains other planned units`() {
    installGoalBuildPack(tempDir)
    val planner = PhasePlanLauncher(tempDir)
    val planned =
      CliRuntime.run(
        listOf("--db", fixture.dbPath.toString(), "phase", "plan", ISSUE_KEY, PLAN_INTAKE, "--agent", "codex"),
        fixture.context(launcher = planner).copy(repositoryRoot = tempDir),
      )
    assertEquals(0, planned.exitCode, planned.stdout + planned.stderr)
    val before = planArtifacts()
    val records =
      requireNotNull(
        JsonCodec.anyToStringAnyMap(DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_PHASE_RECORDS.value(before)),
      )
    val subtask =
      Files.walk(storeSpecsRoot(tempDir)).use { paths ->
        paths.toList().single { it.fileName.toString() == SUBTASK_FILES.getValue(1) }
      }
    Files.writeString(
      subtask,
      Files.readString(
        subtask,
      ).replace("1. Subtask 1 works.", "1. Subtask 1 works with the changed acceptance boundary."),
    )
    val goal = GoalFixtureAgentRunLauncher(fixture, noTerminalSubtask = 1)

    val resumed = runFull(PlanThenGoalLauncher(planner, goal))

    assertEquals(3, resumed.exitCode, resumed.stdout + resumed.stderr)
    val after = planArtifacts()
    assertEquals(
      DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_EXECUTION_PLAN.value(before),
      DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_EXECUTION_PLAN.value(after),
    )
    val retained =
      requireNotNull(
        JsonCodec.anyToStringAnyMap(DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_PHASE_RECORDS.value(after)),
      )
    listOf("preplan", "plan", "plan_subtasks:2").forEach { assertEquals(records[it], retained[it], it) }
    assertEquals(listOf("plan_subtasks"), planner.phaseIds.drop(PLAN_LAUNCHES.size))
    assertTrue(planner.timeouts.all { it == null }, planner.timeouts.toString())
    assertEquals(1, goal.childLaunches.size)
  }

  @Test
  fun `full run on a key with a stored goal resumes the goal without planning again`() {
    installGoalBuildPack(tempDir)
    val firstPlanner = PhasePlanLauncher(tempDir)
    runFull(PlanThenGoalLauncher(firstPlanner, GoalFixtureAgentRunLauncher(fixture, noTerminalSubtask = 1)))
    val planWorkflow = planWorkflowId()
    val secondPlanner = PhasePlanLauncher(tempDir)

    runFull(PlanThenGoalLauncher(secondPlanner, GoalFixtureAgentRunLauncher(fixture, noTerminalSubtask = 1)))

    assertTrue(secondPlanner.phaseIds.isEmpty(), secondPlanner.phaseIds.toString())
    assertEquals(1, rowCount("goal_runner_controls"))
    assertEquals(planWorkflow, planWorkflowId())
  }

  private fun runFull(
    launcher: AgentRunLauncher,
    intake: String = "$ISSUE_KEY Split the work into two parts.",
  ) = CliRuntime.run(
    listOf(
      "--db",
      fixture.dbPath.toString(),
      intake,
      "--agent",
      "codex",
      "--repo-root",
      tempDir.toString(),
    ),
    fixture.context(launcher = launcher).copy(repositoryRoot = tempDir),
  )

  private fun planWorkflowId(): String = planWorkflowIds().single()

  private fun planWorkflowIds(): List<String> =
    DriverManager.getConnection("jdbc:sqlite:${fixture.dbPath}").use { connection ->
      connection.createStatement().use { statement ->
        statement.executeQuery("SELECT workflow_id, artifacts_json FROM feature_task_workflows").use { rows ->
          buildList {
            while (rows.next()) {
              val artifacts = DurableWorkflowArtifacts.fromAny(JsonCodec.parseValue(rows.getString(2)))
              if (ExecutionPlanArtifactView.definitionId(artifacts) == SkeletonDefinition.PLAN.id) {
                add(rows.getString(1))
              }
            }
          }
        }
      }
    }

  private fun planArtifacts(): DurableWorkflowArtifacts =
    DriverManager.getConnection("jdbc:sqlite:${fixture.dbPath}").use { connection ->
      connection.prepareStatement("SELECT artifacts_json FROM feature_task_workflows WHERE workflow_id = ?").use {
          query ->
        query.setString(1, planWorkflowId())
        query.executeQuery().use { rows ->
          check(rows.next())
          DurableWorkflowArtifacts.fromAny(JsonCodec.parseValue(rows.getString(1)))
        }
      }
    }

  private fun workflowIdsMentioning(planWorkflowId: String): List<String> =
    DriverManager.getConnection("jdbc:sqlite:${fixture.dbPath}").use { connection ->
      connection.prepareStatement(
        "SELECT workflow_id FROM feature_task_workflows WHERE workflow_id <> ? AND steps_json LIKE '%' || ? || '%'",
      ).use { query ->
        query.setString(1, planWorkflowId)
        query.setString(2, planWorkflowId)
        query.executeQuery().use { rows -> buildList { while (rows.next()) add(rows.getString(1)) } }
      }
    }

  private fun settledIntakeStep(): Map<String, Any?> =
    DriverManager.getConnection("jdbc:sqlite:${fixture.dbPath}").use { connection ->
      connection.createStatement().use { statement ->
        statement.executeQuery("SELECT steps_json FROM feature_task_workflows").use { rows ->
          check(rows.next())
          val steps = requireNotNull(JsonCodec.parseValue(rows.getString(1)) as? List<*>)
          val intake = steps.mapNotNull(JsonCodec::anyToStringAnyMap).single { it["step_id"] == "intake" }
          mapOf("status" to intake["status"], "attempt_count" to (intake["attempt_count"] as Number).toInt())
        }
      }
    }

  private fun resumedWorkflowId(): String =
    DriverManager.getConnection("jdbc:sqlite:${fixture.dbPath}").use { connection ->
      connection.createStatement().use { statement ->
        statement.executeQuery("SELECT workflow_id FROM feature_task_workflows").use { rows ->
          check(rows.next())
          rows.getString(1)
        }
      }
    }

  private fun assertGoalStartsAtImplementation(launcher: PhasePlanLauncher) {
    val implementation = GoalFixtureAgentRunLauncher(fixture, noTerminalSubtask = 1)
    val goal =
      CliRuntime.run(
        listOf(
          "--db",
          fixture.dbPath.toString(),
          fixture.parentSpec.parent.toString(),
          "--agent",
          "codex",
          "--repo-root",
          tempDir.toString(),
        ),
        fixture.context(launcher = implementation),
      )
    assertEquals(3, goal.exitCode, goal.stdout + goal.stderr)
    assertEquals(PLAN_LAUNCHES, launcher.phaseIds)
    assertEquals(3, rowCount("feature_task_workflows"))
    assertEquals(1, implementation.requests.size)
    assertTrue(implementation.requests.single().skillRunRequest.goalContinuation != null)
    val workflowId = goal.payload?.get("workflow_id")?.toString().orEmpty()
    val child = RuntimeWorkflowTestSupport.get(fixture.dbPath, workflowId, fixture.context(launcher = implementation))
    assertEquals("implement", child["current_step_id"])
    val settled =
      CliRuntime.run(
        listOf("--db", fixture.dbPath.toString(), "phase", "plan", ISSUE_KEY, "--agent", "codex"),
        fixture.context(launcher = launcher).copy(repositoryRoot = tempDir),
      )
    assertEquals(0, settled.exitCode, settled.stdout + settled.stderr)
    assertEquals(PLAN_LAUNCHES, launcher.phaseIds)
    assertEquals(PLANNED_UNITS, completedPlanUnitIds())
  }

  private fun completedPlanUnitIds(): List<String> =
    DriverManager.getConnection("jdbc:sqlite:${fixture.dbPath}").use { connection ->
      connection.prepareStatement("SELECT artifacts_json FROM feature_task_workflows WHERE workflow_id = ?").use {
        it.setString(1, planWorkflowId())
        it.executeQuery().use { rows ->
          check(rows.next())
          val artifacts = requireNotNull(JsonCodec.anyToStringAnyMap(JsonCodec.parseValue(rows.getString(1))))
          val recordsKey = DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_PHASE_RECORDS.label()
          requireNotNull(JsonCodec.anyToStringAnyMap(artifacts[recordsKey]))
            .filter { (phaseId, record) ->
              phaseId.startsWith("plan_subtasks:") && JsonCodec.anyToStringAnyMap(record)?.get("status") == "completed"
            }.keys
            .sorted()
        }
      }
    }

  private fun planLaunches(): List<Pair<String, List<String>>> =
    DriverManager.getConnection("jdbc:sqlite:${fixture.dbPath}").use { connection ->
      connection.createStatement().use { statement ->
        statement.executeQuery(
          "SELECT launch_scope, launch_arguments_json FROM standalone_phase_status WHERE phase_id = 'plan' " +
            "ORDER BY run_sequence",
        ).use { rows ->
          buildList {
            while (rows.next()) {
              val arguments = JsonCodec.parseValue(rows.getString(2)) as List<*>
              add(rows.getString(1) to arguments.map { it.toString() })
            }
          }
        }
      }
    }

  private fun planReport(): String? =
    DriverManager.getConnection("jdbc:sqlite:${fixture.dbPath}").use { connection ->
      connection.createStatement().use { statement ->
        statement.executeQuery("SELECT plan_report FROM standalone_phase_status WHERE phase_id = 'plan'").use { rows ->
          assertTrue(rows.next())
          rows.getString(1)
        }
      }
    }

  private fun rowCount(table: String): Int =
    DriverManager.getConnection("jdbc:sqlite:${fixture.dbPath}").use { connection ->
      val exists =
        connection.prepareStatement("SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = ?").use { query ->
          query.setString(1, table)
          query.executeQuery().use { rows -> rows.next() }
        }
      if (!exists) return@use 0
      connection.createStatement().use { statement ->
        statement.executeQuery("SELECT COUNT(*) FROM $table").use { rows ->
          assertTrue(rows.next())
          rows.getInt(1)
        }
      }
    }

  private class PlanThenGoalLauncher(
    private val planner: PhasePlanLauncher,
    private val goal: GoalFixtureAgentRunLauncher,
  ) : AgentRunLauncher {
    override fun resolveLaunchModel(request: AgentRunLaunchModelRequest): EffectiveLaunchModel =
      passThroughResolvedLaunchModel(request)

    override fun launch(request: AgentRunLaunchRequest): AgentRunLaunchOutcome {
      val skillRequest = request.skillRunRequest
      return if (skillRequest.goalContinuation == null && skillRequest.promptOverride != null) {
        planner.launch(request)
      } else {
        goal.launch(request)
      }
    }
  }

  private class PhasePlanLauncher(
    private val repoRoot: Path,
    private val dirtyOutsideBundle: Boolean = false,
    private val incompleteFirstBundle: Boolean = false,
    private val authorNothingFirst: Boolean = false,
    private val failPlanSubtasks: Boolean = false,
  ) : AgentRunLauncher {
    val phaseIds = CopyOnWriteArrayList<String>()
    val timeouts = CopyOnWriteArrayList<Duration?>()

    override fun resolveLaunchModel(request: AgentRunLaunchModelRequest): EffectiveLaunchModel =
      passThroughResolvedLaunchModel(request)

    override fun launch(request: AgentRunLaunchRequest): AgentRunLaunchOutcome {
      if (request.skillRunRequest.issueKey.startsWith("bi_")) {
        return agentRunLaunchFacts(SupportedAgent.CODEX, termination = AgentRunTermination.SpawnFailed)
      }
      timeouts += request.skillRunRequest.timeout
      val phaseId =
        requireNotNull(PHASE_LINE.find(request.skillRunRequest.promptOverride.orEmpty())?.groupValues?.get(1)) {
          "phase plan launched a prompt with no phase header"
        }
      phaseIds += phaseId
      if (failPlanSubtasks && phaseId == "plan_subtasks") {
        return agentRunLaunchFacts(SupportedAgent.CODEX, termination = AgentRunTermination.SpawnFailed)
      }
      val stdout = if (phaseId == "plan") authorBundle() else phasePlanningPayload(phaseId)
      if (dirtyOutsideBundle && phaseIds.count { it == "plan" } == 1 && phaseId == "plan") {
        Files.writeString(repoRoot.resolve("outside-plan.txt"), "Concurrent edit")
      }
      return agentRunLaunchFacts(agent = SupportedAgent.CODEX, stdout = stdout, stderr = "")
    }

    fun authorBundle(): String {
      if (authorNothingFirst && phaseIds.count { it == "plan" } == 1) return "Authored nothing yet."
      val bundle = seededBundle()
      Files.writeString(
        bundle.resolve("spec.md"),
        specText("Parent", "The split work completes.") +
          "\nThis invocation prepares artifacts only. Stop after planning. Do not execute the goal from this phase.\n",
      )
      SUBTASK_FILES.forEach { (id, fileName) ->
        if (incompleteFirstBundle && phaseIds.count { it == "plan" } == 1 && id == 2) return@forEach
        Files.writeString(bundle.resolve(fileName), specText("Subtask $id", "Subtask $id works."))
      }
      Files.writeString(
        bundle.resolve("decomposition-manifest.yaml"),
        manifestYaml("specs/${bundle.fileName}", bundle.fileName.toString().removePrefix("$ISSUE_KEY-")),
      )
      return "Split the work into two ordered subtasks."
    }

    private fun seededBundle(): Path = storeSpecsRoot(repoRoot).resolve(bundleNames().single())

    fun bundleNames(): List<String> =
      Files.list(storeSpecsRoot(repoRoot)).use { directories ->
        directories.toList().map { it.fileName.toString() }.filter { it.startsWith("$ISSUE_KEY-") }
      }

    private fun specText(
      title: String,
      criterion: String,
    ): String =
      "# $title\n\n## Acceptance Criteria\n\n1. $criterion\n" +
        "\n## Implementation Details\nImplement $criterion in the owning production path.\n"
  }

  private companion object {
    const val ISSUE_KEY = "SKILL-904"
    const val PLAN_INTAKE = "Split the work into two parts."
    val PHASE_LINE = Regex("""Phase: (\w+) \(""")
    val SUBTASK_FILES = mapOf(1 to "spec_subtask_1_first-part.md", 2 to "spec_subtask_2_second-part.md")
    val PLAN_LAUNCHES = listOf("preplan", "plan", "plan_subtasks", "plan_subtasks")
    val RESUMED_PLAN_LAUNCHES = listOf("preplan", "plan", "plan", "plan_subtasks", "plan_subtasks")
    val PLANNED_UNITS = listOf("plan_subtasks:1", "plan_subtasks:2")

    fun storeSpecsRoot(checkoutHome: Path): Path =
      Files.walk(checkoutHome).use { paths ->
        paths
          .filter { it.fileName?.toString() == "specs" && it.parent?.parent?.fileName?.toString() == "projects" }
          .toList()
          .single()
      }

    fun manifestYaml(
      bundleDirectory: String,
      featureName: String,
    ): String =
      """
      ---
      contract_version: "$DECOMPOSITION_MANIFEST_CONTRACT_VERSION"
      issue_key: "$ISSUE_KEY"
      feature_name: "$featureName"
      parent_spec_path: "$bundleDirectory/spec.md"
      status: "pending"
      execution_model: "same_branch_commit_per_subtask"
      base_branch: "main"
      feature_branch: "feat/$ISSUE_KEY-$featureName"
      stack_branches: []
      current_subtask_intent:
        subtask_id: 1
        action: "start"
      subtasks:
      - id: 1
        name: "first part"
        spec_path: "$bundleDirectory/spec_subtask_1_first-part.md"
        status: "pending"
        branch: null
        commit_sha: null
        workflow_id: null
        blocked_reason: null
        last_resumable_step: null
        linear_issue_id: null
        finalizing_agent_id: null
        participating_agent_ids: []
        dependencies: []
      - id: 2
        name: "second part"
        spec_path: "$bundleDirectory/spec_subtask_2_second-part.md"
        status: "pending"
        branch: null
        commit_sha: null
        workflow_id: null
        blocked_reason: null
        last_resumable_step: null
        linear_issue_id: null
        finalizing_agent_id: null
        participating_agent_ids: []
        dependencies:
        - subtask_id: 1
          optional: false
          skipped: false
      """.trimIndent() + "\n"
  }
}
