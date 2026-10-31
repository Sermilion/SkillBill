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
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.test.Test
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
        runtime.databaseSession.transaction { unit ->
          unit.standalonePhaseStatuses.register(
            StandalonePhaseStatusRegistration(
              "repo-root-realpath-v1:${root.toRealPath()}", "smoke", "SKILL-447", null, "detail-read", "review",
              "detail-read-execution", "active", "review", now, "detail-read-owner", 1, now.plusSeconds(300),
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
