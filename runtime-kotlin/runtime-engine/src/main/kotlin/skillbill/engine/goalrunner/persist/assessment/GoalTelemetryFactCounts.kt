package skillbill.engine.goalrunner.persist.assessment

import skillbill.contracts.JsonCodec
import skillbill.contracts.SharedPayloadKeys
import skillbill.contracts.workflow.featuretask.ValidationTestProbePayloadKeys
import skillbill.goalrunner.assessment.model.GoalAssessmentRecord
import skillbill.goalrunner.assessment.model.GoalAssessmentVerification
import skillbill.telemetry.model.GoalAssessmentTelemetryCounts
import skillbill.telemetry.model.GoalSubtaskTraceProbeCounts
import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionInteractionTrace
import skillbill.workflow.taskruntime.model.review.FeatureTaskRuntimeReviewSeverity
import skillbill.workflow.taskruntime.model.validation.ValidationTestProbeResult

internal fun AcceptanceCriterionInteractionTrace.toTraceProbeCounts(
  validateOutputArtifact: String?,
): GoalSubtaskTraceProbeCounts {
  val probes = validateOutputArtifact?.let(::validationProbeEntries).orEmpty()

  fun resultCount(result: ValidationTestProbeResult): Int =
    probes.count { it[ValidationTestProbePayloadKeys.RESULT] == result.wireValue }
  return GoalSubtaskTraceProbeCounts(
    scenariosTraced =
      (tracedScenarioRefs + brokenScenarioRefs + repairedScenarioRefs + unknownScenarioRefs).distinct().size,
    scenariosBroken = (brokenScenarioRefs + repairedScenarioRefs).distinct().size,
    scenariosRepaired = repairedScenarioRefs.distinct().size,
    scenariosUnknown = unknownScenarioRefs.distinct().size,
    probesKilled = resultCount(ValidationTestProbeResult.KILLED),
    probesSurvived = resultCount(ValidationTestProbeResult.SURVIVED),
    probesRepaired = probes.count { it[ValidationTestProbePayloadKeys.REPAIRED] == true },
    probesNotRun = resultCount(ValidationTestProbeResult.NOT_RUN),
  )
}

internal fun GoalAssessmentRecord.toTelemetryCounts(): GoalAssessmentTelemetryCounts {
  val findings = rounds.flatMap { it.findings }

  fun severityCount(severity: FeatureTaskRuntimeReviewSeverity): Int = findings.count { it.severity == severity }

  fun verificationCount(verification: GoalAssessmentVerification): Int =
    findings.count { it.verification == verification }
  return GoalAssessmentTelemetryCounts(
    blockerFindings = severityCount(FeatureTaskRuntimeReviewSeverity.BLOCKER),
    majorFindings = severityCount(FeatureTaskRuntimeReviewSeverity.MAJOR),
    minorFindings = severityCount(FeatureTaskRuntimeReviewSeverity.MINOR),
    confirmed = verificationCount(GoalAssessmentVerification.CONFIRMED),
    refuted = verificationCount(GoalAssessmentVerification.REFUTED),
    rounds = rounds.size,
    openFindings = openFindingRefs.size,
    exitReason = exitReason?.wireValue,
  )
}

private fun validationProbeEntries(outputArtifact: String): List<Map<String, Any?>> {
  val envelope =
    JsonCodec.parseObjectOrNull(outputArtifact)
      ?.let(JsonCodec::jsonElementToValue)
      ?.let(JsonCodec::anyToStringAnyMap)
  val produced = envelope?.get(SharedPayloadKeys.PRODUCED_OUTPUTS)?.let(JsonCodec::anyToStringAnyMap)
  return JsonCodec.anyToStringAnyMapList(produced?.get(ValidationTestProbePayloadKeys.TEST_PROBES)).orEmpty()
}
