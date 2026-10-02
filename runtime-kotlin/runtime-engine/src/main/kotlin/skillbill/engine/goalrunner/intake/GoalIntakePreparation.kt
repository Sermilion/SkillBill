package skillbill.engine.goalrunner.intake

import me.tatarka.inject.annotations.Inject
import skillbill.engine.featuretask.prepare.FeatureSpecPreparationWriter
import skillbill.engine.goalrunner.manifest.GoalRunnerManifestStore
import skillbill.engine.goalrunner.model.GoalRunnerManifestState
import skillbill.engine.goalrunner.model.GoalRunnerRunRequest
import skillbill.featurespec.model.FeatureSpecPreparationDecision
import skillbill.featurespec.model.FeatureSpecPreparationMode
import skillbill.featurespec.model.FeatureSpecSubtaskPreparation
import skillbill.featurespec.model.FeatureSpecWriteRequest

@Inject
class GoalIntakePreparation(
  private val manifestStore: GoalRunnerManifestStore,
  private val specWriter: FeatureSpecPreparationWriter,
) {
  fun prepare(request: GoalRunnerRunRequest): GoalRunnerManifestState? {
    manifestStore.loadByIssueKey(request.issueKey, request.repoRoot)?.let { return it }
    val intake = request.intake?.takeIf(String::isNotBlank) ?: return null
    val criteria = acceptanceCriteria(intake)
    val constraints = listOf(TRACKER_RESOLUTION)
    specWriter.write(
      request.repoRoot,
      FeatureSpecWriteRequest(
        decision =
          FeatureSpecPreparationDecision(
            request.issueKey,
            intake,
            criteria,
            constraints,
            emptyList(),
            FeatureSpecPreparationMode.SINGLE_SPEC,
          ),
        featureName = "intake",
        parentSpecOverview = intake,
        validationStrategy = VALIDATION,
        subtasks =
          listOf(
            FeatureSpecSubtaskPreparation(
              id = 1,
              name = "Implement the requested change",
              scope = intake + "\n\n" + TRACKER_RESOLUTION,
              acceptanceCriteria = criteria,
              nonGoals = emptyList(),
              dependencyNotes = "The full goal owns planning and execution of the supplied requirements.",
              validationStrategy = VALIDATION,
              nextPath = "Complete the goal and prepare its pull request.",
            ),
          ),
      ),
    )
    return manifestStore.loadByIssueKey(request.issueKey, request.repoRoot)
  }

  private fun acceptanceCriteria(intake: String): List<String> {
    val lines = intake.lines()
    val start = lines.indexOfFirst { it.trim().matches(ACCEPTANCE_HEADING) }
    val listed =
      if (start < 0) {
        emptyList()
      } else {
        lines.drop(start + 1)
          .takeWhile { !it.trim().startsWith("#") }
          .mapNotNull { ITEM.matchEntire(it)?.groupValues?.get(1)?.trim()?.takeIf(String::isNotBlank) }
      }
    return listed.ifEmpty { listOf(intake.replace(Regex("\\s+"), " ")) }
  }

  private companion object {
    val ACCEPTANCE_HEADING = Regex("(?i)#{1,6}\\s+acceptance criteria\\s*")
    val ITEM = Regex("\\s*(?:[-*]|[0-9]+[.)])\\s+(?:\\[[ xX]\\]\\s+)?(.+)")
    const val VALIDATION = "Run the repository's required checks and verify every supplied acceptance criterion."
    const val TRACKER_RESOLUTION =
      "If the intake contains an unresolved tracker link or issue key, fetch that exact issue through its " +
        "connected tracker before planning. Linear, Jira, and other connected trackers use the same rule. " +
        "Use the returned requirements, not the URL title. If lookup fails or the connection is unavailable, " +
        "block with the returned reason before implementation; never infer or substitute requirements. " +
        "A LOCAL key identifies this workflow and is not a claim that a tracker issue exists."
  }
}
