package dev.skillbill.desktop.core.data.runtime

import dev.skillbill.desktop.core.common.DatabaseDispatcher
import dev.skillbill.desktop.core.common.Diagnostics
import dev.skillbill.desktop.core.common.DiagnosticsSwitch
import dev.skillbill.desktop.core.data.session.SessionSelection
import dev.skillbill.desktop.core.data.session.SessionSelectionHolder
import dev.skillbill.desktop.core.domain.model.DetailList
import dev.skillbill.desktop.core.domain.model.GoalQaFinding
import dev.skillbill.desktop.core.domain.model.QaAssertionOutcome
import dev.skillbill.desktop.core.domain.model.QaRunAssertion
import dev.skillbill.desktop.core.domain.model.QaRunCoverage
import dev.skillbill.desktop.core.domain.model.QaRunDetails
import dev.skillbill.desktop.core.domain.model.QaRunFinding
import dev.skillbill.desktop.core.domain.model.QaRunRetainedResource
import dev.skillbill.desktop.core.domain.model.QaRunTemporaryEdit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import skillbill.di.core.OptionalCallbacks
import skillbill.di.core.RuntimeComponent
import skillbill.di.core.RuntimeContext
import skillbill.di.core.TransportContext
import skillbill.di.core.WorkflowOpsContext
import skillbill.di.core.create
import skillbill.goalrunner.model.GoalRunnerQaFinding
import skillbill.goalrunner.model.GoalRunnerQaResult
import skillbill.model.EnvironmentContext
import skillbill.ports.idestatus.model.StandalonePhaseStatusRegistration
import skillbill.ports.idestatus.model.StandaloneNarrowWriteResult
import skillbill.ports.idestatus.model.StandalonePhaseStatusUpdate
import skillbill.ports.idestatus.model.StandaloneQaChecklist
import skillbill.ports.idestatus.model.StandaloneQaOutcome
import skillbill.ports.idestatus.model.StandaloneValidationOutcome
import skillbill.ports.idestatus.model.LiveChecksWriteMode
import skillbill.ports.idestatus.model.ValidationCheckEvent
import skillbill.ports.idestatus.model.ValidationCheckEventRecord
import skillbill.workflow.taskruntime.model.qa.QaAssertionOutcome as RuntimeQaAssertionOutcome
import skillbill.workflow.taskruntime.model.qa.QaRunAssertion as RuntimeQaRunAssertion
import skillbill.workflow.taskruntime.model.qa.QaRunCoverage as RuntimeQaRunCoverage
import skillbill.workflow.taskruntime.model.qa.QaRunDetails as RuntimeQaRunDetails
import skillbill.workflow.taskruntime.model.qa.QaRunFinding as RuntimeQaRunFinding
import skillbill.workflow.taskruntime.model.qa.QaRunRetainedResource as RuntimeQaRunRetainedResource
import skillbill.workflow.taskruntime.model.qa.QaRunTemporaryEdit as RuntimeQaRunTemporaryEdit
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame

