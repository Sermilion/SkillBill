package skillbill.workflow.decomposition.runtime

import skillbill.error.shellcontent.invalidDecompositionManifestSchema
import skillbill.workflow.decomposition.model.DecompositionManifestValidationFailureCode

fun invalidManifest(
  sourceLabel: String,
  reason: String,
): Nothing =
  throw invalidDecompositionManifestSchema(
    sourceLabel = sourceLabel,
    reason = reason,
    code = DecompositionManifestValidationFailureCode.SCHEMA_INVALID,
  )
