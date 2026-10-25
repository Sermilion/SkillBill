package dev.skillbill.tray.core.data.repository

import skillbill.infrastructure.host.jvm.JdkHostPlatformPort
import skillbill.contracts.persistence.DesktopReadSchema
import dev.skillbill.tray.core.data.ScriptedSignals
import dev.skillbill.tray.core.data.WorkStack
import dev.skillbill.tray.core.data.session.MonotonicClock
import dev.skillbill.tray.core.data.signal.DesktopCommitSignalSource
import dev.skillbill.tray.core.data.store.candidateQuery
import dev.skillbill.tray.core.data.workStack
import dev.skillbill.tray.core.domain.model.*
import dev.skillbill.tray.core.domain.model.Retention
import dev.skillbill.tray.core.domain.repository.Outcome
import dev.skillbill.tray.core.testing.TestClock
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import org.junit.jupiter.api.Test
import skillbill.infrastructure.sqlite.establishTemporarySchemaReadiness
import skillbill.infrastructure.sqlite.sqliteSessionFactoryForTests
import skillbill.di.core.RuntimeComponent
import skillbill.di.core.RuntimeContext
import skillbill.di.core.TransportContext
import skillbill.di.core.WorkflowOpsContext
import skillbill.di.core.OptionalCallbacks
import skillbill.di.core.create
import skillbill.model.EnvironmentContext
import skillbill.ports.idestatus.model.WorkBoardRequest
import skillbill.ports.idestatus.model.WorkBoardWindow
import skillbill.goalrunner.model.GoalRunnerControlState
import skillbill.goalrunner.model.GoalRunnerExecutionLease
import skillbill.infrastructure.host.JdkFeatureTaskRuntimeWorkerSupervisor
import skillbill.infrastructure.contracts.workflow.decomposition.DecompositionManifestSchemaValidator
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.ports.workflow.decomposition.encodeManifestWireMap
import skillbill.ports.workflow.model.WorkflowFamily
import skillbill.ports.workflow.toRecord
import skillbill.workflow.engine.WorkflowEngine
import skillbill.workflow.engine.model.WorkflowArtifactPatch
import skillbill.workflow.engine.model.WorkflowUpdateInput
import skillbill.workflow.engine.model.DurableWorkflowArtifactFamily
import skillbill.workflow.decomposition.model.DecompositionManifest
import skillbill.workflow.decomposition.model.DecompositionSubtask
import skillbill.workflow.decomposition.model.DecompositionDependency
import skillbill.contracts.JsonCodec
import skillbill.workflow.decomposition.model.CurrentSubtaskIntent
import skillbill.workflow.model.WorkflowStatus
import skillbill.workflow.model.FeatureTaskWorkflowMode
import skillbill.idestatus.model.IdeStatusLaunchScope
import skillbill.ports.idestatus.model.StandalonePhaseStatusRegistration
import skillbill.ports.idestatus.model.StandalonePhaseStatusUpdate
import java.nio.file.Files
import java.io.IOException
import java.time.Clock as JavaClock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.time.Clock
import kotlin.time.toKotlinInstant
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

/** The board, detail and control repositories against real runtime-migrated SQLite stores, wired as the app wires them. */
class WorkStackTest {
  private val now = Instant.now()

  @Test
  fun `keyless retries have one sidebar entry and retain older attempts in detail`() = runBlocking {
    establishTemporarySchemaReadiness("desktop-keyless-retries").use { fixture ->
      val factory = sqliteSessionFactoryForTests(fixture.tempDir, fixture.dbPath.toString(), emptyMap())
      factory.transaction { unit ->
        val first = registration(fixture.tempDir.toString(), "older", now.minusSeconds(60), "main")
          .copy(issueKey = null, lifecycleState = "blocked")
        unit.standalonePhaseStatuses.register(first)
        val canceled = unit.standalonePhaseStatuses.register(first.copy(invocationId = "canceled", executionId = "execution-canceled",
          startedAt = now.minusSeconds(40), lifecycleState = "paused"))
        unit.standalonePhaseStatuses.cancel(canceled, now.minusSeconds(20))
        unit.standalonePhaseStatuses.register(first.copy(invocationId = "latest", executionId = "execution-latest",
          startedAt = now.minusSeconds(10)))
        unit.standalonePhaseStatuses.register(first.copy(invocationId = "other", executionId = "execution-other",
          branchCorrelation = "other"))
      }
      val source = source(fixture.tempDir, TestClock(now.toKotlinInstant()))
      try {
        source.selectDatabase(fixture.dbPath.toString())
        assertEquals(setOf("latest", "other"), value(source.board(WorkSection.NEEDS_YOU, Retention.DAY))
          .items.map { it.identity.id }.toSet())
        assertTrue(value(source.board(WorkSection.HISTORY, Retention.DAY)).items.isEmpty())
        assertTrue(value(source.board(WorkSection.RECENT, Retention.DAY)).items.isEmpty())
        assertTrue(value(source.board(WorkSection.PHASES, Retention.DAY)).items.isEmpty())
        factory.transaction { unit ->
          val latest = assertNotNull(unit.standalonePhaseStatuses.readByInvocation("latest"))
          unit.standalonePhaseStatuses.cancel(latest, now.minusSeconds(1))
        }
        val history = value(source.board(WorkSection.HISTORY, Retention.DAY))
        assertEquals(listOf("latest"), history.items.map { it.identity.id })
        assertEquals(Lifecycle.CANCELED, history.items.single().lifecycle)
        assertEquals(listOf("latest"), value(source.board(WorkSection.RECENT, Retention.DAY)).items.map { it.identity.id })
        assertEquals(listOf("other"), value(source.board(WorkSection.NEEDS_YOU, Retention.DAY)).items.map { it.identity.id })
        val detail = assertIs<Outcome.Value<Detail>>(source.detail(history.items.single().identity)).value
        assertEquals(setOf("older", "canceled", "latest"), detail.executionHistory.map { it.invocationId }.toSet())
      } finally { source.closeAndJoin() }
    }
  }

