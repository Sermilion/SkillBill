package skillbill.engine.goalrunner.status

import skillbill.error.core.catchingNonCancellation
import skillbill.goalrunner.model.GoalRunnerPhaseCard
import skillbill.goalrunner.model.GoalRunnerPlanRecord
import skillbill.workflow.engine.model.DurableWorkflowArtifacts
import skillbill.workflow.taskruntime.artifact.goalPlanningImport
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimePhaseRecord

internal fun attachPlanningRecord(
  cards: List<GoalRunnerPhaseCard>,
  artifacts: DurableWorkflowArtifacts,
  records: Map<String, FeatureTaskRuntimePhaseRecord>,
  degradation: SubtaskReadDegradation,
): List<GoalRunnerPhaseCard> {
  val imported =
    catchingNonCancellation { artifacts.goalPlanningImport() }.getOrElse { error ->
      degradation.record(GoalRunnerSubtaskReadSource.PHASE_CARD_OUTPUT, "planning_import", "omitted", error)
      null
    } ?: return cards
  return cards.map { card ->
    if (card.phaseId != "plan") {
      card
    } else {
      card.copy(
        output =
          (card.output ?: phaseCardOutput(emptyMap(), card.phaseId)).copy(
            planRecord =
              GoalRunnerPlanRecord(
                imported.manifestOrder,
                imported.governedSubSpecPath,
                imported.subSpecHash,
                imported.planPayloadSha256,
                records[card.phaseId]?.repairEvidence?.let { "${it.format.wireValue} · ${it.operation.wireValue}" },
              ),
          ),
      )
    }
  }
}
