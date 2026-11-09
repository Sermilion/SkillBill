package skillbill.engine.specbrowser

import me.tatarka.inject.annotations.Inject
import skillbill.application.decomposition.DECOMPOSITION_MANIFEST_FILENAME
import skillbill.application.runtime.RuntimeSingleton
import skillbill.engine.goal.GoalIssueStartedReader
import skillbill.engine.specbrowser.model.SpecBrowserCounts
import skillbill.engine.specbrowser.model.SpecBrowserProject
import skillbill.engine.specbrowser.model.SpecBrowserSnapshot
import skillbill.engine.specbrowser.model.SpecBrowserStatus
import skillbill.engine.specbrowser.model.SpecBundleControl
import skillbill.engine.specbrowser.model.SpecBundleDeletion
import skillbill.engine.work.IdeStatusService
import skillbill.engine.work.control.WorkRunActions
import skillbill.ports.db.DatabaseSessionFactory
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.ports.projectstore.ProjectCatalogPort
import skillbill.ports.projectstore.ProjectStorePort
import skillbill.ports.projectstore.model.ProjectCatalogEntry
import skillbill.ports.repository.RepositoryEnclosingRootPort
import skillbill.ports.workflow.decomposition.DecompositionManifestStore
import skillbill.ports.workflow.decomposition.DecompositionManifestValidator
import skillbill.ports.workflow.goalstate.GoalRuntimeStateFileStore
import skillbill.ports.workflow.goalstate.model.GoalRuntimeDirectoryDeletion
import java.nio.file.Path

