package skillbill.engine.featuretask.incident.recovery

import skillbill.ports.persistence.UnitOfWork
import skillbill.workflow.taskruntime.model.blockincident.BlockIncident
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentRecovery

internal fun recordBlockIncidentRecoveryOutcome(
  unit: UnitOfWork,
  incident: BlockIncident,
  recovery: BlockIncidentRecovery,
) {
  check(
    unit.blockIncidents.recordRecoveryOutcome(
      incident.occurrence.occurrenceToken,
      incident.state.revision,
      recovery,
    ),
  ) {
    "Block incident recovery summary was not updated because occurrence " +
      "'${incident.occurrence.occurrenceToken}' changed revision."
  }
}
