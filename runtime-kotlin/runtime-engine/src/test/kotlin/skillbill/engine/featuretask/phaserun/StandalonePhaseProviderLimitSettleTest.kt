package skillbill.engine.featuretask.phaserun

import skillbill.engine.featuretask.incident.BlockIncidentCapture
import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeRunEvent
import skillbill.ports.blockincident.InMemoryBlockIncidentSpool
import skillbill.ports.diagnostics.NoopRuntimeDiagnostics
import skillbill.ports.monitor.model.MonitorOutcomeKind
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimeProviderLimitSignal
import skillbill.workflow.taskruntime.validation.FeatureTaskRuntimeProviderLimitPause
import java.nio.file.Files
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StandalonePhaseProviderLimitSettleTest {
  @Test
  fun `provider limit blocked settles paused without spooling a block incident`() {
    val home = Files.createTempDirectory("standalone-provider-limit-settle")
    val clock = Clock.fixed(Instant.parse("2026-11-05T01:00:00Z"), ZoneOffset.UTC)
    val database = phaseRunDatabase(home, clock)
    val spool = InMemoryBlockIncidentSpool()
    val capture = BlockIncidentCapture(database, clock, spool, NoopRuntimeDiagnostics)
    val publisher =
      StandalonePhaseStatusPublisherFactory(
        database,
        testStandalonePhaseRegistration(clock, NoopRuntimeDiagnostics),
        clock,
        NoopRuntimeDiagnostics,
        capture,
        null,
      ).forPhase(home, "validation", INVOCATION)
    try {
      publisher.emit(FeatureTaskRuntimeRunEvent.PhaseStarted("wf-1", "validation", "codex", 1, false))
      val reason =
        FeatureTaskRuntimeProviderLimitPause.reason(
          phaseLabel = "Feature-task-runtime phase 'validation'",
          agentId = "codex",
          resumePhaseId = "validation",
          signal = FeatureTaskRuntimeProviderLimitSignal(evidence = "usage limit", resetHint = null),
        )
      val blocked =
        PhaseRunResult.Blocked(
          invocationId = INVOCATION,
          completedStepIds = listOf("validation"),
          reviewResult = null,
          stepId = "validation",
          reason = reason,
        )

      publisher.settle(blocked)

      val record = database.read { it.standalonePhaseStatuses.readByInvocation(INVOCATION) }
      assertEquals("paused", record?.lifecycleState)
      assertEquals(reason, record?.terminalResult)
      assertEquals(emptyList(), database.transaction { it.blockIncidents.pending(8) })
      assertEquals(emptyList(), database.read { it.blockIncidents.listForWork(null, INVOCATION, 8) })
      assertTrue(spool.pending(8).isEmpty())
      val outcome = primaryOutcomeOf(Result.success(blocked))
      assertEquals(MonitorOutcomeKind.PAUSED, outcome.kind)
      assertEquals(reason, outcome.detail)
    } finally {
      home.toFile().deleteRecursively()
    }
  }

  private companion object {
    const val INVOCATION = "inv-provider-limit"
  }
}
