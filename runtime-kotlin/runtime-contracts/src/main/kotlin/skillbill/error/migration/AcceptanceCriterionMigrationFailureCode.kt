package skillbill.error.migration

import skillbill.error.core.RuntimeFailureCode
import skillbill.error.core.SkillBillRuntimeException

enum class AcceptanceCriterionMigrationFailureCode : RuntimeFailureCode {
  INVALID_MIGRATION_PROVENANCE,
  ARTIFACT_MIGRATION_REFUSED,
  MIGRATION_WORKER_ACTIVE,
  MIGRATION_RECONCILIATION_REQUIRED,
}

fun invalidAcceptanceCriterionMigration(
  source: String,
  reason: String,
  cause: Throwable? = null,
): SkillBillRuntimeException =
  SkillBillRuntimeException(
    AcceptanceCriterionMigrationFailureCode.INVALID_MIGRATION_PROVENANCE,
    "Criterion migration provenance '$source' is invalid: $reason. Original data must be retained.",
    cause,
  )
