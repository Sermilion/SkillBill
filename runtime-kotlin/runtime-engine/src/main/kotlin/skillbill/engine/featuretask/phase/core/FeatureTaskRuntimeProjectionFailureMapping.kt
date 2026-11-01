package skillbill.engine.featuretask.phase.core

import skillbill.error.featuretask.FeatureTaskRuntimeHandoffProjectionFailureKind
import skillbill.workflow.taskruntime.model.handoff.task.FeatureTaskRuntimeProjectionFailureClassification

public fun FeatureTaskRuntimeHandoffProjectionFailureKind.toMeasurementFailureClassification():
  FeatureTaskRuntimeProjectionFailureClassification =
  when (this) {
    FeatureTaskRuntimeHandoffProjectionFailureKind.UNSUPPORTED_CONTRACT_VERSION ->
      FeatureTaskRuntimeProjectionFailureClassification.UNSUPPORTED_VERSION
    FeatureTaskRuntimeHandoffProjectionFailureKind.BUDGET_OVERFLOW ->
      FeatureTaskRuntimeProjectionFailureClassification.BUDGET_OVERFLOW
    FeatureTaskRuntimeHandoffProjectionFailureKind.CHECKPOINT_POLICY_VIOLATION ->
      FeatureTaskRuntimeProjectionFailureClassification.STALE_CHECKPOINT
    FeatureTaskRuntimeHandoffProjectionFailureKind.MISSING_REQUIRED_SOURCE,
    FeatureTaskRuntimeHandoffProjectionFailureKind.UNDECLARED_FIELD,
    -> FeatureTaskRuntimeProjectionFailureClassification.UNPROJECTABLE_SOURCE
    FeatureTaskRuntimeHandoffProjectionFailureKind.MALFORMED_FIELD,
    FeatureTaskRuntimeHandoffProjectionFailureKind.DUPLICATE_PROJECTION_NAME,
    FeatureTaskRuntimeHandoffProjectionFailureKind.INVALID_COMPACT_REFERENCE,
    FeatureTaskRuntimeHandoffProjectionFailureKind.SCHEMA_INVALID,
    -> FeatureTaskRuntimeProjectionFailureClassification.INVALID_CONTRACT
  }
