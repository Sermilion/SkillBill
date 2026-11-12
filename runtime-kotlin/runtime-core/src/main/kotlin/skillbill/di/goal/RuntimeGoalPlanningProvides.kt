package skillbill.di.goal

import me.tatarka.inject.annotations.Provides
import skillbill.engine.goalrunner.execution.core.DefaultGoalRunnerExecutionCoordinator
import skillbill.engine.goalrunner.execution.core.GoalRunnerExecutionCoordinator
import skillbill.engine.goalrunner.plan.GoalPlanCheckoutPreparer
import skillbill.engine.goalrunner.plan.StandalonePlanRun
import skillbill.engine.goalrunner.planning.source.GoalPlanningSource
import skillbill.engine.goalrunner.planning.source.PlanWorkflowGoalPlanningSource
import skillbill.infrastructure.workflow.branchworktree.GitBranchWorktreeResources
import skillbill.infrastructure.workflow.goalplanning.FileSystemGoalPlanningBoundaryBodyResolver
import skillbill.infrastructure.workflow.goalplanning.FileSystemGoalPlanningContextDiscovery
import skillbill.infrastructure.workflow.goalworktree.GitGoalWorktreeResources
import skillbill.ports.goalrunner.planning.GoalPlanningBoundaryBodyResolver
import skillbill.ports.goalrunner.planning.GoalPlanningContextDiscovery
import skillbill.ports.workflow.branchworktree.BranchWorktreeResources
import skillbill.ports.workflow.goalworktree.GoalWorktreeResources

internal interface RuntimeGoalPlanningProvides {
  @Provides
  fun goalPlanCheckoutPreparer(plan: () -> StandalonePlanRun): GoalPlanCheckoutPreparer =
    GoalPlanCheckoutPreparer { issueKey, intake, repoRoot, selectedWorkflowId ->
      plan().prepareGoalCheckout(issueKey, intake, repoRoot, selectedWorkflowId)
    }

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

  @Provides
  fun goalWorktreeResources(adapter: GitGoalWorktreeResources): GoalWorktreeResources = adapter

  @Provides
  fun branchWorktreeResources(adapter: GitBranchWorktreeResources): BranchWorktreeResources = adapter
}
