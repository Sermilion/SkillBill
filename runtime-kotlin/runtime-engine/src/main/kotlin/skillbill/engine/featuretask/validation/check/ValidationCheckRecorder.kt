package skillbill.engine.featuretask.validation.check

import me.tatarka.inject.annotations.Inject
import skillbill.error.core.SkillBillRuntimeException
import skillbill.error.core.catchingNonCancellation
import skillbill.error.core.failureCodeLabel
import skillbill.error.featuretask.validation.ValidationCheckRejectionReason
import skillbill.error.featuretask.validation.isValidationCheckFailure
import skillbill.error.featuretask.validation.validationCheckRejected
import skillbill.error.featuretask.validation.validationCheckRejectionReason
import skillbill.idestatus.model.IdeStatusLaunchScope
import skillbill.idestatus.model.IdeStatusLifecycleState
import skillbill.idestatus.model.boundedIdeStatusActivity
import skillbill.ports.db.DatabaseSessionFactory
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.ports.idestatus.StandalonePhaseStatusRepository
import skillbill.ports.idestatus.model.StandaloneNarrowWriteResult
import skillbill.ports.idestatus.model.StandalonePhaseStatusRecord
import skillbill.ports.idestatus.model.ValidationCheckEvent
import skillbill.ports.idestatus.model.ValidationCheckEventRecord
import skillbill.ports.idestatus.model.ValidationCheckOutcome
import skillbill.workflow.taskruntime.model.skeleton.SkeletonDefinition
import java.time.Clock

public const val VALIDATION_CHECK_EVENT_LIMIT: Int = 500

public data class ValidationCheckReport(
  public val invocationId: String?,
  public val event: ValidationCheckEvent?,
  public val checkId: String,
  public val name: String,
  public val command: String,
  public val workingDir: String,
  public val outcome: ValidationCheckOutcome? = null,
  public val exitCode: Int? = null,
  public val note: String? = null,
)

public sealed interface ValidationCheckOutcomeResult {
  public data class Recorded(
    public val sequence: Int,
    public val attempt: Int,
  ) : ValidationCheckOutcomeResult

  public data class Rejected(public val reason: ValidationCheckRejectionReason) : ValidationCheckOutcomeResult

  public data class Failed(public val reason: String) : ValidationCheckOutcomeResult
}