  @Test
  fun `detail accepts its snapshot when a writer commits while observation time resolves`() = runBlocking {
    establishTemporarySchemaReadiness("desktop-detail-concurrent-writer").use { fixture ->
      val factory = sqliteSessionFactoryForTests(fixture.tempDir, fixture.dbPath.toString(), emptyMap())
      factory.transaction { it.standalonePhaseStatuses.register(registration(fixture.tempDir.toString(), "selected", now, "main")) }
      var armed = false
      val writes = AtomicInteger()
      val observed = now.toKotlinInstant()
      val clock = object : Clock {
        override fun now(): kotlin.time.Instant {
          if (armed) factory.transaction {
            it.standalonePhaseStatuses.register(registration(fixture.tempDir.toString(), "writer-${writes.incrementAndGet()}", now, "main"))
          }
          return observed
        }
      }
      val source = workStack(fixture.tempDir.toRealPath(), clock)
      try {
        source.selectDatabase(fixture.dbPath.toString())
        val selected = value(source.board(WorkSection.PHASES, Retention.WEEK)).items.single()
        armed = true
        val detail = assertIs<Outcome.Value<Detail>>(source.detail(selected.identity)).value
        assertEquals(selected.identity, detail.item.identity)
        assertEquals(1, writes.get())
      } finally { source.closeAndJoin() }
    }
  }

  @Test
  fun `detail publishes its snapshot without retrying unrelated commits after projection`() = runBlocking {
    establishTemporarySchemaReadiness("desktop-detail-snapshot-writer").use { fixture ->
      val factory = sqliteSessionFactoryForTests(fixture.tempDir, fixture.dbPath.toString(), emptyMap())
      factory.transaction { it.standalonePhaseStatuses.register(registration(fixture.tempDir.toString(), "selected", now, "main")) }
      var armed = false
      val writes = AtomicInteger()
      val source = workStack(fixture.tempDir.toRealPath(), TestClock(now.toKotlinInstant()), afterSnapshot = {
        if (armed) factory.transaction {
          it.standalonePhaseStatuses.register(registration(fixture.tempDir.toString(), "writer-${writes.incrementAndGet()}", now, "main"))
        }
      })
      try {
        source.selectDatabase(fixture.dbPath.toString())
        val selected = value(source.board(WorkSection.PHASES, Retention.WEEK)).items.single()
        armed = true
        val detail = assertIs<Outcome.Value<Detail>>(source.detail(selected.identity)).value
        assertEquals(selected.identity, detail.item.identity)
        assertEquals(1, writes.get())
        armed = false
        val live = value(source.board(WorkSection.PHASES, Retention.WEEK))
        assertTrue(live.revision!! > detail.item.databaseRevision!!)
        assertTrue(live.items.any { it.identity.id == "writer-1" })
      } finally { source.closeAndJoin() }
    }
  }

  @Test
  fun `history publishes a coherent snapshot when an unrelated writer commits after projection`() = runBlocking {
    establishTemporarySchemaReadiness("desktop-history-concurrent-writer").use { fixture ->
      val factory = sqliteSessionFactoryForTests(fixture.tempDir, fixture.dbPath.toString(), emptyMap())
      val finished = now.minusSeconds(30)
      factory.transaction { unit ->
        val record = unit.standalonePhaseStatuses.register(registration(fixture.tempDir.toString(), "finished", finished.minusSeconds(10), "main"))
        unit.standalonePhaseStatuses.update(StandalonePhaseStatusUpdate(record.executionId, record.statusRevision,
          record.leaseOwner, record.leaseGeneration, "terminal", "review", null, finished, finishedAt = finished, terminalResult = "completed"))
      }
      val ticks = AtomicInteger()
      val writes = AtomicInteger()
      val source = workStack(fixture.tempDir.toRealPath(), TestClock(now.toKotlinInstant()),
        monotonicClock = MonotonicClock {
          if (ticks.incrementAndGet() % 4 == 0) {
            val id = "writer-${writes.incrementAndGet()}"
            factory.transaction { it.standalonePhaseStatuses.register(registration(fixture.tempDir.toString(), id, now, "main")) }
          }
          System.nanoTime()
        })
      try {
        source.selectDatabase(fixture.dbPath.toString())
        val history = value(source.board(WorkSection.HISTORY, Retention.WEEK))
        assertEquals(listOf("finished"), history.items.map { it.identity.id })
        assertEquals(1, writes.get())
        val live = value(source.board(WorkSection.PHASES, Retention.WEEK))
        assertTrue(live.revision!! > history.revision!!)
        assertTrue(live.items.any { it.identity.id == "writer-1" })
      } finally { source.closeAndJoin() }
    }
  }

