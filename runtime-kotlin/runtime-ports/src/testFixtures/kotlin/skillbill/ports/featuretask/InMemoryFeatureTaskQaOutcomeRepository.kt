package skillbill.ports.featuretask

import skillbill.ports.featuretask.model.FeatureTaskQaOutcome

class InMemoryFeatureTaskQaOutcomeRepository : FeatureTaskQaOutcomeRepository {
  private val outcomes = LinkedHashMap<String, FeatureTaskQaOutcome>()

  override fun upsert(outcome: FeatureTaskQaOutcome) {
    outcomes[outcome.workflowId] = outcome
  }

  override fun find(workflowId: String): FeatureTaskQaOutcome? = outcomes[workflowId]
}
