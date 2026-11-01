package skillbill.engine.goalrunner

import skillbill.engine.goalrunner.persist.GoalRunnerWorkflowOutcomeStore
import skillbill.engine.work.EmptyOutcomeStore
import skillbill.goalrunner.model.history.GoalFinalRepairContext
import skillbill.goalrunner.model.history.GoalFinalRepairOutput
import skillbill.goalrunner.model.history.GoalHistoryCompletion
import skillbill.goalrunner.model.history.GoalHistoryWriterEntry
import skillbill.goalrunner.model.quality.GoalQualityExecution
import skillbill.goalrunner.model.quality.GoalQualityExecutionAdmission
import skillbill.goalrunner.model.quality.GoalQualityExecutionInputs
import skillbill.goalrunner.model.quality.GoalQualityExecutionPlans
import skillbill.goalrunner.model.quality.GoalQualityExecutionProgress
import skillbill.goalrunner.model.quality.GoalQualityExecutionRecord
import skillbill.goalrunner.model.quality.GoalQualityExecutionStatus
import skillbill.goalrunner.model.quality.GoalQualityPhaseProgress
import skillbill.goalrunner.model.quality.GoalQualityReadiness
import skillbill.goalrunner.model.quality.GoalQualityValidationDisposition
import skillbill.workflow.taskruntime.model.skeleton.SkeletonDefinition

internal open class TestGoalQualityOutcomeStore(private val settled: Boolean = false) :
  GoalRunnerWorkflowOutcomeStore by EmptyOutcomeStore {
  private val records = mutableMapOf<String, GoalQualityExecutionRecord>()
  private val contexts = mutableMapOf<String, GoalFinalRepairContext>()
  private val completions = mutableMapOf<String, GoalHistoryCompletion>()

  override fun goalHistoryWriterEntries(
    parentWorkflowId: String,
    expectedSelection: List<Pair<Int, String?>>,
  ): List<GoalHistoryWriterEntry> =
    expectedSelection.map { (subtaskId, childId) ->
      GoalHistoryWriterEntry(
        subtaskId,
        childId ?: "test-child-$subtaskId",
        "goal-child@4",
        legacyDisposition = "already_written",
      )
    }

  override fun goalQualityExecution(parentWorkflowId: String): GoalQualityExecutionRecord? =
    records[parentWorkflowId] ?: if (settled) {
      GoalQualityExecutionRecord(
        readiness = GoalQualityReadiness.ADMITTED,
        execution =
          GoalQualityExecution(
            parentWorkflowId, "test-quality", "test-tree", null, null, null, null,
            GoalQualityExecutionProgress(), GoalQualityExecutionStatus.SETTLED_PASSED,
            GoalQualityValidationDisposition.NOT_REQUIRED, settledContentFingerprint = "test-tree",
          ),
      )
    } else {
      null
    }

  override fun resolvedGoalQualityChildIds(
    parentWorkflowId: String,
    expectedSelection: List<Pair<Int, String?>>,
  ) = expectedSelection.mapNotNull { it.second }

  override fun admitGoalQualityExecution(
    parentWorkflowId: String,
    baselineContentFingerprint: String,
    governingInputs: GoalQualityExecutionInputs,
    executionPlans: GoalQualityExecutionPlans,
  ): GoalQualityExecutionRecord =
    records.getOrPut(parentWorkflowId) {
      contexts[parentWorkflowId] = GoalFinalRepairContext(emptyList())
      GoalQualityExecutionRecord(readiness = GoalQualityReadiness.CLEAN_UNSTARTED).admit(
        GoalQualityExecutionAdmission(
          parentWorkflowId,
          "test-quality",
          baselineContentFingerprint,
          "selection",
          "governing",
          governingInputs,
          executionPlans,
        ),
      )
    }

  override fun transitionGoalQualityExecution(
    parentWorkflowId: String,
    expectedInvocationId: String,
    expected: GoalQualityExecutionRecord,
    next: GoalQualityExecutionRecord,
  ): Boolean {
    val current = records[parentWorkflowId] ?: return false
    if (current.execution?.invocationId != expectedInvocationId) return false
    records[parentWorkflowId] =
      next.copy(
        execution = requireNotNull(next.execution).copy(progress = current.execution?.progress),
      )
    return true
  }

  override fun goalQualityPhaseProgress(
    parentWorkflowId: String,
    invocationId: String,
    definitionId: String,
    acceptedPlan: String,
  ): GoalQualityPhaseProgress {
    val progress = requireNotNull(records[parentWorkflowId]?.execution?.progress)
    return if (definitionId == SkeletonDefinition.GOAL_QA.id) progress.qa else progress.validation
  }

  override fun persistGoalQualityPhaseProgress(
    parentWorkflowId: String,
    invocationId: String,
    definitionId: String,
    acceptedPlan: String,
    progress: GoalQualityPhaseProgress,
  ): Boolean {
    val record = requireNotNull(records[parentWorkflowId])
    val execution = requireNotNull(record.execution)
    if (execution.invocationId != invocationId) return false
    val prior = requireNotNull(execution.progress)
    val next =
      if (definitionId == SkeletonDefinition.GOAL_QA.id) {
        prior.copy(
          qa = progress,
        )
      } else {
        prior.copy(validation = progress)
      }
    records[parentWorkflowId] = record.copy(execution = execution.copy(progress = next))
    return true
  }

  override fun recordGoalFinalRepairOutput(
    parentWorkflowId: String,
    expectedInvocationId: String,
    output: GoalFinalRepairOutput,
  ): Boolean {
    val context = contexts[parentWorkflowId] ?: GoalFinalRepairContext(emptyList())
    contexts[parentWorkflowId] = context.copy(outputs = (context.outputs + output).distinct())
    return true
  }

  override fun recordGoalFinalRepairWarning(
    parentWorkflowId: String,
    expectedInvocationId: String,
    warning: String?,
  ): Boolean {
    contexts[parentWorkflowId] =
      (
        contexts[parentWorkflowId] ?: GoalFinalRepairContext(
          emptyList(),
        )
      ).copy(warning = warning)
    return true
  }

  override fun goalFinalRepairContext(parentWorkflowId: String): GoalFinalRepairContext? =
    contexts[parentWorkflowId] ?: GoalFinalRepairContext(
      emptyList(),
    )

  override fun saveGoalHistoryCompletion(
    parentWorkflowId: String,
    expectedInvocationId: String,
    completion: GoalHistoryCompletion,
  ): Boolean {
    completions[parentWorkflowId] = completion
    return true
  }

  override fun goalHistoryCompletion(parentWorkflowId: String) = completions[parentWorkflowId]
}
