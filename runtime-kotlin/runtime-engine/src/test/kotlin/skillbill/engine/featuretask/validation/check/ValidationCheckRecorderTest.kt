package skillbill.engine.featuretask.validation.check

import skillbill.error.featuretask.validation.ValidationCheckRejectionReason
import skillbill.infrastructure.sqlite.sqliteDatabaseSessionFactory
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.ports.idestatus.model.StandalonePhaseStatusRecord
import skillbill.ports.idestatus.model.StandalonePhaseStatusRegistration
import skillbill.ports.idestatus.model.ValidationCheckEvent
import skillbill.ports.idestatus.model.ValidationCheckOutcome
import skillbill.workflow.taskruntime.model.skeleton.SkeletonDefinition
import java.nio.file.Files
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ValidationCheckRecorderTest {
  private val warnings = mutableListOf<String>()
  private val diagnostics =
    object : RuntimeDiagnostics {
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
  private val database =
    sqliteDatabaseSessionFactory(
      Files.createTempDirectory("validation-check"),
      environment = mapOf("TEST" to "1"),
      diagnostics = diagnostics,
    )
  private val recorder = ValidationCheckRecorder(database, Clock.fixed(NOW, ZoneOffset.UTC), diagnostics)

  @Test
  fun `a retried check records runtime-owned sequences, attempts, times and activity without a revision bump`() {
    register()
    val before = row()
    val activities = mutableListOf<String?>()
    val steps =
      listOf(
        report(ValidationCheckEvent.PLANNED),
        report(ValidationCheckEvent.STARTED),
        report(ValidationCheckEvent.FINISHED, ValidationCheckOutcome.FAILED, exitCode = 1),
        report(ValidationCheckEvent.STARTED),
        report(ValidationCheckEvent.FINISHED, ValidationCheckOutcome.PASSED),
      )

    val results = steps.map { step -> recorder.record(step).also { activities += row().currentActivity } }

    assertEquals(
      listOf(1 to 0, 2 to 1, 3 to 1, 4 to 2, 5 to 2).map { (sequence, attempt) ->
        ValidationCheckOutcomeResult.Recorded(sequence, attempt)
      },
      results,
    )
    val events = database.read { it.standalonePhaseStatuses.readValidationCheckEvents(RUN) }
    assertEquals(listOf(1, 2, 3, 4, 5), events.map { it.sequence })
    assertEquals(listOf(0, 1, 1, 2, 2), events.map { it.attempt })
    assertTrue(events.all { it.recordedAt == NOW })
    assertEquals(1, events[2].exitCode)
    assertEquals(
      listOf(
        before.currentActivity,
        "Running Unit tests",
        "Finished Unit tests: failed",
        "Running Unit tests",
        "Finished Unit tests: passed",
      ),
      activities,
    )
    val after = row()
    assertEquals(before.statusRevision, after.statusRevision)
    assertEquals(before.updatedAt, after.updatedAt)
  }

  @Test
  fun `rejected events write nothing and log their reason`() {
    register(invocationId = "qa-run", phaseId = SkeletonDefinition.QA.id)
    register(invocationId = "paused-run", lifecycleState = "paused")
    register()
    recorder.record(report(ValidationCheckEvent.PLANNED))
    recorder.record(report(ValidationCheckEvent.STARTED, checkId = "lint"))
    val cases =
      listOf(
        report(ValidationCheckEvent.PLANNED, invocationId = "qa-run") to
          ValidationCheckRejectionReason.NOT_STANDALONE_VALIDATION,
        report(ValidationCheckEvent.PLANNED, invocationId = "paused-run") to
          ValidationCheckRejectionReason.RUN_NOT_ACTIVE,
        report(ValidationCheckEvent.FINISHED, ValidationCheckOutcome.FAILED) to
          ValidationCheckRejectionReason.CHECK_NOT_STARTED,
        report(ValidationCheckEvent.STARTED, checkId = "lint") to
          ValidationCheckRejectionReason.CHECK_ALREADY_RUNNING,
        report(ValidationCheckEvent.STARTED, ValidationCheckOutcome.PASSED) to
          ValidationCheckRejectionReason.INVALID_EVENT,
        report(ValidationCheckEvent.STARTED, exitCode = 0) to ValidationCheckRejectionReason.INVALID_EVENT,
        report(ValidationCheckEvent.PLANNED, invocationId = " ") to ValidationCheckRejectionReason.NO_RUN_IDENTITY,
      )
    warnings.clear()

    cases.forEach { (report, reason) ->
      val countsBefore = eventCounts()
      assertEquals(ValidationCheckOutcomeResult.Rejected(reason), recorder.record(report), reason.wireValue)
      assertEquals(countsBefore, eventCounts(), reason.wireValue)
    }
    assertEquals(
      cases.map { (_, reason) -> "kind=${reason.wireValue}" },
      warnings.map { it.substringAfter("seam=validation_check ").substringBefore(" ") },
    )
  }

  private fun eventCounts(): List<Int> =
    database.read { unit ->
      listOf(RUN, "qa-run", "paused-run").map { unit.standalonePhaseStatuses.readValidationCheckEvents(it).size }
    }

  private fun row(): StandalonePhaseStatusRecord =
    checkNotNull(database.read { it.standalonePhaseStatuses.readByInvocation(RUN) })

  private fun report(
    event: ValidationCheckEvent,
    outcome: ValidationCheckOutcome? = null,
    exitCode: Int? = null,
    checkId: String = "unit-tests",
    invocationId: String = RUN,
  ): ValidationCheckReport =
    ValidationCheckReport(
      invocationId = invocationId,
      event = event,
      checkId = checkId,
      name = "Unit tests",
      command = "./gradlew test",
      workingDir = "runtime-kotlin",
      outcome = outcome,
      exitCode = exitCode,
    )

  private fun register(
    invocationId: String = RUN,
    phaseId: String = SkeletonDefinition.VALIDATION.id,
    lifecycleState: String = "active",
  ) {
    database.transaction {
      it.standalonePhaseStatuses.register(
        StandalonePhaseStatusRegistration(
          repositoryIdentity = "repo", branchCorrelation = "main", issueKey = null, workflowId = null,
          invocationId = invocationId, phaseId = phaseId, executionId = "execution-$invocationId",
          lifecycleState = lifecycleState, currentStep = phaseId, startedAt = Instant.EPOCH, leaseOwner = "owner",
          leaseGeneration = 1, leaseExpiresAt = Instant.EPOCH.plusSeconds(3600),
        ),
      )
    }
  }

  private companion object {
    const val RUN = "validation-run"
    val NOW: Instant = Instant.parse("2026-11-06T10:00:00Z")
  }
}