@Inject
public class ValidationCheckRecorder(
  private val database: DatabaseSessionFactory,
  private val clock: Clock,
  private val diagnostics: RuntimeDiagnostics,
) {
  public fun record(report: ValidationCheckReport): ValidationCheckOutcomeResult {
    val invocationId =
      report.invocationId?.takeIf(String::isNotBlank)
        ?: return rejected(report.invocationId.orEmpty(), ValidationCheckRejectionReason.NO_RUN_IDENTITY)
    return catchingNonCancellation {
      database.transaction { unit -> recordInTransaction(unit.standalonePhaseStatuses, invocationId, report) }
    }.getOrElse { failure ->
      val reason =
        (failure as? SkillBillRuntimeException)
          ?.takeIf { it.isValidationCheckFailure }
          ?.validationCheckRejectionReason
      if (reason != null) rejected(invocationId, reason) else failed(invocationId, failure)
    }
  }

  private fun recordInTransaction(
    statuses: StandalonePhaseStatusRepository,
    invocationId: String,
    report: ValidationCheckReport,
  ): ValidationCheckOutcomeResult.Recorded {
    val row = statuses.readByInvocation(invocationId) ?: reject(ValidationCheckRejectionReason.RUN_NOT_FOUND)
    requireLiveValidationRun(row)
    val event = report.event ?: reject(ValidationCheckRejectionReason.INVALID_EVENT)
    requireValidShape(event, report)
    val events = statuses.readValidationCheckEvents(invocationId)
    if (events.size >= VALIDATION_CHECK_EVENT_LIMIT) reject(ValidationCheckRejectionReason.EVENT_LIMIT_REACHED)
    val attempt = attemptOf(events, event, report)
    val record =
      ValidationCheckEventRecord(
        invocationId = invocationId,
        sequence = ValidationCheckHistory.nextSequence(events),
        checkId = report.checkId,
        event = event,
        name = report.name,
        command = report.command,
        workingDir = report.workingDir,
        outcome = report.outcome,
        exitCode = report.exitCode,
        note = boundedIdeStatusActivity(report.note),
        attempt = attempt,
        recordedAt = clock.instant(),
      )
    statuses.appendValidationCheckEvent(record)
    activityAfter(events + record, record)?.let { activity ->
      when (statuses.writeCurrentActivity(invocationId, activity)) {
        StandaloneNarrowWriteResult.WRITTEN -> Unit
        StandaloneNarrowWriteResult.MISSING -> reject(ValidationCheckRejectionReason.RUN_NOT_FOUND)
        StandaloneNarrowWriteResult.FINISHED -> reject(ValidationCheckRejectionReason.RUN_NOT_ACTIVE)
      }
    }
    return ValidationCheckOutcomeResult.Recorded(record.sequence, record.attempt)
  }

  private fun requireLiveValidationRun(row: StandalonePhaseStatusRecord) {
    if (row.phaseId != SkeletonDefinition.VALIDATION.id || row.launchScope != IdeStatusLaunchScope.STANDALONE) {
      reject(ValidationCheckRejectionReason.NOT_STANDALONE_VALIDATION)
    }
    if (row.finishedAt != null || row.lifecycleState != IdeStatusLifecycleState.ACTIVE.wireValue) {
      reject(ValidationCheckRejectionReason.RUN_NOT_ACTIVE)
    }
  }

  private fun requireValidShape(
    event: ValidationCheckEvent,
    report: ValidationCheckReport,
  ) {
    val finished = event == ValidationCheckEvent.FINISHED
    val outcomeMismatch = finished != (report.outcome != null)
    val exitCodeMisplaced = !finished && report.exitCode != null
    if (outcomeMismatch || exitCodeMisplaced) reject(ValidationCheckRejectionReason.INVALID_EVENT)
  }

  private fun attemptOf(
    events: List<ValidationCheckEventRecord>,
    event: ValidationCheckEvent,
    report: ValidationCheckReport,
  ): Int {
    val open = ValidationCheckHistory.openStart(events, report.checkId)
    val nextAttempt = ValidationCheckHistory.startedCount(events, report.checkId) + 1
    return when (event) {
      ValidationCheckEvent.PLANNED -> 0
      ValidationCheckEvent.STARTED ->
        if (open != null) reject(ValidationCheckRejectionReason.CHECK_ALREADY_RUNNING) else nextAttempt
      ValidationCheckEvent.FINISHED ->
        when {
          open != null -> open.attempt
          report.outcome == ValidationCheckOutcome.SKIPPED -> nextAttempt
          else -> reject(ValidationCheckRejectionReason.CHECK_NOT_STARTED)
        }
    }
  }

  private fun activityAfter(
    events: List<ValidationCheckEventRecord>,
    record: ValidationCheckEventRecord,
  ): String? =
    when (record.event) {
      ValidationCheckEvent.PLANNED -> null
      ValidationCheckEvent.STARTED -> boundedIdeStatusActivity("Running ${record.name}")
      ValidationCheckEvent.FINISHED ->
        ValidationCheckHistory.newestOpenStart(events)
          ?.let { boundedIdeStatusActivity("Running ${it.name}") }
          ?: boundedIdeStatusActivity("Finished ${record.name}: ${record.outcome?.wireValue}")
    }

  private fun reject(reason: ValidationCheckRejectionReason): Nothing = throw validationCheckRejected(reason)

  private fun rejected(
    invocationId: String,
    reason: ValidationCheckRejectionReason,
  ): ValidationCheckOutcomeResult {
    diagnostics.warning("seam=validation_check kind=${reason.wireValue} invocation=${bounded(invocationId)}")
    return ValidationCheckOutcomeResult.Rejected(reason)
  }

  private fun failed(
    invocationId: String,
    failure: Throwable,
  ): ValidationCheckOutcomeResult {
    val label = failure.failureCodeLabel() ?: failure::class.simpleName
    diagnostics.warning("seam=validation_check kind=failed invocation=${bounded(invocationId)} failure=$label")
    return ValidationCheckOutcomeResult.Failed(FAILED_REASON)
  }

  private fun bounded(invocationId: String): String =
    invocationId.take(MAX_LOGGED_INVOCATION_LENGTH).replace(UNSAFE_LOG_CHARACTER, "_")

  private companion object {
    const val FAILED_REASON = "database write failed"
    const val MAX_LOGGED_INVOCATION_LENGTH = 128
    val UNSAFE_LOG_CHARACTER = Regex("[^A-Za-z0-9._-]")
  }
}
