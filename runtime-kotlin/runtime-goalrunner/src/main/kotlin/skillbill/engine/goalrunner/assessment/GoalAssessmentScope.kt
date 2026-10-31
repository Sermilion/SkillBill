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

  fun fixAttributedSubtasks(
    prior: GoalAssessmentRecord,
    roundNumber: Int,
  ): Set<Int> {
    val settled = prior.rounds.take(roundNumber - 1)
    if (settled.lastOrNull()?.fix?.commitSha == null) return emptySet()
    val fixed = GoalAssessmentVerdicts.fixSetOf(settled).findingRefs.toSet()
    return settled.flatMap { it.findings }
      .filter { it.findingRef in fixed }
      .mapNotNull { it.subtaskId }
      .toSet()
  }

  fun subtaskTouched(
    subtask: GoalAssessmentSubtaskInput,
    scope: Set<String>,
  ): Boolean = subtask.rangeFiles.any { normalize(it) in scope }

  fun crossSubtaskTouched(
    prior: GoalAssessmentRecord,
    roundNumber: Int,
    scope: Set<String>,
  ): Boolean {
    val settled = prior.rounds.take(roundNumber - 1)
    val evidence =
      settled.flatMap { it.scenarios }.associateBy { it.scenarioRef }.values.map { it.evidence } +
        settled.flatMap { it.regressions }.associateBy { it.regressionRef }.values.map { it.evidence }
    return evidence.any { touches(it, scope) }
  }

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

  private fun touches(
    evidence: String,
    scope: Set<String>,
  ): Boolean {
    val cited = CITATION.findAll(evidence).map { normalize(it.groupValues[1]) }.toSet()
    return cited.isEmpty() || cited.any { it in scope }
  }

  private fun squash(text: String): String = text.trim().lowercase().replace(WHITESPACE, " ")

  private val CITATION = Regex("""([\w./\-]+\.\w+):\d+""")
  private val WHITESPACE = Regex("\\s+")
}
