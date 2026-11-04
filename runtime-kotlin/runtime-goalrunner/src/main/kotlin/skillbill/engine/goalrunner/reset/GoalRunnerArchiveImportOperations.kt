package skillbill.engine.goalrunner.reset

import me.tatarka.inject.annotations.Inject
import skillbill.application.decomposition.repoRelativePath
import skillbill.application.workflow.decomposition.decomposedParentsForPurgeFromRecords
import skillbill.application.workflow.decomposition.isPlanWorkflow
import skillbill.application.workflow.decomposition.matchesIssueKeyForFeatureTaskDiscovery
import skillbill.contracts.SharedPayloadKeys
import skillbill.contracts.workflow.goal.GOAL_ARCHIVE_CONTRACT_VERSION
import skillbill.contracts.workflow.identity.task.FEATURE_TASK_EXECUTION_IDENTITY_CONTRACT_VERSION
import skillbill.contracts.workflow.payload.WorkflowWirePayloadKeys
import skillbill.engine.goal.goalRepositoryIdentity
import skillbill.engine.goalplanning.GoalPlanningPreparationCheckpoint
import skillbill.engine.goalrunner.archive.GoalArchiveBundleReader
import skillbill.engine.goalrunner.archive.ParsedGoalArchiveBundle
import skillbill.engine.goalrunner.manifest.GoalRunnerManifestStore
import skillbill.engine.goalrunner.model.GoalRunnerArchiveInspectResult
import skillbill.engine.goalrunner.model.GoalRunnerImportRequest
import skillbill.engine.goalrunner.model.GoalRunnerImportResult
import skillbill.error.core.SkillBillRuntimeException
import skillbill.error.core.catchingNonCancellation
import skillbill.error.goal.goalArchivePathTraversalError
import skillbill.error.goal.invalidGoalArchiveSchemaError
import skillbill.goalrunner.model.GOAL_PAUSE_REASON_IMPORTED
import skillbill.goalrunner.model.GoalRunnerControlState
import skillbill.ports.db.DatabaseSessionFactory
import skillbill.ports.db.model.DatabasePathProtectionResult
import skillbill.ports.db.model.FencedTransactionResult
import skillbill.ports.filesystem.model.DatabaseFileObservation
import skillbill.ports.goalrunner.GoalArchiveEnvelopeValidator
import skillbill.ports.goalrunner.GoalPlanningPreparationRepository
import skillbill.ports.goalrunner.model.GoalArchiveEnvelope
import skillbill.ports.goalrunner.model.GoalArchiveTableRow
import skillbill.ports.goalrunner.model.GoalPlanningPreparationRecord
import skillbill.ports.goalrunner.model.GoalSubtaskPlanCheckpoint
import skillbill.ports.goalrunner.model.SharedGoalPreplanCheckpoint
import skillbill.ports.persistence.UnitOfWork
import skillbill.ports.projectstore.ProjectStorePort
import skillbill.ports.repository.RepositoryEnclosingRootPort
import skillbill.ports.workflow.WorkflowArtifactWireEncoder
import skillbill.ports.workflow.WorkflowStateRepository
import skillbill.ports.workflow.decomposition.DecompositionManifestStore
import skillbill.ports.workflow.gitops.WorkflowGitOperations
import skillbill.ports.workflow.gitops.model.WorkflowGitOperationResult
import skillbill.workflow.model.FeatureTaskExecutionIdentity
import skillbill.workflow.model.FeatureTaskExecutionIdentityPolicy
import skillbill.workflow.model.FeatureTaskRouteScope
import skillbill.workflow.model.FeatureTaskWorkflowMode
import java.nio.file.Path
import java.time.Clock