  @Test
  fun `history skips legacy verification rows without repository identity and retains readable siblings`() = runBlocking {
    establishTemporarySchemaReadiness("desktop-history-missing-identity").use { fixture ->
      val finished = now.minusSeconds(30)
      fixture.connection.prepareStatement(
        "INSERT INTO feature_verify_workflows (workflow_id, workflow_status, started_at, updated_at, state_entered_at, finished_at) VALUES (?, 'completed', ?, ?, ?, ?)",
      ).use { statement ->
        repeat(12) { index ->
          statement.setString(1, "legacy-verify-$index")
          for (column in 2..5) statement.setString(column, now.minusSeconds(5).toString())
          statement.executeUpdate()
        }
      }
      val factory = sqliteSessionFactoryForTests(fixture.tempDir, fixture.dbPath.toString(), emptyMap())
      factory.transaction { unit ->
        val record = unit.standalonePhaseStatuses.register(registration(fixture.tempDir.toString(), "readable-history", finished.minusSeconds(10), "main"))
        unit.standalonePhaseStatuses.update(StandalonePhaseStatusUpdate(record.executionId, record.statusRevision,
          record.leaseOwner, record.leaseGeneration, "terminal", "review", null, finished, finishedAt = finished, terminalResult = "completed"))
      }
      val source = source(fixture.tempDir, TestClock(now.toKotlinInstant()))
      try {
        source.selectDatabase(fixture.dbPath.toString())
        val board = value(source.board(WorkSection.HISTORY, Retention.WEEK))
        assertEquals(listOf("readable-history"), board.items.map { it.identity.id })
        assertTrue("missing_repository_identity" in board.diagnostics)
        assertNull(board.nextCursor)
        fixture.connection.createStatement().use { statement ->
          statement.executeQuery("SELECT COUNT(*) FROM feature_verify_workflows").use { rows ->
            assertTrue(rows.next())
            assertEquals(12, rows.getInt(1))
          }
        }
      } finally { source.closeAndJoin() }
    }
  }

  @Test
  fun `observer registration failure stays typed until explicit refresh reconnects`() = runBlocking {
    establishTemporarySchemaReadiness("desktop-observer-registration-failure").use { fixture ->
      val attempts = AtomicInteger()
      val source = workStack(fixture.tempDir.toRealPath(), TestClock(now.toKotlinInstant()),
        commitSignals = { diagnostics ->
          ScriptedSignals { selected, ready ->
            if (attempts.incrementAndGet() == 1) flow { throw IOException("watch registration failed") }
            else DesktopCommitSignalSource(diagnostics).changes(selected, ready)
          }
        })
      try {
        source.selectDatabase(fixture.dbPath.toString())
        val failure = assertIs<Outcome.Failure>(source.board(WorkSection.PHASES, Retention.DAY))
        assertEquals(ProblemCode.OBSERVATION_UNAVAILABLE, failure.problem.code)
        repeat(3) {
          assertEquals(ProblemCode.OBSERVATION_UNAVAILABLE,
            assertIs<Outcome.Failure>(source.board(WorkSection.PHASES, Retention.DAY)).problem.code)
        }
        assertEquals(1, attempts.get())
        source.refresh()
        assertTrue(value(source.board(WorkSection.PHASES, Retention.DAY)).items.isEmpty())
        assertEquals(2, attempts.get())
      } finally {
        source.closeAndJoin()
      }
    }
  }

  @Test
  fun `exclusive writer lock reports busy and reconnect recovers after rollback`() = runBlocking {
    establishTemporarySchemaReadiness("desktop-busy-store").use { fixture ->
      val source = source(fixture.tempDir, TestClock(now.toKotlinInstant()))
      source.selectDatabase(fixture.dbPath.toString())
      try {
        fixture.connection.createStatement().use { it.execute("PRAGMA locking_mode = EXCLUSIVE"); it.execute("BEGIN EXCLUSIVE") }
        assertEquals(StorageFailure.BUSY, (source.board(WorkSection.PHASES, Retention.DAY) as Outcome.Failure).problem.storageFailure)
        fixture.connection.createStatement().use { it.execute("ROLLBACK"); it.execute("PRAGMA locking_mode = NORMAL") }
        fixture.connection.close()
        source.refresh()
        assertTrue(value(source.board(WorkSection.PHASES, Retention.DAY)).items.isEmpty())
      } finally { source.close() }
    }
  }

