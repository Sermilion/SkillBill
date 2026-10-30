package skillbill.engine.goalrunner.intake

import me.tatarka.inject.annotations.Inject
import skillbill.contracts.issuekey.goalIntakeSpecBundleDirectory
import skillbill.contracts.issuekey.issueAndFeature
import skillbill.contracts.projectstore.LegacyProjectStatePaths
import skillbill.engine.featuretask.prepare.FeatureSpecPreparationWriter
import skillbill.engine.featuretask.slot.intake.LegacySpecIntake
import skillbill.engine.featuretask.slot.intake.LegacySpecRoute
import skillbill.engine.featuretask.slot.intake.PlanSpecIntakePreparation
import skillbill.engine.featuretask.slot.intake.PlanSpecSeed
import skillbill.engine.goalrunner.manifest.GoalRunnerManifestStore
import skillbill.engine.goalrunner.model.GoalIntakeMissingInput
import skillbill.engine.goalrunner.model.GoalRunnerManifestState
import skillbill.engine.goalrunner.model.GoalRunnerRunRequest
import skillbill.error.core.InvalidFeatureSpecPreparationRequestError
import skillbill.featurespec.model.FeatureSpecPreparationDecision
import skillbill.featurespec.model.FeatureSpecPreparationMode
import skillbill.ports.featurespec.FeatureSpecPathResolverPort
import skillbill.ports.featurespec.model.FeatureSpecOrigin
import skillbill.ports.featurespec.model.FeatureSpecPathResolveInput
import skillbill.ports.featurespec.model.FeatureSpecPathResolveResult
import skillbill.ports.projectstore.ProjectStorePort
import skillbill.ports.projectstore.model.resolveSpecPath
import skillbill.ports.workflow.decomposition.DecompositionManifestStore
import skillbill.review.spec.GovernedSpecSectionParser
import skillbill.review.spec.GovernedSpecSectionParser.ACCEPTANCE_CRITERIA_PREFIX
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimePlanSpecOrigin
import java.nio.file.Path

