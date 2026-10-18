package skillbill.di.goal

import me.tatarka.inject.annotations.Provides
import skillbill.engine.goalrunner.execution.core.DefaultGoalRunnerExecutionCoordinator
import skillbill.engine.goalrunner.execution.core.GoalRunnerExecutionCoordinator
import skillbill.engine.goalrunner.planning.source.GoalPlanningSource
import skillbill.engine.goalrunner.planning.source.PlanWorkflowGoalPlanningSource
import skillbill.infrastructure.workflow.goalplanning.FileSystemGoalPlanningBoundaryBodyResolver
import skillbill.infrastructure.workflow.goalplanning.FileSystemGoalPlanningContextDiscovery
import skillbill.ports.goalrunner.planning.GoalPlanningBoundaryBodyResolver
import skillbill.ports.goalrunner.planning.GoalPlanningContextDiscovery

internal interface RuntimeGoalPlanningProvides {
  @Provides
  fun goalRunnerExecutionCoordinator(
    coordinator: DefaultGoalRunnerExecutionCoordinator,
  ): GoalRunnerExecutionCoordinator = coordinator

  @Provides
  fun goalPlanningSource(source: PlanWorkflowGoalPlanningSource): GoalPlanningSource = source

  @Provides
  fun goalPlanningContextDiscovery(adapter: FileSystemGoalPlanningContextDiscovery): GoalPlanningContextDiscovery =
    adapter

  @Provides
  fun goalPlanningBoundaryBodyResolver(
    adapter: FileSystemGoalPlanningBoundaryBodyResolver,
  ): GoalPlanningBoundaryBodyResolver = adapter
}
