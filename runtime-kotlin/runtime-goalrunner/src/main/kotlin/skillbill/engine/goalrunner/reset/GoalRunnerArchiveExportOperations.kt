package skillbill.engine.goalrunner.reset

import me.tatarka.inject.annotations.Inject
import skillbill.application.decomposition.decompositionManifestPath
import skillbill.application.decomposition.findMatchingDecompositionManifests
import skillbill.application.decomposition.model.SpecBundleScope
import skillbill.application.decomposition.repoRelativePath
import skillbill.application.decomposition.resolvedParentSpecPath
import skillbill.contracts.JsonCodec
import skillbill.contracts.projectstore.LegacyProjectStatePaths
import skillbill.contracts.projectstore.ProjectStoreLayout
import skillbill.contracts.workflow.goal.GOAL_ARCHIVE_CONTRACT_VERSION
import skillbill.contracts.workflow.goal.GoalArchiveEnvelopeKeys
import skillbill.contracts.workflow.goal.GoalArchiveRecordKeys
import skillbill.contracts.workflow.goal.GoalPlanningPreparationPayloadKeys
import skillbill.contracts.workflow.goal.goalArchiveEnvelopeWireMap
import skillbill.engine.decomposition.encodeDecompositionManifestYaml
import skillbill.engine.goalplanning.toEnvelopeMap
import skillbill.engine.goalrunner.archive.toGoalArchiveFieldMap
import skillbill.engine.goalrunner.manifest.GoalRunnerManifestStore
import skillbill.engine.goalrunner.model.GoalRunnerExportPreflightResult
import skillbill.engine.goalrunner.model.GoalRunnerExportRefusalCategory
import skillbill.engine.goalrunner.model.GoalRunnerExportRequest
import skillbill.engine.goalrunner.model.GoalRunnerExportResult
import skillbill.engine.goalrunner.status.GoalRunnerStatusProjectionAssembler
import skillbill.error.core.SkillBillRuntimeException
import skillbill.error.core.catchingNonCancellation
import skillbill.error.goal.goalArchivePathTraversalError
import skillbill.goalrunner.model.ExecutionLiveness
import skillbill.ports.db.DatabaseSessionFactory
import skillbill.ports.db.model.DatabasePathProtectionResult
import skillbill.ports.db.model.FencedTransactionResult
import skillbill.ports.filesystem.model.DatabaseFileObservation
import skillbill.ports.goalrunner.GoalArchiveEnvelopeValidator
import skillbill.ports.goalrunner.GoalArchiveSnapshotPort
import skillbill.ports.goalrunner.model.GoalArchiveEnvelope
import skillbill.ports.goalrunner.model.GoalArchiveOwnedSnapshot
import skillbill.ports.goalrunner.model.GoalArchiveOwnedTables
import skillbill.ports.persistence.model.GoalPurgeTarget
import skillbill.ports.projectstore.ProjectStorePort
import skillbill.ports.projectstore.model.ProjectStoreLocation
import skillbill.ports.projectstore.model.SpecPathLocation
import skillbill.ports.projectstore.model.classifySpecPath
import skillbill.ports.projectstore.model.resolveSpecPath
import skillbill.ports.workflow.WorkflowArtifactWireEncoder
import skillbill.ports.workflow.decomposition.DecompositionManifestStore
import skillbill.ports.workflow.decomposition.DecompositionManifestValidator
import skillbill.ports.workflow.gitops.WorkflowGitOperations
import skillbill.ports.workflow.gitops.model.WorkflowGitOperationResult
import skillbill.workflow.decomposition.model.DecompositionManifest
import skillbill.workflow.model.FeatureTaskExecutionIdentityPolicy
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Clock
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.createDirectories
import kotlin.io.path.name