  @Test
  fun `live ordering retains nanosecond precision instead of breaking ties by identity`() = runBlocking {
    establishTemporarySchemaReadiness("desktop-precise-order").use { fixture ->
      val factory = sqliteSessionFactoryForTests(fixture.tempDir, fixture.dbPath.toString(), emptyMap())
      val base = now.minusSeconds(10).truncatedTo(java.time.temporal.ChronoUnit.SECONDS)
      factory.transaction { unit ->
        unit.standalonePhaseStatuses.register(registration(fixture.tempDir.toString(), "z-older", base.plusNanos(1), "main").copy(issueKey = "SKILL-8000"))
        unit.standalonePhaseStatuses.register(registration(fixture.tempDir.toString(), "a-newer", base.plusNanos(2), "main").copy(issueKey = "SKILL-8001"))
      }
      val source = source(fixture.tempDir, TestClock(now.toKotlinInstant()))
      try {
        source.selectDatabase(fixture.dbPath.toString())
        assertEquals(listOf("a-newer", "z-older"), value(source.board(WorkSection.PHASES, Retention.DAY)).items.map { it.identity.id })
      } finally { source.close() }
    }
  }

  @Test
  fun `a plan the goal command started is listed with goal runs and reads as the goal`() = runBlocking {
    establishTemporarySchemaReadiness("desktop-goal-plan").use { fixture ->
      val factory = sqliteSessionFactoryForTests(fixture.tempDir, fixture.dbPath.toString(), emptyMap())
      factory.transaction { unit ->
        unit.standalonePhaseStatuses.register(registration(fixture.tempDir.toString(), "goal-plan", now.minusSeconds(10), "main")
          .copy(phaseId = "plan", currentStep = "plan", launchScope = IdeStatusLaunchScope.GOAL))
        unit.standalonePhaseStatuses.register(registration(fixture.tempDir.toString(), "phase-plan", now.minusSeconds(5), "main")
          .copy(issueKey = "SKILL-424", phaseId = "plan", currentStep = "plan"))
      }
      val source = source(fixture.tempDir, TestClock(now.toKotlinInstant()))
      try {
        source.selectDatabase(fixture.dbPath.toString())
        val goal = value(source.board(WorkSection.GOALS, Retention.DAY)).items.single()
        assertEquals("goal-plan", goal.identity.id)
        assertTrue(goal.goalLaunched)
        assertFalse(goal.showsAsStandalonePhase())
        val phase = value(source.board(WorkSection.PHASES, Retention.DAY)).items.single()
        assertEquals("phase-plan", phase.identity.id)
        assertTrue(phase.showsAsStandalonePhase())
      } finally { source.close() }
    }
  }

  @Test
  fun `older schema and changed Room identity fail without migration or metadata repair`() = runBlocking {
    establishTemporarySchemaReadiness("desktop-schema-refusal").use { fixture ->
      val source = source(fixture.tempDir, TestClock(now.toKotlinInstant()))
      try {
        fixture.connection.createStatement().use { it.execute("PRAGMA user_version = 51") }
        source.selectDatabase(fixture.dbPath.toString())
        assertEquals(StorageFailure.INCOMPATIBLE_SCHEMA, (source.board(WorkSection.GOALS, Retention.DAY) as Outcome.Failure).problem.storageFailure)
        fixture.connection.createStatement().use { statement ->
          statement.executeQuery("PRAGMA user_version").use { it.next(); assertEquals(51, it.getInt(1)) }
          statement.execute("PRAGMA user_version = 52")
          statement.execute("UPDATE room_master_table SET identity_hash = 'foreign-schema' WHERE id = 42")
        }
        assertEquals(StorageFailure.INCOMPATIBLE_SCHEMA, (source.board(WorkSection.GOALS, Retention.DAY) as Outcome.Failure).problem.storageFailure)
        fixture.connection.createStatement().use { statement ->
          statement.executeQuery("SELECT identity_hash FROM room_master_table WHERE id = 42").use { it.next(); assertEquals("foreign-schema", it.getString(1)) }
        }
      } finally { source.close() }
    }
  }

  @Test
  fun `selecting an older store applies runtime migrations and preserves its saved work`() = runBlocking {
    establishTemporarySchemaReadiness("desktop-upgrade-selected-store").use { fixture ->
      val factory = sqliteSessionFactoryForTests(fixture.tempDir, fixture.dbPath.toString(), emptyMap())
      factory.transaction { unit ->
        unit.standalonePhaseStatuses.register(registration(fixture.tempDir.toString(), "saved", now, "main")
          .copy(lifecycleState = "blocked"))
      }
      fixture.connection.createStatement().use { statement ->
        statement.execute("DELETE FROM schema_migrations WHERE version = 56")
        statement.execute("PRAGMA user_version = 55")
      }
      val source = source(fixture.tempDir, TestClock(now.toKotlinInstant()))
      try {
        source.selectDatabase(fixture.dbPath.toString())
        assertEquals(listOf("saved"), value(source.board(WorkSection.NEEDS_YOU, Retention.DAY)).items.map { it.identity.id })
        val migratedAt = fixture.connection.createStatement().use { statement ->
          statement.executeQuery("SELECT applied_at FROM schema_migrations WHERE version = 56").use { rows ->
            assertTrue(rows.next())
            rows.getString(1)
          }
        }
        source.selectDatabase(fixture.dbPath.toString())
        assertEquals(listOf("saved"), value(source.board(WorkSection.NEEDS_YOU, Retention.DAY)).items.map { it.identity.id })
        fixture.connection.createStatement().use { statement ->
          statement.executeQuery("PRAGMA user_version").use { rows ->
            assertTrue(rows.next())
            assertEquals(DesktopReadSchema.VERSION, rows.getInt(1))
          }
          statement.executeQuery("SELECT applied_at FROM schema_migrations WHERE version = 56").use { rows ->
            assertTrue(rows.next())
            assertEquals(migratedAt, rows.getString(1))
            assertFalse(rows.next())
          }
        }
      } finally { source.close() }
    }
  }

