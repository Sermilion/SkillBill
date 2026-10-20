package skillbill.infrastructure.sqlite.workflow.featuretask

import skillbill.error.core.SkillBillRuntimeException
import skillbill.infrastructure.sqlite.sqliteDatabaseSessionFactory
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.ports.idestatus.model.IdeStatusWorkflowRegistration
import skillbill.ports.idestatus.model.StandaloneEligiblePage
import skillbill.ports.idestatus.model.StandaloneEligibleSlice
import skillbill.ports.idestatus.model.StandaloneMonitorCleanup
import skillbill.ports.idestatus.model.StandaloneMonitorTarget
import skillbill.ports.idestatus.model.StandalonePhaseExecutionAnchor
import skillbill.ports.idestatus.model.StandalonePhaseStatusRegistration
import skillbill.ports.idestatus.model.StandalonePhaseStatusUpdate
import skillbill.ports.idestatus.model.StandalonePhaseStatusUpdateResult
import skillbill.ports.idestatus.model.StandalonePlanReport
import skillbill.ports.idestatus.model.StandalonePlanSubtask
import skillbill.ports.idestatus.model.StandaloneQaCheck
import skillbill.ports.idestatus.model.StandaloneQaCriterion
import skillbill.ports.idestatus.model.StandaloneQaCriterionFinding
import skillbill.ports.idestatus.model.StandaloneQaCriterionStatus
import skillbill.ports.idestatus.model.StandaloneQaOutcome
import skillbill.ports.idestatus.model.StandaloneQaReport
import skillbill.ports.monitor.model.MonitorRetentionReason
import java.nio.file.Files
import java.sql.DriverManager
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class StandalonePhaseStatusStoreTest {
  @Test
  fun `workflow execution backfilled with SQLite timestamps remains readable without masking corruption`() {
    DriverManager.getConnection("jdbc:sqlite::memory:").use { connection ->
      connection.createStatement().use {
        it.executeUpdate(
          "CREATE TABLE ide_status_workflow_execution AS SELECT 'repo' AS repository_identity, " +
            "'main' AS branch_correlation, 'SKILL-418' AS issue_key, 'workflow' AS workflow_id, " +
            "'invocation' AS invocation_id, 'execution' AS execution_id, 'store' AS status_store_id, " +
            "'1' AS run_sequence, '1' AS status_revision, 'active' AS lifecycle_state, " +
            "'2026-10-09 05:35:36' AS started_at, '2026-10-09T05:36:00Z' AS updated_at, " +
            "NULL AS agent_environment_json",
        )
      }
      val normalizations = mutableListOf<String>()
      val diagnostics =
        object : RuntimeDiagnostics {
          override fun warning(
            message: String,
            error: Throwable?,
          ) = Unit

          override fun error(
            message: String,
            error: Throwable?,
          ) = Unit

          override fun info(message: String) {
            normalizations += message
          }
        }
      val store = StandalonePhaseStatusStore(connection, Clock.fixed(Instant.EPOCH, ZoneOffset.UTC), diagnostics, false)
      val execution = store.latestWorkflowExecution("workflow")
      assertEquals(Instant.parse("2026-10-09T05:35:36Z"), execution?.startedAt)
      assertEquals(Instant.parse("2026-10-09T05:36:00Z"), execution?.updatedAt)
      assertEquals(1, normalizations.size)
      connection.createStatement().use {
        it.executeUpdate(
          "UPDATE ide_status_workflow_execution SET started_at = 'broken'",
        )
      }
      assertFailsWith<SkillBillRuntimeException> { store.latestWorkflowExecution("workflow") }
    }
  }

  @Test
  fun `execution sequence and revision remain canonical when carrying into another digit`() {
    val directory = Files.createTempDirectory("standalone-status")
    val database = sqliteDatabaseSessionFactory(userHome = directory, environment = mapOf("TEST" to "1"))
    val now = Instant.EPOCH
    val registration =
      StandalonePhaseStatusRegistration(
        repositoryIdentity = "repo", branchCorrelation = "main", issueKey = null, workflowId = null,
        invocationId = "run-1", phaseId = "review", executionId = "execution-1", lifecycleState = "active",
        currentStep = "review", startedAt = now, leaseOwner = "owner", leaseGeneration = 1,
        leaseExpiresAt = now.plusSeconds(3600),
      )
    repeat(10) { index ->
      val record =
        database.transaction {
          it.standalonePhaseStatuses.register(
            registration.copy(invocationId = "run-$index", executionId = "execution-$index"),
          )
        }
      assertEquals((index + 1).toString(), record.runSequence)
    }
    repeat(9) { index ->
      val result =
        database.transaction {
          it.standalonePhaseStatuses.update(
            StandalonePhaseStatusUpdate(
              executionId = "execution-9",
              expectedRevision = (index + 1).toString(),
              leaseOwner = "owner",
              leaseGeneration = 1,
              lifecycleState = "active",
              currentStep = "review",
              currentActivity = null,
              updatedAt = now,
            ),
          )
        }
      assertEquals(StandalonePhaseStatusUpdateResult.ACCEPTED, result)
    }
    val latest = database.read { it.standalonePhaseStatuses.readEligible("repo", "main", now).first() }
    assertEquals("10", latest.runSequence)
    assertEquals("10", latest.statusRevision)
  }

  @Test
  fun `a later monitor registration terminals a prior active monitor on the same branch`() {
    val directory = Files.createTempDirectory("standalone-monitor-supersede")
    val database = sqliteDatabaseSessionFactory(userHome = directory, environment = mapOf("TEST" to "1"))
    val started = Instant.parse("2026-10-10T11:02:24Z")
    database.transaction {
      it.standalonePhaseStatuses.register(monitorRegistration("first", started, started.plusSeconds(3600)))
    }
    val secondStarted = Instant.parse("2026-10-10T12:27:39Z")
    database.transaction {
      it.standalonePhaseStatuses.register(
        monitorRegistration("second", secondStarted, Instant.parse("9999-12-31T00:00:00Z")),
      )
    }
    val rows =
      database.read {
        it.standalonePhaseStatuses.readEligible("repo", "feat/crashlytics", secondStarted)
      }
    assertEquals("terminal", rows.first { it.executionId == "execution-first" }.lifecycleState)
    assertEquals("active", rows.first { it.executionId == "execution-second" }.lifecycleState)
    assertEquals(
      "Superseded by a later monitor run.",
      rows.first { it.executionId == "execution-first" }.terminalResult,
    )
  }

  @Test
  fun `monitor registration preserves its finite lease like an ordinary phase`() {
    val directory = Files.createTempDirectory("standalone-monitor-lease")
    val database = sqliteDatabaseSessionFactory(userHome = directory, environment = mapOf("TEST" to "1"))
    val started = Instant.parse("2026-10-10T11:02:24Z")
    val expired = Instant.parse("2026-10-10T11:02:25Z")
    database.transaction {
      it.standalonePhaseStatuses.register(monitorRegistration("watch", started, expired))
      it.standalonePhaseStatuses.register(
        monitorRegistration("review", started, expired).copy(
          phaseId = "review",
          invocationId = "run-review",
          executionId = "execution-review",
          currentStep = "review",
        ),
      )
    }
    val later = Instant.parse("2026-10-10T12:00:00Z")
    val rows = database.read { it.standalonePhaseStatuses.readEligible("repo", "feat/crashlytics", later) }
    assertEquals("active", rows.first { it.executionId == "execution-watch" }.lifecycleState)
    assertEquals(
      expired,
      rows.first { it.executionId == "execution-watch" }.leaseExpiresAt,
    )
    assertEquals(expired, rows.first { it.executionId == "execution-review" }.leaseExpiresAt)
  }

  @Test
  fun `eligible read pages each slice newest first strictly after an anchor`() {
    val directory = Files.createTempDirectory("standalone-eligible-pages")
    val database = sqliteDatabaseSessionFactory(userHome = directory, environment = mapOf("TEST" to "1"))
    val now = Instant.parse("2026-10-11T00:00:00Z")
    val finishedAt = mapOf("settled-old" to now.minusSeconds(7 * 3600), "settled-1" to now.minusSeconds(1800))
    val names = listOf("live-1", "settled-old", "live-2", "settled-1", "live-3")
    database.transaction {
      names.forEach { name ->
        it.standalonePhaseStatuses.register(
          StandalonePhaseStatusRegistration(
            repositoryIdentity = "repo", branchCorrelation = "main", issueKey = null, workflowId = null,
            invocationId = name, phaseId = "review", executionId = "execution-$name", lifecycleState = "active",
            currentStep = "review", startedAt = now.minusSeconds(8 * 3600), leaseOwner = "owner-$name",
            leaseGeneration = 1, leaseExpiresAt = now.plusSeconds(86_400),
          ),
        )
        finishedAt[name]?.let { finished ->
          it.standalonePhaseStatuses.update(
            StandalonePhaseStatusUpdate(
              executionId = "execution-$name", expectedRevision = "1", leaseOwner = "owner-$name",
              leaseGeneration = 1, lifecycleState = "terminal", currentStep = "review", currentActivity = null,
              updatedAt = finished, finishedAt = finished, terminalResult = "done",
            ),
          )
        }
      }
    }

    fun read(page: StandaloneEligiblePage) =
      database.read { it.standalonePhaseStatuses.readEligible("repo", "main", now, page) }.map { it.invocationId }
    val head = database.read { it.standalonePhaseStatuses.readEligible("repo", "main", now, StandaloneEligiblePage(2)) }

    assertEquals(listOf("live-3", "settled-1"), head.map { it.invocationId })
    val live = StandaloneEligibleSlice.LIVE
    assertEquals(listOf("live-3", "live-2", "live-1"), read(StandaloneEligiblePage(slice = live)))
    assertEquals(listOf("settled-1"), read(StandaloneEligiblePage(slice = StandaloneEligibleSlice.SETTLED)))
    val anchor = StandalonePhaseExecutionAnchor(head.first().runSequence, head.first().invocationId)
    assertEquals(listOf("live-2"), read(StandaloneEligiblePage(1, anchor, live)))
  }

  @Test
  fun `recorded agent environment survives the phase and workflow round trip and stays empty when absent`() {
    val directory = Files.createTempDirectory("standalone-agent-environment")
    val database = sqliteDatabaseSessionFactory(userHome = directory, environment = mapOf("TEST" to "1"))
    val started = Instant.parse("2026-10-14T08:00:00Z")
    val recorded = mapOf("CLAUDE_CONFIG_DIR" to "/home/dev/.claude-work")
    val workflow =
      IdeStatusWorkflowRegistration(
        "repo",
        "main",
        "SKILL-1",
        "goal-parent",
        "workflow-invocation-1",
        "workflow-execution-1",
        "active",
        started,
      )
    database.transaction {
      it.standalonePhaseStatuses.register(monitorRegistration("recorded", started, started.plusSeconds(60)))
      it.standalonePhaseStatuses.register(
        monitorRegistration("env", started, started.plusSeconds(60)).copy(agentEnvironment = recorded),
      )
      it.standalonePhaseStatuses.registerWorkflow(workflow)
      it.standalonePhaseStatuses.registerWorkflow(
        workflow.copy(
          invocationId = "workflow-invocation-2",
          executionId = "workflow-execution-2",
          startedAt = started.plusSeconds(1),
          agentEnvironment = recorded,
        ),
      )
    }
    database.read {
      assertEquals(emptyMap(), it.standalonePhaseStatuses.readByInvocation("run-recorded")?.agentEnvironment)
      assertEquals(recorded, it.standalonePhaseStatuses.readByInvocation("run-env")?.agentEnvironment)
      assertEquals(recorded, it.standalonePhaseStatuses.latestWorkflowExecution("goal-parent")?.agentEnvironment)
    }
  }

  @Test
  fun `a monitor blocked before creating a worktree retains its exact target after reopening the store`() {
    val directory = Files.createTempDirectory("standalone-monitor-admission-failure")
    val started = Instant.parse("2026-10-18T08:41:00Z")
    val registration =
      targetRegistration("blocked", 7, started, started.plusSeconds(3600)).copy(
        lifecycleState = "blocked",
        monitorTarget = targetOf(7).copy(worktreePath = null),
      )
    sqliteDatabaseSessionFactory(userHome = directory, environment = mapOf("TEST" to "1")).transaction {
      it.standalonePhaseStatuses.register(registration)
    }
    val reopened = sqliteDatabaseSessionFactory(userHome = directory, environment = mapOf("TEST" to "1"))
    val saved = reopened.read { it.standalonePhaseStatuses.readByInvocation(registration.invocationId) }
    assertEquals(registration.monitorTarget, saved?.monitorTarget)
    assertEquals("blocked", saved?.lifecycleState)
  }

  @Test
  fun `monitors of different pull requests sharing a branch stay active and are found from any branch`() {
    val directory = Files.createTempDirectory("standalone-monitor-targets")
    val database = sqliteDatabaseSessionFactory(userHome = directory, environment = mapOf("TEST" to "1"))
    val started = Instant.parse("2026-10-10T11:02:24Z")
    val lease = started.plusSeconds(3600)
    database.transaction {
      it.standalonePhaseStatuses.register(targetRegistration("seven", 7, started, lease))
      it.standalonePhaseStatuses.register(targetRegistration("eight", 8, started.plusSeconds(1), lease))
      it.standalonePhaseStatuses.register(monitorRegistration("legacy", started.plusSeconds(2), lease))
    }
    val rows = database.read { it.standalonePhaseStatuses.readEligible("repo", "unrelated-branch", started) }
    assertEquals(
      setOf("execution-seven", "execution-eight"),
      rows.map { it.executionId }.toSet(),
    )
    assertEquals(listOf("active", "active"), rows.map { it.lifecycleState })
    assertEquals(
      setOf("github.com/acme/repo#7", "github.com/acme/repo#8"),
      rows.map { it.monitorTarget?.pullRequestKey }.toSet(),
    )
    val legacy = database.read { it.standalonePhaseStatuses.readEligible("repo", "feat/crashlytics", started) }
    assertEquals("active", legacy.first { it.executionId == "execution-legacy" }.lifecycleState)
  }

  @Test
  fun `monitor cleanup round trips on a target row and is refused on a row without a target`() {
    val directory = Files.createTempDirectory("standalone-monitor-cleanup")
    val database = sqliteDatabaseSessionFactory(userHome = directory, environment = mapOf("TEST" to "1"))
    val started = Instant.parse("2026-10-10T11:02:24Z")
    database.transaction {
      it.standalonePhaseStatuses.register(targetRegistration("seven", 7, started, started.plusSeconds(3600)))
      it.standalonePhaseStatuses.register(monitorRegistration("legacy", started, started.plusSeconds(3600)))
    }
    val retained = StandaloneMonitorCleanup.Retained(MonitorRetentionReason.DIRTY_WORKTREE, "Dirty. Path: /wt/7")
    val result =
      database.transaction {
        it.standalonePhaseStatuses.update(cleanupUpdate("execution-seven", started, retained))
      }
    assertEquals(StandalonePhaseStatusUpdateResult.ACCEPTED, result)
    val record = database.read { it.standalonePhaseStatuses.readByInvocation("run-seven") }
    assertEquals(retained, record?.monitorCleanup)
    assertEquals(targetOf(7), record?.monitorTarget)
    assertFailsWith<IllegalArgumentException> {
      database.transaction {
        it.standalonePhaseStatuses.update(
          cleanupUpdate("execution-legacy", started, StandaloneMonitorCleanup.Released),
        )
      }
    }
  }

  @Test
  fun `a settled QA run keeps its per-criterion report and outcome`() {
    val directory = Files.createTempDirectory("standalone-qa-report")
    val database = sqliteDatabaseSessionFactory(userHome = directory, environment = mapOf("TEST" to "1"))
    val started = Instant.parse("2026-10-20T14:46:26Z")
    database.transaction {
      it.standalonePhaseStatuses.register(
        monitorRegistration("qa", started, started.plusSeconds(3600)).copy(phaseId = "qa"),
      )
    }
    val report =
      StandaloneQaReport(
        listOf(
          StandaloneQaCriterion(
            "AC-1",
            StandaloneQaCriterionStatus.PASSED,
            listOf(StandaloneQaCheck("Picker opens.", true)),
            emptyList(),
          ),
          StandaloneQaCriterion(
            "AC-2",
            StandaloneQaCriterionStatus.FAILED,
            listOf(StandaloneQaCheck("Card field stays empty.", false)),
            listOf(
              StandaloneQaCriterionFinding("QA-1", listOf("Delete the card"), "Field is empty.", "Field shows 4444."),
            ),
          ),
          StandaloneQaCriterion(
            null,
            StandaloneQaCriterionStatus.PASSED,
            listOf(StandaloneQaCheck("App launches.", true)),
            emptyList(),
          ),
        ),
      )
    val settle =
      StandalonePhaseStatusUpdate(
        executionId = "execution-qa", expectedRevision = "1", leaseOwner = "owner", leaseGeneration = 1,
        lifecycleState = "terminal", currentStep = "qa", currentActivity = null, updatedAt = started,
        finishedAt = started, terminalResult = "QA verdict: findings_open.",
        qaOutcome = StandaloneQaOutcome.FINDINGS, qaReport = report,
      )

    assertEquals(
      StandalonePhaseStatusUpdateResult.ACCEPTED,
      database.transaction {
        it.standalonePhaseStatuses.update(settle)
      },
    )

    val record = database.read { it.standalonePhaseStatuses.readByInvocation("run-qa") }
    assertEquals(report, record?.qaReport)
    assertEquals(StandaloneQaOutcome.FINDINGS, record?.qaOutcome)
    assertEquals("QA verdict: findings_open.", record?.terminalResult)
  }

  @Test
  fun `a settled plan run keeps the subtasks it planned`() {
    val directory = Files.createTempDirectory("standalone-plan-report")
    val database = sqliteDatabaseSessionFactory(userHome = directory, environment = mapOf("TEST" to "1"))
    val started = Instant.parse("2026-10-20T16:58:18Z")
    database.transaction {
      it.standalonePhaseStatuses.register(
        monitorRegistration("plan", started, started.plusSeconds(3600)).copy(phaseId = "plan"),
      )
    }
    val report =
      StandalonePlanReport(
        "Record in-workflow review runs",
        ".feature-specs/SKILL-433-review-runs/spec.md",
        listOf(
          StandalonePlanSubtask(1, "Persist review runs", ".feature-specs/SKILL-433-review-runs/01.md", emptyList()),
          StandalonePlanSubtask(2, "Show review runs", ".feature-specs/SKILL-433-review-runs/02.md", listOf(1)),
        ),
      )
    val settle =
      StandalonePhaseStatusUpdate(
        executionId = "execution-plan", expectedRevision = "1", leaseOwner = "owner", leaseGeneration = 1,
        lifecycleState = "terminal", currentStep = "plan", currentActivity = null, updatedAt = started,
        finishedAt = started, terminalResult = "completed", planReport = report,
      )

    assertEquals(
      StandalonePhaseStatusUpdateResult.ACCEPTED,
      database.transaction {
        it.standalonePhaseStatuses.update(settle)
      },
    )

    val record = database.read { it.standalonePhaseStatuses.readByInvocation("run-plan") }
    assertEquals(report, record?.planReport)
    assertEquals("completed", record?.terminalResult)
  }

  @Test
  fun `a partially recorded monitor target fails loudly instead of reading as a legacy row`() {
    val directory = Files.createTempDirectory("standalone-monitor-corrupt-target")
    val dbPath = directory.resolve("status.db")
    val database =
      sqliteDatabaseSessionFactory(
        userHome = directory,
        dbPathOverride = dbPath.toString(),
        environment = mapOf("TEST" to "1"),
      )
    val started = Instant.parse("2026-10-10T11:02:24Z")
    database.transaction {
      it.standalonePhaseStatuses.register(targetRegistration("seven", 7, started, started.plusSeconds(3600)))
    }
    DriverManager.getConnection("jdbc:sqlite:$dbPath").use { connection ->
      connection.createStatement().use {
        it.executeUpdate("UPDATE standalone_phase_status SET monitor_head_ref = NULL")
      }
    }
    assertFailsWith<SkillBillRuntimeException> {
      database.read { it.standalonePhaseStatuses.readByInvocation("run-seven") }
    }
  }

  private fun targetOf(number: Int) =
    StandaloneMonitorTarget(
      pullRequestKey = "github.com/acme/repo#$number",
      pullRequestUrl = "https://github.com/acme/repo/pull/$number",
      headRepository = "github.com/fork-$number/repo",
      headRef = "feat/crashlytics",
      callerRoot = "/caller",
      worktreePath = "/wt/$number",
    )

  private fun targetRegistration(
    suffix: String,
    number: Int,
    startedAt: Instant,
    leaseExpiresAt: Instant,
  ) = monitorRegistration(suffix, startedAt, leaseExpiresAt).copy(monitorTarget = targetOf(number))

  private fun cleanupUpdate(
    executionId: String,
    at: Instant,
    cleanup: StandaloneMonitorCleanup,
  ) = StandalonePhaseStatusUpdate(
    executionId = executionId, expectedRevision = "1", leaseOwner = "owner", leaseGeneration = 1,
    lifecycleState = "terminal", currentStep = "monitor", currentActivity = null, updatedAt = at,
    finishedAt = at, terminalResult = "done", monitorCleanup = cleanup,
  )

  private fun monitorRegistration(
    suffix: String,
    startedAt: Instant,
    leaseExpiresAt: Instant,
  ): StandalonePhaseStatusRegistration =
    StandalonePhaseStatusRegistration(
      repositoryIdentity = "repo",
      branchCorrelation = "feat/crashlytics",
      issueKey = null,
      workflowId = null,
      invocationId = "run-$suffix",
      phaseId = "monitor",
      executionId = "execution-$suffix",
      lifecycleState = "active",
      currentStep = "monitor",
      startedAt = startedAt,
      leaseOwner = "owner",
      leaseGeneration = 1,
      leaseExpiresAt = leaseExpiresAt,
    )
}
