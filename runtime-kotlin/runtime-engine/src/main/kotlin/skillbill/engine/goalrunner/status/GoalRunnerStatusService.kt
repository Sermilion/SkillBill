package skillbill.engine.goalrunner.status

import me.tatarka.inject.annotations.Inject
import skillbill.engine.goalrunner.execution.core.GoalRunnerAcceptanceCoordinator
import skillbill.engine.goalrunner.manifest.GoalRunnerManifestStore
import skillbill.engine.goalrunner.model.GoalRunnerAcceptRequest
import skillbill.engine.goalrunner.model.GoalRunnerAcceptResult
import skillbill.engine.goalrunner.model.GoalRunnerCompleteRequest
import skillbill.engine.goalrunner.model.GoalRunnerCompleteResult
import skillbill.engine.goalrunner.model.GoalRunnerFreshQualityRequest
import skillbill.engine.goalrunner.model.GoalRunnerFreshQualityResult
import skillbill.engine.goalrunner.model.GoalRunnerPauseResult
import skillbill.engine.goalrunner.model.GoalRunnerPurgeRequest
import skillbill.engine.goalrunner.model.GoalRunnerPurgeResult
import skillbill.engine.goalrunner.model.GoalRunnerRepairRequest
import skillbill.engine.goalrunner.model.GoalRunnerRepairResult
import skillbill.engine.goalrunner.model.GoalRunnerReplanRequest
import skillbill.engine.goalrunner.model.GoalRunnerReplanResult
import skillbill.engine.goalrunner.model.GoalRunnerResetRequest
import skillbill.engine.goalrunner.model.GoalRunnerResetResult
import skillbill.engine.goalrunner.model.GoalRunnerResumeResult
import skillbill.engine.goalrunner.model.GoalRunnerStatusRequest
import skillbill.engine.goalrunner.model.GoalRunnerStopVerbResult
import skillbill.engine.goalrunner.repair.GoalRunnerFreshQualityCoordinator
import skillbill.engine.goalrunner.repair.GoalRunnerRepairCoordinator
import skillbill.engine.goalrunner.reset.GoalRunnerPurgeCoordinator
import skillbill.engine.goalrunner.reset.GoalRunnerResetReplanCoordinator
import skillbill.goalrunner.model.GoalRunnerAcceptedSubtask
import skillbill.goalrunner.model.GoalRunnerStatusProjection
import skillbill.model.RepositoryRoot
import skillbill.ports.filesystem.model.DatabaseFileObservation
import skillbill.workflow.decomposition.model.DecompositionSubtask
import java.nio.file.Path

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
  private val freshQualityCoordinator: GoalRunnerFreshQualityCoordinator,
) {
  public fun status(request: GoalRunnerStatusRequest): GoalRunnerStatusProjection? {
    return manifestStore.loadDurableByIssueKey(request.issueKey, request.repoRoot)
      ?.let { loadedState -> projectionAssembler.projectWorkflow(loadedState.parentWorkflowId, request) }
  }

  public fun statusForWorkflow(
    parentWorkflowId: String,
    request: GoalRunnerStatusRequest,
  ): GoalRunnerStatusProjection? = projectionAssembler.projectWorkflow(parentWorkflowId, request)

  public fun reconcileSubtaskPage(
    parentWorkflowId: String,
    request: GoalRunnerStatusRequest,
    subtasks: List<DecompositionSubtask>,
  ): List<DecompositionSubtask> = projectionAssembler.reconcileSubtaskPage(parentWorkflowId, request, subtasks)

  public fun statusRefresh(request: GoalRunnerStatusRequest): GoalRunnerStatusProjection? = status(request)

  public fun pause(
    issueKey: String,
    repoRoot: Path? = null,
    expectedParentWorkflowId: String? = null,
  ): GoalRunnerPauseResult =
    controlVerbs.pause(
      issueKey,
      effectiveGoalRepoRoot(repoRoot, repositoryRoot),
      expectedParentWorkflowId,
    )

  public fun stop(
    issueKey: String,
    repoRoot: Path? = null,
    expectedParentWorkflowId: String? = null,
    databaseFileObservation: DatabaseFileObservation? = null,
    beforeAdmission: (() -> Boolean)? = null,
  ): GoalRunnerStopVerbResult =
    controlVerbs.stop(
      issueKey,
      effectiveGoalRepoRoot(repoRoot, repositoryRoot),
      expectedParentWorkflowId,
      databaseFileObservation,
      beforeAdmission,
    )

  public fun resume(
    issueKey: String,
    repoRoot: Path? = null,
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
