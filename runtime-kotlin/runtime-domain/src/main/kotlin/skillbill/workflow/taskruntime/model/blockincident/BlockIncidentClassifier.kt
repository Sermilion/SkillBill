package skillbill.workflow.taskruntime.model.blockincident

import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimeFailureDisposition
import java.time.Instant

object BlockIncidentClassifier {
  fun classify(
    occurrence: BlockIncidentOccurrence,
    evidence: List<BlockIncidentDiagnosisEvidence>,
    degradations: Set<BlockIncidentDegradation>,
    completedAt: Instant,
  ): BlockIncidentDiagnosisRecord {
    val rule = BLOCK_INCIDENT_CLASSIFICATION_RULES.firstOrNull { it.matches(occurrence, evidence) }
    val category = rule?.category ?: BlockIncidentResponsibleCategory.UNRESOLVED
    val certainty = certaintyFor(rule, evidence)
    val prerequisites =
      listOfNotNull(
        rule?.prerequisite?.let { BlockIncidentPrerequisite(it, BlockIncidentPrerequisiteCheck.USER_CONFIRMED) },
      )
    val action = recoveryActionFor(occurrence, prerequisites)
    return BlockIncidentDiagnosisRecord(
      occurrenceToken = occurrence.occurrenceToken,
      revision = 0,
      summary = summaryOf(occurrence, category).take(BlockIncidentDiagnosisRecord.SUMMARY_MAX),
      whatHappened = whatHappenedOf(occurrence).take(BlockIncidentDiagnosisRecord.WHAT_HAPPENED_MAX),
      cause = null,
      certainty = certainty,
      uncertaintyNote =
        if (rule == null) unmappedNoteOf(occurrence).take(BlockIncidentDiagnosisRecord.UNCERTAINTY_NOTE_MAX) else null,
      responsibleCategory = category,
      evidence = evidence.take(BlockIncidentDiagnosisRecord.EVIDENCE_MAX),
      prerequisites = prerequisites,
      proposedRecoveryAction = action,
      recoveryEffects = recoveryEffectsOf(occurrence, action),
      noWorkaroundKnown = action == BlockIncidentRecoveryAction.NONE && prerequisites.isEmpty(),
      degradations = degradations,
      investigatorKind = BlockIncidentInvestigatorKind.DETERMINISTIC,
      completedAt = completedAt,
    )
  }

  private fun certaintyFor(
    rule: BlockIncidentClassificationRule?,
    evidence: List<BlockIncidentDiagnosisEvidence>,
  ): BlockIncidentCertainty {
    val supported = evidence.any { it.supportsDiagnosis() }
    return when {
      rule == null -> BlockIncidentCertainty.UNKNOWN
      rule.certainty == BlockIncidentCertainty.DIAGNOSED && !supported -> BlockIncidentCertainty.SUSPECTED
      else -> rule.certainty
    }
  }

  private fun recoveryActionFor(
    occurrence: BlockIncidentOccurrence,
    prerequisites: List<BlockIncidentPrerequisite>,
  ): BlockIncidentRecoveryAction {
    val reopenable =
      occurrence.origin == BlockIncidentOrigin.ACCEPTED_PHASE_BLOCK &&
        occurrence.failureDisposition in REOPENABLE_DISPOSITIONS &&
        occurrence.phaseId != null &&
        occurrence.attempt != null
    return when {
      reopenable -> BlockIncidentRecoveryAction.REOPEN_BLOCKED_PHASE
      prerequisites.isNotEmpty() -> BlockIncidentRecoveryAction.RETRY_AFTER_PREREQUISITE
      else -> BlockIncidentRecoveryAction.NONE
    }
  }

  private fun recoveryEffectsOf(
    occurrence: BlockIncidentOccurrence,
    action: BlockIncidentRecoveryAction,
  ): String? =
    when (action) {
      BlockIncidentRecoveryAction.REOPEN_BLOCKED_PHASE ->
        "Reopens phase ${occurrence.phaseId} attempt ${occurrence.attempt} and resumes the saved run."
      BlockIncidentRecoveryAction.RETRY_AFTER_PREREQUISITE ->
        "Retries the blocked work after the listed prerequisite is confirmed."
      BlockIncidentRecoveryAction.NONE -> null
    }

  private fun summaryOf(
    occurrence: BlockIncidentOccurrence,
    category: BlockIncidentResponsibleCategory,
  ): String = "${occurrence.origin.wireValue} at ${locationOf(occurrence)} classified as ${category.wireValue}."

  private fun whatHappenedOf(occurrence: BlockIncidentOccurrence): String =
    "The run stopped with ${occurrence.origin.wireValue} at ${locationOf(occurrence)}. " +
      "Failure code: ${occurrence.failureCode ?: NONE_LABEL}. " +
      "Disposition: ${occurrence.failureDisposition ?: NONE_LABEL}."

  private fun unmappedNoteOf(occurrence: BlockIncidentOccurrence): String =
    "No classification rule matches failure code ${occurrence.failureCode ?: NONE_LABEL} " +
      "with disposition ${occurrence.failureDisposition ?: NONE_LABEL}."

  private fun locationOf(occurrence: BlockIncidentOccurrence): String =
    when {
      occurrence.phaseId != null && occurrence.attempt != null ->
        "phase ${occurrence.phaseId} attempt ${occurrence.attempt}"
      occurrence.phaseId != null -> "phase ${occurrence.phaseId}"
      else -> "workflow ${occurrence.workflowId ?: NONE_LABEL}"
    }

  private const val NONE_LABEL = "none"

  private val REOPENABLE_DISPOSITIONS =
    setOf(
      FeatureTaskRuntimeFailureDisposition.RETRYABLE.wireValue,
      FeatureTaskRuntimeFailureDisposition.NEEDS_USER_ACTION.wireValue,
    )
}