@RuntimeSingleton
@Inject
public class SpecBrowserService(
  private val catalog: ProjectCatalogPort,
  private val projectStore: ProjectStorePort,
  private val manifests: DecompositionManifestStore,
  validator: DecompositionManifestValidator,
  private val database: DatabaseSessionFactory,
  status: IdeStatusService,
  runs: WorkRunActions,
  private val goals: GoalIssueStartedReader,
  private val repositories: RepositoryEnclosingRootPort,
  private val files: GoalRuntimeStateFileStore,
  private val diagnostics: RuntimeDiagnostics,
) {
  private val manifestCache = SpecManifestCache(manifests, validator)
  private val treeReader = SpecTreeReader(manifests, manifestCache)
  private val liveGoals = SpecLiveGoalReader(database, status, runs, repositories, diagnostics)

  public fun projects(): List<SpecBrowserProject> = catalog.list().filter { it.hasSpecs }.map { project(it, null) }

  public fun projectFor(repoRoot: Path): SpecBrowserProject {
    require(files.directoryExists(repoRoot)) { "Path '$repoRoot' is not an available directory." }
    val checkout = repositories.enclosingRepositoryRoot(repoRoot)
    require(files.realPath(checkout.resolve(".git")) != null) { "Path '$repoRoot' is not inside a Git checkout." }
    val location = projectStore.resolve(checkout)
    val name = displayName(checkout, location.projectId)
    val entry = catalog.list().firstOrNull { it.projectId == location.projectId }
    return entry?.let { project(it, checkout) }
      ?: SpecBrowserProject(location.projectId, name, location.specsRoot, checkout, listOf(checkout))
  }

  public fun snapshot(
    projectId: String,
    preferredRepoRoot: Path?,
  ): SpecBrowserSnapshot {
    val project = locate(projectId, preferredRepoRoot)
    val specsRoot = project?.specsRoot ?: return missingProjectSnapshot(projectId)
    val read = treeReader.read(specsRoot)
    val live = liveGoals.overrides(checkoutRoots(project), liveCandidates(read.roots)).orEmpty()
    val tree = resolveSpecTree(read.roots, live)
    return SpecBrowserSnapshot(project, tree.roots, tree.counts, tree.anyInProgress, read.failure)
  }

  public fun control(
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
        else -> availableFacts(issueKey, project, treeStatus)
      }
    return specBundleControl(facts)
  }

  public fun deleteBundle(
    projectId: String,
    bundlePath: String,
    preferredRepoRoot: Path?,
  ): SpecBundleDeletion {
    val project = locate(projectId, preferredRepoRoot)
    val specsRoot =
      project?.specsRoot?.toAbsolutePath()?.normalize()
        ?: return SpecBundleDeletion.Refused(NO_SPEC_FOLDER_REASON)
    val directory = specsRoot.resolve(bundlePath).normalize()
    if (!directory.startsWith(specsRoot) || directory == specsRoot) {
      return SpecBundleDeletion.Refused(OUTSIDE_SPEC_FOLDER_REASON)
    }
    val realRoot = files.realPath(specsRoot)
    val realDirectory = files.realPath(directory)
    if (realRoot == null || realDirectory == null) return SpecBundleDeletion.Absent
    val refusal =
      when {
        realDirectory != realRoot.resolve(specsRoot.relativize(directory)) -> LINKED_BUNDLE_REASON
        !isBundleDirectory(realDirectory) -> NOT_A_BUNDLE_REASON
        goalHoldsBundle(checkoutRoots(project), realDirectory) -> LIVE_BUNDLE_REASON
        else -> null
      }
    return refusal?.let(SpecBundleDeletion::Refused) ?: deleteDirectory(realDirectory)
  }

  private fun deleteDirectory(directory: Path): SpecBundleDeletion =
    when (val deletion = files.deleteDirectoryTree(directory)) {
      GoalRuntimeDirectoryDeletion.Deleted -> SpecBundleDeletion.Deleted(directory)
      GoalRuntimeDirectoryDeletion.Absent -> SpecBundleDeletion.Absent
      is GoalRuntimeDirectoryDeletion.Failed -> SpecBundleDeletion.Failed(deletion.reason)
    }

  private fun goalHoldsBundle(
    checkoutRoots: List<Path>,
    directory: Path,
  ): Boolean {
    val manifest = manifestCache.read(directory.resolve(DECOMPOSITION_MANIFEST_FILENAME))
    val issueKey = rawSpecBundle(directory.fileName.toString(), manifest).issueKey ?: return false
    return liveGoals.goals(checkoutRoots, issueKey).any(::holdsRunner)
  }

  private fun isBundleDirectory(directory: Path): Boolean =
    manifests.isRegularFile(directory.resolve(DECOMPOSITION_MANIFEST_FILENAME)) ||
      manifests.isRegularFile(directory.resolve(SPEC_FILENAME))

  private fun availableFacts(
    issueKey: String,
    project: SpecBrowserProject,
    treeStatus: SpecBrowserStatus,
  ): SpecBundleControlFacts {
    val roots = checkoutRoots(project)
    val everStarted = roots.any { goals.hasStarted(issueKey, it) }
    return SpecBundleControlFacts(
      issueKey = issueKey,
      unavailableReason = null,
      everStarted = everStarted,
      treeStatus = treeStatus,
      live = liveGoals.controlFacts(roots, issueKey),
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
      SpecBrowserProject(
        projectId,
        displayName(preferredRepoRoot, projectId),
        it.specsRoot,
        preferredRepoRoot,
        listOf(preferredRepoRoot),
      )
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
    return SpecBrowserProject(entry.projectId, name, entry.specsRoot, repoRoot, entry.checkoutRoots.distinct())
  }

  private fun displayName(
    repoRoot: Path?,
    projectId: String,
  ): String = repoRoot?.fileName?.toString() ?: projectId

  private fun checkoutRoots(project: SpecBrowserProject): List<Path> =
    (listOfNotNull(project.repoRoot) + project.checkoutRoots).distinct()

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
    const val SPEC_FILENAME = "spec.md"
    const val NO_SPEC_FOLDER_REASON = "This project has no spec folder."
    const val OUTSIDE_SPEC_FOLDER_REASON = "Only a spec folder inside the project's spec store can be deleted."
    const val LINKED_BUNDLE_REASON = "The spec folder is reached through a symbolic link, so it was not deleted."
    const val NOT_A_BUNDLE_REASON = "The folder holds no spec.md or decomposition manifest, so it is not a spec folder."
    const val LIVE_BUNDLE_REASON = "The goal for this spec is running. Pause or cancel it before deleting its files."
  }
}
