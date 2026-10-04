package skillbill.di.goal

import me.tatarka.inject.annotations.Provides
import skillbill.engine.goalrunner.manifest.GoalRunnerManifestStore
import skillbill.engine.goalrunner.manifest.WorkflowGoalRunnerManifestStore
import skillbill.engine.goalrunner.persist.GoalRunnerWorkflowOutcomeStore
import skillbill.engine.goalrunner.persist.WorkflowGoalRunnerOutcomeStore
import skillbill.engine.goalrunner.planning.hydration.GoalChildPlanningHydratorPort
import skillbill.engine.goalrunner.planning.hydration.GoalChildPlanningHydratorPortAdapter
import skillbill.engine.goalrunner.repair.GoalRunnerChildRepairOperations
import skillbill.engine.goalrunner.repair.GoalRunnerChildRepairRunnerPort

internal interface RuntimeGoalRunnerStoreProvides {
  @Provides
  fun goalRunnerManifestStore(store: WorkflowGoalRunnerManifestStore): GoalRunnerManifestStore = store

  @Provides
  fun goalRunnerWorkflowOutcomeStore(store: WorkflowGoalRunnerOutcomeStore): GoalRunnerWorkflowOutcomeStore = store

  @Provides
  fun goalRunnerChildRepairExecutorPort(operations: GoalRunnerChildRepairOperations): GoalRunnerChildRepairRunnerPort =
    operations

  @Provides
  fun goalChildPlanningHydratorPort(adapter: GoalChildPlanningHydratorPortAdapter): GoalChildPlanningHydratorPort =
    adapter
}