  @Test
  fun `live phase behind five thousand terminal records is projected without visiting history`() = runBlocking {
    establishTemporarySchemaReadiness("desktop-history-starvation").use { fixture ->
      val factory = sqliteSessionFactoryForTests(fixture.tempDir, fixture.dbPath.toString(), emptyMap())
      fixture.connection.createStatement().use { it.executeUpdate(
        "INSERT INTO goal_issue_progress (parent_workflow_id, issue_key, first_started_at, status, state_entered_at) " +
          "VALUES ('older-goal', 'SKILL-423', '${now.minusSeconds(7200)}', 'running', '$now')") }
      factory.transaction { unit ->
        val engine = WorkflowEngine()
        val base = engine.openRecord(WorkflowFamily.TASK_RUNTIME.definition, "older-goal", "owned-test", "implement")
        val manifest = DecompositionManifest(issueKey = "SKILL-423", featureName = "owned desktop fixture",
          parentSpecPath = ".feature-specs/SKILL-423/spec.md", baseBranch = "main", featureBranch = "feat/SKILL-423",
          currentSubtaskIntent = CurrentSubtaskIntent(997, "blocked"),
          subtasks = (1..1000).map { id -> DecompositionSubtask(id, "Subtask $id", ".feature-specs/SKILL-423/spec_subtask_$id.md",
            status = if (id == 997) "blocked" else if (id % 2 == 0) "complete" else "pending",
            blockedReason = if (id == 997) "Exact goal reason " + "x".repeat(2000) else null,
            lastResumableStep = if (id == 997) "implement" else null,
            dependencies = if (id == 1000) (1..999).map { DecompositionDependency(it, false, false) } else emptyList()) })
        val encoded = DecompositionManifestSchemaValidator().encodeManifestWireMap(manifest)
        val parent = engine.updateRecord(WorkflowFamily.TASK_RUNTIME.definition, base, WorkflowUpdateInput(
          workflowStatus = WorkflowStatus.RUNNING, currentStepId = "implement", stepUpdates = null,
          artifactsPatch = WorkflowArtifactPatch.from(mapOf(DurableWorkflowArtifactFamily.DECOMPOSITION_RUNTIME.label() to encoded)),
          sessionId = "owned-test"))
        unit.workflowStates.saveFeatureTaskWorkflow(parent.toRecord().copy(issueKey = "SKILL-423"), FeatureTaskWorkflowMode.RUNTIME)
        val process = JdkFeatureTaskRuntimeWorkerSupervisor(object : RuntimeDiagnostics {
          override fun warning(message: String, error: Throwable?) = Unit
          override fun error(message: String, error: Throwable?) = Unit
        }, JdkHostPlatformPort).currentProcess()
        unit.goalRunnerControls.persistControlState("older-goal", GoalRunnerControlState(repositoryIdentity = "repo-root-realpath-v1:${fixture.tempDir.toRealPath()}",
          executionLease = GoalRunnerExecutionLease(1, "owned-test", process.hostIdentity, process.bootIdentity, process.pid,
            process.processBirthToken, now, now.plusSeconds(3600))))
        unit.standalonePhaseStatuses.register(registration(fixture.tempDir.toString(), "live", now.minusSeconds(3600), "live-branch"))
        repeat(5000) { index ->
          val record = unit.standalonePhaseStatuses.register(registration(fixture.tempDir.toString(), "done-$index", now.minusSeconds(30), "branch-$index"))
          unit.standalonePhaseStatuses.update(StandalonePhaseStatusUpdate(record.executionId, record.statusRevision,
            record.leaseOwner, record.leaseGeneration, "terminal", "review", null, now.minusSeconds(1),
            finishedAt = now.minusSeconds(1), terminalResult = "completed"))
        }
      }
      val runtime = RuntimeComponent::class.create(RuntimeContext(
        EnvironmentContext(fixture.dbPath.toString(), environment = emptyMap(), userHome = fixture.tempDir,
          repositoryRoot = fixture.tempDir), TransportContext(), WorkflowOpsContext(), OptionalCallbacks()))
      val statusRequest = skillbill.engine.goalrunner.model.GoalRunnerStatusRequest("SKILL-423",
        repoRoot = fixture.tempDir, pruneCheckpointRefs = false, includeCompletedSubtaskValidation = false)
      val originalStatus = assertNotNull(runtime.goalRunnerStatusService.status(statusRequest))
      val boundedStatus = assertNotNull(runtime.goalRunnerStatusService.statusForWorkflow("older-goal", statusRequest))
      assertEquals(originalStatus, boundedStatus)
      assertEquals(997, boundedStatus.currentSubtaskId)
      assertEquals(500, boundedStatus.completeCount)
      assertEquals(1, boundedStatus.blockedCount)
      assertEquals(499, boundedStatus.pendingCount)
      assertEquals(2018, boundedStatus.currentSubtaskBlockedReason?.length)
      val shared = runtime.ideStatusService.board(WorkBoardRequest(WorkBoardWindow.SIX_HOURS, now, 10))
      assertEquals(setOf("older-goal", "live"), shared.items.filter { it.liveSection != null }
        .map { it.snapshot.execution?.invocationId ?: it.snapshot.workflowId }.toSet())
      assertEquals(10, shared.candidatesExamined)
      val source = source(fixture.tempDir, TestClock(now.toKotlinInstant()))
      try {
        source.selectDatabase(fixture.dbPath.toString())
        val query = candidateQuery(WorkSection.PHASES,
          now.minusSeconds(21600).toString(), now.toString(), null, null, "", 10)
        val plan = fixture.connection.prepareStatement("EXPLAIN QUERY PLAN ${query.sql}").use { statement ->
          statement.setString(1, now.toString()); statement.setString(2, now.toString())
          statement.setString(3, ""); statement.setString(4, "")
          (5..8).forEach { statement.setNull(it, java.sql.Types.NULL) }
          statement.setInt(9, 10)
          statement.executeQuery().use { rows -> buildList { while (rows.next()) add(rows.getString(4)) } }
        }
        assertTrue(plan.any { "SEARCH s USING" in it }, plan.joinToString("\n"))
        val first = value(source.board(WorkSection.PHASES, Retention.SIX_HOURS))
        assertEquals(listOf("live"), first.items.map { it.identity.id })
        assertContains(first.diagnostics, "projection_items=1")
        val goals = value(source.board(WorkSection.GOALS, Retention.SIX_HOURS))
        assertEquals(listOf("older-goal"), goals.items.map { it.identity.id })
        assertContains(goals.diagnostics, "projection_items=1")
        Files.createDirectories(fixture.tempDir.resolve(".git"))
        Files.writeString(fixture.tempDir.resolve(".git/HEAD"), "ref: refs/heads/feat/checked-out-now\n")
        val selectedDetail = (source.detail(goals.items.single().identity) as Outcome.Value).value
        assertEquals("feat/checked-out-now", selectedDetail.item.branch)
        // The subtask list fills its head page up to the runtime's page-size cap, whatever page size the tray asks for.
        assertEquals((1..100).toList(), selectedDetail.subtasks.map { it.id })
        assertEquals(1000, selectedDetail.completeCount + selectedDetail.pendingCount + selectedDetail.blockedCount)
        assertNotNull(selectedDetail.cursors[DetailList.SUBTASKS])
        // Lists page by their own continuation, never by offset, so the tail is reached by following the subtask cursor.
        var tailDetail = selectedDetail
        repeat(100) {
          val next = tailDetail.cursors[DetailList.SUBTASKS] ?: return@repeat
          tailDetail = (source.detail(goals.items.single().identity, next, DetailList.SUBTASKS) as Outcome.Value).value
        }
        assertNull(tailDetail.cursors[DetailList.SUBTASKS], "The subtask list did not end within 100 pages.")
        assertEquals(1000, tailDetail.subtasks.last().id)
        assertEquals(10, tailDetail.subtasks.last().dependencies.size)
        assertEquals((1..10).toList(), selectedDetail.dependencies.map { it.dependency.subtaskId })
        val dependenciesCursor = assertNotNull(selectedDetail.cursors[DetailList.DEPENDENCIES])
        val nextDependencies = (source.detail(goals.items.single().identity, dependenciesCursor,
          DetailList.DEPENDENCIES) as Outcome.Value).value
        assertEquals((11..20).toList(), nextDependencies.dependencies.map { it.dependency.subtaskId })
        // A store write between pages keeps a list's continuation usable: it resumes after a row, not at an offset.
        fixture.connection.createStatement().use { statement ->
          statement.executeUpdate("UPDATE ${DesktopReadSchema.REVISIONS_TABLE} SET revision = revision + 1 " +
            "WHERE topic = '${DesktopReadSchema.GOALS_TOPIC}'")
        }
        assertTrue(value(source.board(WorkSection.GOALS, Retention.SIX_HOURS)).revision!! > selectedDetail.item.databaseRevision!!)
        val afterWrite = assertIs<Outcome.Value<Detail>>(source.detail(goals.items.single().identity,
          assertNotNull(nextDependencies.cursors[DetailList.DEPENDENCIES]), DetailList.DEPENDENCIES)).value
        assertEquals((21..30).toList(), afterWrite.dependencies.map { it.dependency.subtaskId })
        // A continuation belongs to its own list: presenting it for another list is refused, never applied to that list.
        assertIs<Outcome.Failure>(source.detail(goals.items.single().identity, dependenciesCursor, DetailList.SUBTASKS))
        val target = goals.items.single()
        assertIs<Outcome.Failure>(source.pause(target.copy(repositoryRoot = "/wrong-repository")))
        assertIs<Outcome.Failure>(source.pause(target.copy(databaseGeneration = target.databaseGeneration + 1)))
        factory.transaction { unit ->
          val current = unit.goalRunnerControls.controlState("older-goal")
          unit.goalRunnerControls.persistControlState("older-goal", current.copy(executionLease = current.executionLease!!.copy(ownerToken = "new-owner", generation = 2)))
        }
        assertIs<Outcome.Failure>(source.pause(target))
        val current = value(source.board(WorkSection.GOALS, Retention.SIX_HOURS)).items.single()
        assertEquals(Outcome.Value(ControlConfirmation.INTENT_RECORDED), source.pause(current))
        assertIs<Outcome.Failure>(source.pause(current))
        assertIs<Outcome.Failure>(source.stop(current))
        assertFalse(factory.read { it.goalRunnerControls.controlState("older-goal").goalCompletedAt != null })
        val history = value(source.board(WorkSection.HISTORY, Retention.DAY, filter = "branch-4999"))
        assertEquals(listOf("done-4999"), history.items.map { it.identity.id })
        assertEquals(first.items.map { it.identity to it.lifecycle }, value(source.board(WorkSection.PHASES, Retention.WEEK)).items.map { it.identity to it.lifecycle })
        factory.transaction { unit ->
          val manifest = DecompositionManifest(issueKey = "SKILL-423", featureName = "last dependency blocks",
            parentSpecPath = ".feature-specs/SKILL-423/spec.md", baseBranch = "main", featureBranch = "feat/SKILL-423",
            currentSubtaskIntent = CurrentSubtaskIntent(1000, "start"),
            subtasks = (1..1001).map { id -> DecompositionSubtask(id, "Subtask $id", "spec_$id.md",
              status = if (id >= 1000) "pending" else if (id == 999) "completed_no_change" else "complete",
              dependencies = if (id == 1000) (1..999).map { DecompositionDependency(it, false, false) } else emptyList()) })
          val record = assertNotNull(unit.workflowStates.getFeatureTaskWorkflow("older-goal"))
          val artifacts = JsonCodec.mapToJsonString(mapOf(DurableWorkflowArtifactFamily.DECOMPOSITION_RUNTIME.label()
            to DecompositionManifestSchemaValidator().encodeManifestWireMap(manifest)))
          unit.workflowStates.saveFeatureTaskWorkflow(record.copy(artifactsJson = artifacts), FeatureTaskWorkflowMode.RUNTIME)
        }
        val fullDependencyStatus = assertNotNull(runtime.goalRunnerStatusService.status(statusRequest))
        val boundedDependencyStatus = assertNotNull(runtime.goalRunnerStatusService.statusForWorkflow("older-goal", statusRequest))
        assertEquals(fullDependencyStatus, boundedDependencyStatus)
        assertEquals(1001, boundedDependencyStatus.currentSubtaskId)
      } finally { source.close() }
    }
  }

