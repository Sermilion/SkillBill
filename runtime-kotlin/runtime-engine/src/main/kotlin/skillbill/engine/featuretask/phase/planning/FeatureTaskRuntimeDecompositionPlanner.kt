package skillbill.engine.featuretask.phase.planning

import me.tatarka.inject.annotations.Inject
import skillbill.application.decomposition.DECOMPOSITION_MANIFEST_FILENAME
import skillbill.application.decomposition.repoRelativePath
import skillbill.application.decomposition.resolvedParentSpecPath
import skillbill.contracts.projectstore.LegacyProjectStatePaths
import skillbill.contracts.projectstore.ProjectStoreLayout
import skillbill.engine.featuretask.prepare.FeatureSpecPreparationRuntime
import skillbill.engine.featuretask.prepare.FeatureSpecPreparationWriter
import skillbill.error.core.InvalidFeatureSpecPreparationRequestError
import skillbill.featurespec.model.FeatureSpecPreparationIntake
import skillbill.featurespec.model.FeatureSpecWriteResult
import skillbill.ports.featurespec.FeatureSpecPathResolverPort
import skillbill.ports.featurespec.model.FeatureSpecPathResolveInput
import skillbill.ports.featurespec.model.FeatureSpecPathResolveResult
import skillbill.ports.projectstore.ProjectStorePort
import skillbill.ports.projectstore.model.SpecPathLocation
import skillbill.ports.projectstore.model.classifySpecPath
import skillbill.ports.workflow.decomposition.DecompositionManifestStore
import skillbill.workflow.decomposition.model.DecompositionSubtask
import skillbill.workflow.taskruntime.model.handoff.task.FeatureTaskRuntimeRunInvariants
import java.nio.file.Path
import java.time.Instant

@Inject
class FeatureTaskRuntimeDecompositionPlanner(
  private val preparationRuntime: FeatureSpecPreparationRuntime,
  private val preparationWriter: FeatureSpecPreparationWriter,
  private val specPathResolver: FeatureSpecPathResolverPort,
  private val projectStore: ProjectStorePort,
  private val fileStore: DecompositionManifestStore,
) {
  fun bundlePath(
    repoRoot: Path,
    value: String,
  ): Path = resolvedParentSpecPath(repoRoot, Path.of(value), projectStore.resolve(repoRoot))

  fun bundleLabel(
    repoRoot: Path,
    path: Path,
  ): String = repoRelativePath(repoRoot, projectStore.resolve(repoRoot), path)

  fun existingParentSpec(
    repoRoot: Path,
    issueKey: String,
  ): Path? =
    when (val resolved = specPathResolver.resolve(FeatureSpecPathResolveInput(issueKey, null, repoRoot))) {
      is FeatureSpecPathResolveResult.SingleMatch -> Path.of(resolved.specPath)
      is FeatureSpecPathResolveResult.Ambiguous -> resolved.matches.first()
      is FeatureSpecPathResolveResult.Explicit,
      is FeatureSpecPathResolveResult.NoMatch,
      -> null
    }

  fun verifyAuthoredBundle(
    repoRoot: Path,
    issueKey: String,
    runInvariants: FeatureTaskRuntimeRunInvariants,
  ): FeatureSpecWriteResult {
    val resolved = specPathResolver.resolve(FeatureSpecPathResolveInput(issueKey, null, repoRoot))
    val parentSpecPath =
      (resolved as? FeatureSpecPathResolveResult.SingleMatch)?.specPath?.let(Path::of)
        ?: throw InvalidFeatureSpecPreparationRequestError(
          fieldPath = "parent_spec",
          reason =
            "plan must author exactly one $issueKey-<slug>/spec.md bundle under " +
              "${projectStore.resolve(repoRoot).specsRoot} (`skill-bill project path` prints the project store).",
        )
    preparationRuntime.prepareForFeatureSpec(
      FeatureSpecPreparationIntake(
        issueKey = issueKey,
        intendedOutcome = AUTHORED_BUNDLE_REASON,
        acceptanceCriteria = runInvariants.acceptanceCriteria,
        constraints = runInvariants.mandatesAndOverrides.ifEmpty { listOf("Runtime decompose planning stop.") },
        nonGoals = emptyList(),
      ),
    )
    return preparationWriter.verifyAuthored(repoRoot, parentSpecPath)
  }

  fun authoredSubtasks(parentSpecPath: Path): List<DecompositionSubtask> =
    preparationWriter.authoredSubtasks(parentSpecPath)

  fun planBundleTarget(
    repoRoot: Path,
    issueKey: String,
    workflowId: String,
    governedSpecPath: String?,
    providerHonorsAdditionalDirectories: Boolean,
  ): PlanBundleTarget? {
    val checkout = repoRoot.toAbsolutePath().normalize()
    val governedBundle = governedSpecPath?.let { bundlePath(checkout, it) }
    val bundle =
      (governedBundle ?: existingParentSpec(checkout, issueKey))?.let(checkout::resolve)?.normalize()?.parent
        ?: return null
    val store = projectStore.resolve(checkout)
    return when {
      store.classifySpecPath(bundle, checkout) is SpecPathLocation.Legacy -> PlanBundleTarget.Legacy(bundle)
      providerHonorsAdditionalDirectories -> PlanBundleTarget.Store(bundle)
      else -> {
        val stagingRoot = planStagingRoot(checkout).resolve(workflowId)
        PlanBundleTarget.Staged(
          stagingRoot,
          stagingRoot.resolve(ProjectStoreLayout.SPECS_DIRECTORY).resolve(bundle.fileName),
          bundle,
        )
      }
    }
  }

  fun specsRoot(repoRoot: Path): Path = projectStore.resolve(repoRoot).specsRoot

  fun planStagingRoot(checkout: Path): Path =
    checkout.resolve(LegacyProjectStatePaths.RUNTIME_STATE_DIRECTORY).resolve(LegacyProjectStatePaths.STAGING_DIRECTORY)

  fun bundleTree(directory: Path): List<Path> = fileStore.listTree(directory)

  fun writeBundleFile(
    target: Path,
    content: String,
  ) = fileStore.writeTextAtomically(target, content)

  fun deleteBundlePath(target: Path) = fileStore.deleteIfExists(target)

  fun isBundleFile(path: Path): Boolean = fileStore.isRegularFile(path)

  fun bundleChildDirectories(directory: Path): List<Path> = fileStore.listDirectChildDirectories(directory)

  fun bundleLastModified(path: Path): Instant = fileStore.lastModified(path)

  fun specText(specPath: Path): String = fileStore.readText(specPath)

  fun existingManifest(
    repoRoot: Path,
    issueKey: String,
  ): Path? =
    existingParentSpec(repoRoot, issueKey)
      ?.resolveSibling(DECOMPOSITION_MANIFEST_FILENAME)
      ?.takeIf(fileStore::isRegularFile)

  companion object {
    const val AUTHORED_BUNDLE_REASON = "Plan authored a governed spec bundle."
  }
}
