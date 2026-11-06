package skillbill.infrastructure.sqlite.workflow.featuretask

import skillbill.error.core.SkillBillRuntimeException
import skillbill.infrastructure.sqlite.core.migration.area.idestatus.encodeQaDetails
import skillbill.infrastructure.sqlite.sqliteDatabaseSessionFactory
import skillbill.ports.agentrun.activity.model.AgentRunActivity
import skillbill.ports.agentrun.activity.model.AgentRunActivityKind
import skillbill.ports.agentrun.activity.model.AgentRunActivityState
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.ports.idestatus.model.IdeStatusWorkflowRegistration
import skillbill.ports.idestatus.model.StandaloneEligiblePage
import skillbill.ports.idestatus.model.StandaloneEligibleSlice
import skillbill.ports.idestatus.model.StandaloneMonitorCleanup
import skillbill.ports.idestatus.model.StandaloneMonitorTarget
import skillbill.ports.idestatus.model.StandalonePhaseExecutionAnchor
import skillbill.ports.idestatus.model.StandalonePhaseOutputLine
import skillbill.ports.idestatus.model.StandalonePhaseOutputSnapshot
import skillbill.ports.idestatus.model.StandalonePhaseStatusRegistration
import skillbill.ports.idestatus.model.StandalonePhaseStatusUpdate
import skillbill.ports.idestatus.model.StandalonePhaseStatusUpdateResult
import skillbill.ports.idestatus.model.StandalonePlanActivity
import skillbill.ports.idestatus.model.StandalonePlanReport
import skillbill.ports.idestatus.model.StandalonePlanSubtask
import skillbill.ports.idestatus.model.StandaloneQaChecklist
import skillbill.ports.idestatus.model.StandaloneQaChecklistWriteResult
import skillbill.ports.idestatus.model.StandaloneQaOutcome
import skillbill.ports.monitor.model.MonitorRetentionReason
import skillbill.workflow.taskruntime.model.qa.QaRunDetails
import java.nio.file.Files
import java.sql.DriverManager
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class StandalonePhaseStatusStoreTest {
  @Test
  fun `tool completion replaces its start and retained activity keeps stream provenance`() {
    val database = checklistDatabase()
    val record = database.transaction { it.standalonePhaseStatuses.register(checklistRegistration()) }
    val stream =
      StandalonePlanActivity(
        "preplan",
        Instant.EPOCH,
        Instant.EPOCH,
        AgentRunActivity("stream", AgentRunActivityKind.STREAM, "", "claude"),
      )
    val calls =
      (1..140).map { index ->
        val at = Instant.EPOCH.plusSeconds(index.toLong())
        StandalonePlanActivity(
          "preplan",
          at,
          at,
          AgentRunActivity(
            "call-$index",
            AgentRunActivityKind.READ,
            "file-$index",
            "claude",
            state = AgentRunActivityState.STARTED,
          ),
        )
      }

    fun persist(events: List<StandalonePlanActivity>) =
      database.transaction {
        it.standalonePhaseStatuses.writeOutputSnapshot(
          record.executionId,
          StandalonePhaseOutputSnapshot(pendingActivity = events),
        )
      }
    persist(listOf(stream) + calls)
    val call = calls.last()
    val completed =
      call.copy(
        updatedAt = call.at.plusMillis(100),
        event = call.event.copy(state = AgentRunActivityState.FAILED, durationMs = 100),
      )
    repeat(2) { persist(listOf(completed)) }
    val retained = checkNotNull(database.read { it.standalonePhaseStatuses.readOutput(record.executionId) }).activities
    assertEquals(129, retained.size)
    assertEquals(stream, retained.first())
    assertEquals(completed, retained.last())
    assertTrue(retained.none { it.event.id == "call-1" })
  }

  @Test
  fun `output history retains older lines and pages without duplicating a retried snapshot`() {
    val database = checklistDatabase()
    val record = database.transaction { it.standalonePhaseStatuses.register(checklistRegistration()) }
    val lines = (1..7).map { StandalonePhaseOutputLine("line-$it", "[subtask $it] output", Instant.EPOCH, it) }
    val snapshot = StandalonePhaseOutputSnapshot(tail = lines.takeLast(4).map { it.text }, pendingLines = lines)
    repeat(2) { database.transaction { it.standalonePhaseStatuses.writeOutputSnapshot(record.executionId, snapshot) } }
    val newest = database.read { it.standalonePhaseStatuses.readOutputLines(record.executionId, null, 3) }
    val older =
      database.read {
        it.standalonePhaseStatuses.readOutputLines(
          record.executionId,
          newest.last().sequence,
          10,
        )
      }
    assertEquals(listOf(7, 6, 5), newest.map { it.subtaskId })
    assertEquals(listOf(4, 3, 2, 1), older.map { it.subtaskId })
    assertEquals(
      lines.takeLast(4).map {
        it.text
      },
      database.read { it.standalonePhaseStatuses.readOutput(record.executionId)?.tail },
    )
    assertTrue(database.read { it.standalonePhaseStatuses.readOutputLines("other-execution", null, 10) }.isEmpty())
  }

  @Test
  fun `a checklist write stores on a live row and changes no status or lease column, and a finished row refuses`() {
    val database = checklistDatabase()
    val before = database.transaction { it.standalonePhaseStatuses.register(checklistRegistration()) }
    val checklist = StandaloneQaChecklist(invocationDirectory = "/qa/run", testedCommit = "abc")

    val written = database.transaction { it.standalonePhaseStatuses.writeQaChecklist("qa-run", checklist) }
    val stored = checkNotNull(database.read { it.standalonePhaseStatuses.readByInvocation("qa-run") })

    assertEquals(StandaloneQaChecklistWriteResult.WRITTEN, written)
    assertEquals(before.copy(qaChecklist = checklist), stored)
    assertEquals(
      StandaloneQaChecklistWriteResult.MISSING,
      database.transaction { it.standalonePhaseStatuses.writeQaChecklist("other", checklist) },
    )
    database.transaction {
      it.standalonePhaseStatuses.update(
        StandalonePhaseStatusUpdate(
          executionId = stored.executionId, expectedRevision = stored.statusRevision, leaseOwner = stored.leaseOwner,
          leaseGeneration = stored.leaseGeneration, lifecycleState = "canceled", currentStep = "qa",
          currentActivity = null, updatedAt = Instant.EPOCH.plusSeconds(1), finishedAt = Instant.EPOCH.plusSeconds(1),
          terminalResult = "canceled",
        ),
      )
    }
    assertEquals(
      StandaloneQaChecklistWriteResult.FINISHED,
      database.transaction {
        it.standalonePhaseStatuses.writeQaChecklist("qa-run", checklist.copy(testedCommit = "changed"))
      },
    )
    assertEquals(
      checklist,
      database.read { it.standalonePhaseStatuses.readByInvocation("qa-run") }?.qaChecklist,
    )
  }

  @Test
  fun `a malformed or unknown version checklist reads as null with a diagnostic and the row still returns`() {
    val diagnostics = RecordingChecklistDiagnostics()
    val warnings = diagnostics.warnings
    val database = checklistDatabase(diagnostics)
    database.transaction { it.standalonePhaseStatuses.register(checklistRegistration()) }

    listOf("{\"contract_version\":\"9.9\"}", "{\"contract_version\":\"0.1\"}").forEach { stored ->
      warnings.clear()
      DriverManager.getConnection("jdbc:sqlite:${database.resolveDbPath()}").use { connection ->
        connection.prepareStatement("UPDATE standalone_phase_status SET qa_checklist = ?").use { statement ->
          statement.setString(1, stored)
          statement.executeUpdate()
        }
      }

      val record = database.read { it.standalonePhaseStatuses.readByInvocation("qa-run") }

      assertEquals("qa-run", record?.invocationId)
      assertEquals(null, record?.qaChecklist)
      assertEquals(1, warnings.count { "seam=qa_progress kind=unreadable" in it })
    }
  }

  @Test
  fun `stored QA details round trip, and an unknown outcome reads as null with a warning`() {
    val diagnostics = RecordingChecklistDiagnostics()
    val warnings = diagnostics.warnings
    val database = checklistDatabase(diagnostics)
    database.transaction { it.standalonePhaseStatuses.register(checklistRegistration()) }
    val details = QaRunDetails(reportPath = "/qa/report.md", skipReason = "empty_scope")

    fun storeDetails(json: String) =
      DriverManager.getConnection("jdbc:sqlite:${database.resolveDbPath()}").use { connection ->
        connection.prepareStatement("UPDATE standalone_phase_status SET qa_details = ?").use { statement ->
          statement.setString(1, json)
          statement.executeUpdate()
        }
      }

    storeDetails(encodeQaDetails(details))
    assertEquals(details, database.read { it.standalonePhaseStatuses.readByInvocation("qa-run") }?.qaDetails)

    storeDetails("{\"assertions\":[{\"outcome\":\"bogus\",\"title\":\"t\",\"detail\":\"\"}]}")
    val record = database.read { it.standalonePhaseStatuses.readByInvocation("qa-run") }

    assertEquals("qa-run", record?.invocationId)
    assertEquals(null, record?.qaDetails)
    val warning = warnings.single { "seam=qa_details kind=unreadable invocation=qa-run" in it }
    assertTrue("bogus" in warning, warning)
  }

  private fun checklistDatabase(diagnostics: RuntimeDiagnostics = NoopChecklistDiagnostics) =
    sqliteDatabaseSessionFactory(
      userHome = Files.createTempDirectory("standalone-checklist"),
      environment = mapOf("TEST" to "1"),
      diagnostics = diagnostics,
    )

  private fun checklistRegistration() =
    StandalonePhaseStatusRegistration(
      repositoryIdentity = "repo", branchCorrelation = "main", issueKey = null, workflowId = null,
      invocationId = "qa-run", phaseId = "qa", executionId = "execution-qa", lifecycleState = "active",
      currentStep = "qa", startedAt = Instant.EPOCH, leaseOwner = "owner", leaseGeneration = 1,
      leaseExpiresAt = Instant.EPOCH.plusSeconds(3600),
    )

  private class RecordingChecklistDiagnostics : RuntimeDiagnostics {
    val warnings = mutableListOf<String>()

    override fun warning(
      message: String,
      error: Throwable?,
    ) {
      warnings += message
    }

    override fun error(
      message: String,
      error: Throwable?,
    ) = Unit

    override fun info(message: String) = Unit
  }

  private object NoopChecklistDiagnostics : RuntimeDiagnostics {
    override fun warning(
      message: String,
      error: Throwable?,
    ) = Unit

    override fun error(
      message: String,
      error: Throwable?,
    ) = Unit

    override fun info(message: String) = Unit
  }

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
  fun `latest standalone execution prefers an active row over a later blocked launch`() {
    val directory = Files.createTempDirectory("standalone-latest-live")
    val database = sqliteDatabaseSessionFactory(userHome = directory, environment = mapOf("TEST" to "1"))
    val started = Instant.parse("2026-11-02T17:47:23Z")
    database.transaction {
      it.standalonePhaseStatuses.register(
        StandalonePhaseStatusRegistration(
          repositoryIdentity = "repo", branchCorrelation = "main", issueKey = "SKILL-467",
          workflowId = "wftr-plan", invocationId = "run-live", phaseId = "plan",
          executionId = "execution-live", lifecycleState = "active", currentStep = "plan",
          startedAt = started, leaseOwner = "owner-live", leaseGeneration = 1,
          leaseExpiresAt = started.plusSeconds(30),
        ),
      )
      it.standalonePhaseStatuses.register(
        StandalonePhaseStatusRegistration(
          repositoryIdentity = "repo", branchCorrelation = "main", issueKey = "SKILL-467",
          workflowId = "wftr-plan", invocationId = "run-refused", phaseId = "plan",
          executionId = "execution-refused", lifecycleState = "blocked", currentStep = "plan",
          startedAt = started.plusSeconds(120), leaseOwner = "owner-refused", leaseGeneration = 1,
          leaseExpiresAt = started.plusSeconds(150),
        ),
      )
    }

    val latest = database.read { it.standalonePhaseStatuses.latestStandaloneExecution("wftr-plan") }

    assertEquals("run-live", latest?.invocationId)
    assertEquals("active", latest?.lifecycleState)
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
  fun `a branch target round trips and a key and url mismatch is rejected`() {
    val directory = Files.createTempDirectory("standalone-monitor-branch-target")
    val started = Instant.parse("2026-11-02T08:41:00Z")
    val target =
      targetOf(7).copy(
        pullRequestKey = "github.com/acme/repo@feat/x",
        pullRequestUrl = "https://github.com/acme/repo/tree/feat/x",
      )
    val registration = targetRegistration("branch", 7, started, started.plusSeconds(3600)).copy(monitorTarget = target)
    sqliteDatabaseSessionFactory(userHome = directory, environment = mapOf("TEST" to "1")).transaction {
      it.standalonePhaseStatuses.register(registration)
    }
    val saved =
      sqliteDatabaseSessionFactory(userHome = directory, environment = mapOf("TEST" to "1"))
        .read { it.standalonePhaseStatuses.readByInvocation(registration.invocationId) }

    assertEquals(target, saved?.monitorTarget)
    assertEquals("feat/x", saved?.monitorTarget?.branchRef)
    assertFailsWith<IllegalArgumentException> {
      target.copy(pullRequestUrl = "https://github.com/acme/repo/pull/7")
    }
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
  fun `a settled QA run keeps its report text verbatim and outcome`() {
    val directory = Files.createTempDirectory("standalone-qa-report")
    val database = sqliteDatabaseSessionFactory(userHome = directory, environment = mapOf("TEST" to "1"))
    val started = Instant.parse("2026-10-20T14:46:26Z")
    database.transaction {
      it.standalonePhaseStatuses.register(
        monitorRegistration("qa", started, started.plusSeconds(3600)).copy(phaseId = "qa"),
      )
    }
    val reportText = "Verdict: findings_open\n\n### QA-1 Card field stays empty\nField shows 4444.\n"
    val settle = qaSettle(started, reportText)

    assertEquals(
      StandalonePhaseStatusUpdateResult.ACCEPTED,
      database.transaction {
        it.standalonePhaseStatuses.update(settle)
      },
    )

    val record = database.read { it.standalonePhaseStatuses.readByInvocation("run-qa") }
    assertEquals(reportText, record?.qaReportText)
    assertEquals(StandaloneQaOutcome.FINDINGS, record?.qaOutcome)
    assertEquals("QA verdict: findings_open.", record?.terminalResult)
  }

  @Test
  fun `a QA run settled with a legacy criteria matrix reads back without report text`() {
    val directory = Files.createTempDirectory("standalone-qa-legacy-report")
    val database = sqliteDatabaseSessionFactory(userHome = directory, environment = mapOf("TEST" to "1"))
    val started = Instant.parse("2026-10-20T14:46:26Z")
    database.transaction {
      it.standalonePhaseStatuses.register(
        monitorRegistration("qa", started, started.plusSeconds(3600)).copy(phaseId = "qa"),
      )
      it.standalonePhaseStatuses.update(qaSettle(started, reportText = null))
    }
    DriverManager.getConnection("jdbc:sqlite:${database.resolveDbPath()}").use { connection ->
      connection.createStatement().use {
        it.executeUpdate(
          "UPDATE standalone_phase_status SET qa_report = " +
            "'[{\"id\":\"AC-1\",\"status\":\"passed\",\"checks\":[],\"findings\":[]}]' WHERE invocation_id = 'run-qa'",
        )
      }
    }

    val record = database.read { it.standalonePhaseStatuses.readByInvocation("run-qa") }

    assertEquals(null, record?.qaReportText)
    assertEquals(StandaloneQaOutcome.FINDINGS, record?.qaOutcome)
  }

  @Test
  fun `a QA update stores its details and a later update without details keeps them`() {
    val directory = Files.createTempDirectory("standalone-qa-details")
    val database = sqliteDatabaseSessionFactory(userHome = directory, environment = mapOf("TEST" to "1"))
    val started = Instant.parse("2026-10-20T14:46:26Z")
    val details = QaRunDetails(reportPath = "/qa/report.md", skipReason = "empty_scope")
    database.transaction {
      it.standalonePhaseStatuses.register(
        monitorRegistration("qa", started, started.plusSeconds(3600)).copy(phaseId = "qa"),
      )
    }
    val progress =
      qaSettle(started, reportText = null).copy(
        lifecycleState = "active",
        finishedAt = null,
        terminalResult = null,
        qaOutcome = null,
        qaDetails = details,
      )

    assertEquals(
      StandalonePhaseStatusUpdateResult.ACCEPTED,
      database.transaction { it.standalonePhaseStatuses.update(progress) },
    )
    assertEquals(details, database.read { it.standalonePhaseStatuses.readByInvocation("run-qa") }?.qaDetails)

    val reportText = "Verdict: findings_open\n"
    assertEquals(
      StandalonePhaseStatusUpdateResult.ACCEPTED,
      database.transaction {
        it.standalonePhaseStatuses.update(qaSettle(started, reportText).copy(expectedRevision = "2"))
      },
    )
    val record = database.read { it.standalonePhaseStatuses.readByInvocation("run-qa") }
    assertEquals(details, record?.qaDetails)
    assertEquals(reportText, record?.qaReportText)
  }

  private fun qaSettle(
    started: Instant,
    reportText: String?,
  ) = StandalonePhaseStatusUpdate(
    executionId = "execution-qa", expectedRevision = "1", leaseOwner = "owner", leaseGeneration = 1,
    lifecycleState = "terminal", currentStep = "qa", currentActivity = null, updatedAt = started,
    finishedAt = started, terminalResult = "QA verdict: findings_open.",
    qaOutcome = StandaloneQaOutcome.FINDINGS, qaReportText = reportText,
  )

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
        listOf("/store/specs/SKILL-433-review-runs/spec.md", "/store/specs/SKILL-433-review-runs/01.md"),
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
