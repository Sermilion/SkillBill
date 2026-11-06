package skillbill.engine.goalrunner.reset

import me.tatarka.inject.annotations.Inject
import skillbill.application.decomposition.repoRelativePath
import skillbill.application.workflow.decomposition.decomposedParentsForPurgeFromRecords
import skillbill.application.workflow.decomposition.isPlanWorkflow
import skillbill.application.workflow.decomposition.listDecomposedParentsForPurge
import skillbill.application.workflow.decomposition.listPlanWorkflowsForPurge
import skillbill.application.workflow.decomposition.matchesIssueKeyForFeatureTaskDiscovery
import skillbill.contracts.JsonCodec
import skillbill.contracts.SharedPayloadKeys
import skillbill.contracts.goalrunner.GoalRunnerControlPayloadKeys
import skillbill.contracts.workflow.WORKFLOW_STATE_CONTRACT_VERSION
import skillbill.contracts.workflow.WORKFLOW_STATE_READABLE_CONTRACT_VERSIONS
import skillbill.contracts.workflow.goal.GOAL_ARCHIVE_CONTRACT_VERSION
import skillbill.contracts.workflow.goal.GoalPlanningPreparationPayloadKeys
import skillbill.contracts.workflow.identity.task.FEATURE_TASK_EXECUTION_IDENTITY_CONTRACT_VERSION
import skillbill.contracts.workflow.identity.task.FeatureTaskRuntimeGoalContinuationArtifactPayloadKeys
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
import skillbill.ports.goalrunner.model.GoalArchiveOwnedSnapshot
import skillbill.ports.goalrunner.model.GoalArchiveTableRow
import skillbill.ports.goalrunner.model.GoalPlanningPreparationRecord
import skillbill.ports.goalrunner.model.GoalSubtaskPlanCheckpoint
import skillbill.ports.goalrunner.model.SharedGoalPreplanCheckpoint
import skillbill.ports.persistence.UnitOfWork
import skillbill.ports.persistence.model.GoalPurgeTarget
import skillbill.ports.projectstore.ProjectStorePort
import skillbill.ports.repository.RepositoryEnclosingRootPort
import skillbill.ports.workflow.WorkflowArtifactWireEncoder
import skillbill.ports.workflow.WorkflowStateRepository
import skillbill.ports.workflow.decomposition.DecompositionManifestStore
import skillbill.ports.workflow.decomposition.DecompositionManifestValidator
import skillbill.ports.workflow.gitops.WorkflowGitOperations
import skillbill.ports.workflow.gitops.model.WorkflowGitOperationResult
import skillbill.ports.workflow.model.WorkflowStateRecord
import skillbill.workflow.engine.model.DurableWorkflowArtifactFamily
import skillbill.workflow.engine.model.DurableWorkflowArtifacts
import skillbill.workflow.model.FeatureTaskExecutionIdentity
import skillbill.workflow.model.FeatureTaskExecutionIdentityPolicy
import skillbill.workflow.model.FeatureTaskRouteScope
import skillbill.workflow.model.FeatureTaskWorkflowMode
import skillbill.workflow.taskruntime.artifact.goalPlanningImport
import java.nio.file.Files
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
  private val manifestValidator: DecompositionManifestValidator,
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
    val normalizedBundle =
      normalizeSpecReferences(
        bundle,
        goalRepositoryIdentity(repoRoot, repositoryEnclosingRootPort),
        issueKey,
      )
        ?: return GoalRunnerImportResult.Refused(
          issueKey,
          "Goal archive contains a spec reference that cannot be mapped to an archived spec entry; " +
            "import made no changes.",
        )
    validateSpecEntries(normalizedBundle, projectStore.resolve(repoRoot).specsRoot.normalize())
    val archiveParentIds = archiveParentWorkflowIds(issueKey, bundle)
    if (archiveParentIds.isEmpty()) {
      return GoalRunnerImportResult.Refused(
        issueKey,
        "Goal archive has no discovered parent workflow to mark a pending import; import made no changes.",
      )
    }
    return headRefusal(bundle.envelope, repoRoot)?.copy(issueKey = issueKey)
      ?: collisionRefusal(issueKey, repoRoot, archiveParentIds)
      ?: archiveContentRefusal(issueKey, normalizedBundle)
      ?: restoreAdmitted(repoRoot, archivePath, normalizedBundle, archiveParentIds, expectedObservation)
  }

  private fun normalizeSpecReferences(
    bundle: ParsedGoalArchiveBundle,
    destIdentity: String,
    issueKey: String,
  ): ParsedGoalArchiveBundle? =
    GoalArchiveSpecNormalizer(manifestValidator, manifestFileStore, destIdentity, issueKey).normalize(bundle)

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
    val context = ImportContext(repoRoot, archivePath, bundle, issueKey, archiveParentIds, observation)
    val restore =
      when (val result = database.transactionFenced(observation) { restoreTransaction(context, it) }) {
        FencedTransactionResult.ChangedTarget -> return GoalRunnerImportResult.Refused(issueKey, changedSummary)
        is FencedTransactionResult.Committed -> result.value
      }
    if (restore == null) {
      return GoalRunnerImportResult.Refused(
        issueKey,
        "Goal '$issueKey' already has destination workflow or planning state; import refused.",
      )
    }
    return writeSpecs(context, restore)
  }

  private data class ImportContext(
    val repoRoot: Path,
    val archivePath: Path,
    val bundle: ParsedGoalArchiveBundle,
    val issueKey: String,
    val parentIds: Set<String>,
    val observation: DatabaseFileObservation,
  )

  private fun restoreTransaction(
    context: ImportContext,
    unitOfWork: UnitOfWork,
  ): Map<String, Int>? {
    val repoRoot = context.repoRoot
    val bundle = context.bundle
    val issueKey = context.issueKey
    val parentIds = context.parentIds
    val destIdentity = goalRepositoryIdentity(repoRoot, repositoryEnclosingRootPort)
    val workflows = unitOfWork.workflowStates
    val archiveIds = bundle.snapshot.workflows.mapTo(linkedSetOf()) { it.workflowId }
    val existingById = archiveIds.mapNotNull(workflows::getFeatureTaskWorkflow)
    val archiveTarget = archiveTarget(bundle, parentIds)
    val existingSnapshot = unitOfWork.snapshotGoalArchiveOwned(archiveTarget)
    val planningParents =
      bundle.snapshot.sharedPreplans.map { it.identity.parentGoalWorkflowId } +
        bundle.snapshot.subtaskPlans.map { it.identity.parentGoalWorkflowId } +
        bundle.snapshot.legacyPreparations.map { it.parentGoalWorkflowId }
    val existingSpecs = existingSpecEntries(bundle, projectStore.resolve(repoRoot).specsRoot.normalize())
    if (isRecovery(context, unitOfWork)) {
      return recoveryRows(context, unitOfWork, existingSnapshot, existingSpecs, destIdentity)
    }
    val existingForIssue =
      FeatureTaskWorkflowMode.entries.flatMap { mode ->
        workflows.findFeatureTaskWorkflowsForIssue(mode, issueKey, destIdentity)
      }.distinctBy(WorkflowStateRecord::workflowId)
    val existingParents = workflows.listDecomposedParentsForPurge(issueKey, destIdentity).parents
    val existingPlans = workflows.listPlanWorkflowsForPurge(issueKey, destIdentity)
    val workflowCollision =
      existingById.isNotEmpty() || existingForIssue.isNotEmpty() ||
        existingParents.isNotEmpty() || existingPlans.isNotEmpty() || existingSnapshot.workflows.isNotEmpty()
    val planningCollision =
      planningParents.any { parentId ->
        unitOfWork.goalPlanningPreparations.hasPreparedSharedPreplan(parentId) ||
          unitOfWork.goalPlanningPreparations.preparedPlanStatusCount(parentId) > 0
      }
    val planningSnapshotCollision =
      existingSnapshot.sharedPreplans.isNotEmpty() ||
        existingSnapshot.subtaskPlans.isNotEmpty() || existingSnapshot.legacyPreparations.isNotEmpty()
    val ownedCollision = existingSnapshot.ownedTables.orderedTables().any { (_, rows) -> rows.isNotEmpty() }
    val identityCollision =
      archiveTarget.workflowIds.any { workflows.getFeatureTaskExecutionIdentity(it) != null }
    if (listOf(
        workflowCollision,
        existingSpecs.isNotEmpty(),
        planningCollision,
        planningSnapshotCollision,
        ownedCollision,
        identityCollision,
      ).any { it }
    ) {
      return null
    }
    return restoreSnapshot(repoRoot, issueKey, parentIds, bundle, unitOfWork)
  }

  private fun recoveryRows(
    context: ImportContext,
    unitOfWork: UnitOfWork,
    existing: GoalArchiveOwnedSnapshot,
    specs: List<Pair<Path, ByteArray>>,
    destIdentity: String,
  ): Map<String, Int>? {
    val bundle = context.bundle
    val workflows = unitOfWork.workflowStates
    val existingForIssue =
      FeatureTaskWorkflowMode.entries.flatMap { mode ->
        workflows.findFeatureTaskWorkflowsForIssue(mode, context.issueKey, destIdentity)
      }.distinctBy(WorkflowStateRecord::workflowId)
    val existingParents = workflows.listDecomposedParentsForPurge(context.issueKey, destIdentity).parents
    val existingPlans = workflows.listPlanWorkflowsForPurge(context.issueKey, destIdentity)
    val matchingRows = recoveryWorkflowsMatch(existing.workflows, bundle.snapshot.workflows, destIdentity)
    val archivedForIssue =
      bundle.snapshot.workflows.filter { it.matchesIssueKeyForFeatureTaskDiscovery(context.issueKey) }
    val matchingIssueRows = recoveryWorkflowsMatch(existingForIssue, archivedForIssue, destIdentity)
    val expectedPlanIds = bundle.snapshot.workflows.filter { it.isPlanWorkflow() }.map { it.workflowId }.toSet()
    val matchingParents = existingParents.map { it.record.workflowId }.toSet() == context.parentIds
    val matchingPlans = existingPlans.map(WorkflowStateRecord::workflowId).toSet() == expectedPlanIds
    val expectedSnapshot = recoverySnapshot(context, destIdentity)
    val matchingPlanning =
      existing.sharedPreplans.sortedBy { it.identity.parentGoalWorkflowId } ==
        expectedSnapshot.sharedPreplans.sortedBy { it.identity.parentGoalWorkflowId } &&
        existing.subtaskPlans.sortedWith(compareBy({ it.identity.parentGoalWorkflowId }, { it.subtaskId })) ==
        expectedSnapshot.subtaskPlans.sortedWith(compareBy({ it.identity.parentGoalWorkflowId }, { it.subtaskId })) &&
        existing.legacyPreparations.sortedWith(compareBy({ it.parentGoalWorkflowId }, { it.subtaskId })) ==
        expectedSnapshot.legacyPreparations.sortedWith(compareBy({ it.parentGoalWorkflowId }, { it.subtaskId }))
    val matchingOwnedTables =
      ownedTablesForRecovery(existing).mapValues { (_, rows) -> canonicalRows(rows) } ==
        ownedTablesForRecovery(expectedSnapshot).mapValues { (_, rows) -> canonicalRows(rows) }
    val expectedIdentities = restoredIdentities(bundle, destIdentity, context.issueKey)
    val actualIdentities =
      archiveTarget(bundle, context.parentIds).workflowIds.mapNotNull {
        unitOfWork.workflowStates.getFeatureTaskExecutionIdentity(it)
      }
    val matchingIdentities =
      expectedIdentities.sortedBy(FeatureTaskExecutionIdentity::workflowId) ==
        actualIdentities.sortedBy(FeatureTaskExecutionIdentity::workflowId)
    val matchingSpecs =
      specs.all { (path, bytes) ->
        Files.isRegularFile(path) && Files.readAllBytes(path).contentEquals(bytes)
      }
    val pendingParents =
      context.parentIds.all { parentId ->
        val control = unitOfWork.goalRunnerControls.controlState(parentId)
        control.archiveImportFingerprint == bundle.fingerprint && control.archiveImportSpecsPending &&
          archivedControlSettingsMatch(bundle, parentId, control)
      }
    return if (listOf(
        matchingRows,
        matchingIssueRows,
        matchingParents,
        matchingPlans,
        matchingPlanning,
        matchingOwnedTables,
        matchingIdentities,
        matchingSpecs,
        pendingParents,
      ).all { it }
    ) {
      rowCounts(bundle)
    } else {
      null
    }
  }

  private fun recoverySnapshot(
    context: ImportContext,
    destIdentity: String,
  ): GoalArchiveOwnedSnapshot {
    val snapshot = context.bundle.snapshot
    val sharedParents = snapshot.sharedPreplans.map { it.identity.parentGoalWorkflowId }.toSet()
    return snapshot.copy(
      sharedPreplans = snapshot.sharedPreplans.map { it.rewriteIdentity(destIdentity, context.issueKey) },
      subtaskPlans = snapshot.subtaskPlans.map { it.rewriteIdentity(destIdentity, context.issueKey) },
      legacyPreparations =
        snapshot.legacyPreparations
          .filterNot { it.parentGoalWorkflowId in sharedParents }
          .map { it.rewriteLegacy(destIdentity, context.issueKey) },
    )
  }

  private fun archiveTarget(
    bundle: ParsedGoalArchiveBundle,
    parentIds: Set<String>,
  ): GoalPurgeTarget {
    val ownedRows = bundle.snapshot.ownedTables.orderedTables().flatMap { (_, rows) -> rows }
    val planningParentIds =
      bundle.snapshot.sharedPreplans.map { it.identity.parentGoalWorkflowId } +
        bundle.snapshot.subtaskPlans.map { it.identity.parentGoalWorkflowId } +
        bundle.snapshot.legacyPreparations.map { it.parentGoalWorkflowId }
    return GoalPurgeTarget(
      parentWorkflowIds =
        buildSet {
          addAll(parentIds)
          addAll(planningParentIds)
          ownedRows.mapNotNullTo(this) { row ->
            val columns = row.orderedEntries().toMap()
            (
              columns[GoalPlanningPreparationPayloadKeys.PARENT_GOAL_WORKFLOW_ID]
                ?: columns[FeatureTaskRuntimeGoalContinuationArtifactPayloadKeys.PARENT_WORKFLOW_ID]
            )
              ?.toString()
              ?.takeIf(String::isNotBlank)
          }
        },
      workflowIds =
        buildSet {
          bundle.snapshot.workflows.forEach { add(it.workflowId) }
          ownedRows.mapNotNullTo(this) { row ->
            row.orderedEntries().toMap()[SharedPayloadKeys.WORKFLOW_ID]?.toString()?.takeIf(String::isNotBlank)
          }
        },
    )
  }

  private fun ownedTablesForRecovery(snapshot: GoalArchiveOwnedSnapshot): Map<String, List<GoalArchiveTableRow>> =
    snapshot.ownedTables.orderedTables()
      .filterNot { (table, _) ->
        table in
          setOf(
            "feature_task_execution_identities",
            "feature_task_runtime_worker_leases",
            "goal_runner_controls",
          )
      }
      .associate { (table, rows) ->
        table to if (table == "worktree_edit_journal") rows.map(::withoutJournalId) else rows
      }

  private fun canonicalRows(rows: List<GoalArchiveTableRow>): List<Map<String, Any?>> =
    rows.map { it.orderedEntries().toMap() }.sortedBy { it.toSortedMap().toString() }

  private fun recoveryWorkflowRecord(
    record: WorkflowStateRecord,
    archived: WorkflowStateRecord,
  ): WorkflowStateRecord =
    record.copy(
      startedAt = if (archived.startedAt.isNullOrBlank()) null else record.startedAt,
      updatedAt = null,
      finishedAt = if (archived.finishedAt.isNullOrBlank()) null else record.finishedAt,
      contractVersion =
        when {
          record.contractVersion.isBlank() || record.contractVersion in WORKFLOW_STATE_READABLE_CONTRACT_VERSIONS ->
            WORKFLOW_STATE_CONTRACT_VERSION
          else -> record.contractVersion
        },
      mode = FeatureTaskWorkflowMode.RUNTIME,
      implementationSkill = if (archived.implementationSkill.isNullOrBlank()) null else record.implementationSkill,
      stateEnteredAt = null,
      stateEnteredAtEstimated = false,
    )

  private fun recoveryWorkflowsMatch(
    restored: List<WorkflowStateRecord>,
    archived: List<WorkflowStateRecord>,
    destIdentity: String,
  ): Boolean {
    val restoredById = restored.associateBy(WorkflowStateRecord::workflowId)
    val archivedById = archived.associateBy(WorkflowStateRecord::workflowId)
    return restoredById.size == restored.size && archivedById.size == archived.size &&
      restoredById.keys == archivedById.keys &&
      archivedById.all { (workflowId, archivedRecord) ->
        recoveryWorkflowRecord(restoredById.getValue(workflowId), archivedRecord) ==
          recoveryWorkflowRecord(archivedRecord.rewritePlanningImportIdentity(destIdentity), archivedRecord)
      }
  }

  private fun WorkflowStateRecord.rewritePlanningImportIdentity(destIdentity: String): WorkflowStateRecord {
    val planningImport = typedArtifacts.goalPlanningImport() ?: return this
    return copy(
      typedArtifacts =
        DurableWorkflowArtifacts.fromMap(
          typedArtifacts +
            DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_GOAL_PLANNING_IMPORT.entry(
              planningImport.copy(repositoryIdentity = destIdentity),
            ),
        ),
    )
  }

  private fun withoutJournalId(row: GoalArchiveTableRow): GoalArchiveTableRow =
    GoalArchiveTableRow(row.orderedEntries().filterNot { it.first == "id" }.toMap())

  private fun restoredIdentities(
    bundle: ParsedGoalArchiveBundle,
    destIdentity: String,
    issueKey: String,
  ): List<FeatureTaskExecutionIdentity> {
    val rows = bundle.snapshot.ownedTables.rows(GoalArchiveSpecReferenceKeys.EXECUTION_IDENTITIES_TABLE).orEmpty()
    if (rows.isNotEmpty()) return rows.map { it.toExecutionIdentity(destIdentity, issueKey) }
    return bundle.snapshot.workflows.mapNotNull { workflow ->
      if (workflow.issueKey.isNullOrBlank()) return@mapNotNull null
      FeatureTaskExecutionIdentity(
        workflowId = workflow.workflowId,
        normalizedIssueKey = issueKey,
        repositoryIdentity = destIdentity,
        governedSpecPath = "",
        mode = workflow.mode ?: FeatureTaskWorkflowMode.RUNTIME,
        routeScope = FeatureTaskRouteScope.STANDALONE,
      )
    }
  }

  private fun isRecovery(
    context: ImportContext,
    unitOfWork: UnitOfWork,
  ): Boolean =
    context.parentIds.isNotEmpty() &&
      context.parentIds.all { parentId ->
        val control = unitOfWork.goalRunnerControls.controlState(parentId)
        val matchingImport =
          control.archiveImportFingerprint == context.bundle.fingerprint &&
            control.archiveImportSpecsPending
        val pausedImport = control.paused && control.pauseReason == GOAL_PAUSE_REASON_IMPORTED
        matchingImport && pausedImport && control.executionLease == null
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
      unitOfWork.workflowStates.saveFeatureTaskWorkflow(
        workflow.rewritePlanningImportIdentity(destIdentity),
        FeatureTaskWorkflowMode.RUNTIME,
      )
    }
    restoreIdentities(bundle, destIdentity, issueKey, unitOfWork.workflowStates)
    restorePlanning(bundle, destIdentity, issueKey, unitOfWork.goalPlanningPreparations)
    unitOfWork.restoreGoalArchiveOwnedTables(bundle.snapshot.ownedTables)
    for (parentId in parentIds) {
      val archivedControl = unitOfWork.goalRunnerControls.controlState(parentId)
      unitOfWork.goalRunnerControls.persistControlState(
        parentId,
        importedControlState(destIdentity, archivedControl).copy(
          archiveImportFingerprint = bundle.fingerprint,
          archiveImportSpecsPending = true,
        ),
      )
    }
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
    restoredIdentities(bundle, destIdentity, issueKey).forEach(workflowStates::saveFeatureTaskExecutionIdentity)
  }

  private fun writeSpecs(
    context: ImportContext,
    rowCounts: Map<String, Int>,
  ): GoalRunnerImportResult {
    val archivePath = context.archivePath
    val bundle = context.bundle
    val issueKey = context.issueKey
    val store = projectStore.resolve(context.repoRoot)
    val specsRoot = store.specsRoot.normalize()
    val writes =
      bundle.specEntries.map { (relative, bytes) ->
        specsRoot.resolve(relative).normalize() to bytes.decodeToString()
      }.filterNot { (path, content) -> Files.isRegularFile(path) && Files.readString(path) == content }
    val conflicts =
      existingSpecEntries(bundle, specsRoot)
        .filter { (path, bytes) -> !Files.isRegularFile(path) || Files.readString(path) != bytes.decodeToString() }
    if (conflicts.isNotEmpty()) {
      return GoalRunnerImportResult.Refused(
        issueKey,
        "Destination spec paths differ from the pending archive import: ${conflicts.map { it.first }}",
      )
    }
    val missing =
      catchingNonCancellation {
        if (writes.isNotEmpty()) manifestFileStore.writeBundleAtomically(writes) {}
      }.exceptionOrNull()
    if (missing != null) {
      val paths = writes.map { (path, _) -> repoRelativePath(context.repoRoot, store, path) }
      return GoalRunnerImportResult.Incomplete(
        issueKey = issueKey,
        missingSpecPaths = paths,
        featureBranch = bundle.envelope.featureBranch,
        commitSha = bundle.envelope.commitSha,
        archivePath = archivePath,
        retryHint = "Retry with the same import command: skill-bill goal import ${archivePath.fileName}",
      )
    }
    val completed = completeSpecImport(context)
    if (!completed) {
      return GoalRunnerImportResult.Refused(
        issueKey,
        "Pending archive import state changed before spec restoration completed.",
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

  private fun completeSpecImport(context: ImportContext): Boolean {
    val issueKey = context.issueKey
    val bundle = context.bundle
    return when (
      val result =
        database.transactionFenced(context.observation) { unitOfWork ->
          val pending =
            archiveParentWorkflowIds(issueKey, bundle).map { parentId ->
              parentId to unitOfWork.goalRunnerControls.controlState(parentId)
            }
          if (pending.any { (_, control) ->
              control.archiveImportFingerprint != bundle.fingerprint || !control.archiveImportSpecsPending
            }
          ) {
            return@transactionFenced false
          }
          pending.forEach { (parentId, control) ->
            unitOfWork.goalRunnerControls.persistControlState(
              parentId,
              control.copy(archiveImportSpecsPending = false),
            )
          }
          true
        }
    ) {
      FencedTransactionResult.ChangedTarget -> false
      is FencedTransactionResult.Committed -> result.value
    }
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

  private fun existingSpecEntries(
    bundle: ParsedGoalArchiveBundle,
    specsRoot: Path,
  ): List<Pair<Path, ByteArray>> =
    bundle.specEntries.mapNotNull { (relative, bytes) ->
      specsRoot.resolve(relative).normalize().takeIf { Files.exists(it) }?.let { it to bytes }
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

  private fun importedControlState(
    destIdentity: String,
    archived: GoalRunnerControlState,
  ): GoalRunnerControlState =
    archived.copy(
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
      executionMainCheckoutOutcome = null,
      executionMainCheckoutDetail = null,
    )

  private fun archivedControlSettingsMatch(
    bundle: ParsedGoalArchiveBundle,
    parentId: String,
    control: GoalRunnerControlState,
  ): Boolean {
    val row =
      bundle.snapshot.ownedTables.rows("goal_runner_controls").orEmpty().firstOrNull { archived ->
        archived.orderedEntries().toMap()[GoalRunnerControlPayloadKeys.PARENT_WORKFLOW_ID] == parentId
      } ?: return control.stopAfterSubtaskId == null && control.validationQualityRetriesBySubtask.isEmpty()
    val raw =
      row.orderedEntries().toMap()[GoalRunnerControlPayloadKeys.CONTROL_STATE_JSON] as? String
        ?: return control.stopAfterSubtaskId == null && control.validationQualityRetriesBySubtask.isEmpty()
    val archivedState =
      JsonCodec.parseObjectOrNull(raw)
        ?.let(JsonCodec::jsonElementToValue)
        ?.let(JsonCodec::anyToStringAnyMap) ?: return false
    val stopAfter =
      (archivedState[GoalRunnerControlPayloadKeys.STOP_AFTER_SUBTASK_ID] as? Number)?.toInt()
    val retries =
      (archivedState[GoalRunnerControlPayloadKeys.VALIDATION_QUALITY_RETRIES_BY_SUBTASK] as? Map<*, *>)
        ?.mapNotNull { (key, value) ->
          val subtaskId = key?.toString()?.toIntOrNull()
          val retryCount = (value as? Number)?.toInt()
          if (subtaskId == null || retryCount == null) null else subtaskId to retryCount
        }?.toMap().orEmpty()
    return control.stopAfterSubtaskId == stopAfter && control.validationQualityRetriesBySubtask == retries
  }

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

internal object GoalArchiveSpecReferenceKeys {
  const val EXECUTION_IDENTITIES_TABLE = "feature_task_execution_identities"
  const val ROUTE_SCOPE = "route_scope"
  const val GOVERNED_SPEC_PATH = "governed_spec_path"
  const val GOVERNED_SUB_SPEC_PATH = "governed_sub_spec_path"
  const val SUBTASK_SPEC_PATHS = "subtask_spec_paths"
  const val INTEGRITY_SHA256 = "integrity_sha256"
}

private fun GoalArchiveTableRow.toExecutionIdentity(
  destIdentity: String,
  issueKey: String,
): FeatureTaskExecutionIdentity {
  val columns = orderedEntries().toMap()
  val workflowId = columns[SharedPayloadKeys.WORKFLOW_ID]?.toString().orEmpty()
  if (workflowId.isBlank()) {
    throw invalidGoalArchiveSchemaError(
      GoalArchiveSpecReferenceKeys.EXECUTION_IDENTITIES_TABLE,
      SharedPayloadKeys.WORKFLOW_ID,
      "workflow_id is required",
    )
  }
  return FeatureTaskExecutionIdentity(
    workflowId = workflowId,
    normalizedIssueKey = issueKey,
    repositoryIdentity = destIdentity,
    governedSpecPath = columns[GoalArchiveSpecReferenceKeys.GOVERNED_SPEC_PATH]?.toString().orEmpty(),
    mode = identityMode(columns[WorkflowWirePayloadKeys.MODE]?.toString().orEmpty()),
    routeScope = identityRouteScope(columns[GoalArchiveSpecReferenceKeys.ROUTE_SCOPE]?.toString().orEmpty()),
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
      GoalArchiveSpecReferenceKeys.EXECUTION_IDENTITIES_TABLE,
      WorkflowWirePayloadKeys.MODE,
      "unsupported value '$wire'",
    )
}

private fun identityRouteScope(wire: String): FeatureTaskRouteScope {
  if (wire.isBlank()) return FeatureTaskRouteScope.STANDALONE
  return FeatureTaskRouteScope.entries.firstOrNull { it.wireValue == wire }
    ?: throw invalidGoalArchiveSchemaError(
      GoalArchiveSpecReferenceKeys.EXECUTION_IDENTITIES_TABLE,
      GoalArchiveSpecReferenceKeys.ROUTE_SCOPE,
      "unsupported value '$wire'",
    )
}
