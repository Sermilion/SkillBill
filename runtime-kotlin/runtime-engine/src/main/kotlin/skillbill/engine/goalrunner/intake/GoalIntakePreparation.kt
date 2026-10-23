package skillbill.engine.goalrunner.intake

import me.tatarka.inject.annotations.Inject
import skillbill.contracts.issuekey.issueAndFeature
import skillbill.engine.featuretask.prepare.FeatureSpecPreparationWriter
import skillbill.engine.goalrunner.manifest.GoalRunnerManifestStore
import skillbill.engine.goalrunner.model.GoalIntakeAdmission
import skillbill.engine.goalrunner.model.GoalIntakeMissingInput
import skillbill.engine.goalrunner.model.GoalRunnerManifestState
import skillbill.engine.goalrunner.model.GoalRunnerRunRequest
import skillbill.error.core.InvalidFeatureSpecPreparationRequestError
import skillbill.featurespec.model.FeatureSpecPreparationDecision
import skillbill.featurespec.model.FeatureSpecPreparationMode
import skillbill.ports.featurespec.FeatureSpecPathResolverPort
import skillbill.ports.featurespec.model.FeatureSpecPathResolveInput
import skillbill.ports.featurespec.model.FeatureSpecPathResolveResult
import skillbill.ports.projectstore.ProjectStorePort
import skillbill.ports.projectstore.model.ProjectStoreLocation
import skillbill.ports.projectstore.model.classifySpecPath
import skillbill.ports.projectstore.model.specBundleRoots
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
) {
  fun issueKeyForExistingSpec(
    intake: String,
    repoRoot: Path,
  ): String? = referencedSpecPath(intake, repoRoot)?.let { GoalIntake.parse(it.toString()).issueKey }

  fun archivedSpec(
    intake: String,
    repoRoot: Path,
  ): GoalIntakeAdmission.ArchivedSpec? {
    val active = activeSpecPath(intake, repoRoot) ?: return null
    val root = repoRoot.toAbsolutePath().normalize()
    val store = projectStore.resolve(repoRoot)
    val activeRoot =
      store.specBundleRoots(root).map { it.toAbsolutePath().normalize() }
        .firstOrNull { active.startsWith(it) && !active.startsWith(it.resolve(ARCHIVE)) }
        ?: return null
    val archived = activeRoot.resolve(ARCHIVE).resolve(activeRoot.relativize(active))
    if (!fileStore.isRegularFile(archived)) return null
    return GoalIntakeAdmission.ArchivedSpec(
      issueKey = GoalIntake.parse(active.toString()).issueKey,
      activeSpecPath = store.specLabel(active, root),
      archivedSpecPath = store.specLabel(archived, root),
    )
  }

  private fun ProjectStoreLocation.specLabel(
    path: Path,
    checkoutRoot: Path,
  ): String = classifySpecPath(path, checkoutRoot)?.relative ?: checkoutRoot.relativize(path).joinToString("/")

  private fun activeSpecPath(
    intake: String,
    repoRoot: Path,
  ): Path? {
    referencedSpecPath(intake, repoRoot)?.let { return it.toAbsolutePath().normalize() }
    val issueKey = GoalIntake.parseOrNull(intake)?.issueKey ?: return null
    return when (
      val resolved = specPathResolver.resolve(FeatureSpecPathResolveInput(issueKey, null, repoRoot))
    ) {
      is FeatureSpecPathResolveResult.Explicit -> repoRoot.resolve(resolved.specPath).toAbsolutePath().normalize()
      is FeatureSpecPathResolveResult.SingleMatch -> repoRoot.resolve(resolved.specPath).toAbsolutePath().normalize()
      is FeatureSpecPathResolveResult.NoMatch, is FeatureSpecPathResolveResult.Ambiguous -> null
    }
  }

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

  fun resolvePlanSpec(
    issueKey: String,
    intake: String?,
    repoRoot: Path,
  ): PlanSpecSeed {
    val supplied = intake?.takeIf(String::isNotBlank) ?: issueKey
    val existing = existingSpecPath(supplied, issueKey, repoRoot)
    if (existing != null) {
      val kept = keepsOperatorSpec(existing)
      return PlanSpecSeed(
        existing,
        if (kept) FeatureTaskRuntimePlanSpecOrigin.OPERATOR else FeatureTaskRuntimePlanSpecOrigin.SEEDED,
      )
    }
    val planned = specWriter.plannedParentSpecPath(repoRoot, issueKey, newWorkFeatureName(GoalIntake.parse(supplied)))
    return PlanSpecSeed(planned, FeatureTaskRuntimePlanSpecOrigin.SEEDED)
  }

  fun keepsOperatorSpec(specPath: Path): Boolean =
    fileStore.isRegularFile(specPath) && hasAcceptanceList(fileStore.readText(specPath))

  fun intakePlanSpec(
    issueKey: String,
    intake: String?,
    repoRoot: Path,
    resolution: PlanSpecSeed,
  ): PlanSpecSeed {
    if (resolution.specOrigin == FeatureTaskRuntimePlanSpecOrigin.OPERATOR) return resolution
    val supplied = intake?.takeIf(String::isNotBlank) ?: issueKey
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
    const val ARCHIVE = "done"
    val ACCEPTANCE_HEADING = Regex("(?i)#{1,6}\\s+acceptance criteria\\s*")
    val ITEM = Regex("\\s*(?:[-*]|[0-9]+[.)])\\s+(?:\\[[ xX]\\]\\s+)?(.+)")
    const val VALIDATION = "Run the repository's required checks and verify every supplied acceptance criterion."
    const val TRACKER_RESOLUTION =
      "Supplied requirements are authoritative and need no tracker lookup. Locally allocated issue keys " +
        "do not require a tracker connection. Only an explicit unresolved tracker reference without " +
        "requirements needs lookup through its connected tracker before planning. Use the returned " +
        "requirements, not the URL title. If that lookup fails, block with the returned reason before " +
        "implementation; never infer or substitute requirements."
  }
}
