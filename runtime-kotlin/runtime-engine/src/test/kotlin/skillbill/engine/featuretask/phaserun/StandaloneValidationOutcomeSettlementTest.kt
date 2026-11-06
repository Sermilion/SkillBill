package skillbill.engine.featuretask.phaserun

import skillbill.engine.featuretask.incident.BlockIncidentCapture
import skillbill.ports.blockincident.InMemoryBlockIncidentSpool
import skillbill.ports.db.DatabaseSessionFactory
import skillbill.ports.diagnostics.NoopRuntimeDiagnostics
import skillbill.ports.idestatus.model.LiveChecksWriteMode
import skillbill.ports.idestatus.model.StandaloneValidationOutcome
import skillbill.ports.idestatus.model.ValidationCheckEvent
import skillbill.ports.idestatus.model.ValidationCheckEventRecord
import skillbill.ports.idestatus.model.ValidationCheckOutcome
import skillbill.workflow.taskruntime.model.skeleton.SkeletonDefinition
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class StandaloneValidationOutcomeSettlementTest {
  private val home: Path = Files.createTempDirectory("standalone-validation-outcome")
  private val clock = Clock.fixed(Instant.parse("2026-11-06T10:00:00Z"), ZoneOffset.UTC)
  private val database: DatabaseSessionFactory = phaseRunDatabase(home, clock)

  @AfterTest
  fun cleanup() {
    home.toFile().deleteRecursively()
  }

  @Test
  fun `settlement keeps the last finished event per check and ignores skipped and unfinished checks`() {
    val retriedThenPassed =
      listOf(
        started("a"),
        finished("a", ValidationCheckOutcome.FAILED),
        started("a"),
        finished("a", ValidationCheckOutcome.PASSED),
      )
    val skippedAndUnfinished = listOf(finished("c", ValidationCheckOutcome.SKIPPED), started("d"))
    val blocked = listOf(started("b"), finished("b", ValidationCheckOutcome.BLOCKED))

    assertEquals(
      StandaloneValidationOutcome.BLOCKED,
      settledOutcome("inv-blocked", LiveChecksWriteMode.AVAILABLE, retriedThenPassed + blocked + skippedAndUnfinished),
    )
    assertEquals(
      StandaloneValidationOutcome.PASSED,
      settledOutcome("inv-passed", LiveChecksWriteMode.AVAILABLE, retriedThenPassed + skippedAndUnfinished),
    )
    assertNull(settledOutcome("inv-skipped", LiveChecksWriteMode.AVAILABLE, skippedAndUnfinished))
    assertNull(settledOutcome("inv-unavailable", LiveChecksWriteMode.UNAVAILABLE, retriedThenPassed))
  }

  private fun settledOutcome(
    invocationId: String,
    mode: LiveChecksWriteMode,
    events: List<(String, Int) -> ValidationCheckEventRecord>,
  ): StandaloneValidationOutcome? {
    val capture = BlockIncidentCapture(database, clock, InMemoryBlockIncidentSpool(), NoopRuntimeDiagnostics)
    val registrations = testStandalonePhaseRegistration(clock, NoopRuntimeDiagnostics)
    val publisher =
      StandalonePhaseStatusPublisherFactory(database, registrations, clock, NoopRuntimeDiagnostics, capture, null)
        .forPhase(home, SkeletonDefinition.VALIDATION.id, invocationId)
    publisher.publishActivity(SkeletonDefinition.VALIDATION.id, "Running checks")
    database.transaction { unit ->
      val statuses = unit.standalonePhaseStatuses
      statuses.writeLiveChecks(invocationId, mode)
      events.forEachIndexed { index, event -> statuses.appendValidationCheckEvent(event(invocationId, index + 1)) }
    }

    publisher.settle(
      PhaseRunResult.Completed(
        invocationId = invocationId,
        completedStepIds = listOf(SkeletonDefinition.VALIDATION.id),
        reviewResult = null,
        value = "Validation finished.",
      ),
    )

    return database.read { it.standalonePhaseStatuses.readByInvocation(invocationId) }?.validationOutcome
  }

  private fun started(checkId: String): (String, Int) -> ValidationCheckEventRecord =
    { invocationId, sequence -> event(invocationId, sequence, checkId, ValidationCheckEvent.STARTED, null) }

  private fun finished(
    checkId: String,
    outcome: ValidationCheckOutcome,
  ): (String, Int) -> ValidationCheckEventRecord =
    { invocationId, sequence -> event(invocationId, sequence, checkId, ValidationCheckEvent.FINISHED, outcome) }

  private fun event(
    invocationId: String,
    sequence: Int,
    checkId: String,
    event: ValidationCheckEvent,
    outcome: ValidationCheckOutcome?,
  ): ValidationCheckEventRecord =
    ValidationCheckEventRecord(
      invocationId = invocationId,
      sequence = sequence,
      checkId = checkId,
      event = event,
      name = checkId,
      command = "./gradlew $checkId",
      workingDir = ".",
      outcome = outcome,
      exitCode = null,
      note = null,
      attempt = 1,
      recordedAt = clock.instant(),
    )
}
