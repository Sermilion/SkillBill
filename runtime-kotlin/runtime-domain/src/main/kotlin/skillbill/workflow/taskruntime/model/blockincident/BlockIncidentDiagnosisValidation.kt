package skillbill.workflow.taskruntime.model.blockincident

import skillbill.contracts.workflow.identity.blockincident.BlockIncidentDiagnosisKeys

data class BlockIncidentDiagnosisViolation(val code: BlockIncidentDiagnosisViolationCode, val field: String)

enum class BlockIncidentDiagnosisViolationCode(val wireValue: String) {
  DIAGNOSED_WITHOUT_EVIDENCE(BlockIncidentDiagnosisKeys.VIOLATION_DIAGNOSED_WITHOUT_EVIDENCE),
  UNRESOLVED_NOT_UNKNOWN(BlockIncidentDiagnosisKeys.VIOLATION_UNRESOLVED_NOT_UNKNOWN),
  EFFECTS_WITHOUT_ACTION(BlockIncidentDiagnosisKeys.VIOLATION_EFFECTS_WITHOUT_ACTION),
  LENGTH_EXCEEDED(BlockIncidentDiagnosisKeys.VIOLATION_LENGTH_EXCEEDED),
  COUNT_EXCEEDED(BlockIncidentDiagnosisKeys.VIOLATION_COUNT_EXCEEDED),
  BLANK(BlockIncidentDiagnosisKeys.VIOLATION_BLANK),
  INVALID_DIGEST(BlockIncidentDiagnosisKeys.VIOLATION_INVALID_DIGEST),
}

fun BlockIncidentDiagnosisRecord.validation(): List<BlockIncidentDiagnosisViolation> =
  buildList {
    if (certainty == BlockIncidentCertainty.DIAGNOSED && evidence.none { it.supportsDiagnosis() }) {
      add(BlockIncidentDiagnosisViolation(BlockIncidentDiagnosisViolationCode.DIAGNOSED_WITHOUT_EVIDENCE, "certainty"))
    }
    if (responsibleCategory == BlockIncidentResponsibleCategory.UNRESOLVED &&
      certainty != BlockIncidentCertainty.UNKNOWN
    ) {
      add(BlockIncidentDiagnosisViolation(BlockIncidentDiagnosisViolationCode.UNRESOLVED_NOT_UNKNOWN, "certainty"))
    }
    if (proposedRecoveryAction == BlockIncidentRecoveryAction.NONE && recoveryEffects != null) {
      add(
        BlockIncidentDiagnosisViolation(BlockIncidentDiagnosisViolationCode.EFFECTS_WITHOUT_ACTION, "recovery_effects"),
      )
    }
    addTextViolations(this@validation)
    addEvidenceViolations(this@validation)
  }

internal fun BlockIncidentDiagnosisEvidence.supportsDiagnosis(): Boolean = available && kind !in NON_SUPPORTING_KINDS

private fun MutableList<BlockIncidentDiagnosisViolation>.addTextViolations(record: BlockIncidentDiagnosisRecord) {
  listOf(
    Triple("summary", record.summary, BlockIncidentDiagnosisRecord.SUMMARY_MAX),
    Triple("what_happened", record.whatHappened, BlockIncidentDiagnosisRecord.WHAT_HAPPENED_MAX),
  ).forEach { (field, value, limit) ->
    if (value.isBlank()) add(BlockIncidentDiagnosisViolation(BlockIncidentDiagnosisViolationCode.BLANK, field))
    addLengthViolation(field, value, limit)
  }
  addLengthViolation("cause", record.cause, BlockIncidentDiagnosisRecord.CAUSE_MAX)
  addLengthViolation("uncertainty_note", record.uncertaintyNote, BlockIncidentDiagnosisRecord.UNCERTAINTY_NOTE_MAX)
  addLengthViolation("recovery_effects", record.recoveryEffects, BlockIncidentDiagnosisRecord.RECOVERY_EFFECTS_MAX)
}

private fun MutableList<BlockIncidentDiagnosisViolation>.addEvidenceViolations(record: BlockIncidentDiagnosisRecord) {
  if (record.evidence.size > BlockIncidentDiagnosisRecord.EVIDENCE_MAX) {
    add(BlockIncidentDiagnosisViolation(BlockIncidentDiagnosisViolationCode.COUNT_EXCEEDED, "evidence"))
  }
  if (record.prerequisites.size > BlockIncidentDiagnosisRecord.PREREQUISITES_MAX) {
    add(BlockIncidentDiagnosisViolation(BlockIncidentDiagnosisViolationCode.COUNT_EXCEEDED, "prerequisites"))
  }
  record.evidence.forEachIndexed { index, item ->
    val field = "evidence[$index]"
    if (item.reference.isBlank()) {
      add(BlockIncidentDiagnosisViolation(BlockIncidentDiagnosisViolationCode.BLANK, "$field.reference"))
    }
    addLengthViolation("$field.reference", item.reference, BlockIncidentDiagnosisRecord.EVIDENCE_REFERENCE_MAX)
    addLengthViolation("$field.summary", item.summary, BlockIncidentDiagnosisRecord.EVIDENCE_SUMMARY_MAX)
    if (!DIGEST_PATTERN.matches(item.sha256)) {
      add(BlockIncidentDiagnosisViolation(BlockIncidentDiagnosisViolationCode.INVALID_DIGEST, "$field.sha256"))
    }
  }
}

private fun MutableList<BlockIncidentDiagnosisViolation>.addLengthViolation(
  field: String,
  value: String?,
  limit: Int,
) {
  if (value != null && value.length > limit) {
    add(BlockIncidentDiagnosisViolation(BlockIncidentDiagnosisViolationCode.LENGTH_EXCEEDED, field))
  }
}

private val NON_SUPPORTING_KINDS =
  setOf(BlockIncidentDiagnosisEvidenceKind.ORIGINAL_FAILURE, BlockIncidentDiagnosisEvidenceKind.RUNTIME_VERSION)
private val DIGEST_PATTERN = Regex("[0-9a-f]{64}")
