package skillbill.engine.work.validation

import skillbill.ports.idestatus.model.ValidationCheckEvent
import skillbill.ports.idestatus.model.ValidationCheckEventRecord
import skillbill.ports.idestatus.model.WorkValidationCheck
import skillbill.ports.idestatus.model.WorkValidationCheckAttempt
import skillbill.ports.persistence.UnitOfWork

internal fun validationChecksFor(
  current: Boolean,
  invocationId: String,
  unitOfWork: UnitOfWork,
): List<WorkValidationCheck> =
  if (current) {
    validationChecksOf(unitOfWork.standalonePhaseStatuses.readValidationCheckEvents(invocationId))
  } else {
    emptyList()
  }

internal fun validationChecksOf(events: List<ValidationCheckEventRecord>): List<WorkValidationCheck> {
  val checks = linkedMapOf<String, MutableCheck>()
  events.sortedBy { it.sequence }.forEach { event ->
    val check = checks.getOrPut(event.checkId) { MutableCheck(event.checkId) }
    check.name = event.name
    check.command = event.command
    check.workingDir = event.workingDir
    when (event.event) {
      ValidationCheckEvent.PLANNED -> Unit
      ValidationCheckEvent.STARTED ->
        check.openAttempt =
          WorkValidationCheckAttempt(
            event.attempt,
            event.recordedAt,
            null,
            null,
            null,
            event.note,
          )
      ValidationCheckEvent.FINISHED -> {
        val open = check.openAttempt
        if (open == null) {
          check.attempts +=
            WorkValidationCheckAttempt(
              event.attempt,
              null,
              event.recordedAt,
              event.outcome,
              event.exitCode,
              event.note,
            )
        } else {
          check.attempts +=
            open.copy(
              finishedAt = event.recordedAt,
              outcome = event.outcome,
              exitCode = event.exitCode,
              note = event.note ?: open.note,
            )
          check.openAttempt = null
        }
      }
    }
  }
  return checks.values.map { check ->
    WorkValidationCheck(
      check.id,
      check.name,
      check.command,
      check.workingDir,
      check.attempts + listOfNotNull(check.openAttempt),
    )
  }
}

private class MutableCheck(val id: String) {
  var name = ""
  var command = ""
  var workingDir = ""
  var openAttempt: WorkValidationCheckAttempt? = null
  val attempts = mutableListOf<WorkValidationCheckAttempt>()
}
