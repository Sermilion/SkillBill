package skillbill.infrastructure.sqlite.workflow.featuretask

import skillbill.error.core.SkillBillRuntimeException
import skillbill.error.core.rethrowUnless
import skillbill.error.shellcontent.FeatureTaskRuntimeFailureCode
import skillbill.infrastructure.sqlite.core.ops.degradedValuePreview
import skillbill.infrastructure.sqlite.core.ops.recordDegradedValue
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.ports.featuretask.model.parseFeatureTaskRuntimeWorkerLeaseInstant
import java.time.Instant

internal fun parseWorkerLeaseInstant(
  workflowId: String,
  field: String,
  value: String,
  diagnostics: RuntimeDiagnostics,
): Instant =
  try {
    parseFeatureTaskRuntimeWorkerLeaseInstant(workflowId, field, value)
  } catch (error: SkillBillRuntimeException) {
    error.rethrowUnless(error.code == FeatureTaskRuntimeFailureCode.INVALID_WORKER_OWNERSHIP_SCHEMA)
    diagnostics.recordDegradedValue(
      seam = "worker_lease.$field",
      expected = "RFC 3339 instant",
      used = value.degradedValuePreview(),
      error = error,
    )
    throw error
  }
