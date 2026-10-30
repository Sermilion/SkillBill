package skillbill.engine.featuretask.incident.investigation

import me.tatarka.inject.annotations.Inject
import skillbill.engine.diagnostics.block.BlockIncidentInvestigationResult
import skillbill.engine.diagnostics.block.BlockIncidentInvestigator
import skillbill.engine.featuretask.incident.report.BlockIncidentLaunchFacts
import skillbill.engine.featuretask.incident.report.BlockIncidentLaunchFactsReader
import skillbill.ports.db.DatabaseSessionFactory
import skillbill.workflow.taskruntime.model.blockincident.BlockIncident
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentClassifier
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentDegradation
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentDiagnosis
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentDiagnosisRecord
import java.time.Clock

@Inject
class EvidenceBlockIncidentInvestigator(
  private val database: DatabaseSessionFactory,
  private val clock: Clock,
  private val collector: BlockIncidentEvidenceCollector,
  private val agentPass: BlockIncidentAgentPass,
  private val launchFactsReader: BlockIncidentLaunchFactsReader?,
) : BlockIncidentInvestigator {
  override fun investigate(
    claimed: BlockIncident,
    workerDegradations: Set<BlockIncidentDegradation>,
  ): BlockIncidentInvestigationResult {
    val (incident, evidence, launchFacts) =
      database.read { unitOfWork ->
        val current = unitOfWork.blockIncidents.find(claimed.occurrence.occurrenceToken) ?: claimed
        Triple(
          current,
          collector.collect(unitOfWork, current),
          launchFactsReader?.read(unitOfWork, current.occurrence)
            ?: BlockIncidentLaunchFacts(null, null, null),
        )
      }
    val draft =
      BlockIncidentClassifier.classify(
        incident.occurrence,
        evidence.items,
        evidence.degradations + workerDegradations,
        clock.instant(),
      )
    val record =
      agentPass.enrich(
        incident,
        evidence,
        draft.copy(
          providerId = launchFacts.providerId,
          phaseStrategyId = launchFacts.phaseStrategyId,
          modelId = launchFacts.modelId,
        ),
      ).copy(completedAt = clock.instant())
    return BlockIncidentInvestigationResult(settledDiagnosis(incident, record), record)
  }

  private fun settledDiagnosis(
    incident: BlockIncident,
    record: BlockIncidentDiagnosisRecord,
  ): BlockIncidentDiagnosis {
    val degradations = record.degradations
    val deadlinePassed = !clock.instant().isBefore(incident.state.deadline)
    return when {
      BlockIncidentDegradation.AGENT_UNAVAILABLE in degradations -> BlockIncidentDiagnosis.UNAVAILABLE_AGENT
      BlockIncidentDegradation.DEADLINE_EXCEEDED in degradations && deadlinePassed -> BlockIncidentDiagnosis.TIMED_OUT
      degradations.isNotEmpty() -> BlockIncidentDiagnosis.PARTIAL
      else -> BlockIncidentDiagnosis.COMPLETE
    }
  }
}