  @Test
  fun `resubscribing the same query creates usable continuation cursors in its new epoch`() = runBlocking {
    establishTemporarySchemaReadiness("desktop-resubscribe-pages").use { fixture ->
      val factory = sqliteSessionFactoryForTests(fixture.tempDir, fixture.dbPath.toString(), emptyMap())
      factory.transaction { unit ->
        repeat(12) { index ->
          unit.standalonePhaseStatuses.register(registration(fixture.tempDir.toString(), "resubscribe-$index",
            now.minusSeconds(index.toLong()), "main").copy(issueKey = "SKILL-${2000 + index}"))
        }
      }
      val source = source(fixture.tempDir, TestClock(now.toKotlinInstant()))
      source.selectDatabase(fixture.dbPath.toString())
      try {
        val first = value(withTimeout(10_000) { source.observe(WorkSection.PHASES, Retention.DAY).first() })
        val received = Channel<Outcome<Board>>(Channel.UNLIMITED)
        val live = launch { source.observe(WorkSection.PHASES, Retention.DAY).collect { received.send(it) } }
        try {
          val second = value(withTimeout(10_000) { received.receive() })
          assertTrue(second.queryEpoch > first.queryEpoch)
          // The first subscription was released, so its cursor is stale; the live one continues.
          assertIs<Outcome.Failure>(source.board(WorkSection.PHASES, Retention.DAY, assertNotNull(first.nextCursor)))
          val appended = value(source.board(WorkSection.PHASES, Retention.DAY, assertNotNull(second.nextCursor)))
          assertEquals(second.queryEpoch, appended.queryEpoch)
          assertEquals(12, (second.items + appended.items).map { it.identity }.toSet().size)
        } finally { live.cancelAndJoin() }
      } finally { source.closeAndJoin() }
    }
  }

