package skillbill.engine.apprun

import me.tatarka.inject.annotations.Inject
import skillbill.engine.featuretask.validation.routedConcretePacks
import skillbill.error.core.SkillBillRuntimeException
import skillbill.error.core.rethrowUnless
import skillbill.error.shellcontent.isShellContentContractFailure
import skillbill.ports.scaffold.install.InstalledPlatformPackCatalogPort
import skillbill.ports.workflow.gitops.WorkflowGitOperations
import skillbill.ports.workflow.gitops.model.WorkflowGitNameListResult
import skillbill.ports.workflow.gitops.model.WorkflowGitOperationResult
import skillbill.scaffold.model.PlatformManifest
import skillbill.scaffold.model.QaTargetKind
import java.nio.file.Path

public sealed interface AppRunCapabilityResolution {
  public data class Declared(val recipes: List<AppRunRecipe>) : AppRunCapabilityResolution

  public data class Absent(val routedPackSlug: String?) : AppRunCapabilityResolution

  public data class Incompatible(val reason: String) : AppRunCapabilityResolution
}

public data class AppRunRecipe(val packSlug: String, val platforms: Set<QaTargetKind>, val scriptPath: Path)

@Inject
public class AppRunCapabilityResolver(
  private val gitOperations: WorkflowGitOperations,
  private val installedCatalog: InstalledPlatformPackCatalogPort,
) {
  public fun liveRoutingInput(
    repoRoot: Path,
    checkout: Path,
  ): AppRunGoalRoutingInputResult =
    when (val identity = gitOperations.indexTreeIdentity(checkout)) {
      is WorkflowGitOperationResult.Failed ->
        AppRunGoalRoutingInputResult.Failed("Could not read the live goal index tree: ${identity.error}")
      is WorkflowGitOperationResult.Ok ->
        AppRunGoalRoutingInputResult.Ready(
          AppRunGoalRoutingInput.Live(repoRoot, checkout, identity.value),
        )
    }

  public fun completedRoutingInput(
    repoRoot: Path,
    branch: String,
  ): AppRunGoalRoutingInputResult =
    when (val resolved = gitOperations.resolveBranchCommit(repoRoot, branch)) {
      is WorkflowGitOperationResult.Failed ->
        AppRunGoalRoutingInputResult.Failed("Could not resolve goal branch '$branch': ${resolved.error}")
      is WorkflowGitOperationResult.Ok ->
        AppRunGoalRoutingInputResult.Ready(AppRunGoalRoutingInput.Completed(repoRoot, resolved.value))
    }

  public fun resolve(input: AppRunGoalRoutingInput): AppRunCapabilityResolution =
    when (val paths = input.trackedPaths()) {
      is WorkflowGitNameListResult.Failed ->
        AppRunCapabilityResolution.Incompatible(
          "Could not read goal tracked paths: ${paths.error}",
        )
      is WorkflowGitNameListResult.Listed -> resolve(paths.names)
    }

  public fun platforms(input: AppRunGoalRoutingInput): List<String> =
    when (val resolution = resolve(input)) {
      is AppRunCapabilityResolution.Declared ->
        resolution.recipes.flatMap { it.platforms }.map(QaTargetKind::wireValue).distinct().sorted()
      is AppRunCapabilityResolution.Absent, is AppRunCapabilityResolution.Incompatible -> emptyList()
    }

  private fun resolve(paths: List<String>): AppRunCapabilityResolution {
    val manifests =
      try {
        installedCatalog.manifests()
      } catch (failure: SkillBillRuntimeException) {
        failure.rethrowUnless(failure.isShellContentContractFailure())
        return AppRunCapabilityResolution.Incompatible(
          "Installed platform pack discovery failed: ${failure.message ?: failure.javaClass.simpleName}.",
        )
      }
    val routed = routedConcretePacks(manifests, paths)
    val recipes =
      routed.sortedBy(PlatformManifest::slug).mapNotNull { pack ->
        pack.appRun?.let { AppRunRecipe(pack.slug, it.platforms, Path.of(pack.packRoot.resolve(it.script).value)) }
      }
    return if (recipes.isEmpty()) {
      AppRunCapabilityResolution.Absent(routed.singleOrNull()?.slug)
    } else {
      AppRunCapabilityResolution.Declared(recipes)
    }
  }

  private fun AppRunGoalRoutingInput.trackedPaths(): WorkflowGitNameListResult =
    when (this) {
      is AppRunGoalRoutingInput.Live -> gitOperations.trackedPaths(checkout)
      is AppRunGoalRoutingInput.Completed -> gitOperations.trackedPathsAtCommit(repoRoot, commitSha)
    }
}
