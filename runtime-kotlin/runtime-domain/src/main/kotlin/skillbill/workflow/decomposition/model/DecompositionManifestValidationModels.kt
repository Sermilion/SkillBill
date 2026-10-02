package skillbill.workflow.decomposition.model

import skillbill.contracts.workflow.featuretask.DECOMPOSITION_MANIFEST_VALIDATION_CONTRACT_VERSION
import skillbill.error.core.FailureWireCode
import skillbill.error.core.RuntimeFailureCode
import skillbill.error.core.failureWireByValue
import skillbill.error.shellcontent.InvalidDecompositionManifestSchemaError
import skillbill.review.context.model.hunk.SHA256_HEX

private const val DECOMPOSITION_MANIFEST_VALIDATION_VERSION: String =
  DECOMPOSITION_MANIFEST_VALIDATION_CONTRACT_VERSION

enum class DecompositionManifestValidationFormat(val wireValue: String) {
  JSON("json"),
  YAML("yaml"),
}

enum class DecompositionManifestRepairOperation(val wireValue: String) {
  REMOVE_EXTRA_CLOSING_DELIMITER("remove_extra_closing_delimiter"),
  ADD_MISSING_CLOSING_DELIMITER("add_missing_closing_delimiter"),
}

enum class DecompositionManifestValidationFailureCode(
  override val wireValue: String,
) : FailureWireCode, RuntimeFailureCode {
  MALFORMED("malformed"),
  ROOT_NOT_OBJECT("root_not_object"),
  DUPLICATE_KEY("duplicate_key"),
  NO_REPAIR_CANDIDATE("no_repair_candidate"),
  AMBIGUOUS_REPAIR("ambiguous_repair"),
  REPAIR_LIMIT_EXCEEDED("repair_limit_exceeded"),
  UNSUPPORTED_REPAIR("unsupported_repair"),
  INVALID_SHAPE("invalid_shape"),
  SCHEMA_INVALID("schema_invalid"),
  COHERENCE_INVALID("coherence_invalid"),
  ;

  companion object {
    private const val HIERARCHY = "DecompositionManifestValidationFailureCode"

    fun fromWire(value: String?): DecompositionManifestValidationFailureCode =
      when {
        value.isNullOrBlank() -> SCHEMA_INVALID
        else -> entries.failureWireByValue(value, HIERARCHY)
      }
  }
}

data class DecompositionManifestValidationSourceLocation(
  val sourceLabel: String,
  val offset: Int,
  val line: Int,
  val column: Int,
) {
  init {
    require(sourceLabel.isNotBlank()) { "Manifest sourceLabel must be non-blank." }
    require(offset >= 0) { "Manifest source offset must be non-negative." }
    require(line >= 1) { "Manifest source line must be >= 1." }
    require(column >= 1) { "Manifest source column must be >= 1." }
  }
}

data class DecompositionManifestRepairEvidence(
  val contractVersion: String = DECOMPOSITION_MANIFEST_VALIDATION_VERSION,
  val validatorVersion: String = DECOMPOSITION_MANIFEST_VALIDATION_VERSION,
  val format: DecompositionManifestValidationFormat,
  val originalDigest: String,
  val repairedDigest: String,
  val operation: DecompositionManifestRepairOperation,
  val sourceLocation: DecompositionManifestValidationSourceLocation,
) {
  init {
    require(contractVersion == DECOMPOSITION_MANIFEST_VALIDATION_VERSION) {
      "Manifest repair evidence has unsupported contract version '$contractVersion'."
    }
    require(validatorVersion == DECOMPOSITION_MANIFEST_VALIDATION_VERSION) {
      "Manifest repair evidence has unsupported validator version '$validatorVersion'."
    }
    require(originalDigest.matches(SHA256_HEX)) {
      "Manifest repair evidence originalDigest must be lowercase SHA-256."
    }
    require(repairedDigest.matches(SHA256_HEX)) {
      "Manifest repair evidence repairedDigest must be lowercase SHA-256."
    }
    require(originalDigest != repairedDigest) {
      "Manifest repair evidence must describe a changed payload."
    }
  }

  private companion object {
    val SHA256_HEX = Regex("[0-9a-f]{64}")
  }
}

sealed interface DecompositionManifestValidationResult {
  val contractVersion: String
    get() = DECOMPOSITION_MANIFEST_VALIDATION_VERSION

  data class AcceptedUnchanged(
    val manifest: DecompositionManifest,
    val yamlText: String,
  ) : DecompositionManifestValidationResult

  data class AcceptedAfterRepair(
    val manifest: DecompositionManifest,
    val yamlText: String,
    val evidence: DecompositionManifestRepairEvidence,
  ) : DecompositionManifestValidationResult

  data class Rejected(
    val code: DecompositionManifestValidationFailureCode,
    val reason: String,
    val sourceLocation: DecompositionManifestValidationSourceLocation? = null,
  ) : DecompositionManifestValidationResult
}

fun DecompositionManifestValidationResult.requireAccepted(sourceLabel: String): DecompositionManifest =
  when (this) {
    is DecompositionManifestValidationResult.AcceptedUnchanged -> manifest
    is DecompositionManifestValidationResult.AcceptedAfterRepair -> manifest
    is DecompositionManifestValidationResult.Rejected -> throw InvalidDecompositionManifestSchemaError(
      sourceLabel = sourceLabel,
      reason = reason,
      failureCode = code.wireValue,
    )
  }
