package skillbill.engine.specbrowser

import me.tatarka.inject.annotations.Inject
import skillbill.application.decomposition.DECOMPOSITION_MANIFEST_FILENAME
import skillbill.application.runtime.RuntimeSingleton
import skillbill.engine.goalrunner.manifest.GoalRunnerManifestStore
import skillbill.engine.specbrowser.model.SpecBrowserCounts
import skillbill.engine.specbrowser.model.SpecBrowserProject
import skillbill.engine.specbrowser.model.SpecBrowserSnapshot
import skillbill.engine.specbrowser.model.SpecBrowserStatus
import skillbill.engine.specbrowser.model.SpecBundleControl
import skillbill.engine.work.IdeStatusService
import skillbill.engine.work.control.WorkRunActions
import skillbill.ports.db.DatabaseSessionFactory
import skillbill.ports.projectstore.ProjectCatalogPort
import skillbill.ports.projectstore.ProjectStorePort
import skillbill.ports.projectstore.model.ProjectCatalogEntry
import skillbill.ports.repository.RepositoryEnclosingRootPort
import skillbill.ports.workflow.decomposition.DecompositionManifestStore
import skillbill.ports.workflow.decomposition.DecompositionManifestValidator
import java.nio.file.Path

@RuntimeSingleton
@Inject
class SpecBrowserService(
  private val catalog: ProjectCatalogPort,
  private val projectStore: ProjectStorePort,
  manifests: DecompositionManifestStore,
  validator: DecompositionManifestValidator,
  private val database: DatabaseSessionFactory,
  status: IdeStatusService,
  runs: WorkRunActions,
  private val goals: GoalRunnerManifestStore,
  repositoryEnclosingRootPort: RepositoryEnclosingRootPort,
) {
  private val manifestCache = SpecManifestCache(manifests, validator)
  private val treeReader = SpecTreeReader(manifests, manifestCache)
  private val liveGoals = SpecLiveGoalReader(database, status, runs, repositoryEnclosingRootPort)

  fun projects(): List<SpecBrowserProject> = catalog.list().filter { it.hasSpecs }.map { project(it, null) }

  fun projectFor(repoRoot: Path): SpecBrowserProject {
    val location = projectStore.resolve(repoRoot)
    val name = displayName(repoRoot, location.projectId)
    return SpecBrowserProject(location.projectId, name, location.specsRoot, repoRoot)
  }

  fun snapshot(
    projectId: String,
    preferredRepoRoot: Path?,
  ): SpecBrowserSnapshot {
    val project = locate(projectId, preferredRepoRoot)
    val specsRoot = project?.specsRoot ?: return missingProjectSnapshot(projectId)
    val read = treeReader.read(specsRoot)
    val live = project.repoRoot?.let { liveGoals.overrides(it, liveCandidates(read.roots)) }.orEmpty()
    val tree = resolveSpecTree(read.roots, live)
    return SpecBrowserSnapshot(project, tree.roots, tree.counts, tree.anyInProgress, read.failure)
  }

  fun control(
    projectId: String,
    bundlePath: String,
    preferredRepoRoot: Path?,
  ): SpecBundleControl {
    val project = locate(projectId, preferredRepoRoot)
    val specsRoot = project?.specsRoot?.normalize() ?: return noCheckoutControl()
    val directory = specsRoot.resolve(bundlePath).normalize()
    require(directory.startsWith(specsRoot) && directory != specsRoot) {
      "Bundle path '$bundlePath' is outside the spec folder."
    }
    val manifest = manifestCache.read(directory.resolve(DECOMPOSITION_MANIFEST_FILENAME))
    val bundle = rawSpecBundle(directory.fileName.toString(), manifest)
    val treeStatus = bundleStatus(null, manifest)
    val repoRoot = project.repoRoot
    val issueKey = bundle.issueKey
    val facts =
      when {
        issueKey == null -> unavailableFacts(null, NO_KEY_REASON, treeStatus)
        repoRoot == null -> unavailableFacts(issueKey, NO_CHECKOUT_REASON, treeStatus)
        !database.databaseExists() -> unavailableFacts(issueKey, NO_DATABASE_REASON, treeStatus)
        else -> availableFacts(issueKey, repoRoot, treeStatus)
      }
    return specBundleControl(facts)
  }

  private fun availableFacts(
    issueKey: String,
    repoRoot: Path,
    treeStatus: SpecBrowserStatus,
  ): SpecBundleControlFacts {
    val ownership = goals.discoverPurgeOwnership(issueKey, repoRoot)
    return SpecBundleControlFacts(
      issueKey = issueKey,
      unavailableReason = null,
      everStarted = ownership.parentWorkflowIds.isNotEmpty() || ownership.planWorkflowIds.isNotEmpty(),
      treeStatus = treeStatus,
      live = liveGoals.controlFacts(repoRoot, issueKey),
    )
  }

  private fun unavailableFacts(
    issueKey: String?,
    reason: String,
    treeStatus: SpecBrowserStatus,
  ): SpecBundleControlFacts =
    SpecBundleControlFacts(
      issueKey = issueKey,
      unavailableReason = reason,
      everStarted = false,
      treeStatus = treeStatus,
      live = null,
    )

  private fun noCheckoutControl(): SpecBundleControl =
    specBundleControl(unavailableFacts(null, NO_CHECKOUT_REASON, SpecBrowserStatus.PENDING))

  private fun locate(
    projectId: String,
    preferredRepoRoot: Path?,
  ): SpecBrowserProject? {
    val entry = catalog.list().firstOrNull { it.projectId == projectId }
    if (entry != null) return project(entry, preferredRepoRoot)
    if (preferredRepoRoot == null) return null
    val location = projectStore.resolve(preferredRepoRoot)
    return location.takeIf { it.projectId == projectId }?.let {
      SpecBrowserProject(projectId, displayName(preferredRepoRoot, projectId), it.specsRoot, preferredRepoRoot)
    }
  }

  private fun project(
    entry: ProjectCatalogEntry,
    preferredRepoRoot: Path?,
  ): SpecBrowserProject {
    val repoRoot =
      preferredRepoRoot?.takeIf { projectStore.resolve(it).projectId == entry.projectId }
        ?: entry.checkoutRoots.firstOrNull()
    val name = displayName(repoRoot ?: entry.lastCheckoutRoot, entry.projectId)
    return SpecBrowserProject(entry.projectId, name, entry.specsRoot, repoRoot)
  }

  private fun displayName(
    repoRoot: Path?,
    projectId: String,
  ): String = repoRoot?.fileName?.toString() ?: projectId

  private fun missingProjectSnapshot(projectId: String): SpecBrowserSnapshot =
    SpecBrowserSnapshot(
      project = SpecBrowserProject(projectId, projectId, null, null),
      roots = emptyList(),
      counts = SpecBrowserCounts(0, 0, 0, 0),
      anyInProgress = false,
      failure = "Project $projectId is no longer in the Skill Bill store.",
    )

  private companion object {
    const val NO_KEY_REASON = "This spec folder has no issue key."
    const val NO_CHECKOUT_REASON = "No checkout of this project exists on this machine."
    const val NO_DATABASE_REASON = "Skill Bill has no run database yet."
  }
}
