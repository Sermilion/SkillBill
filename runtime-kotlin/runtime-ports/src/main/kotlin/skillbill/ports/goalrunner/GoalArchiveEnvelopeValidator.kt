package skillbill.ports.goalrunner

import skillbill.ports.goalrunner.model.GoalArchiveEnvelope

/** Validates a portable goal-archive envelope against the archive schema. */
fun interface GoalArchiveEnvelopeValidator {
  /** Loud-fails when [envelope] does not match the portable archive schema. */
  fun validate(envelope: GoalArchiveEnvelope)
}
