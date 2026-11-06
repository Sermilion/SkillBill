package skillbill.engine.featuretask.phaserun

import skillbill.engine.featuretask.incident.BlockIncidentCapture
import skillbill.ports.blockincident.InMemoryBlockIncidentSpool
import skillbill.ports.diagnostics.NoopRuntimeDiagnostics
import skillbill.workflow.taskruntime.model.qa.QaRunDetails
import skillbill.workflow.taskruntime.model.qa.QaRunRetainedResource
import java.nio.file.Files
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals

class StandalonePhaseQaDetailsSettleTest {
  @Test
  fun `a completed QA run stores its details on the status row`() {
    val home = Files.createTempDirectory("standalone-qa-details-settle")
    val clock = Clock.fixed(Instant.parse("2026-11-05T01:00:00Z"), ZoneOffset.UTC)
    val database = phaseRunDatabase(home, clock)
    val capture = BlockIncidentCapture(database, clock, InMemoryBlockIncidentSpool(), NoopRuntimeDiagnostics)
    val publisher =
      StandalonePhaseStatusPublisherFactory(
        database,
        testStandalonePhaseRegistration(clock, NoopRuntimeDiagnostics),
        clock,
        NoopRuntimeDiagnostics,
        capture,
        null,
      ).forPhase(home, "qa", INVOCATION)
    val details =
      QaRunDetails(
        reportPath = "/qa/report.md",
        retainedResources = listOf(QaRunRetainedResource("process", "48213")),
      )
    try {
      publisher.settle(
        PhaseRunResult.Completed(
          invocationId = INVOCATION,
          completedStepIds = listOf("qa"),
          reviewResult = null,
          value = "QA verdict: passed.",
          qaDetails = details,
        ),
      )

      assertEquals(details, database.read { it.standalonePhaseStatuses.readByInvocation(INVOCATION) }?.qaDetails)
    } finally {
      home.toFile().deleteRecursively()
    }
  }

  private companion object {
    const val INVOCATION = "inv-qa-details"
  }
}