class RuntimeDetailReadsTest {
  @Test
  fun `validation detail includes only validation runs from its branch and maps current activity`() {
    runBlocking {
      val root = Files.createTempDirectory("runtime-validation-branch-runs")
      val sessionJob = SupervisorJob()
      val database = root.resolve("work.db")
      val runtime = RuntimeComponent::class.create(
        RuntimeContext(
          EnvironmentContext(database.toString(), environment = mapOf("SKILL_BILL_TEST" to "1"), userHome = root, repositoryRoot = root),
          TransportContext(), WorkflowOpsContext(), OptionalCallbacks(),
        ),
      )
      try {
        val now = Instant.parse("2026-01-01T00:00:00Z")
        val repositoryIdentity = runtime.repositoryEnclosingRootPort.repositoryIdentity(root)
        runtime.databaseSession.transaction { unit ->
          listOf(
            "validation-a1" to "branch-a",
            "validation-a2" to "branch-a",
            "validation-b1" to "branch-b",
            "review-a1" to "branch-a",
          ).mapIndexed { index, (invocation, branch) ->
            unit.standalonePhaseStatuses.register(
              StandalonePhaseStatusRegistration(
                repositoryIdentity, branch, "SKILL-508", null, invocation,
                if (invocation.startsWith("review")) "review" else "validation",
                "$invocation-execution", "active", "validation", now.plusSeconds(index.toLong()),
                "$invocation-owner", 1, Instant.parse("9999-01-01T00:00:00Z"), launchAgentId = "agent-$invocation",
              ),
            )
          }
        }
        runtime.databaseSession.transaction { unit ->
          assertEquals(
            StandaloneNarrowWriteResult.WRITTEN,
            unit.standalonePhaseStatuses.writeCurrentActivity("validation-a2", "Reading reports"),
          )
          assertEquals(StandaloneNarrowWriteResult.WRITTEN, unit.standalonePhaseStatuses.writeLiveChecks("validation-a2", LiveChecksWriteMode.AVAILABLE))
          unit.standalonePhaseStatuses.appendValidationCheckEvent(
            ValidationCheckEventRecord(
              "validation-a2", 1, "compile", ValidationCheckEvent.PLANNED, "Compile", "./gradlew check", ".", null, null, null, 0, now,
            ),
          )
          unit.standalonePhaseStatuses.appendValidationCheckEvent(
            ValidationCheckEventRecord(
              "review-a1", 1, "compile", ValidationCheckEvent.PLANNED, "Compile", "./gradlew check", ".", null, null, null, 0, now,
            ),
          )
          val record = requireNotNull(unit.standalonePhaseStatuses.readByInvocation("validation-a2"))
          unit.standalonePhaseStatuses.update(
            StandalonePhaseStatusUpdate(
              executionId = record.executionId,
              expectedRevision = record.statusRevision,
              leaseOwner = record.leaseOwner,
              leaseGeneration = record.leaseGeneration,
              lifecycleState = record.lifecycleState,
              currentStep = record.currentStep,
              currentActivity = record.currentActivity,
              updatedAt = now.plusSeconds(1),
              validationOutcome = StandaloneValidationOutcome.PASSED,
            ),
          )
        }
        val holder = SessionSelectionHolder().apply {
          choose(SessionSelection(1, database.toString(), null, kotlinx.coroutines.CoroutineScope(sessionJob + Dispatchers.IO)))
        }
        val reads = RuntimeReads(runtime, holder, DatabaseDispatcher(), DiagnosticsSwitch(false), NoopDiagnostics)
        val detailReads = RuntimeDetailReads(reads, runtime, NoopDiagnostics, holder.selection.id)
        val loaded = assertIs<DetailRead.Loaded>(detailReads.first("validation-a2"))
        assertEquals(listOf("validation-a2", "validation-a1"), loaded.sections.detail.validationBranchRuns.map { it.invocationId })
        assertEquals("Reading reports", loaded.sections.detail.currentActivity)
        val current = loaded.sections.detail.executionHistory.first { it.invocationId == "validation-a2" }
        assertEquals("validation-a2", loaded.sections.detail.selectedExecution?.invocationId)
        assertEquals(1, loaded.sections.detail.executionHistory.count { it.invocationId == "validation-a2" })
        assertEquals(true, current.liveChecks)
        assertEquals(dev.skillbill.desktop.core.domain.model.ValidationOutcome.Passed, current.validationOutcome)
        assertEquals("compile", current.validationChecks.single().checkId)
        assertEquals(false, loaded.sections.detail.executionHistory.first { it.invocationId == "validation-a1" }.liveChecks)
        val review = assertIs<DetailRead.Loaded>(detailReads.first("review-a1"))
        assertEquals(emptyList(), review.sections.detail.executionHistory.first { it.invocationId == "review-a1" }.validationChecks)
      } finally {
        sessionJob.cancel()
        runtime.databaseReadConnections.release()
        Files.walk(root).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
      }
    }
  }

