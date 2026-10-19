package skillbill.ports.featuretask

import skillbill.ports.featuretask.model.FeatureTaskQaOutcome

/** The latest full-run QA outcome of each feature-task workflow, stored for later status surfaces. */
interface FeatureTaskQaOutcomeRepository {
  fun upsert(outcome: FeatureTaskQaOutcome)

  fun find(workflowId: String): FeatureTaskQaOutcome?
}