@Inject
public class GoalRunnerArchiveImportOperations(
  private val manifestStore: GoalRunnerManifestStore,
  private val gitOperations: WorkflowGitOperations,
  private val manifestFileStore: DecompositionManifestStore,
  private val database: DatabaseSessionFactory,
  private val projectStore: ProjectStorePort,
  private val repositoryEnclosingRootPort: RepositoryEnclosingRootPort,
  private val clock: Clock,
  private val planningCheckpoint: GoalPlanningPreparationCheckpoint,
  private val envelopeValidator: GoalArchiveEnvelopeValidator,
  private val workflowArtifactWireEncoder: WorkflowArtifactWireEncoder,
) {
  private val changedSummary = "Database file changed during import; no rows were restored."

  public fun inspect(archivePath: Path): GoalRunnerArchiveInspectResult {
    val normalized = archivePath.toAbsolutePath().normalize()
    val bundle =
      catchingNonCancellation { GoalArchiveBundleReader.read(normalized, workflowArtifactWireEncoder) }
        .getOrElse { error ->
          if (error is SkillBillRuntimeException) throw error
          return GoalRunnerArchiveInspectResult.Failed(error.message ?: error::class.simpleName.orEmpty())
        }
    envelopeValidator.validate(bundle.envelope)
    if (bundle.envelope.contractVersion != GOAL_ARCHIVE_CONTRACT_VERSION) {
      return GoalRunnerArchiveInspectResult.Refused(
        "Goal archive contract version '${bundle.envelope.contractVersion}' is not supported; " +
          "expected '$GOAL_ARCHIVE_CONTRACT_VERSION'.",
      )
    }
    return GoalRunnerArchiveInspectResult.Ready(
      bundle.fingerprint,
      FeatureTaskExecutionIdentityPolicy.canonicalIssueKey(bundle.envelope.issueKey),
      bundle.envelope.featureBranch,
      bundle.envelope.commitSha,
    )
  }

  public fun import(
    request: GoalRunnerImportRequest,
    expectedObservation: DatabaseFileObservation? = null,
  ): GoalRunnerImportResult {
    val repoRoot = request.repoRoot ?: error("repoRoot is required to import a goal archive.")
    val archivePath = request.archivePath.toAbsolutePath().normalize()
    val protection =
      when (val result = database.acquireDatabasePathProtection()) {
        is DatabasePathProtectionResult.Acquired -> result.protection
        is DatabasePathProtectionResult.Unavailable ->
          return GoalRunnerImportResult.Refused(
            issueKey = "",
            reason = "Database path protection is unavailable; import made no changes. ${result.reason}",
          )
      }
    return protection.use { importProtected(repoRoot, archivePath, expectedObservation, request.expectedFingerprint) }
  }

  private fun importProtected(
    repoRoot: Path,
    archivePath: Path,
    expectedObservation: DatabaseFileObservation?,
    expectedFingerprint: String?,
  ): GoalRunnerImportResult {
    val bundle =
      catchingNonCancellation {
        GoalArchiveBundleReader.read(archivePath, workflowArtifactWireEncoder)
      }.getOrElse { error ->
        if (error is SkillBillRuntimeException) throw error
        return GoalRunnerImportResult.Failed("", error.message ?: error::class.simpleName.orEmpty())
      }
    envelopeValidator.validate(bundle.envelope)
    if (expectedFingerprint != null && bundle.fingerprint != expectedFingerprint) {
      return GoalRunnerImportResult.Refused(
        FeatureTaskExecutionIdentityPolicy.canonicalIssueKey(bundle.envelope.issueKey),
        "The archive changed after inspection; inspect it again before importing.",
      )
    }
    val issueKey = FeatureTaskExecutionIdentityPolicy.canonicalIssueKey(bundle.envelope.issueKey)
    return admitImport(repoRoot, archivePath, bundle, issueKey, expectedObservation)
  }

  private fun admitImport(
    repoRoot: Path,
    archivePath: Path,
    bundle: ParsedGoalArchiveBundle,
    issueKey: String,
    expectedObservation: DatabaseFileObservation?,
  ): GoalRunnerImportResult {
    if (bundle.envelope.contractVersion != GOAL_ARCHIVE_CONTRACT_VERSION) {
      return GoalRunnerImportResult.Refused(
        issueKey,
        "Goal archive contract version '${bundle.envelope.contractVersion}' is not supported; " +
          "expected '$GOAL_ARCHIVE_CONTRACT_VERSION'.",
      )
    }
    validateSpecEntries(bundle, projectStore.resolve(repoRoot).specsRoot.normalize())
    val archiveParentIds = archiveParentWorkflowIds(issueKey, bundle)
    return headRefusal(bundle.envelope, repoRoot)?.copy(issueKey = issueKey)
      ?: collisionRefusal(issueKey, repoRoot, archiveParentIds)
      ?: archiveContentRefusal(issueKey, bundle)
      ?: restoreAdmitted(repoRoot, archivePath, bundle, archiveParentIds, expectedObservation)
  }

  private fun restoreAdmitted(
    repoRoot: Path,
    archivePath: Path,
    bundle: ParsedGoalArchiveBundle,
    archiveParentIds: Set<String>,
    expectedObservation: DatabaseFileObservation?,
  ): GoalRunnerImportResult {
    val issueKey = FeatureTaskExecutionIdentityPolicy.canonicalIssueKey(bundle.envelope.issueKey)
    val observation =
      expectedObservation ?: if (database.databaseExists()) {
        database.read { it.databaseFileObservation }
      } else {
        null
      }
    observationRefusal(issueKey, observation)?.let { return it }
    requireNotNull(observation)
    val restore =
      when (
        val result =
          database.transactionFenced(observation) { unitOfWork ->
            restoreSnapshot(repoRoot, issueKey, archiveParentIds, bundle, unitOfWork)
          }
      ) {
        FencedTransactionResult.ChangedTarget -> return GoalRunnerImportResult.Refused(issueKey, changedSummary)
        is FencedTransactionResult.Committed -> result.value
      }
    return writeSpecs(repoRoot, archivePath, issueKey, bundle, restore)
  }

  private fun restoreSnapshot(
    repoRoot: Path,
    issueKey: String,
    parentIds: Set<String>,
    bundle: ParsedGoalArchiveBundle,
    unitOfWork: UnitOfWork,
  ): Map<String, Int> {
    val destIdentity = goalRepositoryIdentity(repoRoot, repositoryEnclosingRootPort)
    for (workflow in bundle.snapshot.workflows) {
      unitOfWork.workflowStates.saveFeatureTaskWorkflow(workflow, FeatureTaskWorkflowMode.RUNTIME)
    }
    restoreIdentities(bundle, destIdentity, issueKey, unitOfWork.workflowStates)
    restorePlanning(bundle, destIdentity, issueKey, unitOfWork.goalPlanningPreparations)
    for (parentId in parentIds) {
      unitOfWork.goalRunnerControls.persistControlState(parentId, importedControlState(destIdentity))
    }
    unitOfWork.restoreGoalArchiveOwnedTables(bundle.snapshot.ownedTables)
    return rowCounts(bundle)
  }

  private fun restorePlanning(
    bundle: ParsedGoalArchiveBundle,
    destIdentity: String,
    issueKey: String,
    preparations: GoalPlanningPreparationRepository,
  ) {
    val sharedParents = bundle.snapshot.sharedPreplans.map { it.identity.parentGoalWorkflowId }.toSet()
    bundle.snapshot.sharedPreplans.forEach { checkpoint ->
      val rewritten = checkpoint.rewriteIdentity(destIdentity, issueKey)
      planningCheckpoint.validateSharedPreplanForWrite(rewritten)
      preparations.saveSharedPreplan(rewritten)
    }
    bundle.snapshot.subtaskPlans.forEach { checkpoint ->
      val rewritten = checkpoint.rewriteIdentity(destIdentity, issueKey)
      planningCheckpoint.validateSubtaskPlanForWrite(rewritten)
      preparations.saveSubtaskPlan(rewritten)
    }
    bundle.snapshot.legacyPreparations
      .filter { it.parentGoalWorkflowId !in sharedParents }
      .forEach { record ->
        preparations.saveLegacyPreparation(record.rewriteLegacy(destIdentity, issueKey))
      }
  }

  private fun restoreIdentities(
    bundle: ParsedGoalArchiveBundle,
    destIdentity: String,
    issueKey: String,
    workflowStates: WorkflowStateRepository,
  ) {
    val rows = bundle.snapshot.ownedTables.rows("feature_task_execution_identities").orEmpty()
    if (rows.isNotEmpty()) {
      rows.forEach { row ->
        workflowStates.saveFeatureTaskExecutionIdentity(row.toExecutionIdentity(destIdentity, issueKey))
      }
      return
    }
    bundle.snapshot.workflows.forEach { workflow ->
      if (workflow.issueKey.isNullOrBlank()) return@forEach
      workflowStates.saveFeatureTaskExecutionIdentity(
        FeatureTaskExecutionIdentity(
          workflowId = workflow.workflowId,
          normalizedIssueKey = issueKey,
          repositoryIdentity = destIdentity,
          governedSpecPath = "",
          mode = workflow.mode ?: FeatureTaskWorkflowMode.RUNTIME,
          routeScope = FeatureTaskRouteScope.STANDALONE,
        ),
      )
    }
  }

  private fun writeSpecs(
    repoRoot: Path,
    archivePath: Path,
    issueKey: String,
    bundle: ParsedGoalArchiveBundle,
    rowCounts: Map<String, Int>,
  ): GoalRunnerImportResult {
    val store = projectStore.resolve(repoRoot)
    val specsRoot = store.specsRoot.normalize()
    val writes =
      bundle.specEntries.map { (relative, bytes) ->
        specsRoot.resolve(relative).normalize() to bytes.decodeToString()
      }
    val missing =
      catchingNonCancellation {
        manifestFileStore.writeBundleAtomically(writes) {}
      }.exceptionOrNull()
    if (missing != null) {
      val paths = writes.map { (path, _) -> repoRelativePath(repoRoot, store, path) }
      return GoalRunnerImportResult.Incomplete(
        issueKey = issueKey,
        missingSpecPaths = paths,
        featureBranch = bundle.envelope.featureBranch,
        commitSha = bundle.envelope.commitSha,
        archivePath = archivePath,
        retryHint = "Retry with the same import command: skill-bill goal import ${archivePath.fileName}",
      )
    }
    return GoalRunnerImportResult.Imported(
      issueKey = issueKey,
      featureBranch = bundle.envelope.featureBranch,
      commitSha = bundle.envelope.commitSha,
      archivePath = archivePath,
      rowCounts = rowCounts,
    )
  }

  private fun validateSpecEntries(
    bundle: ParsedGoalArchiveBundle,
    specsRoot: Path,
  ) {
    for ((relative, _) in bundle.specEntries) {
      if (relative.isBlank() || relative.contains("..") || Path.of(relative).isAbsolute) {
        throw goalArchivePathTraversalError(relative)
      }
      val target = specsRoot.resolve(relative).normalize()
      if (!target.startsWith(specsRoot)) {
        throw goalArchivePathTraversalError(relative)
      }
    }
  }

  private fun headRefusal(
    envelope: GoalArchiveEnvelope,
    repoRoot: Path,
  ): GoalRunnerImportResult.Refused? {
    val current =
      when (val result = gitOperations.currentBranch(repoRoot)) {
        is WorkflowGitOperationResult.Ok -> result.value.takeIf(String::isNotBlank)
        is WorkflowGitOperationResult.Failed -> null
      }
    if (current == envelope.featureBranch) return null
    val reason =
      "Current branch '${current ?: "<unavailable>"}' is not the recorded feature branch " +
        "'${envelope.featureBranch}' at commit '${envelope.commitSha}'; import refused."
    return GoalRunnerImportResult.Refused(
      issueKey = envelope.issueKey,
      reason = reason,
      featureBranch = envelope.featureBranch,
      commitSha = envelope.commitSha,
    )
  }

  private fun collisionRefusal(
    issueKey: String,
    repoRoot: Path,
    archiveParentIds: Set<String>,
  ): GoalRunnerImportResult.Refused? {
    val local = manifestStore.discoverPurgeOwnership(issueKey, repoRoot).parentWorkflowIds
    if (local.isEmpty()) return null
    if (local == archiveParentIds) return null
    return GoalRunnerImportResult.Refused(
      issueKey = issueKey,
      reason =
        "Goal '$issueKey' already has parent workflow ids $local that differ from the archive parent ids " +
          "$archiveParentIds; import refused.",
    )
  }

  private fun archiveContentRefusal(
    issueKey: String,
    bundle: ParsedGoalArchiveBundle,
  ): GoalRunnerImportResult? {
    val prose =
      bundle.snapshot.workflows.firstOrNull { workflow ->
        workflow.mode == FeatureTaskWorkflowMode.PROSE
      }
    if (prose != null) {
      return GoalRunnerImportResult.Refused(
        issueKey,
        "Goal archive workflow '${prose.workflowId}' uses prose mode and cannot be imported.",
      )
    }
    val sharedParents = bundle.snapshot.sharedPreplans.map { it.identity.parentGoalWorkflowId }.toSet()
    if (bundle.snapshot.legacyPreparations.any { it.parentGoalWorkflowId in sharedParents }) {
      return GoalRunnerImportResult.Failed(
        issueKey,
        "Goal archive contains both modern shared preplans and legacy preparations for the same parent workflow.",
      )
    }
    return null
  }

  private fun observationRefusal(
    issueKey: String,
    observation: DatabaseFileObservation?,
  ): GoalRunnerImportResult.Refused? {
    val selectedPath = database.resolveDbPath().toAbsolutePath().normalize()
    val reason =
      when {
        !database.databaseExists() -> "Selected database does not exist; import made no changes."
        observation == null -> "Database file identity is unavailable; import made no changes."
        observation.path.toAbsolutePath().normalize() != selectedPath ->
          "Database observation does not match the selected database path; import made no changes."
        else -> null
      }
    return reason?.let { GoalRunnerImportResult.Refused(issueKey, it) }
  }

  private fun importedControlState(destIdentity: String): GoalRunnerControlState =
    GoalRunnerControlState(
      paused = true,
      pauseRequested = false,
      pauseConsumed = false,
      pauseReason = GOAL_PAUSE_REASON_IMPORTED,
      pausedAt = clock.instant().toString(),
      repositoryIdentity = destIdentity,
      executionLease = null,
      executionWorktreePath = null,
      executionWorktreeRemovedAt = null,
      executionWorktreeRetentionReason = null,
      executionWorktreeRetentionDetail = null,
    )

  private fun archiveParentWorkflowIds(
    issueKey: String,
    bundle: ParsedGoalArchiveBundle,
  ): Set<String> {
    val candidates =
      bundle.snapshot.workflows.filter { workflow ->
        !workflow.isPlanWorkflow() && workflow.matchesIssueKeyForFeatureTaskDiscovery(issueKey)
      }
    return decomposedParentsForPurgeFromRecords(candidates, issueKey)
      .parents
      .map { it.record.workflowId }
      .toCollection(LinkedHashSet())
  }

  private fun rowCounts(bundle: ParsedGoalArchiveBundle): Map<String, Int> =
    buildMap {
      put("feature_task_workflows", bundle.snapshot.workflows.size)
      put("goal_shared_preplans", bundle.snapshot.sharedPreplans.size)
      put("goal_subtask_plans", bundle.snapshot.subtaskPlans.size)
      put("goal_planning_preparations", bundle.snapshot.legacyPreparations.size)
      bundle.snapshot.ownedTables.orderedTables().forEach { (table, rows) -> put(table, rows.size) }
    }
}

