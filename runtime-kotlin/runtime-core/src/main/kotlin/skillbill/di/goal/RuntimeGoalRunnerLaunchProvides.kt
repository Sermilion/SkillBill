package skillbill.di.goal

import me.tatarka.inject.annotations.Provides
import skillbill.application.agentrun.AgentRunGoalRunnerSubtaskLauncher
import skillbill.engine.goalrunner.assessment.DefaultGoalRunnerAssessmentGate
import skillbill.engine.goalrunner.assessment.GoalRunnerAssessmentGate
import skillbill.engine.goalrunner.monitoring.DefaultGoalRunnerCiMonitor
import skillbill.engine.goalrunner.monitoring.GoalRunnerCiMonitor
import skillbill.engine.goalrunner.persist.GoalRunnerAttemptLedgerStore
import skillbill.engine.goalrunner.persist.WorkflowGoalRunnerOutcomeStore
import skillbill.engine.goalrunner.qa.DefaultGoalRunnerPostChangeValidation
import skillbill.engine.goalrunner.qa.DefaultGoalRunnerQaGate
import skillbill.engine.goalrunner.qa.GoalRunnerPostChangeValidation
import skillbill.engine.goalrunner.qa.GoalRunnerQaGate
import skillbill.engine.goalrunner.repair.GoalRunnerChildRepairStore
import skillbill.engine.goalrunner.repair.WorkflowGoalRunnerChildRepairStore
import skillbill.ports.goalrunner.runner.GoalRunnerSubtaskLauncher

internal interface RuntimeGoalRunnerLaunchProvides {
  @Provides
  fun goalRunnerCiMonitor(monitor: DefaultGoalRunnerCiMonitor): GoalRunnerCiMonitor = monitor

  @Provides
  fun goalRunnerQaGate(gate: DefaultGoalRunnerQaGate): GoalRunnerQaGate = gate

  @Provides
  fun goalRunnerAssessmentGate(gate: DefaultGoalRunnerAssessmentGate): GoalRunnerAssessmentGate = gate

  @Provides
  fun goalRunnerPostChangeValidation(
    validation: DefaultGoalRunnerPostChangeValidation,
  ): GoalRunnerPostChangeValidation = validation

  @Provides
  fun goalRunnerSubtaskLauncher(adapter: AgentRunGoalRunnerSubtaskLauncher): GoalRunnerSubtaskLauncher = adapter

  @Provides
  fun goalRunnerAttemptLedgerStore(adapter: WorkflowGoalRunnerOutcomeStore): GoalRunnerAttemptLedgerStore = adapter

  @Provides
  fun goalRunnerChildRepairStore(adapter: WorkflowGoalRunnerChildRepairStore): GoalRunnerChildRepairStore = adapter
}
