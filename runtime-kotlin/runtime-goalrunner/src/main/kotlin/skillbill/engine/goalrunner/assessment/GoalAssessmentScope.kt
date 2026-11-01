package skillbill.engine.goalrunner.assessment

import skillbill.goalrunner.assessment.GoalAssessmentLoopRules
import skillbill.goalrunner.assessment.model.GoalAssessmentFinding
import skillbill.goalrunner.assessment.model.GoalAssessmentRecord

internal object GoalAssessmentScope {
  fun of(
    prior: GoalAssessmentRecord,
    roundNumber: Int,
  ): List<String> =
    prior.rounds.getOrNull(roundNumber - 2)?.fix?.takeIf { it.commitSha != null }
      ?.changedFiles.orEmpty().map(::normalize).distinct()

  fun carriedOf(
    prior: GoalAssessmentRecord,
    roundNumber: Int,
  ): List<GoalAssessmentFinding> =
    GoalAssessmentLoopRules.openFindings(prior.copy(rounds = prior.rounds.take(roundNumber - 1)))

  fun restates(
    carried: List<GoalAssessmentFinding>,
    path: String,
    failureScenario: String,
  ): Boolean =
    carried.any { normalize(it.path) == normalize(path) && squash(it.failureScenario) == squash(failureScenario) }

  fun normalize(path: String): String = path.trim().removePrefix("./")

  private fun squash(text: String): String = text.trim().lowercase().replace(WHITESPACE, " ")

  private val WHITESPACE = Regex("\\s+")
}