private fun SharedGoalPreplanCheckpoint.rewriteIdentity(
  destIdentity: String,
  issueKey: String,
): SharedGoalPreplanCheckpoint =
  copy(identity = identity.copy(repositoryIdentity = destIdentity, normalizedIssueKey = issueKey))

private fun GoalSubtaskPlanCheckpoint.rewriteIdentity(
  destIdentity: String,
  issueKey: String,
): GoalSubtaskPlanCheckpoint =
  copy(identity = identity.copy(repositoryIdentity = destIdentity, normalizedIssueKey = issueKey))

private fun GoalPlanningPreparationRecord.rewriteLegacy(
  destIdentity: String,
  issueKey: String,
): GoalPlanningPreparationRecord = copy(repositoryIdentity = destIdentity, normalizedIssueKey = issueKey)

private fun GoalArchiveTableRow.toExecutionIdentity(
  destIdentity: String,
  issueKey: String,
): FeatureTaskExecutionIdentity {
  val columns = orderedEntries().toMap()
  val workflowId = columns[SharedPayloadKeys.WORKFLOW_ID]?.toString().orEmpty()
  if (workflowId.isBlank()) {
    throw invalidGoalArchiveSchemaError(
      "feature_task_execution_identities",
      SharedPayloadKeys.WORKFLOW_ID,
      "workflow_id is required",
    )
  }
  return FeatureTaskExecutionIdentity(
    workflowId = workflowId,
    normalizedIssueKey = issueKey,
    repositoryIdentity = destIdentity,
    governedSpecPath = columns["governed_spec_path"]?.toString().orEmpty(),
    mode = identityMode(columns[WorkflowWirePayloadKeys.MODE]?.toString().orEmpty()),
    routeScope = identityRouteScope(columns["route_scope"]?.toString().orEmpty()),
    contractVersion =
      columns[SharedPayloadKeys.CONTRACT_VERSION]?.toString().orEmpty().ifBlank {
        FEATURE_TASK_EXECUTION_IDENTITY_CONTRACT_VERSION
      },
  )
}

private fun identityMode(wire: String): FeatureTaskWorkflowMode {
  if (wire.isBlank()) return FeatureTaskWorkflowMode.RUNTIME
  return FeatureTaskWorkflowMode.fromWireValue(wire)
    ?: throw invalidGoalArchiveSchemaError(
      "feature_task_execution_identities",
      WorkflowWirePayloadKeys.MODE,
      "unsupported value '$wire'",
    )
}

private fun identityRouteScope(wire: String): FeatureTaskRouteScope {
  if (wire.isBlank()) return FeatureTaskRouteScope.STANDALONE
  return FeatureTaskRouteScope.entries.firstOrNull { it.wireValue == wire }
    ?: throw invalidGoalArchiveSchemaError(
      "feature_task_execution_identities",
      "route_scope",
      "unsupported value '$wire'",
    )
}
