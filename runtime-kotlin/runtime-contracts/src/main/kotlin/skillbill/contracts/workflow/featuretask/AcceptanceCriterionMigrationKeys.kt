package skillbill.contracts.workflow.featuretask

import skillbill.contracts.SharedPayloadKeys

const val ACCEPTANCE_CRITERION_MIGRATION_CONTRACT_VERSION = "0.1"

object AcceptanceCriterionMigrationKeys {
  const val MIGRATION_SOURCE_VERSION = "migration_source_version"
  const val CONTRACT_VERSION = SharedPayloadKeys.CONTRACT_VERSION
  const val WORKFLOW_ID = SharedPayloadKeys.WORKFLOW_ID
  const val ORIGINAL_ARTIFACTS_JSON = "original_artifacts_json"
  const val SOURCE_DIGEST = "source_digest"
  const val STEPS = "steps"
  const val FAMILY = "family"
  const val SOURCE_VERSION = "source_version"
  const val TARGET_VERSION = "target_version"
  const val CONTEXTS = "contexts"
  const val CRITERION_REF = AcceptanceCriterionRepairPayloadKeys.CRITERION_REF
  const val BUDGET_IDENTITY = "budget_identity"
  const val HISTORICAL_CHARGES = "historical_charges"
  const val ATTRIBUTION_UNKNOWN = "attribution_unknown"
  const val RECONCILIATION_REQUIRED = "reconciliation_required"
  const val ESCALATION_OWED = "escalation_owed"
}