@Inject
class GoalIntakePreparation(
  private val manifestStore: GoalRunnerManifestStore,
  private val specWriter: FeatureSpecPreparationWriter,
  private val specPathResolver: FeatureSpecPathResolverPort,
  private val fileStore: DecompositionManifestStore,
  private val projectStore: ProjectStorePort,
  private val legacySpecIntake: LegacySpecIntake,
) : PlanSpecIntakePreparation {
  fun issueKeyForExistingSpec(
    intake: String,
    repoRoot: Path,
  ): String? = referencedSpecPath(intake, repoRoot)?.let { GoalIntake.parse(it.toString()).issueKey }

  fun issueKeyOf(intake: String): String = GoalIntake.parse(intake).issueKey

  internal fun missingNewWorkInput(
    intake: GoalIntake,
    repoRoot: Path,
  ): GoalIntakeMissingInput? =
    if (existingSpecPath(intake.requirements, intake.issueKey, repoRoot) == null) {
      newWorkGap(intake)
    } else {
      null
    }

  fun prepare(request: GoalRunnerRunRequest): GoalRunnerManifestState? =
    manifestStore.loadByIssueKey(request.issueKey, request.repoRoot)

  override fun resolvePlanSpec(
    issueKey: String,
    intake: String?,
    repoRoot: Path,
  ): PlanSpecSeed {
    val requested = intake?.takeIf(String::isNotBlank) ?: issueKey
    val route = legacyRoute(requested, issueKey, repoRoot)
    val supplied = (route as? LegacySpecRoute.Replan)?.intake ?: requested
    val existing = existingSpecPath(supplied, issueKey, repoRoot, storeOnly = route is LegacySpecRoute.Replan)
    if (existing != null) {
      val kept = keepsOperatorSpec(existing)
      return PlanSpecSeed(
        existing,
        if (kept) FeatureTaskRuntimePlanSpecOrigin.OPERATOR else FeatureTaskRuntimePlanSpecOrigin.SEEDED,
        supplied,
      )
    }
    val planned = specWriter.plannedParentSpecPath(repoRoot, issueKey, newWorkFeatureName(GoalIntake.parse(supplied)))
    return PlanSpecSeed(planned, FeatureTaskRuntimePlanSpecOrigin.SEEDED, supplied)
  }

  private fun legacyRoute(
    intake: String,
    issueKey: String,
    repoRoot: Path,
  ): LegacySpecRoute =
    referencedSpecPath(intake, repoRoot)?.let { legacySpecIntake.route(it, issueKey, repoRoot) }
      ?: if (namesSpecPath(intake)) {
        LegacySpecRoute.NotLegacy
      } else {
        legacySpecIntake.routeIssueKey(issueKey, repoRoot)
      }

  private fun namesSpecPath(intake: String): Boolean {
    val firstToken = intake.trim().substringBefore(' ').substringBefore('\n')
    return firstToken.endsWith(".md") || goalIntakeSpecBundleDirectory(firstToken) != null
  }

  override fun keepsOperatorSpec(specPath: Path): Boolean =
    fileStore.isRegularFile(specPath) && hasAcceptanceList(fileStore.readText(specPath))

  override fun intakePlanSpec(
    issueKey: String,
    intake: String?,
    repoRoot: Path,
    resolution: PlanSpecSeed,
  ): PlanSpecSeed {
    if (resolution.specOrigin == FeatureTaskRuntimePlanSpecOrigin.OPERATOR) return resolution
    val requested = intake?.takeIf(String::isNotBlank) ?: issueKey
    val supplied = resolution.suppliedIntake ?: requested
    val existing = resolution.specPath.takeIf(fileStore::isRegularFile)
    val existingText = existing?.let(fileStore::readText)
    val featureName =
      existing?.parent?.fileName?.toString()?.let { issueAndFeature(it).second }
        ?: newWorkFeatureName(GoalIntake.parse(supplied))
    val specPath =
      specWriter.writeParentSpecOnly(
        repoRoot,
        FeatureSpecPreparationDecision(
          issueKey,
          existingText?.let(::asProse) ?: supplied,
          acceptanceCriteria(existingText ?: supplied),
          listOf(TRACKER_RESOLUTION),
          emptyList(),
          FeatureSpecPreparationMode.DECOMPOSED,
        ),
        featureName,
        VALIDATION,
        existing,
      )
    return PlanSpecSeed(specPath, FeatureTaskRuntimePlanSpecOrigin.SEEDED)
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
    storeOnly: Boolean = false,
  ): Path? {
    if (!storeOnly) referencedSpecPath(intake, repoRoot)?.let { return it }
    if (namesSpecPath(intake)) {
      invalidIntake("parent_spec", "the supplied spec path does not contain a readable spec.md.")
    }
    return when (
      val resolved = specPathResolver.resolve(FeatureSpecPathResolveInput(issueKey, null, repoRoot))
    ) {
      is FeatureSpecPathResolveResult.Explicit -> Path.of(resolved.specPath)
      is FeatureSpecPathResolveResult.SingleMatch ->
        Path.of(resolved.specPath).takeUnless { storeOnly && resolved.origin == FeatureSpecOrigin.LEGACY }
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
    val store = projectStore.resolve(repoRoot)
    return listOf(reference, firstToken).distinct().flatMap { token ->
      val path = store.resolveSpecPath(token, repoRoot).toAbsolutePath().normalize()
      listOf(
        path,
        path.resolve(SPEC_FILE),
        store.specsRoot.resolve(token).resolve(SPEC_FILE),
        repoRoot.resolve(LegacyProjectStatePaths.FEATURE_SPECS_DIRECTORY).resolve(token).resolve(SPEC_FILE),
      )
    }.firstOrNull(fileStore::isRegularFile)
  }

  private fun invalidIntake(
    field: String,
    reason: String,
  ): Nothing = throw InvalidFeatureSpecPreparationRequestError(fieldPath = field, reason = reason)

  private fun hasAcceptanceList(specText: String): Boolean =
    GovernedSpecSectionParser.parseListSection(specText) { it.startsWith(ACCEPTANCE_CRITERIA_PREFIX) }.isNotEmpty()

  private fun asProse(specText: String): String =
    specText.lines().joinToString("\n") { line -> if (line.isBlank()) ">" else "> $line" }

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
    const val SPEC_FILE = "spec.md"
    val ACCEPTANCE_HEADING = Regex("(?i)#{1,6}\\s+acceptance criteria\\s*")
    val ITEM = Regex("\\s*(?:[-*]|[0-9]+[.)])\\s+(?:\\[[ xX]\\]\\s+)?(.+)")
    const val VALIDATION = "Run the repository's required checks and verify every supplied acceptance criterion."
    const val TRACKER_RESOLUTION =
      "Supplied requirements are authoritative and need no tracker lookup. The issue key is identity " +
        "only and does not require a tracker connection. Do not fetch tracker issues or comments, and " +
        "never block because a tracker issue, its comments, or a tracker connection is unavailable."
  }
}
