package skillbill.engine.goalrunner.assessment

import skillbill.goalrunner.assessment.model.GoalAssessmentFindingKind
import skillbill.goalrunner.assessment.model.GoalAssessmentRegressionResult
import skillbill.goalrunner.assessment.model.GoalAssessmentRegressionState
import skillbill.goalrunner.assessment.model.GoalAssessmentScenarioResult
import skillbill.goalrunner.assessment.model.GoalAssessmentScenarioVerdict
import skillbill.goalrunner.assessment.model.GoalAssessmentVerification
import skillbill.workflow.taskruntime.model.review.FeatureTaskRuntimeReviewSeverity

internal data class GoalAssessmentCandidate(
  val kind: GoalAssessmentFindingKind,
  val severity: FeatureTaskRuntimeReviewSeverity,
  val path: String,
  val line: Int,
  val failureScenario: String,
  val guardedRef: String?,
)

internal data class GoalAssessorReport(
  val findings: List<GoalAssessmentCandidate>,
  val scenarios: List<GoalAssessmentScenarioResult>,
  val regressions: List<GoalAssessmentRegressionResult>,
  val rejectedLines: List<String>,
)

internal data class GoalVerifierVerdict(
  val findingRef: String,
  val verdict: GoalAssessmentVerification,
  val evidence: String,
)

internal data class GoalVerifierReport(
  val verdicts: List<GoalVerifierVerdict>,
  val rejectedLines: List<String>,
)

internal sealed interface GoalAssessmentParse<out T> {
  data class Parsed<T>(val report: T) : GoalAssessmentParse<T>

  data class Malformed(val reason: String) : GoalAssessmentParse<Nothing>
}

internal object GoalAssessmentLineParser {
  fun parseAssessor(text: String): GoalAssessmentParse<GoalAssessorReport> {
    val findings = mutableListOf<GoalAssessmentCandidate>()
    val scenarios = mutableListOf<GoalAssessmentScenarioResult>()
    val regressions = mutableListOf<GoalAssessmentRegressionResult>()
    val rejected = mutableListOf<String>()
    var valid = 0
    recordLines(text).forEach { line ->
      val parsed =
        findingOf(line)?.let(findings::add) ?: scenarioOf(line)?.let(scenarios::add)
          ?: regressionOf(line)?.let(regressions::add) ?: FINDING_NONE.matches(line).takeIf { it }
      if (parsed == null) rejected += line.take(MAX_REJECTED_LINE) else valid++
    }
    return if (valid == 0) {
      GoalAssessmentParse.Malformed("the output held no line in the assessor grammar.")
    } else {
      GoalAssessmentParse.Parsed(GoalAssessorReport(findings, scenarios, regressions, rejected))
    }
  }

  fun parseVerifier(text: String): GoalAssessmentParse<GoalVerifierReport> {
    val verdicts = mutableListOf<GoalVerifierVerdict>()
    val rejected = mutableListOf<String>()
    var valid = 0
    recordLines(text).forEach { line ->
      val parsed = verdictOf(line)?.let(verdicts::add) ?: VERIFY_NONE.matches(line).takeIf { it }
      if (parsed == null) rejected += line.take(MAX_REJECTED_LINE) else valid++
    }
    return if (valid == 0) {
      GoalAssessmentParse.Malformed("the output held no line in the verifier grammar.")
    } else {
      GoalAssessmentParse.Parsed(GoalVerifierReport(verdicts, rejected))
    }
  }

  private fun recordLines(text: String): List<String> =
    text.lineSequence()
      .map { it.trim().trimStart('-', '*', '>', ' ', '`').trimEnd('`', ' ') }
      .filter { RECORD_KEYWORD.containsMatchIn(it) }
      .toList()

  private fun findingOf(line: String): GoalAssessmentCandidate? {
    FINDING.matchEntire(line)?.let { match ->
      val (severity, location, scenario) = match.destructured
      return sourceLineOf(location)?.let { (path, lineNumber) ->
        GoalAssessmentCandidate(
          kind = GoalAssessmentFindingKind.DEFECT,
          severity = FeatureTaskRuntimeReviewSeverity.fromWire(severity),
          path = path,
          line = lineNumber,
          failureScenario = scenario.trim(),
          guardedRef = null,
        )
      }
    }
    return TEST_VALUE.matchEntire(line)?.let { match ->
      val (location, guarded, reason) = match.destructured
      sourceLineOf(location)?.let { (path, lineNumber) ->
        GoalAssessmentCandidate(
          kind = GoalAssessmentFindingKind.TEST_VALUE,
          severity = FeatureTaskRuntimeReviewSeverity.MINOR,
          path = path,
          line = lineNumber,
          failureScenario = "The test does not guard $guarded: ${reason.trim()}",
          guardedRef = guarded.removePrefix("./"),
        )
      }
    }
  }

  private fun sourceLineOf(location: String): Pair<String, Int>? {
    val lineNumber = location.substringAfterLast(':').toIntOrNull()?.takeIf { it >= 1 } ?: return null
    return location.substringBeforeLast(':').removePrefix("./") to lineNumber
  }

  private fun scenarioOf(line: String): GoalAssessmentScenarioResult? =
    SCENARIO.matchEntire(line)?.destructured?.let { (ref, verdict, evidence) ->
      GoalAssessmentScenarioResult(
        ref.uppercase(),
        GoalAssessmentScenarioVerdict.entries.first { it.wireValue == verdict.lowercase() },
        evidence.trim(),
      )
    }

  private fun regressionOf(line: String): GoalAssessmentRegressionResult? =
    REGRESSION.matchEntire(line)?.destructured?.let { (ref, state, evidence) ->
      GoalAssessmentRegressionResult(
        ref.uppercase(),
        GoalAssessmentRegressionState.entries.first { it.wireValue == state.lowercase() },
        evidence.trim(),
      )
    }

  private fun verdictOf(line: String): GoalVerifierVerdict? =
    VERIFY.matchEntire(line)?.destructured?.let { (ref, verdict, evidence) ->
      GoalVerifierVerdict(
        ref.uppercase(),
        GoalAssessmentVerification.entries.first { it.wireValue == verdict.lowercase() },
        evidence.trim(),
      )
    }

  private const val MAX_REJECTED_LINE = 160
  private val RECORD_KEYWORD = Regex("""^(Finding|Test value|Scenario|Regression|Verify)\b""", RegexOption.IGNORE_CASE)
  private val FINDING =
    Regex("""^Finding:\s*(blocker|major|minor)\s+(\S+?:\d+)\s+scenario\s+(.+)$""", RegexOption.IGNORE_CASE)
  private val FINDING_NONE = Regex("""^Finding:\s*none\.?$""", RegexOption.IGNORE_CASE)
  private val TEST_VALUE =
    Regex("""^Test value:\s*(\S+?:\d+)\s+guards\s+(\S+?:\d+)\s+reason\s+(.+)$""", RegexOption.IGNORE_CASE)
  private val SCENARIO =
    Regex("""^Scenario\s+(IS-\d{3}):\s*(holds|broken)\s+evidence\s+(.+)$""", RegexOption.IGNORE_CASE)
  private val REGRESSION =
    Regex("""^Regression\s+(TR-\d{3}):\s*(restored|not_restored)\s+evidence\s+(.+)$""", RegexOption.IGNORE_CASE)
  private val VERIFY =
    Regex("""^Verify\s+(GA-\d{3}):\s*(confirmed|refuted)\s+evidence\s+(.+)$""", RegexOption.IGNORE_CASE)
  private val VERIFY_NONE = Regex("""^Verify:\s*none\.?$""", RegexOption.IGNORE_CASE)
}
