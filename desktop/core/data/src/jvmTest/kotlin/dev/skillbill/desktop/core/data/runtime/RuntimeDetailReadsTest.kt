package dev.skillbill.desktop.core.data.runtime

import dev.skillbill.desktop.core.common.DatabaseDispatcher
import dev.skillbill.desktop.core.common.Diagnostics
import dev.skillbill.desktop.core.common.DiagnosticsSwitch
import dev.skillbill.desktop.core.data.session.SessionSelection
import dev.skillbill.desktop.core.data.session.SessionSelectionHolder
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
import skillbill.model.EnvironmentContext
import skillbill.ports.idestatus.model.StandalonePhaseStatusRegistration
import skillbill.ports.idestatus.model.StandalonePhaseStatusUpdate
import skillbill.ports.idestatus.model.StandaloneQaChecklist
import skillbill.ports.idestatus.model.StandaloneQaOutcome
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull

class RuntimeDetailReadsTest {
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
            ),
          )
        }
        val holder = SessionSelectionHolder().apply {
          choose(SessionSelection(1, database.toString(), null, kotlinx.coroutines.CoroutineScope(sessionJob + Dispatchers.IO)))
        }
        val reads = RuntimeReads(runtime, holder, DatabaseDispatcher(), DiagnosticsSwitch(false), NoopDiagnostics)
        val detailReads = RuntimeDetailReads(reads, runtime, NoopDiagnostics, holder.selection.id)

        val loaded = assertIs<DetailRead.Loaded>(detailReads.first("detail-read"))

        assertNotNull(loaded.sections.runOptions)
        val execution = assertNotNull(loaded.sections.detail.executionHistory.singleOrNull())
        val mappedChecklist = assertNotNull(execution.qaChecklist)
        assertEquals(Path.of(checklist.invocationDirectory).normalize(), Path.of(mappedChecklist.invocationDirectory).normalize())
        assertEquals(Path.of(checklist.reportPath).normalize(), Path.of(mappedChecklist.reportPath).normalize())
        assertEquals(listOf("QA-1"), execution.qaFindings.map { it.id })
        assertIs<DetailRead.Gone>(detailReads.first("missing-favorite-id"))
      } finally {
        sessionJob.cancel()
        runtime.databaseReadConnections.release()
        Files.walk(root).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
      }
    }
  }

  private object NoopDiagnostics : Diagnostics {
    override fun record(category: String) = Unit

    override fun recordRequest(category: String, fields: Map<String, Number>) = Unit
  }
}
