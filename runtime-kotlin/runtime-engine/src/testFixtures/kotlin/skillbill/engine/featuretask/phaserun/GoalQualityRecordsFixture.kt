package skillbill.engine.featuretask.phaserun

import skillbill.goalrunner.model.quality.GoalQualityPhaseProgress
import java.time.Clock

fun goalQualityRecordsForTest(
  request: PhaseRunRequest,
  clock: Clock,
  workflowId: String,
): InMemoryPhaseRunRecords =
  InMemoryPhaseRunRecords(
    clock,
    null,
    goalQualityPhaseProgress = request.goalQualityPhaseProgress,
    persistGoalQualityPhaseProgress = request.persistGoalQualityPhaseProgress,
    goalQualityWorkflowId = workflowId,
    qaSourceWitness = request.acceptedGoalQualityQaSourceWitness,
  )

fun InMemoryPhaseRunRecords.qualityProgressForTest(): GoalQualityPhaseProgress = goalQualityProgress()