  @Test
  fun `older selected validation keeps saved checks beyond history page through paging and refresh`() {
    runBlocking {
      val root = Files.createTempDirectory("runtime-selected-validation")
      val sessionJob = SupervisorJob()
      val database = root.resolve("work.db")
      val runtime = RuntimeComponent::class.create(
        RuntimeContext(
          EnvironmentContext(database.toString(), environment = mapOf("SKILL_BILL_TEST" to "1"), userHome = root, repositoryRoot = root),
          TransportContext(), WorkflowOpsContext(), OptionalCallbacks(),
        ),
      )
      try {
        val now = Instant.parse("2026-01-01T00:00:00Z")
        val repositoryIdentity = runtime.repositoryEnclosingRootPort.repositoryIdentity(root)
        val invocations = runtime.databaseSession.transaction { unit ->
          (0 until 22).map { index ->
            val invocation = "validation-$index"
            unit.standalonePhaseStatuses.register(
              StandalonePhaseStatusRegistration(
                repositoryIdentity, "main", "SKILL-508", null, invocation, "validation", "$invocation-execution",
                "active", "validation", now.plusSeconds(index.toLong()), "$invocation-owner", 1,
                Instant.parse("9999-01-01T00:00:00Z"),
              ),
            )
          }
        }
        val selected = invocations.first()
        runtime.databaseSession.transaction { unit ->
          unit.standalonePhaseStatuses.writeLiveChecks(selected.invocationId, LiveChecksWriteMode.AVAILABLE)
          unit.standalonePhaseStatuses.appendValidationCheckEvent(
            ValidationCheckEventRecord(
              selected.invocationId, 1, "compile", ValidationCheckEvent.PLANNED, "Compile", "./gradlew check", ".",
              null, null, null, 0, now,
            ),
          )
          val record = requireNotNull(unit.standalonePhaseStatuses.readByInvocation(selected.invocationId))
          unit.standalonePhaseStatuses.update(
            StandalonePhaseStatusUpdate(
              executionId = record.executionId, expectedRevision = record.statusRevision,
              leaseOwner = record.leaseOwner, leaseGeneration = record.leaseGeneration,
              lifecycleState = record.lifecycleState, currentStep = record.currentStep, currentActivity = record.currentActivity,
              updatedAt = now.plusSeconds(1), validationOutcome = StandaloneValidationOutcome.PASSED,
            ),
          )
        }
        val holder = SessionSelectionHolder().apply {
          choose(SessionSelection(1, database.toString(), null, kotlinx.coroutines.CoroutineScope(sessionJob + Dispatchers.IO)))
        }
        val reads = RuntimeReads(runtime, holder, DatabaseDispatcher(), DiagnosticsSwitch(false), NoopDiagnostics)
        val detailReads = RuntimeDetailReads(reads, runtime, NoopDiagnostics, holder.selection.id)
        val first = assertIs<DetailRead.Loaded>(detailReads.first(selected.invocationId)).sections
        val selectedExecution = requireNotNull(first.detail.selectedExecution)

        assertEquals(selected.invocationId, selectedExecution.invocationId)
        assertEquals(true, selectedExecution.liveChecks)
        assertEquals(dev.skillbill.desktop.core.domain.model.ValidationOutcome.Passed, selectedExecution.validationOutcome)
        assertEquals("compile", selectedExecution.validationChecks.single().checkId)
        assertEquals(20, first.detail.executionHistory.size)
        assertEquals(false, first.detail.executionHistory.any { it.invocationId == selected.invocationId })
        val cursor = requireNotNull(first.listCursors[DetailList.Executions])

        val nextPage = assertIs<DetailRead.Loaded>(detailReads.list(selected.invocationId, DetailList.Executions, cursor)).sections
        assertEquals(selected.invocationId, nextPage.detail.selectedExecution?.invocationId)
        assertEquals(selected.invocationId, nextPage.detail.executionHistory.last().invocationId)

        val refreshed = assertIs<DetailRead.Loaded>(detailReads.refresh(selected.invocationId, mapOf(DetailList.Executions to 21))).sections
        assertEquals(selected.invocationId, refreshed.detail.selectedExecution?.invocationId)
        assertEquals("compile", refreshed.detail.selectedExecution?.validationChecks?.single()?.checkId)
        assertEquals(21, refreshed.detail.executionHistory.size)
        assertNotNull(refreshed.listCursors[DetailList.Executions])
      } finally {
        sessionJob.cancel()
        runtime.databaseReadConnections.release()
        Files.walk(root).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
      }
    }
  }

