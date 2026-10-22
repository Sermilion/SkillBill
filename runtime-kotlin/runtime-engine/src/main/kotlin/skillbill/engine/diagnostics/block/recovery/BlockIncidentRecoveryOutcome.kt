package skillbill.engine.diagnostics.block.recovery

import skillbill.ports.persistence.UnitOfWork
import skillbill.workflow.taskruntime.model.blockincident.BlockIncident
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentRecovery

/**
 * Updates only the incident recovery summary; occurrence and diagnosis rows stay unchanged. The attempt row and
 * the summary settle together: a lost revision CAS throws, which rolls back the attempt transition in the same
 * transaction instead of leaving a terminal attempt without its incident outcome.
 */
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
