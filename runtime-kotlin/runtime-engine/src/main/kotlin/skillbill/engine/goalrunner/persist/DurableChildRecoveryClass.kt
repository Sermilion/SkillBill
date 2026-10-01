package skillbill.engine.goalrunner.persist

import skillbill.engine.goalrunner.model.GoalRunnerWorkflowProgress
import skillbill.workflow.model.DecompositionStatus
import skillbill.engine.recovery.DurableChildRecoveryClass as RecoveryDurableChildClass
import skillbill.engine.recovery.classifyDurableChild as recoveryClassifyDurableChild
import skillbill.engine.recovery.recommendedDurableChildRecoveryCommand as recoveryRecommendedDurableChildRecoveryCommand
import skillbill.engine.recovery.scopedChildRecoveryCommand as recoveryScopedChildRecoveryCommand
import skillbill.engine.recovery.staleChildPlanningRecoveryCommand as recoveryStaleChildPlanningRecoveryCommand

internal typealias DurableChildRecoveryClass = RecoveryDurableChildClass

fun scopedChildRecoveryCommand(
  issueKey: String,
  subtaskId: Int,
): String = recoveryScopedChildRecoveryCommand(issueKey, subtaskId)

fun recommendedDurableChildRecoveryCommand(
  issueKey: String,
  subtaskId: Int,
  subtaskStatus: DecompositionStatus?,
  childProgress: GoalRunnerWorkflowProgress?,
): String = recoveryRecommendedDurableChildRecoveryCommand(issueKey, subtaskId, subtaskStatus, childProgress)

fun staleChildPlanningRecoveryCommand(
  issueKey: String,
  subtaskId: Int,
): String = recoveryStaleChildPlanningRecoveryCommand(issueKey, subtaskId)

internal fun classifyDurableChild(progress: GoalRunnerWorkflowProgress?): DurableChildRecoveryClass =
  recoveryClassifyDurableChild(progress)
