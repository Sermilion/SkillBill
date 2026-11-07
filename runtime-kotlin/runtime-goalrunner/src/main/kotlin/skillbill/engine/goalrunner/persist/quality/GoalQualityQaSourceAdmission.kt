package skillbill.engine.goalrunner.persist.quality

import skillbill.goalrunner.model.quality.GoalQualityExecution
import skillbill.goalrunner.model.quality.matchesNestedQaSourceWitness
import skillbill.goalrunner.model.quality.validQaSourceWitnessIdentity
import skillbill.workflow.taskruntime.model.skeleton.SkeletonDefinition

internal fun validQaSourceWitnessWrite(
  write: GoalQualityPhaseProgressWrite,
  execution: GoalQualityExecution,
): Boolean {
  val existing = requireNotNull(execution.progress).qa
  val next = write.progress
  if (write.definitionId != SkeletonDefinition.GOAL_QA.id) return next.acceptedQaSourceWitnesses.isEmpty()
  if (next.acceptedQaSourceWitnesses.take(
      existing.acceptedQaSourceWitnesses.size,
    ) != existing.acceptedQaSourceWitnesses
  ) {
    return false
  }
  val introduced = next.acceptedQaSourceWitnesses.drop(existing.acceptedQaSourceWitnesses.size)
  val admission = write.preQaBuildSourceProof?.acceptedQaSourceWitness
  val duplicate = next.acceptedQaSourceWitnesses.distinct().size != next.acceptedQaSourceWitnesses.size
  val missingAdmission = admission?.nestedProgressKey != null && admission !in next.acceptedQaSourceWitnesses
  if (duplicate || missingAdmission) return false
  return introduced.all { witness ->
    if (!next.validQaSourceWitnessIdentity(
        witness,
        "${execution.parentWorkflowId}:${execution.invocationId}",
        write.acceptedPlan,
      )
    ) {
      false
    } else {
      val repair = next.buildGateProgress?.acceptedRepairOutputs?.singleOrNull { it.repairTurn == witness.repairTurn }
      val newRepair = repair != null && repair !in existing.buildGateProgress?.acceptedRepairOutputs.orEmpty()
      val newRun =
        next.buildGateProgress?.gateRuns?.lastOrNull()?.let {
          it != existing.buildGateProgress?.gateRuns?.lastOrNull()
        } == true
      witness == admission && existing.matchesNestedQaSourceWitness(witness) ||
        (newRepair || newRun) && next.matchesNestedQaSourceWitness(witness, allowPendingRepair = newRepair)
    }
  }
}
