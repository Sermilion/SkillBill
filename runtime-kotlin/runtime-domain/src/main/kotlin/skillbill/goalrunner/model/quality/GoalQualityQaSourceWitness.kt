package skillbill.goalrunner.model.quality

import skillbill.workflow.model.WorkflowStepStatus
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimePhaseLedgerAction
import skillbill.workflow.taskruntime.model.skeleton.SkeletonDefinition
import skillbill.workflow.taskruntime.model.validation.FeatureTaskRuntimeValidationGateProgress
import java.security.MessageDigest

data class GoalQualityQaSourceWitness(
  val admittedQaPlan: String,
  val phaseId: String,
  val attempt: Int,
  val completionSequenceNumber: Int,
  val acceptedOutputFingerprint: String,
  val nestedProgressKey: String? = null,
  val repairTurn: Int? = null,
  val repositoryCheckpoint: String? = null,
  val contentFingerprint: String? = null,
  val progressRevision: Long = 0,
)

fun GoalQualityExecution.acceptedQaSourceWitness(
  contentFingerprint: String,
  repositoryCheckpoint: String? = null,
): GoalQualityQaSourceWitness? {
  val plan = executionPlans?.qa ?: return null
  val qaProgress = progress?.qa ?: return null
  return qaProgress.acceptedSourceObservations
    .asSequence()
    .mapNotNull { observation ->
      val outputFingerprint = observation.acceptedOutputFingerprint ?: return@mapNotNull null
      if (observation.contentFingerprint != contentFingerprint ||
        observation.phaseId !in SkeletonDefinition.GOAL_QA.stepIds
      ) {
        return@mapNotNull null
      }
      GoalQualityQaSourceWitness(
        plan,
        observation.phaseId,
        observation.attempt,
        observation.completionSequenceNumber,
        outputFingerprint,
      )
    }
    .filter { hasAcceptedQaSource(contentFingerprint, it) }
    .maxByOrNull { it.completionSequenceNumber }
    ?: qaProgress.acceptedQaSourceWitnesses.lastOrNull {
      it.contentFingerprint == contentFingerprint && hasAcceptedQaSource(contentFingerprint, it)
    }
    ?: qaProgress.nestedQaSourceWitness(
      plan,
      "$parentWorkflowId:$invocationId",
      contentFingerprint,
      repositoryCheckpoint,
    )
}

fun GoalQualityExecution.hasAcceptedQaSource(
  contentFingerprint: String,
  witness: GoalQualityQaSourceWitness,
): Boolean {
  if (contentFingerprint.isBlank() || executionPlans?.qa != witness.admittedQaPlan ||
    witness.phaseId !in SkeletonDefinition.GOAL_QA.stepIds
  ) {
    return false
  }
  val qaProgress = progress?.qa ?: return false
  return if (witness.nestedProgressKey != null) {
    witness.contentFingerprint == contentFingerprint &&
      qaProgress.validQaSourceWitnessIdentity(witness, "$parentWorkflowId:$invocationId", witness.admittedQaPlan) &&
      (witness in qaProgress.acceptedQaSourceWitnesses || qaProgress.matchesNestedQaSourceWitness(witness))
  } else {
    qaProgress.hasCompletedQaSource(contentFingerprint, witness)
  }
}

private fun GoalQualityPhaseProgress.hasCompletedQaSource(
  contentFingerprint: String,
  witness: GoalQualityQaSourceWitness,
): Boolean {
  val observation =
    this.acceptedSourceObservations.singleOrNull {
      it.phaseId == witness.phaseId && it.attempt == witness.attempt &&
        it.completionSequenceNumber == witness.completionSequenceNumber &&
        it.contentFingerprint == contentFingerprint &&
        it.acceptedOutputFingerprint == witness.acceptedOutputFingerprint
    } ?: return false
  val completion =
    this.phaseLedger.singleOrNull {
      it.phaseId == observation.phaseId && it.attemptCount == observation.attempt &&
        it.sequenceNumber == observation.completionSequenceNumber &&
        it.action == FeatureTaskRuntimePhaseLedgerAction.COMPLETE
    } ?: return false
  return completion.phaseId == witness.phaseId && this.acceptedOutputs.singleOrNull {
    it.phaseId == observation.phaseId && it.occurrence == observation.attempt &&
      sha256(it.output) == witness.acceptedOutputFingerprint
  } != null
}

