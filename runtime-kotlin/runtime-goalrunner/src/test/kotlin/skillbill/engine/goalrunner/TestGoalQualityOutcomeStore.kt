package skillbill.engine.goalrunner

import skillbill.engine.goalrunner.persist.GoalRunnerWorkflowOutcomeStore
import skillbill.engine.goalrunner.persist.quality.GoalQualityAdmissionRequest
import skillbill.engine.goalrunner.persist.quality.GoalQualityExecutionRead
import skillbill.engine.goalrunner.persist.quality.GoalQualityPhaseProgressWrite
import skillbill.engine.work.EmptyOutcomeStore
import skillbill.goalrunner.model.history.GoalFinalRepairContext
import skillbill.goalrunner.model.history.GoalFinalRepairOutput
import skillbill.goalrunner.model.history.GoalHistoryCompletion
import skillbill.goalrunner.model.history.GoalHistoryWriterEntry
import skillbill.goalrunner.model.quality.GoalQualityExecution
import skillbill.goalrunner.model.quality.GoalQualityExecutionAdmission
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

  override fun retainedGoalQualityExecution(parentWorkflowId: String): GoalQualityExecutionRead? =
    goalQualityExecution(parentWorkflowId)?.let(GoalQualityExecutionRead::Current)

  override fun goalQualityExecution(parentWorkflowId: String): GoalQualityExecutionRecord? =
    records[parentWorkflowId] ?: if (settled) {
      GoalQualityExecutionRecord(
        readiness = GoalQualityReadiness.ADMITTED,
        execution =
          GoalQualityExecution(
            parentWorkflowId, "test-quality", "test-tree", null, null, null, null,
            GoalQualityExecutionProgress(), GoalQualityExecutionStatus.SETTLED_PASSED,
            GoalQualityValidationDisposition.COMPLETED,
            settledContentFingerprint = "test-tree",
            finalValidationContentFingerprint = "test-tree",
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
    admission: GoalQualityAdmissionRequest,
  ): GoalQualityExecutionRecord =
    records.getOrPut(parentWorkflowId) {
      contexts[parentWorkflowId] = GoalFinalRepairContext(emptyList())
      GoalQualityExecutionRecord(readiness = GoalQualityReadiness.CLEAN_UNSTARTED).admit(
        GoalQualityExecutionAdmission(
          parentWorkflowId,
          "test-quality",
          admission.baselineContentFingerprint,
          "selection",
          "governing",
          admission.governingInputs,
          admission.executionPlans,
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
    return when (definitionId) {
      SkeletonDefinition.GOAL_BUILD.id -> progress.build
      SkeletonDefinition.GOAL_QA.id -> progress.qa
      else -> progress.validation
    }
  }

  override fun persistGoalQualityPhaseProgress(write: GoalQualityPhaseProgressWrite): Boolean {
    val parentWorkflowId = write.parentWorkflowId
    val invocationId = write.invocationId
    val definitionId = write.definitionId
    val acceptedPlan = write.acceptedPlan
    val progress = write.progress
    val finalValidationContentFingerprint = write.finalValidationContentFingerprint

    val record = requireNotNull(records[parentWorkflowId])
    val execution = requireNotNull(record.execution)
    if (execution.invocationId != invocationId) return false
    val prior = requireNotNull(execution.progress)
    val next =
      when (definitionId) {
        SkeletonDefinition.GOAL_BUILD.id -> prior.copy(build = progress)
        SkeletonDefinition.GOAL_QA.id -> prior.copy(qa = progress)
        else -> prior.copy(validation = progress)
      }
    records[parentWorkflowId] =
      record.copy(
        execution =
          execution.copy(
            progress = next,
            finalValidationContentFingerprint =
              finalValidationContentFingerprint ?: execution.finalValidationContentFingerprint,
            preQaBuildContentFingerprint =
              write.preQaBuildContentFingerprint ?: execution.preQaBuildContentFingerprint,
          ),
      )
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
