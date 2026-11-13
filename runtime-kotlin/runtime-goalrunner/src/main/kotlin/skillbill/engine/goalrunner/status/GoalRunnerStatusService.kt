package skillbill.engine.goalrunner.status

import me.tatarka.inject.annotations.Inject
import skillbill.engine.goal.GoalRunnerPauseResult
import skillbill.engine.goal.GoalRunnerResumeResult
import skillbill.engine.goal.GoalRunnerStatusPort
import skillbill.engine.goal.GoalRunnerStatusRequest
import skillbill.engine.goal.GoalRunnerStopVerbResult
import skillbill.engine.goalrunner.execution.core.GoalRunnerAcceptanceCoordinator
import skillbill.engine.goalrunner.manifest.GoalRunnerManifestStore
import skillbill.engine.goalrunner.model.GoalRunnerAcceptRequest
import skillbill.engine.goalrunner.model.GoalRunnerAcceptResult
import skillbill.engine.goalrunner.model.GoalRunnerArchiveInspectResult
import skillbill.engine.goalrunner.model.GoalRunnerCompleteRequest
import skillbill.engine.goalrunner.model.GoalRunnerCompleteResult
import skillbill.engine.goalrunner.model.GoalRunnerExportPreflightResult
import skillbill.engine.goalrunner.model.GoalRunnerExportRequest
import skillbill.engine.goalrunner.model.GoalRunnerExportResult
import skillbill.engine.goalrunner.model.GoalRunnerFreshQualityRequest
import skillbill.engine.goalrunner.model.GoalRunnerFreshQualityResult
import skillbill.engine.goalrunner.model.GoalRunnerImportRequest
import skillbill.engine.goalrunner.model.GoalRunnerImportResult
import skillbill.engine.goalrunner.model.GoalRunnerPurgeRequest
import skillbill.engine.goalrunner.model.GoalRunnerPurgeResult
import skillbill.engine.goalrunner.model.GoalRunnerRepairRequest
import skillbill.engine.goalrunner.model.GoalRunnerRepairResult
import skillbill.engine.goalrunner.model.GoalRunnerReplanRequest
import skillbill.engine.goalrunner.model.GoalRunnerReplanResult
import skillbill.engine.goalrunner.model.GoalRunnerResetRequest
import skillbill.engine.goalrunner.model.GoalRunnerResetResult
import skillbill.engine.goalrunner.model.GoalRunnerWorkflowProgress
import skillbill.engine.goalrunner.monitoring.isCiWaitingOperation
import skillbill.engine.goalrunner.repair.GoalRunnerFreshQualityCoordinator
import skillbill.engine.goalrunner.repair.GoalRunnerRepairCoordinator
import skillbill.engine.goalrunner.reset.GoalRunnerArchiveCoordinator
import skillbill.engine.goalrunner.reset.GoalRunnerPurgeCoordinator
import skillbill.engine.goalrunner.reset.GoalRunnerResetReplanCoordinator
import skillbill.goalrunner.ledger.parseInstantOrNull
import skillbill.goalrunner.model.GoalRunnerAcceptedSubtask
import skillbill.goalrunner.model.GoalRunnerStatusProjection
import skillbill.idestatus.model.WorktreeEditSummary
import skillbill.model.RepositoryRoot
import skillbill.ports.filesystem.model.DatabaseFileObservation
import skillbill.workflow.decomposition.model.DecompositionSubtask
import skillbill.workflow.model.goalobservability.GoalProgressEvent
import skillbill.workflow.model.goalobservability.GoalProgressEventKind
import java.nio.file.Path
import java.time.Instant

