package skillbill.engine.goalrunner.status

import me.tatarka.inject.annotations.Inject
import skillbill.engine.goalrunner.execution.core.GoalRunnerAcceptanceCoordinator
import skillbill.engine.goalrunner.manifest.GoalRunnerManifestStore
import skillbill.engine.goalrunner.model.GoalRunnerAcceptRequest
import skillbill.engine.goalrunner.model.GoalRunnerAcceptResult
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
import skillbill.engine.goalrunner.repair.GoalRunnerRepairCoordinator
import skillbill.engine.goalrunner.reset.GoalRunnerPurgeCoordinator
import skillbill.engine.goalrunner.reset.GoalRunnerResetReplanCoordinator
import skillbill.goalrunner.model.GoalRunnerAcceptedSubtask
import skillbill.goalrunner.model.GoalRunnerStatusProjection
import skillbill.model.RepositoryRoot
import java.nio.file.Path

@Inject
class GoalRunnerStatusService(
  private val manifestStore: GoalRunnerManifestStore,
  private val controlVerbs: GoalRunnerStatusControlVerbs,
  private val repairCoordinator: GoalRunnerRepairCoordinator,
  private val acceptanceCoordinator: GoalRunnerAcceptanceCoordinator,
  private val repositoryRoot: RepositoryRoot,
  private val projectionAssembler: GoalRunnerStatusProjectionAssembler,
  private val resetReplanCoordinator: GoalRunnerResetReplanCoordinator,
  private val purgeCoordinator: GoalRunnerPurgeCoordinator,
) {
  fun status(request: GoalRunnerStatusRequest): GoalRunnerStatusProjection? {
    return manifestStore.readByIssueKey(request.issueKey, request.repoRoot)
      ?.let { loadedState -> projectionAssembler.project(loadedState, request) }
  }

  fun statusRefresh(request: GoalRunnerStatusRequest): GoalRunnerStatusProjection? = status(request)

  fun pause(
    issueKey: String,
    repoRoot: Path? = null,
  ): GoalRunnerPauseResult = controlVerbs.pause(issueKey, effectiveGoalRepoRoot(repoRoot, repositoryRoot))

  fun stop(
    issueKey: String,
    repoRoot: Path? = null,
  ): GoalRunnerStopVerbResult = controlVerbs.stop(issueKey, effectiveGoalRepoRoot(repoRoot, repositoryRoot))

  fun resume(
    issueKey: String,
    repoRoot: Path? = null,
  ): GoalRunnerResumeResult = controlVerbs.resume(issueKey, effectiveGoalRepoRoot(repoRoot, repositoryRoot))

  fun reset(request: GoalRunnerResetRequest): GoalRunnerResetResult? = resetReplanCoordinator.reset(request)

  fun purge(request: GoalRunnerPurgeRequest): GoalRunnerPurgeResult =
    purgeCoordinator.purge(
      request.copy(repoRoot = request.repoRoot ?: repositoryRoot.path),
    )

  fun replan(request: GoalRunnerReplanRequest): GoalRunnerReplanResult? = resetReplanCoordinator.replan(request)

  fun hardResetPreflight(issueKey: String): List<GoalRunnerAcceptedSubtask> =
    resetReplanCoordinator.hardResetPreflight(issueKey)

  fun repair(request: GoalRunnerRepairRequest): GoalRunnerRepairResult = repairCoordinator.repair(request)

  fun accept(request: GoalRunnerAcceptRequest): GoalRunnerAcceptResult = acceptanceCoordinator.accept(request)
}

fun effectiveGoalRepoRoot(
  repoRoot: Path?,
  repositoryRoot: RepositoryRoot,
): Path = repoRoot ?: repositoryRoot.path