  @Test
  fun `detail read returns runtime run options for the loaded item`() {
    runBlocking {
      val root = Files.createTempDirectory("runtime-detail-reads")
      val sessionJob = SupervisorJob()
      val database = root.resolve("work.db")
      val runtime = RuntimeComponent::class.create(
        RuntimeContext(
          EnvironmentContext(
            database.toString(),
            environment = mapOf("SKILL_BILL_TEST" to "1"),
            userHome = root,
            repositoryRoot = root,
          ),
          TransportContext(),
          WorkflowOpsContext(),
          OptionalCallbacks(),
        ),
      )
      try {
        val now = Instant.now()
        val repositoryIdentity = runtime.repositoryEnclosingRootPort.repositoryIdentity(root)
        val registered = runtime.databaseSession.transaction { unit ->
          unit.standalonePhaseStatuses.register(
            StandalonePhaseStatusRegistration(
              repositoryIdentity, "smoke", "SKILL-447", null, "detail-read", "review",
              "detail-read-execution", "active", "review", now, "detail-read-owner", 1, now.plusSeconds(300),
            ),
          )
        }
        val checklist = StandaloneQaChecklist(invocationDirectory = root.toString(), reportPath = root.resolve("report.md").toString())
        runtime.databaseSession.transaction { unit ->
          unit.standalonePhaseStatuses.writeQaChecklist(registered.invocationId, checklist)
          unit.standalonePhaseStatuses.update(
            StandalonePhaseStatusUpdate(
              executionId = registered.executionId, expectedRevision = registered.statusRevision, leaseOwner = registered.leaseOwner,
              leaseGeneration = registered.leaseGeneration, lifecycleState = "completed", currentStep = "review",
              currentActivity = null, updatedAt = now.plusSeconds(1), finishedAt = now.plusSeconds(1), terminalResult = "completed",
              qaOutcome = StandaloneQaOutcome.FINDINGS, qaReportText = "Verdict: findings_open\n\n### QA-1 Card field stays empty\nField shows 4444.\n",
              qaDetails = RuntimeQaRunDetails(skipReason = "docker_unavailable", retainedResources = listOf(RuntimeQaRunRetainedResource("process", "42"))),
            ),
          )
        }
        val holder = SessionSelectionHolder().apply {
          choose(SessionSelection(1, database.toString(), null, kotlinx.coroutines.CoroutineScope(sessionJob + Dispatchers.IO)))
        }
        val reads = RuntimeReads(runtime, holder, DatabaseDispatcher(), DiagnosticsSwitch(false), NoopDiagnostics)
        val detailReads = RuntimeDetailReads(reads, runtime, NoopDiagnostics, holder.selection.id)
        val statusService = reads.ideStatusService

        val loaded = assertIs<DetailRead.Loaded>(detailReads.first("detail-read"))

        assertNotNull(loaded.sections.runOptions)
        val execution = assertNotNull(loaded.sections.detail.executionHistory.singleOrNull())
        val mappedChecklist = assertNotNull(execution.qaChecklist)
        assertEquals(Path.of(checklist.invocationDirectory).normalize(), Path.of(mappedChecklist.invocationDirectory).normalize())
        assertEquals(Path.of(checklist.reportPath).normalize(), Path.of(mappedChecklist.reportPath).normalize())
        assertEquals(listOf("QA-1"), execution.qaFindings.map { it.id })
        assertEquals(QaRunDetails(skipReason = "docker_unavailable", retainedResources = listOf(QaRunRetainedResource("process", "42"))), execution.qaDetails)
        assertIs<DetailRead.Gone>(detailReads.first("missing-favorite-id"))
        assertSame(statusService, reads.ideStatusService)
      } finally {
        sessionJob.cancel()
        runtime.databaseReadConnections.release()
        Files.walk(root).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
      }
    }
  }

  @Test
  fun `goal qa keeps every evidence path and all details`() {
    val finding = GoalRunnerQaFinding("QA-1", "Title", false, listOf("a.log", "b.png"), "/run/report.md", "seen", "wanted", "do")
    val details = RuntimeQaRunDetails(
      "/run/report.md", "/run", "docker_unavailable",
      listOf(RuntimeQaRunAssertion(RuntimeQaAssertionOutcome.NOT_EXERCISED, "Live", "No window", listOf("c.log"))),
      listOf(RuntimeQaRunCoverage("backend", "skipped", "docker_unavailable", "none")),
      listOf(RuntimeQaRunRetainedResource("process", "42")),
      listOf(RuntimeQaRunTemporaryEdit("x.txt", "set", true)),
      listOf(RuntimeQaRunFinding("QA-1", "Title", "seen", "wanted", "do", listOf("a.log"))),
    )
    val mapped = GoalRunnerQaResult("attempted", null, 0, Instant.EPOCH, listOf(finding), "report", null, details).toDomain()

    assertEquals(GoalQaFinding("QA-1", "Title", false, listOf("a.log", "b.png"), "/run/report.md", "seen", "wanted", "do"), mapped.findings.single())
    assertEquals(
      QaRunDetails(
        "/run/report.md", "/run", "docker_unavailable",
        listOf(QaRunAssertion(QaAssertionOutcome.NotExercised, "Live", "No window", listOf("c.log"))),
        listOf(QaRunCoverage("backend", "skipped", "docker_unavailable", "none")),
        listOf(QaRunRetainedResource("process", "42")),
        listOf(QaRunTemporaryEdit("x.txt", "set", true)),
        listOf(QaRunFinding("QA-1", "Title", "seen", "wanted", "do", listOf("a.log"))),
      ),
      mapped.details,
    )
    assertNull(GoalRunnerQaResult("attempted", null, 0, Instant.EPOCH).toDomain().details)
  }

  private object NoopDiagnostics : Diagnostics {
    override fun record(category: String) = Unit

    override fun recordRequest(category: String, fields: Map<String, Number>) = Unit
  }
}
