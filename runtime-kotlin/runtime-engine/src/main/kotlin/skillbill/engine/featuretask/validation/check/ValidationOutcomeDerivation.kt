package skillbill.engine.featuretask.validation.check

import skillbill.ports.db.DatabaseSessionFactory
import skillbill.ports.idestatus.model.LiveChecksAvailability
import skillbill.ports.idestatus.model.StandaloneValidationOutcome
import skillbill.ports.idestatus.model.ValidationCheckEvent
import skillbill.ports.idestatus.model.ValidationCheckEventRecord
import skillbill.ports.idestatus.model.ValidationCheckOutcome

internal fun DatabaseSessionFactory.readValidationOutcome(invocationId: String): StandaloneValidationOutcome? =
  transaction { unit ->
    val statuses = unit.standalonePhaseStatuses
    deriveValidationOutcome(
      statuses.readByInvocation(invocationId)?.liveChecks,
      statuses.readValidationCheckEvents(invocationId),
    )
  }

internal fun deriveValidationOutcome(
  liveChecks: LiveChecksAvailability?,
  events: List<ValidationCheckEventRecord>,
): StandaloneValidationOutcome? {
  if (liveChecks != LiveChecksAvailability.AVAILABLE) return null
  val outcomes =
    events
      .filter { it.event == ValidationCheckEvent.FINISHED }
      .groupBy(ValidationCheckEventRecord::checkId)
      .values
      .mapNotNull { finished -> finished.maxBy(ValidationCheckEventRecord::sequence).outcome }
      .filter { it != ValidationCheckOutcome.SKIPPED }
  return when {
    outcomes.isEmpty() -> null
    ValidationCheckOutcome.BLOCKED in outcomes -> StandaloneValidationOutcome.BLOCKED
    ValidationCheckOutcome.FAILED in outcomes -> StandaloneValidationOutcome.FAILED
    else -> StandaloneValidationOutcome.PASSED
  }
}
