package skillbill.di.goal

import me.tatarka.inject.annotations.Provides
import skillbill.application.runtime.RuntimeSingleton
import skillbill.engine.goalrunner.execution.core.DefaultGoalRunnerExecutionCoordinator
import skillbill.engine.goalrunner.execution.core.GoalRunnerExecutionCoordinator
import skillbill.engine.goalrunner.planning.attempt.DurableGoalPlanningAttemptRecorder
import skillbill.engine.goalrunner.planning.attempt.GoalPlanningAttemptRecorder
import skillbill.engine.goalrunner.planning.model.GoalPlanningBurstSchedule
import skillbill.engine.goalrunner.planning.remedies.DurableGoalPlanningRejectionRecorder
import skillbill.engine.goalrunner.planning.remedies.GoalPlanningRejectionRecorder
import skillbill.infrastructure.workflow.goalplanning.FileSystemGoalPlanningBoundaryBodyResolver
import skillbill.infrastructure.workflow.goalplanning.FileSystemGoalPlanningContextDiscovery
import skillbill.ports.goalrunner.planning.GoalPlanningBoundaryBodyResolver
import skillbill.ports.goalrunner.planning.GoalPlanningContextDiscovery

internal interface RuntimeGoalPlanningProvides {
  @Provides
  fun goalRunnerExecutionCoordinator(
    coordinator: DefaultGoalRunnerExecutionCoordinator,
  ): GoalRunnerExecutionCoordinator = coordinator

  @Provides @RuntimeSingleton
  fun goalPlanningAttemptRecorder(recorder: DurableGoalPlanningAttemptRecorder): GoalPlanningAttemptRecorder = recorder

  @Provides
  fun goalPlanningRejectionRecorder(recorder: DurableGoalPlanningRejectionRecorder): GoalPlanningRejectionRecorder =
    recorder

  @Provides
  fun goalPlanningContextDiscovery(adapter: FileSystemGoalPlanningContextDiscovery): GoalPlanningContextDiscovery =
    adapter

  @Provides
  fun goalPlanningBoundaryBodyResolver(
    adapter: FileSystemGoalPlanningBoundaryBodyResolver,
  ): GoalPlanningBoundaryBodyResolver = adapter

  @Provides
  fun goalPlanningBurstSchedule(): GoalPlanningBurstSchedule =
    GoalPlanningBurstSchedule(
      planFanOutCap = GoalPlanningBurstSchedule.DEFAULT_PLAN_FAN_OUT_CAP,
      emptyTurnBackoffBase = GoalPlanningBurstSchedule.DEFAULT_EMPTY_TURN_BACKOFF_BASE,
      emptyTurnBackoffFactor = GoalPlanningBurstSchedule.DEFAULT_EMPTY_TURN_BACKOFF_FACTOR,
      waitSlice = GoalPlanningBurstSchedule.DEFAULT_WAIT_SLICE,
    )
}