  @Test
  fun `exactly one full page has no continuation`() = runBlocking {
    establishTemporarySchemaReadiness("desktop-exact-page-size").use { fixture ->
      val factory = sqliteSessionFactoryForTests(fixture.tempDir, fixture.dbPath.toString(), emptyMap())
      factory.transaction { unit ->
        repeat(10) { index ->
          unit.standalonePhaseStatuses.register(registration(fixture.tempDir.toString(), "page-$index", now.minusSeconds(index.toLong()), "main")
            .copy(issueKey = "SKILL-${index + 3000}"))
        }
      }
      val source = source(fixture.tempDir, TestClock(now.toKotlinInstant()))
      try {
        source.selectDatabase(fixture.dbPath.toString())
        val board = value(source.board(WorkSection.PHASES, Retention.DAY))
        assertEquals(10, board.items.size)
        assertNull(board.nextCursor)
      } finally { source.closeAndJoin() }
    }
  }

  @Test
  fun `continuation rejects changed queries and same-path replacement without reusing old identities`() = runBlocking {
    establishTemporarySchemaReadiness("desktop-page-generation").use { fixture ->
      val factory = sqliteSessionFactoryForTests(fixture.tempDir, fixture.dbPath.toString(), emptyMap())
      factory.transaction { unit ->
        repeat(12) { index ->
          val record = unit.standalonePhaseStatuses.register(registration(fixture.tempDir.toString(), "earlier-$index", now.minusSeconds(1000L + index), "main")
            .copy(issueKey = "SKILL-2000"))
          unit.standalonePhaseStatuses.update(StandalonePhaseStatusUpdate(record.executionId, record.statusRevision,
            record.leaseOwner, record.leaseGeneration, "terminal", "review", null, now.minusSeconds(500), finishedAt = now.minusSeconds(500), terminalResult = "completed"))
        }
        repeat(12) { index ->
        unit.standalonePhaseStatuses.register(registration(fixture.tempDir.toString(), "live-$index", now.minusSeconds(index.toLong()), "main")
          .copy(issueKey = "SKILL-${index + 2000}"))
      } }
      val source = source(fixture.tempDir, TestClock(now.toKotlinInstant()))
      source.selectDatabase(fixture.dbPath.toString())
      try {
        val first = value(source.board(WorkSection.PHASES, Retention.DAY))
        assertEquals(10, first.items.size)
        val cursor = assertNotNull(first.nextCursor)
        val second = value(source.board(WorkSection.PHASES, Retention.DAY, cursor))
        assertEquals(2, second.items.size)
        assertEquals(12, (first.items + second.items).map { it.identity }.toSet().size)
        assertNull(second.nextCursor)
        assertIs<Outcome.Failure>(source.board(WorkSection.PHASES, Retention.DAY, cursor, "changed-filter"))
        val detail = (source.detail(first.items.first().identity) as Outcome.Value).value
        val detailCursor = assertNotNull(detail.nextCursor)
        source.selectDatabase(fixture.dbPath.toString())
        assertIs<Outcome.Failure>(source.detail(first.items.first().identity, detailCursor))
        assertIs<Outcome.Failure>(source.board(WorkSection.PHASES, Retention.DAY, cursor))
        establishTemporarySchemaReadiness("desktop-replacement").use { replacement ->
          source.selectDatabase(null)
          replacement.connection.close()
          fixture.connection.close()
          java.nio.file.Files.move(replacement.dbPath, fixture.dbPath, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
          source.selectDatabase(fixture.dbPath.toString())
          val fresh = value(source.board(WorkSection.PHASES, Retention.DAY))
          assertTrue(fresh.items.isEmpty())
          assertTrue(fresh.generation > first.generation)
        }
      } finally { source.close() }
    }
  }

  @Test
  fun `missing incompatible and corrupt stores are refused without creating or repairing them`() = runBlocking {
    val directory = Files.createTempDirectory("desktop-refusal")
    val missing = directory.resolve("absent.db")
    val source = source(directory, TestClock(now.toKotlinInstant()))
    try {
      source.selectDatabase(missing.toString())
      assertEquals(ProblemCode.MISSING_DATABASE, (source.board(WorkSection.GOALS, Retention.DAY) as Outcome.Failure).problem.code)
      assertFalse(Files.exists(missing))
      val corrupt = directory.resolve("corrupt.db")
      Files.writeString(corrupt, "existing unreadable database")
      val before = Files.readAllBytes(corrupt)
      source.selectDatabase(corrupt.toString())
      assertEquals(ProblemCode.CORRUPT_DATABASE, (source.board(WorkSection.GOALS, Retention.DAY) as Outcome.Failure).problem.code)
      assertContentEquals(before, Files.readAllBytes(corrupt))
    } finally { source.close() }
  }

  private fun source(home: java.nio.file.Path, clock: Clock): WorkStack = workStack(home.toRealPath(), clock)
  private fun registration(root: String, id: String, started: Instant, branch: String) = StandalonePhaseStatusRegistration(
    "repo-root-realpath-v1:${java.nio.file.Path.of(root).toRealPath()}", branch, if (id.startsWith("done-")) "SKILL-${id.removePrefix("done-").toInt() + 1000}" else "SKILL-423", null, id, "review", "execution-$id", "active", "review",
    started, "owned-test", 1, now.plusSeconds(300))
  private fun value(outcome: Outcome<Board>): Board { assertIs<Outcome.Value<Board>>(outcome, outcome.toString()); return outcome.value }
}
