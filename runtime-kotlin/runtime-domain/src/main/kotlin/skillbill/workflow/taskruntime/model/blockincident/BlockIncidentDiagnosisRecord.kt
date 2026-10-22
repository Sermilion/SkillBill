package skillbill.workflow.taskruntime.model.blockincident

import java.time.Instant

data class BlockIncidentDiagnosisEvidence(
  val kind: BlockIncidentDiagnosisEvidenceKind,
  val reference: String,
  val summary: String,
  val sha256: String,
  val trusted: Boolean,
) {
  val available: Boolean get() = sha256 != BlockIncidentDiagnosisRecord.EMPTY_CONTENT_SHA256
}

data class BlockIncidentPrerequisite(
  val kind: BlockIncidentPrerequisiteKind,
  val check: BlockIncidentPrerequisiteCheck,
)

data class BlockIncidentDiagnosisRecord(
  val occurrenceToken: String,
  val revision: Long,
  val summary: String,
  val whatHappened: String,
  val cause: String?,
  val certainty: BlockIncidentCertainty,
  val uncertaintyNote: String?,
  val responsibleCategory: BlockIncidentResponsibleCategory,
  val evidence: List<BlockIncidentDiagnosisEvidence>,
  val prerequisites: List<BlockIncidentPrerequisite>,
  val proposedRecoveryAction: BlockIncidentRecoveryAction,
  val recoveryEffects: String?,
  val noWorkaroundKnown: Boolean,
  val degradations: Set<BlockIncidentDegradation>,
  val investigatorKind: BlockIncidentInvestigatorKind,
  val completedAt: Instant,
) {
  companion object {
    const val SUMMARY_MAX = 280
    const val WHAT_HAPPENED_MAX = 2000
    const val CAUSE_MAX = 2000
    const val UNCERTAINTY_NOTE_MAX = 1000
    const val RECOVERY_EFFECTS_MAX = 1000
    const val EVIDENCE_MAX = 16
    const val PREREQUISITES_MAX = 8
    const val EVIDENCE_SUMMARY_MAX = 512
    const val EVIDENCE_REFERENCE_MAX = 2048
    const val EMPTY_CONTENT_SHA256 = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
  }
}
