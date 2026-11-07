package skillbill.engine.featuretask.validation.check

import skillbill.ports.idestatus.model.ValidationCheckEvent
import skillbill.ports.idestatus.model.ValidationCheckEventRecord

internal object ValidationCheckHistory {
  fun nextSequence(events: List<ValidationCheckEventRecord>): Int = (events.maxOfOrNull { it.sequence } ?: 0) + 1

  fun startedCount(
    events: List<ValidationCheckEventRecord>,
    checkId: String,
  ): Int = events.count { it.checkId == checkId && it.event == ValidationCheckEvent.STARTED }

  fun openStart(
    events: List<ValidationCheckEventRecord>,
    checkId: String,
  ): ValidationCheckEventRecord? =
    events
      .lastOrNull { it.checkId == checkId && it.event != ValidationCheckEvent.PLANNED }
      ?.takeIf { it.event == ValidationCheckEvent.STARTED }

  fun newestOpenStart(events: List<ValidationCheckEventRecord>): ValidationCheckEventRecord? =
    events
      .map { it.checkId }
      .distinct()
      .mapNotNull { checkId -> openStart(events, checkId) }
      .maxByOrNull { it.sequence }
}
