package skillbill.contracts.workflow.featuretask

object InteractionScenarioCatalogPayloadKeys {
  const val CONTRACT_VERSION = "contract_version"
  const val SPEC_DIGEST = "spec_digest"
  const val SCENARIOS = "scenarios"
  const val TRANSITIONAL_REGRESSIONS = "transitional_regressions"
  const val SCENARIO_REF = "scenario_ref"
  const val CRITERION_REFS = "criterion_refs"
  const val SUBTASK_REFS = "subtask_refs"
  const val ENTRY_POINT = "entry_point"
  const val PRECONDITIONS = "preconditions"
  const val STEPS = "steps"
  const val OUTCOME = "outcome"
  const val REGRESSION_REF = "regression_ref"
  const val BEHAVIOR = "behavior"
  const val DISABLED_BY_SUBTASK = "disabled_by_subtask"
  const val RESTORED_BY_SUBTASK = "restored_by_subtask"
}

const val INTERACTION_SCENARIO_CATALOG_CONTRACT_VERSION = "0.1"
