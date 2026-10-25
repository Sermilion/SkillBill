package skillbill.engine.goalrunner.assessment

import skillbill.goalrunner.assessment.model.GoalAssessmentDisposition
import skillbill.goalrunner.assessment.model.GoalAssessmentFinding
import skillbill.goalrunner.assessment.model.GoalAssessmentFindingKind
import skillbill.goalrunner.assessment.model.GoalAssessmentFindingState
import skillbill.goalrunner.assessment.model.GoalAssessmentRegressionState
import skillbill.goalrunner.assessment.model.GoalAssessmentRound
import skillbill.goalrunner.assessment.model.GoalAssessmentVerification

data class GoalAssessmentFixSet(
  val findingRefs: List<String>,
  val regressionRefs: List<String>,
) {
  val isEmpty: Boolean get() = findingRefs.isEmpty() && regressionRefs.isEmpty()
}

internal object GoalAssessmentVerdicts {
  fun candidatesOf(findings: List<GoalAssessmentFinding>): List<GoalAssessmentVerifierCandidate> =
    findings.filter(::needsVerdict).map {
      GoalAssessmentVerifierCandidate(it.findingRef, it.path, it.line, it.failureScenario)
    }

  fun apply(
    findings: List<GoalAssessmentFinding>,
    report: GoalVerifierReport,
  ): List<GoalAssessmentFinding> {
    val verdictsByRef = report.verdicts.groupBy { it.findingRef }
    return findings.map { finding ->
      val verdicts = verdictsByRef[finding.findingRef].orEmpty().takeIf { needsVerdict(finding) }.orEmpty()
      val settled = verdicts.map { it.verdict }.distinct().singleOrNull()
      val evidence = verdicts.firstOrNull { it.verdict == settled }?.evidence.orEmpty()
      when (settled) {
        GoalAssessmentVerification.CONFIRMED ->
          finding.copy(verification = GoalAssessmentVerification.CONFIRMED, verificationEvidence = evidence)
        GoalAssessmentVerification.REFUTED ->
          finding.copy(
            verification = GoalAssessmentVerification.REFUTED,
            verificationEvidence = evidence,
            state = GoalAssessmentFindingState.REFUTED,
          )
        else -> finding
      }
    }
  }

  fun withDispositions(findings: List<GoalAssessmentFinding>): List<GoalAssessmentFinding> {
    val confirmedPaths =
      findings.filter { it.verification == GoalAssessmentVerification.CONFIRMED }.map { it.path.removePrefix("./") }
    return findings.map { finding ->
      val fix =
        when {
          finding.state == GoalAssessmentFindingState.REFUTED -> false
          finding.kind == GoalAssessmentFindingKind.DEFECT && finding.severity.blocksAdvance ->
            finding.verification == GoalAssessmentVerification.CONFIRMED
          else -> finding.path.removePrefix("./") in confirmedPaths
        }
      finding.copy(disposition = if (fix) GoalAssessmentDisposition.FIX else GoalAssessmentDisposition.PR_ONLY)
    }
  }

  fun fixSetOf(round: GoalAssessmentRound): GoalAssessmentFixSet =
    GoalAssessmentFixSet(
      findingRefs =
        round.findings
          .filter { it.disposition == GoalAssessmentDisposition.FIX && it.state == GoalAssessmentFindingState.OPEN }
          .map { it.findingRef },
      regressionRefs =
        round.regressions.filter { it.state == GoalAssessmentRegressionState.NOT_RESTORED }.map { it.regressionRef },
    )

  private fun needsVerdict(finding: GoalAssessmentFinding): Boolean =
    finding.kind == GoalAssessmentFindingKind.DEFECT &&
      finding.severity.blocksAdvance &&
      finding.verification == GoalAssessmentVerification.UNVERIFIED
}
