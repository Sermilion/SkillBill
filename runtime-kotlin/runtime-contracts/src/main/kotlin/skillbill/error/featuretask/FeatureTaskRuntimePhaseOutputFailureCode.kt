package skillbill.error.featuretask

import skillbill.error.core.FailureWireCode
import skillbill.error.core.RuntimeFailureCode
import skillbill.error.core.failureWireByValue

fun coarseFailureKindForPhaseOutputWireCode(wireCode: String): FeatureTaskRuntimePhaseOutputFailureKind =
  FeatureTaskRuntimePhaseOutputFailureCode.fromWire(wireCode).coarseFailureKind

enum class FeatureTaskRuntimePhaseOutputFailureCode(
  override val wireValue: String,
) : FailureWireCode, RuntimeFailureCode {
  MALFORMED("malformed"),
  ROOT_NOT_OBJECT("root_not_object"),
  DUPLICATE_KEY("duplicate_key"),
  NO_REPAIR_CANDIDATE("no_repair_candidate"),
  AMBIGUOUS_REPAIR("ambiguous_repair"),
  REPAIR_LIMIT_EXCEEDED("repair_limit_exceeded"),
  UNSUPPORTED_REPAIR("unsupported_repair"),
  SCHEMA_INVALID("schema_invalid"),
  PHASE_ID_MISMATCH("phase_id_mismatch"),
  SEMANTIC_INVALID("semantic_invalid"),
  MULTIPLE_OUTPUT_CANDIDATES("multiple_output_candidates"),
  ;

  val coarseFailureKind: FeatureTaskRuntimePhaseOutputFailureKind
    get() =
      when (this) {
        MALFORMED,
        ROOT_NOT_OBJECT,
        NO_REPAIR_CANDIDATE,
        AMBIGUOUS_REPAIR,
        REPAIR_LIMIT_EXCEEDED,
        UNSUPPORTED_REPAIR,
        DUPLICATE_KEY,
        -> FeatureTaskRuntimePhaseOutputFailureKind.MALFORMED
        SCHEMA_INVALID,
        PHASE_ID_MISMATCH,
        SEMANTIC_INVALID,
        MULTIPLE_OUTPUT_CANDIDATES,
        -> FeatureTaskRuntimePhaseOutputFailureKind.SCHEMA_INVALID
      }

  companion object {
    private const val HIERARCHY = "FeatureTaskRuntimePhaseOutputFailureCode"

    fun fromWire(value: String): FeatureTaskRuntimePhaseOutputFailureCode = entries.failureWireByValue(value, HIERARCHY)
  }
}
