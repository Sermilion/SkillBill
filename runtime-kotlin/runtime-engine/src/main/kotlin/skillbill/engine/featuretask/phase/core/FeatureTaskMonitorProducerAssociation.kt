package skillbill.engine.featuretask.phase.core

import skillbill.engine.featuretask.model.phase.ProducerOutputQueryArgs
import skillbill.ports.diagnostics.model.ProducerOutputEvidence
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimePhaseLedgerEntry

internal sealed interface FeatureTaskMonitorProducerAssociation {
  data object Matched : FeatureTaskMonitorProducerAssociation

  data class Unavailable(val reason: String) : FeatureTaskMonitorProducerAssociation
}

internal fun associateMonitorProducer(
  query: ProducerOutputQueryArgs,
  start: FeatureTaskRuntimePhaseLedgerEntry,
  completion: FeatureTaskRuntimePhaseLedgerEntry,
  evidence: ProducerOutputEvidence,
): FeatureTaskMonitorProducerAssociation {
  if (query.agentId.isBlank() || start.resolvedAgentId != query.agentId ||
    completion.resolvedAgentId != query.agentId
  ) {
    return FeatureTaskMonitorProducerAssociation.Unavailable("START and COMPLETE producer identity differs")
  }
  val retained =
    ProducerOutputQueryArgs(
      evidence.workflowId,
      evidence.phaseId,
      evidence.attempt,
      evidence.agentId,
      evidence.generation,
    )
  return if (retained == query) {
    FeatureTaskMonitorProducerAssociation.Matched
  } else {
    FeatureTaskMonitorProducerAssociation.Unavailable("retained producer identity differs")
  }
}
