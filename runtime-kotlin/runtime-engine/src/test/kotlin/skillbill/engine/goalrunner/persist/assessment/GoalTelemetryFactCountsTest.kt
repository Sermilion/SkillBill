package skillbill.engine.goalrunner.persist.assessment

import skillbill.goalrunner.assessment.model.GoalAssessmentDisposition
import skillbill.goalrunner.assessment.model.GoalAssessmentExitReason
import skillbill.goalrunner.assessment.model.GoalAssessmentFinding
import skillbill.goalrunner.assessment.model.GoalAssessmentFindingKind
import skillbill.goalrunner.assessment.model.GoalAssessmentFindingState
import skillbill.goalrunner.assessment.model.GoalAssessmentRecord
import skillbill.goalrunner.assessment.model.GoalAssessmentRound
import skillbill.goalrunner.assessment.model.GoalAssessmentVerification
import skillbill.telemetry.model.GoalAssessmentTelemetryCounts
import skillbill.telemetry.model.GoalSubtaskTraceProbeCounts
import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionInteractionTrace
import skillbill.workflow.taskruntime.model.review.FeatureTaskRuntimeReviewSeverity
import kotlin.test.Test
import kotlin.test.assertEquals

class GoalTelemetryFactCountsTest {
  @Test
  fun `scenario counts are distinct refs with repaired scenarios also counted as broken`() {
    val trace =
      AcceptanceCriterionInteractionTrace(
        tracedScenarioRefs = listOf("S1", "S2"),
        brokenScenarioRefs = listOf("S3"),
        repairedScenarioRefs = listOf("S4", "S3"),
        unknownScenarioRefs = listOf("S5"),
      )

    val counts = trace.toTraceProbeCounts(validateOutputArtifact = null)

    assertEquals(5, counts.scenariosTraced)
    assertEquals(2, counts.scenariosBroken)
    assertEquals(2, counts.scenariosRepaired)
    assertEquals(1, counts.scenariosUnknown)
  }

  @Test
  fun `probe counts come from the validate output and a missing or malformed output counts zero`() {
    val output =
      """
      {"produced_outputs":{"test_probes":[
        {"test_ref":"t1","result":"killed","repaired":false},
        {"test_ref":"t2","result":"survived","repaired":true},
        {"test_ref":"t3","result":"not_run"},
        {"test_ref":"t4","result":"killed"}
      ]}}
      """.trimIndent()

    val counts = AcceptanceCriterionInteractionTrace.EMPTY.toTraceProbeCounts(output)

    assertEquals(
      GoalSubtaskTraceProbeCounts(probesKilled = 2, probesSurvived = 1, probesRepaired = 1, probesNotRun = 1),
      counts,
    )
    val empty = AcceptanceCriterionInteractionTrace.EMPTY
    assertEquals(GoalSubtaskTraceProbeCounts(), empty.toTraceProbeCounts(null))
    assertEquals(GoalSubtaskTraceProbeCounts(), empty.toTraceProbeCounts("not json"))
  }

  @Test
  fun `assessment counts total findings across all rounds and carry the exit reason`() {
    val record =
      GoalAssessmentRecord(
        issueKey = "SKILL-442",
        rounds =
          listOf(
            round(1, finding("F1", FeatureTaskRuntimeReviewSeverity.BLOCKER, GoalAssessmentVerification.CONFIRMED)),
            round(
              2,
              finding("F2", FeatureTaskRuntimeReviewSeverity.MAJOR, GoalAssessmentVerification.REFUTED),
              finding("F3", FeatureTaskRuntimeReviewSeverity.MINOR, GoalAssessmentVerification.CONFIRMED),
            ),
          ),
        exitReason = GoalAssessmentExitReason.ROUND_CAP,
        openFindingRefs = listOf("F1"),
      )

    assertEquals(
      GoalAssessmentTelemetryCounts(
        blockerFindings = 1,
        majorFindings = 1,
        minorFindings = 1,
        confirmed = 2,
        refuted = 1,
        rounds = 2,
        openFindings = 1,
        exitReason = "round_cap",
      ),
      record.toTelemetryCounts(),
    )
  }

  private fun round(
    number: Int,
    vararg findings: GoalAssessmentFinding,
  ): GoalAssessmentRound =
    GoalAssessmentRound(
      round = number,
      headSha = "sha",
      scopeFiles = emptyList(),
      assessorRuns = emptyList(),
      findings = findings.toList(),
      scenarios = emptyList(),
      regressions = emptyList(),
      fix = null,
    )

  private fun finding(
    ref: String,
    severity: FeatureTaskRuntimeReviewSeverity,
    verification: GoalAssessmentVerification,
  ): GoalAssessmentFinding =
    GoalAssessmentFinding(
      findingRef = ref,
      kind = GoalAssessmentFindingKind.DEFECT,
      severity = severity,
      path = "A.kt",
      line = 1,
      failureScenario = "scenario",
      guardedRef = null,
      subtaskId = null,
      originRound = 1,
      verification = verification,
      verificationEvidence = "evidence",
      fixRoundsSurvived = 0,
      disposition = GoalAssessmentDisposition.FIX,
      state = GoalAssessmentFindingState.OPEN,
    )
}
