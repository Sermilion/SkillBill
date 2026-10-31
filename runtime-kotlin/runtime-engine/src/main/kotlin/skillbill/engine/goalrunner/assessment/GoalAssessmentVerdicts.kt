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
  val scenarioRefs: List<String> = emptyList(),
) {
  val isEmpty: Boolean get() = findingRefs.isEmpty() && regressionRefs.isEmpty() && scenarioRefs.isEmpty()
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
      val (settled, evidence) = settledVerdict(finding, verdictsByRef, ::needsVerdict)
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

  fun applyCarried(
    findings: List<GoalAssessmentFinding>,
    report: GoalVerifierReport,
  ): List<GoalAssessmentFinding> {
    val verdictsByRef = report.verdicts.groupBy { it.findingRef }
    return findings.map { finding ->
      val (settled, evidence) = settledVerdict(finding, verdictsByRef, ::isCarried)
      when (settled) {
        GoalAssessmentVerification.CONFIRMED ->
          finding.copy(fixRoundsSurvived = finding.fixRoundsSurvived + 1, verificationEvidence = evidence)
        GoalAssessmentVerification.REFUTED ->
          finding.copy(state = GoalAssessmentFindingState.FIXED, verificationEvidence = evidence)
        else -> finding
      }
    }
  }

  fun carriedCandidatesOf(findings: List<GoalAssessmentFinding>): List<GoalAssessmentVerifierCandidate> =
    findings.filter(::isCarried).map {
      GoalAssessmentVerifierCandidate(it.findingRef, it.path, it.line, it.failureScenario)
    }

  fun withDispositions(findings: List<GoalAssessmentFinding>): List<GoalAssessmentFinding> =
    findings.map { finding ->
      val fix =
        finding.state == GoalAssessmentFindingState.OPEN &&
          finding.kind == GoalAssessmentFindingKind.DEFECT &&
          finding.verification == GoalAssessmentVerification.CONFIRMED
      finding.copy(disposition = if (fix) GoalAssessmentDisposition.FIX else GoalAssessmentDisposition.PR_ONLY)
    }

  fun fixSetOf(rounds: List<GoalAssessmentRound>): GoalAssessmentFixSet =
    GoalAssessmentFixSet(
      findingRefs =
        rounds.flatMapIndexed { index, round ->
          round.findings.filter {
            it.disposition == GoalAssessmentDisposition.FIX &&
              it.state == GoalAssessmentFindingState.OPEN &&
              (index == rounds.lastIndex || isCarried(it))
          }
        }.map { it.findingRef },
      regressionRefs =
        rounds.lastOrNull()?.regressions.orEmpty()
          .filter { it.state == GoalAssessmentRegressionState.NOT_RESTORED }.map { it.regressionRef },
    )

  private fun settledVerdict(
    finding: GoalAssessmentFinding,
    verdictsByRef: Map<String, List<GoalVerifierVerdict>>,
    eligible: (GoalAssessmentFinding) -> Boolean,
  ): Pair<GoalAssessmentVerification?, String> {
    val verdicts = verdictsByRef[finding.findingRef].orEmpty().takeIf { eligible(finding) }.orEmpty()
    val settled = verdicts.map { it.verdict }.distinct().singleOrNull()
    return settled to verdicts.firstOrNull { it.verdict == settled }?.evidence.orEmpty()
  }

  private fun isCarried(finding: GoalAssessmentFinding): Boolean =
    finding.kind == GoalAssessmentFindingKind.DEFECT &&
      finding.verification == GoalAssessmentVerification.CONFIRMED &&
      finding.state == GoalAssessmentFindingState.OPEN

  private fun needsVerdict(finding: GoalAssessmentFinding): Boolean =
    finding.kind == GoalAssessmentFindingKind.DEFECT &&
      finding.verification == GoalAssessmentVerification.UNVERIFIED
}
