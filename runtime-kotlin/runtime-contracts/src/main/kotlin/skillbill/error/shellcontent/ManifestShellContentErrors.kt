package skillbill.error.shellcontent

import skillbill.error.core.RuntimeFailureCode
import skillbill.error.core.SkillBillRuntimeException

enum class ManifestFailureCode : RuntimeFailureCode {
  MISSING_MANIFEST,
  INVALID_MANIFEST_SCHEMA,
  INVALID_VALIDATION_GATE_DECLARATION,
  REVIEW_COMPOSITION_CYCLE,
  AMBIGUOUS_LANE_OWNERSHIP,
  INCOMPATIBLE_COMPOSITION_CONTRACT,
  MISSING_COMPOSITION_LAYER,
  MANIFEST_FAILURE,
}

fun missingManifest(message: String, cause: Throwable? = null): SkillBillRuntimeException =
  SkillBillRuntimeException(ManifestFailureCode.MISSING_MANIFEST, message, cause)

fun invalidManifestSchema(message: String, cause: Throwable? = null): SkillBillRuntimeException =
  SkillBillRuntimeException(ManifestFailureCode.INVALID_MANIFEST_SCHEMA, message, cause)

fun invalidValidationGateDeclaration(message: String, cause: Throwable? = null): SkillBillRuntimeException =
  SkillBillRuntimeException(ManifestFailureCode.INVALID_VALIDATION_GATE_DECLARATION, message, cause)

fun missingValidationGate(message: String, cause: Throwable? = null): SkillBillRuntimeException =
  SkillBillRuntimeException(ManifestFailureCode.MANIFEST_FAILURE, message, cause)

fun reviewCompositionCycle(message: String): SkillBillRuntimeException =
  SkillBillRuntimeException(ManifestFailureCode.REVIEW_COMPOSITION_CYCLE, message)

fun ambiguousLaneOwnership(message: String): SkillBillRuntimeException =
  SkillBillRuntimeException(ManifestFailureCode.AMBIGUOUS_LANE_OWNERSHIP, message)

fun incompatibleCompositionContract(message: String): SkillBillRuntimeException =
  SkillBillRuntimeException(ManifestFailureCode.INCOMPATIBLE_COMPOSITION_CONTRACT, message)

fun missingCompositionLayer(message: String): SkillBillRuntimeException =
  SkillBillRuntimeException(ManifestFailureCode.MISSING_COMPOSITION_LAYER, message)