@Inject
public class GoalRunnerArchiveExportOperations(
  private val manifestStore: GoalRunnerManifestStore,
  private val gitOperations: WorkflowGitOperations,
  private val projectionAssembler: GoalRunnerStatusProjectionAssembler,
  private val manifestFileStore: DecompositionManifestStore,
  private val manifestValidator: DecompositionManifestValidator,
  private val database: DatabaseSessionFactory,
  private val projectStore: ProjectStorePort,
  private val clock: Clock,
  private val archiveSnapshotPort: GoalArchiveSnapshotPort,
  private val envelopeValidator: GoalArchiveEnvelopeValidator,
  private val workflowArtifactWireEncoder: WorkflowArtifactWireEncoder,
) {
  private val changedSummary = "Database file changed during export; no archive was written."

  public fun preflight(
    issueKey: String,
    repoRoot: Path,
    expectedObservation: DatabaseFileObservation? = null,
  ): GoalRunnerExportPreflightResult {
    val canonicalKey = FeatureTaskExecutionIdentityPolicy.canonicalIssueKey(issueKey)
    val protection =
      when (val result = database.acquireDatabasePathProtection()) {
        is DatabasePathProtectionResult.Acquired -> result.protection
        is DatabasePathProtectionResult.Unavailable ->
          return GoalRunnerExportPreflightResult.Refused(
            canonicalKey,
            "Database path protection is unavailable; export made no changes. ${result.reason}",
          )
      }
    return protection.use { preflightProtected(canonicalKey, repoRoot, expectedObservation) }
  }

  private fun preflightProtected(
    canonicalKey: String,
    repoRoot: Path,
    expectedObservation: DatabaseFileObservation?,
  ): GoalRunnerExportPreflightResult {
    val observation =
      expectedObservation ?: if (database.databaseExists()) database.read { it.databaseFileObservation } else null
    preflightObservationRefusal(canonicalKey, observation)?.let { return it }
    requireNotNull(observation)
    val plan =
      when (val admission = database.transactionFenced(observation) { discover(repoRoot, canonicalKey) }) {
        FencedTransactionResult.ChangedTarget ->
          return GoalRunnerExportPreflightResult.Refused(canonicalKey, changedSummary)
        is FencedTransactionResult.Committed -> admission.value
      }
    plan.refusal?.let { return GoalRunnerExportPreflightResult.Refused(canonicalKey, it.reason, it.category) }
    return when (val git = branchHeadAdmission(repoRoot, plan)) {
      is BranchHeadAdmission.Refused -> GoalRunnerExportPreflightResult.Refused(canonicalKey, git.reason, git.category)
      is BranchHeadAdmission.Ready ->
        GoalRunnerExportPreflightResult.Ready(canonicalKey, git.featureBranch, git.commitSha)
    }
  }

  private fun preflightObservationRefusal(
    issueKey: String,
    observation: DatabaseFileObservation?,
  ): GoalRunnerExportPreflightResult.Refused? {
    val selectedPath = database.resolveDbPath().toAbsolutePath().normalize()
    val reason =
      when {
        !database.databaseExists() -> "Selected database does not exist; export made no changes."
        observation == null -> "Database file identity is unavailable; export made no changes."
        observation.path.toAbsolutePath().normalize() != selectedPath ->
          "Database observation does not match the selected database path; export made no changes."
        else -> null
      }
    return reason?.let { GoalRunnerExportPreflightResult.Refused(issueKey, it) }
  }

  public fun export(
    request: GoalRunnerExportRequest,
    expectedObservation: DatabaseFileObservation? = null,
  ): GoalRunnerExportResult {
    val issueKey = FeatureTaskExecutionIdentityPolicy.canonicalIssueKey(request.issueKey)
    val outputPath = request.outputPath.toAbsolutePath().normalize()
    if (!outputPath.toString().endsWith(".zip")) {
      return GoalRunnerExportResult.Refused(
        issueKey,
        "Archive destination must end with lowercase .zip: $outputPath. Choose a path ending in .zip.",
      )
    }
    val repoRoot = request.repoRoot ?: error("repoRoot is required to export goal '${request.issueKey}'.")
    val protection =
      when (val result = database.acquireDatabasePathProtection()) {
        is DatabasePathProtectionResult.Acquired -> result.protection
        is DatabasePathProtectionResult.Unavailable ->
          return GoalRunnerExportResult.Refused(
            issueKey,
            "Database path protection is unavailable; export made no changes. ${result.reason}",
          )
      }
    return protection.use {
      exportProtected(repoRoot, issueKey, outputPath, expectedObservation)
    }
  }

  private fun exportProtected(
    repoRoot: Path,
    issueKey: String,
    outputPath: Path,
    expectedObservation: DatabaseFileObservation?,
  ): GoalRunnerExportResult {
    val observation =
      expectedObservation ?: if (database.databaseExists()) {
        database.read { it.databaseFileObservation }
      } else {
        null
      }
    observationRefusal(issueKey, observation)?.let { return it }
    requireNotNull(observation)
    return exportAdmitted(repoRoot, issueKey, outputPath, observation)
  }

  private fun exportAdmitted(
    repoRoot: Path,
    issueKey: String,
    outputPath: Path,
    observation: DatabaseFileObservation,
  ): GoalRunnerExportResult {
    val plan =
      when (val admission = database.transactionFenced(observation) { discover(repoRoot, issueKey) }) {
        FencedTransactionResult.ChangedTarget -> return GoalRunnerExportResult.Refused(issueKey, changedSummary)
        is FencedTransactionResult.Committed -> admission.value
      }
    plan.refusal?.let { return GoalRunnerExportResult.Refused(issueKey, it.reason, it.category) }
    val git =
      when (val admission = branchHeadAdmission(repoRoot, plan)) {
        is BranchHeadAdmission.Refused ->
          return GoalRunnerExportResult.Refused(issueKey, admission.reason, admission.category)
        is BranchHeadAdmission.Ready -> admission
      }
    return snapshotAndWrite(ReadyExport(repoRoot, issueKey, outputPath, observation, plan, git))
  }

  private fun snapshotAndWrite(ready: ReadyExport): GoalRunnerExportResult {
    val snapshot =
      when (
        val result = database.transactionFenced(ready.observation) { archiveSnapshotPort.snapshot(ready.plan.target) }
      ) {
        FencedTransactionResult.ChangedTarget -> return GoalRunnerExportResult.Refused(ready.issueKey, changedSummary)
        is FencedTransactionResult.Committed -> result.value
      }
    if (!isCurrent(ready.observation)) return GoalRunnerExportResult.Refused(ready.issueKey, changedSummary)
    val sourceManifest =
      ready.plan.sourceManifest ?: return GoalRunnerExportResult.Failed(ready.issueKey, "No decomposition manifest.")
    return writeArchive(ready, snapshot, sourceManifest)
  }

  private fun observationRefusal(
    issueKey: String,
    observation: DatabaseFileObservation?,
  ): GoalRunnerExportResult? {
    val selectedPath = database.resolveDbPath().toAbsolutePath().normalize()
    val reason =
      when {
        !database.databaseExists() -> "Selected database does not exist; export made no changes."
        observation == null -> "Database file identity is unavailable; export made no changes."
        observation.path.toAbsolutePath().normalize() != selectedPath ->
          "Database observation does not match the selected database path; export made no changes."
        else -> null
      }
    return reason?.let { GoalRunnerExportResult.Refused(issueKey, it) }
  }

  private fun writeArchive(
    ready: ReadyExport,
    snapshot: GoalArchiveOwnedSnapshot,
    sourceManifest: DecompositionManifest,
  ): GoalRunnerExportResult {
    val specEntries =
      when (val specs = readSpecBundleEntries(ready.repoRoot, sourceManifest, ready.plan.manifestPath)) {
        is SpecBundleRead.Missing ->
          return GoalRunnerExportResult.Failed(ready.issueKey, "Spec bundle file is missing at ${specs.path}.")
        is SpecBundleRead.UnsupportedPath ->
          return GoalRunnerExportResult.Refused(
            ready.issueKey,
            "Spec path '${specs.path}' is outside the supported project spec roots and cannot be archived.",
          )
        is SpecBundleRead.Collision ->
          return GoalRunnerExportResult.Refused(
            ready.issueKey,
            "Distinct spec files map to the same archive entry '${specs.entry}'; export refused.",
          )
        is SpecBundleRead.Ready -> specs.entries
      }
    val documents = archiveDocuments(ready.issueKey, ready.git, snapshot)
    val validationFailure =
      try {
        envelopeValidator.validate(documents.envelope)
        null
      } catch (error: SkillBillRuntimeException) {
        GoalRunnerExportResult.Failed(ready.issueKey, error.message ?: error.code.toString())
      }
    return validationFailure ?: persistArchiveZip(ready, specEntries, documents, snapshot)
  }

  private fun archiveDocuments(
    issueKey: String,
    git: BranchHeadAdmission.Ready,
    snapshot: GoalArchiveOwnedSnapshot,
  ): ArchiveDocuments =
    ArchiveDocuments(
      envelope =
        GoalArchiveEnvelope(
          contractVersion = GOAL_ARCHIVE_CONTRACT_VERSION,
          issueKey = issueKey,
          featureBranch = git.featureBranch,
          commitSha = git.commitSha,
          exportedAt = clock.instant().toString(),
        ),
      workflowsJson = snapshot.workflows.map { it.toGoalArchiveFieldMap(workflowArtifactWireEncoder) },
      planningJson =
        linkedMapOf(
          GoalArchiveRecordKeys.SHARED_PREPLANS to
            snapshot.sharedPreplans.map {
              it.toEnvelopeMap() + (GoalPlanningPreparationPayloadKeys.CREATED_AT to it.createdAt)
            },
          GoalArchiveRecordKeys.SUBTASK_PLANS to
            snapshot.subtaskPlans.map {
              it.toEnvelopeMap() + (GoalPlanningPreparationPayloadKeys.CREATED_AT to it.createdAt)
            },
          GoalArchiveRecordKeys.LEGACY_PREPARATIONS to snapshot.legacyPreparations.map { it.toEnvelopeMap() },
        ),
      ownedTablesJson = ownedTablesJson(snapshot.ownedTables),
    )

  private fun persistArchiveZip(
    ready: ReadyExport,
    specEntries: List<Pair<String, ByteArray>>,
    documents: ArchiveDocuments,
    snapshot: GoalArchiveOwnedSnapshot,
  ): GoalRunnerExportResult {
    val parentDir = ready.outputPath.parent ?: ready.outputPath
    parentDir.createDirectories()
    val tempFile = Files.createTempFile(parentDir, "${ready.outputPath.name}-", ".part")
    try {
      catchingNonCancellation {
        ZipOutputStream(Files.newOutputStream(tempFile)).use { zip ->
          writeJsonEntry(zip, GoalArchiveEnvelopeKeys.ARCHIVE_JSON, documents.envelope.wireMap())
          specEntries.forEach { (entryName, bytes) ->
            zip.putNextEntry(ZipEntry(entryName))
            zip.write(bytes)
            zip.closeEntry()
          }
          writeJsonEntry(zip, GoalArchiveEnvelopeKeys.WORKFLOWS_JSON, documents.workflowsJson)
          writeJsonEntry(zip, GoalArchiveEnvelopeKeys.PLANNING_JSON, documents.planningJson)
          writeJsonEntry(zip, GoalArchiveEnvelopeKeys.OWNED_TABLES_JSON, documents.ownedTablesJson)
        }
        Files.move(tempFile, ready.outputPath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
      }.getOrElse { error ->
        return GoalRunnerExportResult.Failed(ready.issueKey, archiveWriteFailureMessage(error))
      }
    } finally {
      Files.deleteIfExists(tempFile)
    }
    return GoalRunnerExportResult.Exported(
      issueKey = ready.issueKey,
      featureBranch = ready.git.featureBranch,
      commitSha = ready.git.commitSha,
      archivePath = ready.outputPath,
      fileCount = specEntries.size + 4,
      rowCounts = rowCounts(snapshot),
    )
  }

  private fun archiveWriteFailureMessage(error: Throwable): String =
    when (error) {
      is SkillBillRuntimeException -> error.message ?: error.code.toString()
      else -> error.message ?: error::class.simpleName.orEmpty()
    }

  private fun writeJsonEntry(
    zip: ZipOutputStream,
    entryName: String,
    payload: Any,
  ) {
    zip.putNextEntry(ZipEntry(entryName))
    zip.write(JsonCodec.valueToJsonString(payload).encodeToByteArray())
    zip.closeEntry()
  }

  private fun rowCounts(snapshot: GoalArchiveOwnedSnapshot): Map<String, Int> =
    buildMap {
      put("feature_task_workflows", snapshot.workflows.size)
      snapshot.ownedTables.orderedTables().forEach { (table, rows) -> put(table, rows.size) }
      put("goal_shared_preplans", snapshot.sharedPreplans.size)
      put("goal_subtask_plans", snapshot.subtaskPlans.size)
      put("goal_planning_preparations", snapshot.legacyPreparations.size)
    }

  private fun readSpecBundleEntries(
    repoRoot: Path,
    manifest: DecompositionManifest,
    manifestPath: Path?,
  ): SpecBundleRead {
    val store = projectStore.resolve(repoRoot)
    val checkoutRoot = repoRoot.toAbsolutePath().normalize()
    val resolvedManifestPath = (manifestPath ?: derivedManifestPath(repoRoot, manifest)).toAbsolutePath().normalize()
    val traceabilityPath = resolvedManifestPath.resolveSibling("traceability.md")
    val portableManifest = manifest.withPortableSpecPaths(repoRoot, store)
    val paths =
      linkedSetOf(
        resolvedManifestPath,
        resolvedParentSpecPath(repoRoot, Path.of(manifest.parentSpecPath), store),
      ).apply {
        addAll(manifest.subtasks.map { subtask -> resolvedParentSpecPath(repoRoot, Path.of(subtask.specPath), store) })
        if (manifestFileStore.isRegularFile(traceabilityPath)) add(traceabilityPath)
      }
    val entries = mutableListOf<Pair<String, ByteArray>>()
    val entryNames = mutableSetOf<String>()
    for (path in paths) {
      if (!manifestFileStore.isRegularFile(path)) {
        return SpecBundleRead.Missing(repoRelativePath(repoRoot, store, path))
      }
      val absolutePath = path.toAbsolutePath().normalize()
      val location = store.classifySpecPath(absolutePath, checkoutRoot)
      if (location == null) {
        return SpecBundleRead.UnsupportedPath(repoRelativePath(repoRoot, store, path))
      }
      val relative = location.archiveRelativePath()
      val entryName = archiveSpecEntryName(relative)
      if (!entryNames.add(entryName)) return SpecBundleRead.Collision(entryName)
      val content =
        if (path == resolvedManifestPath && portableManifest != manifest) {
          encodeDecompositionManifestYaml(
            portableManifest,
            manifestValidator,
            manifestFileStore,
          )
        } else {
          manifestFileStore.readText(path)
        }
      entries += entryName to content.encodeToByteArray()
    }
    return SpecBundleRead.Ready(entries)
  }

  private fun DecompositionManifest.withPortableSpecPaths(
    repoRoot: Path,
    store: ProjectStoreLocation,
  ): DecompositionManifest =
    copy(
      parentSpecPath = portableSpecPath(parentSpecPath, repoRoot, store),
      subtasks =
        subtasks.map { subtask ->
          subtask.copy(specPath = portableSpecPath(subtask.specPath, repoRoot, store))
        },
    )

  private fun portableSpecPath(
    specPath: String,
    repoRoot: Path,
    store: ProjectStoreLocation,
  ): String {
    val path = store.resolveSpecPath(specPath, repoRoot).toAbsolutePath().normalize()
    val location = store.classifySpecPath(path, repoRoot.toAbsolutePath().normalize()) ?: return specPath
    return ProjectStoreLayout.SPECS_PREFIX + location.archiveRelativePath()
  }

  private fun SpecPathLocation.archiveRelativePath(): String =
    when (this) {
      is SpecPathLocation.Store -> relative.removePrefix(ProjectStoreLayout.SPECS_PREFIX)
      is SpecPathLocation.Legacy ->
        relative.removePrefix(LegacyProjectStatePaths.FEATURE_SPECS_PREFIX)
    }

  private fun archiveSpecEntryName(storeRelativePath: String): String {
    val normalized = storeRelativePath.replace('\\', '/').trimStart('/')
    val entry = GoalArchiveEnvelopeKeys.SPECS_PREFIX + normalized
    val entryPath = Path.of(entry).normalize()
    val specsRoot = Path.of(GoalArchiveEnvelopeKeys.SPECS_PREFIX).normalize()
    if (!entryPath.startsWith(specsRoot) || entry.contains("..")) {
      throw goalArchivePathTraversalError(entry)
    }
    return entryPath.toString().replace('\\', '/')
  }

  private fun branchHeadAdmission(
    repoRoot: Path,
    plan: ArchivePlan,
  ): BranchHeadAdmission {
    val featureBranch =
      plan.sourceManifest?.featureBranch?.takeIf(String::isNotBlank)
        ?: return BranchHeadAdmission.Refused(
          "Goal '${plan.issueKey}' has no recorded feature branch; export refused.",
          GoalRunnerExportRefusalCategory.BRANCH,
        )
    return admittedGit(repoRoot, featureBranch)
  }

  private fun admittedGit(
    repoRoot: Path,
    featureBranch: String,
  ): BranchHeadAdmission {
    val current =
      gitValue(gitOperations.currentBranch(repoRoot))
        ?: return BranchHeadAdmission.Refused("Current branch is unavailable; export refused.")
    if (current != featureBranch) {
      return BranchHeadAdmission.Refused(
        "Current branch '$current' is not the recorded feature branch '$featureBranch'; export refused.",
        GoalRunnerExportRefusalCategory.BRANCH,
      )
    }
    val commitSha =
      gitValue(gitOperations.headCommitSha(repoRoot))
        ?: return BranchHeadAdmission.Refused("HEAD commit SHA is unavailable; export refused.")
    return BranchHeadAdmission.Ready(featureBranch, commitSha)
  }

  private fun gitValue(result: WorkflowGitOperationResult): String? =
    (result as? WorkflowGitOperationResult.Ok)?.value?.takeIf(String::isNotBlank)

  private fun discover(
    repoRoot: Path,
    issueKey: String,
  ): ArchivePlan {
    val ownership = manifestStore.discoverPurgeOwnership(issueKey, repoRoot)
    val diskCandidate =
      findMatchingDecompositionManifests(
        scope = SpecBundleScope(repoRoot, projectStore.resolve(repoRoot)),
        issueKey = issueKey,
        fileStore = manifestFileStore,
        validator = manifestValidator,
        recoverPending = false,
      ).firstOrNull()
    val childrenByParent = ownership.parentWorkflowIds.associateWith(manifestStore::listOwnedGoalChildWorkflowIds)
    val manifests = listOfNotNull(diskCandidate?.manifest) + ownership.manifests.map { it.manifest }
    val manifestRecordedIds =
      manifests.flatMapTo(linkedSetOf()) { manifest ->
        manifest.subtasks.mapNotNull { subtask -> subtask.workflowId?.takeIf(String::isNotBlank) }
      }
    val verifiedIds =
      manifestStore.verifyOwnedWorkflowIds(manifestRecordedIds, ownership.parentWorkflowIds, issueKey, repoRoot)
    val target =
      GoalPurgeTarget(
        parentWorkflowIds = ownership.parentWorkflowIds,
        workflowIds =
          ownership.parentWorkflowIds + childrenByParent.values.flatten() + verifiedIds + ownership.planWorkflowIds,
      )
    val sourceManifest = manifests.firstOrNull()
    val orphanIds = verifiedIds - childrenByParent.values.flatten().toSet()
    val refusal =
      childrenByParent.firstNotNullOfOrNull { (parentId, childIds) ->
        livenessRefusal(issueKey, parentId, childIds + orphanIds)
      }
        ?: planLivenessRefusal(issueKey, ownership.planWorkflowIds)
        ?: leftoverRefusal(issueKey, ownership.unclassifiedWorkflows, ownership.siblingWorktreeWorkflows)
    return ArchivePlan(
      issueKey = issueKey,
      target = target,
      sourceManifest = sourceManifest,
      manifestPath = diskCandidate?.path ?: sourceManifest?.let { derivedManifestPath(repoRoot, it) },
      refusal = refusal,
    )
  }

  private fun leftoverRefusal(
    issueKey: String,
    unclassified: List<String>,
    siblings: List<String>,
  ): ExportRefusal? {
    siblings.firstOrNull()?.let { sibling ->
      val exportHint = sibling.replace("sona goal purge", "sona goal export")
      return ExportRefusal("Goal '$issueKey' has sibling worktree workflows; export refused. $exportHint")
    }
    unclassified.firstOrNull()?.let { leftover ->
      return ExportRefusal("Goal '$issueKey' has undecodable owned workflows; export refused. $leftover")
    }
    return null
  }

  private fun derivedManifestPath(
    repoRoot: Path,
    manifest: DecompositionManifest,
  ): Path =
    decompositionManifestPath(
      repoRoot,
      projectStore.resolve(repoRoot),
      Path.of(manifest.parentSpecPath),
      manifest.subtasks.map { it.specPath },
    )

  private fun livenessRefusal(
    issueKey: String,
    parentWorkflowId: String,
    childWorkflowIds: List<String>,
  ): ExportRefusal? {
    val liveness = projectionAssembler.resolvePurgeBlockingLiveness(parentWorkflowId, childWorkflowIds)
    val reason =
      when (liveness) {
        ExecutionLiveness.IDLE, null -> null
        ExecutionLiveness.LIVE ->
          "Goal '$issueKey' is live; refuse export while a parent or child worker is active. " +
            "Use GoalPauseCommand or GoalStopCommand first."
        ExecutionLiveness.LIVE_EXPIRED ->
          "Goal '$issueKey' has a live-but-expired lease; refuse export until ownership is reconciled."
        ExecutionLiveness.UNKNOWN ->
          "Goal '$issueKey' has unknown execution liveness; refuse export until liveness is known."
        ExecutionLiveness.FOREIGN_HOST ->
          "Goal '$issueKey' has foreign-host ownership; refuse export until ownership is reconciled."
        ExecutionLiveness.PID_REUSED ->
          "Goal '$issueKey' has pid-reused ownership; refuse export until ownership is reconciled."
      }
    return reason?.let { ExportRefusal(it, GoalRunnerExportRefusalCategory.LIVENESS) }
  }

  private fun planLivenessRefusal(
    issueKey: String,
    planWorkflowIds: Collection<String>,
  ): ExportRefusal? {
    val liveness = projectionAssembler.resolveWorkerBlockingLiveness(planWorkflowIds)
    val reason =
      when (liveness) {
        ExecutionLiveness.IDLE, null -> null
        ExecutionLiveness.LIVE ->
          "Goal '$issueKey' has a live plan worker; refuse export while a plan workflow is active. " +
            "Use GoalPauseCommand or GoalStopCommand first."
        ExecutionLiveness.LIVE_EXPIRED ->
          "Goal '$issueKey' has a live-but-expired plan worker; refuse export until ownership is reconciled."
        ExecutionLiveness.UNKNOWN ->
          "Goal '$issueKey' has unknown plan worker liveness; refuse export until liveness is known."
        ExecutionLiveness.FOREIGN_HOST ->
          "Goal '$issueKey' has foreign-host plan ownership; refuse export until ownership is reconciled."
        ExecutionLiveness.PID_REUSED ->
          "Goal '$issueKey' has pid-reused plan ownership; refuse export until ownership is reconciled."
      }
    return reason?.let { ExportRefusal(it, GoalRunnerExportRefusalCategory.LIVENESS) }
  }

  private fun isCurrent(expected: DatabaseFileObservation): Boolean =
    when (database.transactionFenced(expected) { true }) {
      FencedTransactionResult.ChangedTarget -> false
      is FencedTransactionResult.Committed -> true
    }
}

private fun GoalArchiveEnvelope.wireMap(): Map<String, Any?> =
  goalArchiveEnvelopeWireMap(contractVersion, issueKey, featureBranch, commitSha, exportedAt)

private fun ownedTablesJson(tables: GoalArchiveOwnedTables): Map<String, List<Map<String, Any?>>> =
  tables.orderedTables().associate { (name, rows) ->
    name to rows.map { row -> row.orderedEntries().toMap() }
  }

private data class ReadyExport(
  val repoRoot: Path,
  val issueKey: String,
  val outputPath: Path,
  val observation: DatabaseFileObservation,
  val plan: ArchivePlan,
  val git: BranchHeadAdmission.Ready,
)

private sealed interface BranchHeadAdmission {
  data class Ready(
    val featureBranch: String,
    val commitSha: String,
  ) : BranchHeadAdmission

  data class Refused(
    val reason: String,
    val category: GoalRunnerExportRefusalCategory = GoalRunnerExportRefusalCategory.OTHER,
  ) : BranchHeadAdmission
}

private sealed interface SpecBundleRead {
  data class Ready(
    val entries: List<Pair<String, ByteArray>>,
  ) : SpecBundleRead

  data class Missing(
    val path: String,
  ) : SpecBundleRead

  data class UnsupportedPath(
    val path: String,
  ) : SpecBundleRead

  data class Collision(
    val entry: String,
  ) : SpecBundleRead
}

private data class ArchiveDocuments(
  val envelope: GoalArchiveEnvelope,
  val workflowsJson: List<Map<String, Any?>>,
  val planningJson: Map<String, Any?>,
  val ownedTablesJson: Map<String, List<Map<String, Any?>>>,
)

private data class ArchivePlan(
  val issueKey: String,
  val target: GoalPurgeTarget,
  val sourceManifest: DecompositionManifest?,
  val manifestPath: Path?,
  val refusal: ExportRefusal?,
)

private data class ExportRefusal(
  val reason: String,
  val category: GoalRunnerExportRefusalCategory = GoalRunnerExportRefusalCategory.OTHER,
)