private fun sha256(value: String): String =
  MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(Charsets.UTF_8))
    .joinToString("") { "%02x".format(it) }

fun GoalQualityPhaseProgress.nestedQaSourceWitness(
  admittedQaPlan: String,
  outcomeNamespace: String,
  contentFingerprint: String,
  repositoryCheckpoint: String?,
  allowPendingRepair: Boolean = false,
): GoalQualityQaSourceWitness? {
  val record =
    phaseRecords.values.singleOrNull {
      it.status == WorkflowStepStatus.RUNNING || it.status == WorkflowStepStatus.PAUSED
    } ?: return null
  val repair = buildGateProgress?.acceptedRepairOutputs?.lastOrNull() ?: return null
  val witness =
    GoalQualityQaSourceWitness(
      admittedQaPlan, record.phaseId, record.attemptCount, 0, sha256(repair.output),
      buildGateProgressKey, repair.repairTurn, repositoryCheckpoint, contentFingerprint, revision,
    )
  return witness.takeIf {
    validQaSourceWitnessIdentity(
      it,
      outcomeNamespace,
      admittedQaPlan,
    ) && matchesNestedQaSourceWitness(it, allowPendingRepair)
  }
}

fun GoalQualityPhaseProgress.validQaSourceWitnessIdentity(
  witness: GoalQualityQaSourceWitness,
  outcomeNamespace: String,
  admittedQaPlan: String,
): Boolean =
  witness.admittedQaPlan == admittedQaPlan && witness.phaseId in SkeletonDefinition.GOAL_QA.stepIds &&
    witness.attempt > 0 && witness.repairTurn?.let { it > 0 } == true &&
    !witness.contentFingerprint.isNullOrBlank() && !witness.repositoryCheckpoint.isNullOrBlank() &&
    witness.progressRevision in 0..revision &&
    witness.nestedProgressKey ==
    "$outcomeNamespace:${SkeletonDefinition.GOAL_QA.id}:${witness.phaseId}:${witness.attempt}:build" &&
    phaseLedger.any {
      it.phaseId == witness.phaseId && it.attemptCount == witness.attempt &&
        it.action == FeatureTaskRuntimePhaseLedgerAction.START
    }

fun GoalQualityPhaseProgress.matchesNestedQaSourceWitness(
  witness: GoalQualityQaSourceWitness,
  allowPendingRepair: Boolean = false,
): Boolean {
  val record = phaseRecords[witness.phaseId] ?: return false
  val starts =
    phaseLedger.filter {
      it.phaseId == witness.phaseId && it.attemptCount == witness.attempt &&
        it.action == FeatureTaskRuntimePhaseLedgerAction.START
    }
  if (starts.isEmpty() ||
    starts.any {
      it.resolvedAgentId != record.resolvedAgentId || it.executionOrigin != record.executionOrigin
    }
  ) {
    return false
  }
  val gate = buildGateProgress
  val repair =
    gate?.acceptedRepairOutputs?.singleOrNull {
      it.repairTurn == witness.repairTurn && sha256(it.output) == witness.acceptedOutputFingerprint
    } ?: return false
  return record.attemptCount == witness.attempt && buildGateProgressKey == witness.nestedProgressKey &&
    repair.output.isNotBlank() && gate.matchesQaSourceCheckpoint(witness.repositoryCheckpoint, allowPendingRepair)
}

private fun FeatureTaskRuntimeValidationGateProgress.matchesQaSourceCheckpoint(
  checkpoint: String?,
  allowPendingRepair: Boolean,
): Boolean {
  val run = gateRuns.lastOrNull()
  val pending = pendingExternalOperation != null || pendingExternalCheckpoint != null
  val verified =
    run?.let {
      it.repositoryCheckpoint == checkpoint && !it.legacyUnverified &&
        it.executedChecksRecorded && !it.command.isNullOrBlank()
    } == true
  return if (pending) allowPendingRepair && pendingExternalCheckpoint == checkpoint else verified
}
