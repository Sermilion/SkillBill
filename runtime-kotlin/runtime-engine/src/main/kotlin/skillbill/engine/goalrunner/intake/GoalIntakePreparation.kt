package skillbill.engine.goalrunner.intake

import me.tatarka.inject.annotations.Inject
import skillbill.contracts.issuekey.issueAndFeature
import skillbill.engine.featuretask.prepare.FeatureSpecPreparationWriter
import skillbill.engine.goalrunner.manifest.GoalRunnerManifestStore
import skillbill.engine.goalrunner.model.GoalIntakeMissingInput
import skillbill.engine.goalrunner.model.GoalRunnerManifestState
import skillbill.engine.goalrunner.model.GoalRunnerRunRequest
import skillbill.error.core.InvalidFeatureSpecPreparationRequestError
import skillbill.featurespec.model.FeatureSpecPreparationDecision
import skillbill.featurespec.model.FeatureSpecPreparationMode
import skillbill.featurespec.model.FeatureSpecSubtaskPreparation
import skillbill.featurespec.model.FeatureSpecWriteRequest
import skillbill.ports.featurespec.FeatureSpecPathResolverPort
import skillbill.ports.featurespec.model.FeatureSpecPathResolveInput
import skillbill.ports.featurespec.model.FeatureSpecPathResolveResult
import skillbill.ports.workflow.decomposition.DecompositionManifestStore
import skillbill.ports.workflow.gitops.WorkflowGitOperations
import java.nio.file.Path

@Inject
class GoalIntakePreparation(
  private val manifestStore: GoalRunnerManifestStore,
  private val specWriter: FeatureSpecPreparationWriter,
  private val specPathResolver: FeatureSpecPathResolverPort,
  private val fileStore: DecompositionManifestStore,
  private val gitOperations: WorkflowGitOperations,
) {
  fun issueKeyForExistingSpec(
    intake: String,
    repoRoot: Path,
  ): String? = referencedSpecPath(intake, repoRoot)?.let { GoalIntake.parse(it.toString()).issueKey }

  internal fun missingNewWorkInput(
    intake: GoalIntake,
    repoRoot: Path,
  ): GoalIntakeMissingInput? =
    if (existingSpecPath(intake.requirements, intake.issueKey, repoRoot) == null) {
      newWorkGap(intake)
    } else {
      null
    }

  fun prepare(request: GoalRunnerRunRequest): GoalRunnerManifestState? {
    manifestStore.loadByIssueKey(request.issueKey, request.repoRoot)?.let { return it }
    val suppliedIntake = request.intake?.takeIf(String::isNotBlank) ?: return null
    val specPath = existingSpecPath(suppliedIntake, request.issueKey, request.repoRoot)
    val intake = specPath?.let(fileStore::readText) ?: suppliedIntake
    val featureName =
      specPath?.parent?.fileName?.toString()?.let { issueAndFeature(it).second }
        ?: newWorkFeatureName(GoalIntake.parse(suppliedIntake))
    val baseBranch =
      if (specPath == null) {
        "main"
      } else {
        val branch = gitOperations.currentBranch(request.repoRoot)
        if (!branch.ok) invalidIntake("base_branch", branch.error)
        branch.value.ifBlank { "main" }
      }
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
        featureName = featureName,
        baseBranch = baseBranch,
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
      existingParentSpecPath = specPath,
    )
    return manifestStore.loadByIssueKey(request.issueKey, request.repoRoot)
  }

  private fun newWorkFeatureName(intake: GoalIntake): String {
    if (newWorkGap(intake) == GoalIntakeMissingInput.REQUIREMENTS) {
      invalidIntake("requirements", "supply the requirements after the tracker issue key or link.")
    }
    return intake.featureName
      ?: invalidIntake("feature_name", "supply a short description after the tracker issue key.")
  }

  private fun newWorkGap(intake: GoalIntake): GoalIntakeMissingInput? =
    when {
      !intake.hasRequirements -> GoalIntakeMissingInput.REQUIREMENTS
      intake.featureName == null -> GoalIntakeMissingInput.DESCRIPTION
      else -> null
    }

  private fun existingSpecPath(
    intake: String,
    issueKey: String,
    repoRoot: Path,
  ): Path? {
    referencedSpecPath(intake, repoRoot)?.let { return it }
    val firstToken = intake.trim().substringBefore(' ').substringBefore('\n')
    if (firstToken.endsWith(".md") || firstToken.contains(".feature-specs/")) {
      invalidIntake("parent_spec", "the supplied spec path does not contain a readable spec.md.")
    }
    return when (
      val resolved = specPathResolver.resolve(FeatureSpecPathResolveInput(issueKey, null, repoRoot))
    ) {
      is FeatureSpecPathResolveResult.Explicit -> Path.of(resolved.specPath)
      is FeatureSpecPathResolveResult.SingleMatch -> Path.of(resolved.specPath)
      is FeatureSpecPathResolveResult.NoMatch -> null
      is FeatureSpecPathResolveResult.Ambiguous ->
        invalidIntake("parent_spec", "multiple specs match $issueKey; supply the spec path.")
    }
  }

  private fun referencedSpecPath(
    intake: String,
    repoRoot: Path,
  ): Path? {
    val reference = intake.trim()
    val firstToken = reference.substringBefore(' ').substringBefore('\n')
    return listOf(reference, firstToken).distinct().flatMap { token ->
      val path = repoRoot.resolve(token).toAbsolutePath().normalize()
      listOf(path, path.resolve("spec.md"), repoRoot.resolve(".feature-specs").resolve(token).resolve("spec.md"))
    }.firstOrNull(fileStore::isRegularFile)
  }

  private fun invalidIntake(
    field: String,
    reason: String,
  ): Nothing = throw InvalidFeatureSpecPreparationRequestError(fieldPath = field, reason = reason)

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
        "block with the returned reason before implementation; never infer or substitute requirements."
  }
}