@Inject
public class GoalRunnerStatusService(
  private val manifestStore: GoalRunnerManifestStore,
  private val controlVerbs: GoalRunnerStatusControlVerbs,
  private val repairCoordinator: GoalRunnerRepairCoordinator,
  private val acceptanceCoordinator: GoalRunnerAcceptanceCoordinator,
  private val repositoryRoot: RepositoryRoot,
  private val projectionAssembler: GoalRunnerStatusProjectionAssembler,
  private val resetReplanCoordinator: GoalRunnerResetReplanCoordinator,
  private val purgeCoordinator: GoalRunnerPurgeCoordinator,
  private val archiveCoordinator: GoalRunnerArchiveCoordinator,
  private val freshQualityCoordinator: GoalRunnerFreshQualityCoordinator,
) : GoalRunnerStatusPort {
  public override fun status(request: GoalRunnerStatusRequest): GoalRunnerStatusProjection? {
    return manifestStore.loadDurableByIssueKey(request.issueKey, request.repoRoot)
      ?.let { loadedState -> projectionAssembler.projectWorkflow(loadedState.parentWorkflowId, request) }
  }

  public override fun statusForWorkflow(
    parentWorkflowId: String,
    request: GoalRunnerStatusRequest,
  ): GoalRunnerStatusProjection? = projectionAssembler.projectWorkflow(parentWorkflowId, request)

  public override fun reconcileSubtaskPage(
    parentWorkflowId: String,
    request: GoalRunnerStatusRequest,
    subtasks: List<DecompositionSubtask>,
  ): List<DecompositionSubtask> = projectionAssembler.reconcileSubtaskPage(parentWorkflowId, request, subtasks)

  public fun statusRefresh(request: GoalRunnerStatusRequest): GoalRunnerStatusProjection? = status(request)

  public override fun pause(
    issueKey: String,
    repoRoot: Path?,
    expectedParentWorkflowId: String?,
  ): GoalRunnerPauseResult =
    controlVerbs.pause(
      issueKey,
      effectiveGoalRepoRoot(repoRoot, repositoryRoot),
      expectedParentWorkflowId,
    )

  public override fun stop(
    issueKey: String,
    repoRoot: Path?,
    expectedParentWorkflowId: String?,
    databaseFileObservation: DatabaseFileObservation?,
    beforeAdmission: (() -> Boolean)?,
  ): GoalRunnerStopVerbResult =
    controlVerbs.stop(
      issueKey,
      effectiveGoalRepoRoot(repoRoot, repositoryRoot),
      expectedParentWorkflowId,
      databaseFileObservation,
      beforeAdmission,
    )

  public override fun resume(
    issueKey: String,
    repoRoot: Path?,
  ): GoalRunnerResumeResult = controlVerbs.resume(issueKey, effectiveGoalRepoRoot(repoRoot, repositoryRoot))

  public fun reset(request: GoalRunnerResetRequest): GoalRunnerResetResult? = resetReplanCoordinator.reset(request)

  public fun purge(request: GoalRunnerPurgeRequest): GoalRunnerPurgeResult = purge(request, null)

  public fun purge(
    request: GoalRunnerPurgeRequest,
    expectedObservation: DatabaseFileObservation?,
  ): GoalRunnerPurgeResult =
    purgeCoordinator.purge(
      request.copy(repoRoot = request.repoRoot ?: repositoryRoot.path),
      expectedObservation,
    )

  public fun export(request: GoalRunnerExportRequest): GoalRunnerExportResult = export(request, null)

  public fun export(
    request: GoalRunnerExportRequest,
    expectedObservation: DatabaseFileObservation?,
  ): GoalRunnerExportResult =
    archiveCoordinator.export(
      request.copy(repoRoot = request.repoRoot ?: repositoryRoot.path),
      expectedObservation,
    )

  public fun import(
    request: GoalRunnerImportRequest,
    expectedObservation: DatabaseFileObservation? = null,
  ): GoalRunnerImportResult =
    archiveCoordinator.import(
      request.copy(repoRoot = request.repoRoot ?: repositoryRoot.path),
      expectedObservation,
    )

  public fun preflightExport(
    issueKey: String,
    repoRoot: Path?,
    expectedObservation: DatabaseFileObservation?,
  ): GoalRunnerExportPreflightResult =
    archiveCoordinator.preflightExport(
      issueKey,
      effectiveGoalRepoRoot(repoRoot, repositoryRoot),
      expectedObservation,
    )

  public fun inspectArchive(archivePath: Path): GoalRunnerArchiveInspectResult = archiveCoordinator.inspect(archivePath)

  public fun replan(request: GoalRunnerReplanRequest): GoalRunnerReplanResult? = resetReplanCoordinator.replan(request)

  public fun hardResetPreflight(issueKey: String): List<GoalRunnerAcceptedSubtask> =
    resetReplanCoordinator.hardResetPreflight(issueKey)

  public fun repair(request: GoalRunnerRepairRequest): GoalRunnerRepairResult = repairCoordinator.repair(request)

  public fun accept(request: GoalRunnerAcceptRequest): GoalRunnerAcceptResult = acceptanceCoordinator.accept(request)

  public fun freshQuality(request: GoalRunnerFreshQualityRequest): GoalRunnerFreshQualityResult =
    freshQualityCoordinator.choose(
      request.copy(repoRoot = effectiveGoalRepoRoot(request.repoRoot, repositoryRoot)),
    )

  public fun complete(request: GoalRunnerCompleteRequest): GoalRunnerCompleteResult =
    acceptanceCoordinator.complete(request)
}

internal fun effectiveGoalRepoRoot(
  repoRoot: Path?,
  repositoryRoot: RepositoryRoot,
): Path = repoRoot ?: repositoryRoot.path

internal data class GoalProgressStallSignals(
  val progress: GoalRunnerWorkflowProgress?,
  val agentActivityStamps: List<Instant>,
  val latestWorktreeEdit: WorktreeEditSummary?,
) {
  fun latestProgressAt(): Instant? =
    (
      agentActivityStamps +
        listOfNotNull(
          progress?.latestDurableProgressEvent?.timestamp?.let(::parseInstantOrNull),
          progress?.latestDeclaredProgressEvent?.takeIf(::reportsProgress)?.timestamp,
          latestWorktreeEdit?.recordedAt,
          progress?.currentStepEnteredAt,
        )
    ).maxOrNull()

  private fun reportsProgress(event: GoalProgressEvent): Boolean =
    event.eventKind != GoalProgressEventKind.OPERATION_HEARTBEAT || isCiWaitingOperation(event.operationName)
}
