package skillbill.engine.goalrunner.persist.assessment

import skillbill.application.diagnostics.RejectedOutputDiagnosticService
import skillbill.application.diagnostics.model.RejectedOutputDiagnosticRequest
import skillbill.contracts.JsonCodec
import skillbill.contracts.workflow.featuretask.GOAL_ASSESSMENT_RECORD_PREVIOUS_CONTRACT_VERSION
import skillbill.engine.featuretask.model.review.FeatureTaskRuntimeRejectedOutputWrite
import skillbill.engine.featuretask.phase.record.FeatureTaskRuntimePhaseRecorder
import skillbill.engine.featuretask.phase.record.nextQuarantineRegenerationAttempt
import skillbill.engine.featuretask.phase.record.quarantineEntryPatch
import skillbill.engine.goalrunner.persist.GoalRunnerAssessmentRecordStore
import skillbill.engine.goalrunner.persist.saveRunnerArtifactPatch
import skillbill.engine.workflow.support.workflowFamilyFor
import skillbill.error.core.SkillBillRuntimeException
import skillbill.error.shellcontent.FeatureTaskRuntimeFailureCode
import skillbill.goalrunner.assessment.GoalAssessmentLoopRules
import skillbill.goalrunner.assessment.model.GoalAssessmentExitReason
import skillbill.goalrunner.assessment.model.GoalAssessmentRecord
import skillbill.goalrunner.assessment.model.GoalAssessmentRole
import skillbill.goalrunner.assessment.model.GoalAssessmentRunStatus
import skillbill.ports.db.DatabaseSessionFactory
import skillbill.ports.taskruntime.FeatureTaskRuntimeWireArtifactValidator
import skillbill.workflow.engine.WorkflowEngine
import skillbill.workflow.engine.model.DurableWorkflowArtifactFamily
import skillbill.workflow.taskruntime.artifact.asGoalAssessmentRecordWireMap
import skillbill.workflow.taskruntime.artifact.toGoalAssessmentRecord
import skillbill.workflow.taskruntime.model.audit.FeatureTaskRuntimeQuarantineEntry
import skillbill.workflow.taskruntime.model.audit.QUARANTINE_REJECTION_CLASS_ACCEPTANCE_REPAIR_VERSION
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimeWireArtifactKind
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimeWorkflowArtifactMap
import skillbill.workflow.taskruntime.phase.task.FeatureTaskRuntimePhaseWorkflowDefinition
import skillbill.contracts.workflow.featuretask.GoalAssessmentRecordPayloadKeys as Keys

