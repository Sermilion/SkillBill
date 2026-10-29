package skillbill.engine.goalrunner.repair

import me.tatarka.inject.annotations.Inject
import skillbill.application.telemetry.lifecycle.asWorkerOwnership
import skillbill.engine.goalrunner.manifest.GoalRunnerManifestStore
import skillbill.engine.goalrunner.model.GoalRunnerFreshQualityRequest
import skillbill.engine.goalrunner.model.GoalRunnerFreshQualityResult
import skillbill.engine.goalrunner.persist.GoalRunnerWorkflowOutcomeStore
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.ports.taskruntime.FeatureTaskRuntimeWorkerSupervisor
import skillbill.ports.taskruntime.model.FeatureTaskRuntimeProcessInspection
import java.time.Clock

@Inject
class GoalRunnerFreshQualityCoordinator(
  private val manifestStore: GoalRunnerManifestStore,
  private val outcomeStore: GoalRunnerWorkflowOutcomeStore,
  private val workerSupervisor: FeatureTaskRuntimeWorkerSupervisor,
  private val clock: Clock,
  private val diagnostics: RuntimeDiagnostics,
) {
  fun choose(request: GoalRunnerFreshQualityRequest): GoalRunnerFreshQualityResult {
    val loaded =
      manifestStore.loadDurableByIssueKey(request.issueKey, request.repoRoot)
        ?: return rejected(request, "No prepared goal exists for '${request.issueKey}'.")
    val parentWorkflowId = loaded.parentWorkflowId
    if (runnerLive(parentWorkflowId)) {
      return rejected(
        request,
        "A goal runner still holds parent workflow '$parentWorkflowId'. Stop it before choosing fresh quality.",
      )
    }
    if (!outcomeStore.chooseFreshGoalQualityExecution(parentWorkflowId)) {
      return rejected(request, "Parent workflow '$parentWorkflowId' has no ambiguous historical quality state.")
    }
    diagnostics.info(
      "goal ${request.issueKey}: operator chose fresh quality for '$parentWorkflowId'; reason=${request.reason}",
    )
    return GoalRunnerFreshQualityResult.Chosen(request.issueKey, parentWorkflowId, request.reason)
  }

  private fun runnerLive(parentWorkflowId: String): Boolean {
    val lease = manifestStore.executionLease(parentWorkflowId) ?: return false
    val ownership = lease.asWorkerOwnership(parentWorkflowId)
    if (ownership.expiresAtInstant.isAfter(clock.instant())) return true
    return workerSupervisor.inspect(ownership) == FeatureTaskRuntimeProcessInspection.ExactLive
  }

  private fun rejected(
    request: GoalRunnerFreshQualityRequest,
    reason: String,
  ): GoalRunnerFreshQualityResult = GoalRunnerFreshQualityResult.Rejected(request.issueKey, reason)
}
