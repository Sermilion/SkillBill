package skillbill.engine.featuretask.persist.acceptance

import skillbill.engine.featuretask.model.audit.AcceptanceCriterionRepairBinding

fun AcceptanceCriterionRepairPersistence.hydrateForTest(
  workflowId: String,
  binding: AcceptanceCriterionRepairBinding,
) = hydrate(workflowId, binding)