internal class WorkflowGoalRunnerAssessmentRecording(
  private val database: DatabaseSessionFactory,
  private val engine: WorkflowEngine,
  private val wireArtifactValidator: FeatureTaskRuntimeWireArtifactValidator,
  private val recorder: FeatureTaskRuntimePhaseRecorder,
) : GoalRunnerAssessmentRecordStore {
  override fun readGoalAssessmentRecord(parentWorkflowId: String): GoalAssessmentRecordRead =
    database.read { unitOfWork ->
      val family = workflowFamilyFor(unitOfWork.workflowStates, parentWorkflowId)
      val record = family?.let { unitOfWork.workflowStates.get(it, parentWorkflowId) }
      if (record == null || !ARTIFACT.contains(record.artifacts)) {
        GoalAssessmentRecordRead.Absent
      } else {
        decode(ARTIFACT.value(record.artifacts))
      }
    }

  override fun prepareGoalAssessmentRecord(parentWorkflowId: String): GoalAssessmentRecordRead {
    val read = readGoalAssessmentRecord(parentWorkflowId)
    if (read !is GoalAssessmentRecordRead.Corrupt) return read
    val legacy =
      database.read { unit ->
        val family = workflowFamilyFor(unit.workflowStates, parentWorkflowId) ?: return@read null
        unit.workflowStates.get(family, parentWorkflowId)?.let { ARTIFACT.value(it.artifacts) }
      }
    val wire = FeatureTaskRuntimeWorkflowArtifactMap.from(legacy)
    if (wire[Keys.CONTRACT_VERSION] != GOAL_ASSESSMENT_RECORD_PREVIOUS_CONTRACT_VERSION) return read
    val bytes = JsonCodec.mapToJsonString(wire).encodeToByteArray()
    val phase = FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_GOAL_ASSESSMENT
    val reason =
      "Goal assessment now uses focused repair accounting. " +
        "The previous round record requires fresh discovery."
    val diagnostic =
      recorder.recordRejectedOutput(
        RejectedOutputDiagnosticRequest(
          workflowId = parentWorkflowId,
          phaseId = phase,
          attempt = 1,
          rule = QUARANTINE_REJECTION_CLASS_ACCEPTANCE_REPAIR_VERSION,
          path = "/${Keys.CONTRACT_VERSION}",
          reason = reason,
          agentId = "runtime",
          model = "runtime",
          rawResponse = bytes,
        ),
      )
    return database.transaction { unit ->
      val family = workflowFamilyFor(unit.workflowStates, parentWorkflowId) ?: return@transaction read
      val parent = unit.workflowStates.get(family, parentWorkflowId) ?: return@transaction read
      if (ARTIFACT.value(parent.artifacts) != legacy) return@transaction read
      val sha = RejectedOutputDiagnosticService.sha256(bytes)
      val entry =
        FeatureTaskRuntimeQuarantineEntry(
          producingPhaseId = phase,
          consumingPhaseId = phase,
          producingIteration = 1,
          rejectionClass = QUARANTINE_REJECTION_CLASS_ACCEPTANCE_REPAIR_VERSION,
          rejectionDetail = reason,
          regenerationAttempt =
            nextQuarantineRegenerationAttempt(
              parent.artifacts,
              wireArtifactValidator,
              phase,
              1,
              QUARANTINE_REJECTION_CLASS_ACCEPTANCE_REPAIR_VERSION,
            ),
          quarantinedAtIteration = 1,
          diagnosticIdentity = (diagnostic as? FeatureTaskRuntimeRejectedOutputWrite.Written)?.identity,
          rejectedRecordByteSize = bytes.size.toLong(),
          rejectedRecordSha256 = sha,
          diagnosticDegraded = diagnostic !is FeatureTaskRuntimeRejectedOutputWrite.Written,
        )
      val quarantine = quarantineEntryPatch(parent.artifacts, entry, wireArtifactValidator) ?: return@transaction read
      val issueKey = wire[Keys.ISSUE_KEY] as? String ?: return@transaction read
      val regenerated = GoalAssessmentRecord(issueKey)
      val regeneratedWire = regenerated.asGoalAssessmentRecordWireMap()
      wireArtifactValidator.validate(KIND, regeneratedWire, ARTIFACT.label())
      engine.saveRunnerArtifactPatch(unit.workflowStates, family, parent, quarantine + ARTIFACT.entry(regeneratedWire))
      GoalAssessmentRecordRead.Recorded(regenerated)
    }
  }

  override fun recordGoalAssessmentRecord(
    parentWorkflowId: String,
    record: GoalAssessmentRecord,
  ): Boolean {
    val wire = record.asGoalAssessmentRecordWireMap()
    wireArtifactValidator.validate(KIND, wire, ARTIFACT.label())
    return database.transaction { unitOfWork ->
      val family = workflowFamilyFor(unitOfWork.workflowStates, parentWorkflowId) ?: return@transaction false
      val parent = unitOfWork.workflowStates.get(family, parentWorkflowId) ?: return@transaction false
      engine.saveRunnerArtifactPatch(unitOfWork.workflowStates, family, parent, mapOf(ARTIFACT.entry(wire)))
      true
    }
  }

  override fun reopenGoalAssessmentForOperatorResume(parentWorkflowId: String): Boolean =
    database.transaction { unit ->
      val family = workflowFamilyFor(unit.workflowStates, parentWorkflowId) ?: return@transaction false
      val parent = unit.workflowStates.get(family, parentWorkflowId) ?: return@transaction false
      if (unit.goalRunnerControls.controlState(parentWorkflowId).executionLease != null) return@transaction false
      val stored =
        decode(ARTIFACT.value(parent.artifacts)) as? GoalAssessmentRecordRead.Recorded
          ?: return@transaction false
      val old = stored.record
      if (old.exitReason == null || old.exitReason == GoalAssessmentExitReason.CONVERGED ||
        old.exitReason == GoalAssessmentExitReason.SKIPPED_NO_CHANGES
      ) {
        return@transaction false
      }
      val reset = GoalAssessmentLoopRules.resetBudgetForOperatorResume(old)
      val rounds =
        reset.rounds.mapIndexed { index, round ->
          if (index == reset.rounds.lastIndex && old.exitReason == GoalAssessmentExitReason.ASSESSMENT_UNAVAILABLE) {
            round.copy(
              assessorRuns =
                round.assessorRuns.filter {
                  it.status == GoalAssessmentRunStatus.COMPLETED && it.role != GoalAssessmentRole.VERIFIER
                },
            )
          } else {
            round
          }
        }
      val resumed = reset.copy(exitReason = null, rounds = rounds)
      val wire = resumed.asGoalAssessmentRecordWireMap()
      wireArtifactValidator.validate(KIND, wire, ARTIFACT.label())
      engine.saveRunnerArtifactPatch(unit.workflowStates, family, parent, mapOf(ARTIFACT.entry(wire)))
      true
    }

  private fun decode(stored: Any?): GoalAssessmentRecordRead =
    try {
      val wire = FeatureTaskRuntimeWorkflowArtifactMap.from(stored)
      wireArtifactValidator.validate(KIND, wire, ARTIFACT.label())
      GoalAssessmentRecordRead.Recorded(wire.toGoalAssessmentRecord(ARTIFACT.label()))
    } catch (failure: SkillBillRuntimeException) {
      if (failure.code != FeatureTaskRuntimeFailureCode.INVALID_GOAL_ASSESSMENT_RECORD) {
        throw failure
      }
      GoalAssessmentRecordRead.Corrupt(failure.message.orEmpty())
    }

  private companion object {
    val ARTIFACT = DurableWorkflowArtifactFamily.GOAL_ASSESSMENT_RECORD
    val KIND = FeatureTaskRuntimeWireArtifactKind.GOAL_ASSESSMENT_RECORD
  }
}
