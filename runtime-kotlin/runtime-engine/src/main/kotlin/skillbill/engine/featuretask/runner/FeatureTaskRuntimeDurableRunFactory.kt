package skillbill.engine.featuretask.runner

import me.tatarka.inject.annotations.Inject
import skillbill.application.review.parallel.runner.ParallelCodeReviewRunnerResultAssembly
import skillbill.engine.featuretask.lifecycle.continuation.FeatureTaskRuntimeGoalContinuationRecorder
import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeRunRequest
import skillbill.engine.featuretask.phase.core.FeatureTaskPhaseSettlementService
import skillbill.engine.featuretask.runloop.durable.DurablePhaseRunCheckpoints
import skillbill.engine.featuretask.runloop.durable.DurablePhaseRunGoal
import skillbill.engine.featuretask.runloop.durable.DurablePhaseRunSettlements
import skillbill.engine.featuretask.runloop.durable.FeatureTaskRuntimeRunLoopDurableLaunch
import skillbill.engine.featuretask.runloop.durable.FeatureTaskRuntimeRunLoopDurableState
import skillbill.engine.featuretask.runloop.observability.FeatureTaskRuntimeRunObservability
import skillbill.engine.featuretask.runloop.state.FeatureTaskRuntimeRunState
import skillbill.engine.featuretask.slot.PhaseStrategyLookup
import skillbill.ports.db.DatabaseSessionFactory
import skillbill.ports.workflow.gitops.WorkflowGitOperations
import skillbill.workflow.decomposition.model.SpecSource
import skillbill.workflow.taskruntime.model.skeleton.ResolvedPhaseExecutionPlan
import java.time.Clock

@Inject
class FeatureTaskRuntimeDurableRunFactory(
  private val goalContinuationRecorder: FeatureTaskRuntimeGoalContinuationRecorder,
  private val strategies: PhaseStrategyLookup,
  private val clock: Clock,
  private val gitOperations: WorkflowGitOperations,
  private val phaseSettlementService: FeatureTaskPhaseSettlementService,
  private val durableLaunch: FeatureTaskRuntimeRunLoopDurableLaunch,
  private val database: DatabaseSessionFactory,
  private val reviewResultAssembly: ParallelCodeReviewRunnerResultAssembly,
) {
  internal fun create(
    runRequest: FeatureTaskRuntimeRunRequest,
    specSource: SpecSource,
    executionPlan: ResolvedPhaseExecutionPlan,
    observability: FeatureTaskRuntimeRunObservability,
    state: FeatureTaskRuntimeRunState,
  ): FeatureTaskRuntimeRunLoopDurableState =
    FeatureTaskRuntimeRunLoopDurableState(
      state,
      observability,
      specSource,
      executionPlan,
      strategies,
      DurablePhaseRunGoal(goalContinuationRecorder, requireNotNull(runRequest.admittedExecution)),
      DurablePhaseRunSettlements(
        database,
        requireNotNull(runRequest.admittedExecution),
        clock,
        phaseSettlementService,
      ),
      DurablePhaseRunCheckpoints(gitOperations),
      durableLaunch,
      reviewResultAssembly,
      clock,
    )
}
