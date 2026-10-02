package skillbill.error.featuretask

import skillbill.error.core.FailureWireCode
import skillbill.error.core.RuntimeFailureCode

enum class FeatureTaskRuntimePhaseOutputFailureKind(
  override val wireValue: String,
) : FailureWireCode, RuntimeFailureCode {
  MALFORMED("malformed"),
  SCHEMA_INVALID("schema_invalid"),
}

enum class FeatureTaskRuntimeHandoffProjectionFailureKind(
  override val wireValue: String,
) : FailureWireCode, RuntimeFailureCode {
  MISSING_REQUIRED_SOURCE("missing_required_source"),
  MALFORMED_FIELD("malformed_field"),
  UNSUPPORTED_CONTRACT_VERSION("unsupported_contract_version"),
  UNDECLARED_FIELD("undeclared_field"),
  DUPLICATE_PROJECTION_NAME("duplicate_projection_name"),
  BUDGET_OVERFLOW("budget_overflow"),
  INVALID_COMPACT_REFERENCE("invalid_compact_reference"),
  CHECKPOINT_POLICY_VIOLATION("checkpoint_policy_violation"),
  SCHEMA_INVALID("schema_invalid"),
}
