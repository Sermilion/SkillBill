package skillbill.engine.goalrunner.persist.assessment

import skillbill.engine.goalrunner.persist.saveRunnerArtifactPatch
import skillbill.engine.workflow.support.workflowFamilyFor
import skillbill.error.core.SkillBillRuntimeException
import skillbill.error.shellcontent.FeatureTaskRuntimeFailureCode
import skillbill.goalrunner.assessment.model.GoalAssessmentRecord
import skillbill.ports.db.DatabaseSessionFactory
import skillbill.ports.taskruntime.FeatureTaskRuntimeWireArtifactValidator
import skillbill.workflow.engine.WorkflowEngine
import skillbill.workflow.engine.model.DurableWorkflowArtifactFamily
import skillbill.workflow.taskruntime.artifact.asGoalAssessmentRecordWireMap
import skillbill.workflow.taskruntime.artifact.toGoalAssessmentRecord
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimeWireArtifactKind
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimeWorkflowArtifactMap

internal class WorkflowGoalRunnerAssessmentRecording(
  private val database: DatabaseSessionFactory,
  private val engine: WorkflowEngine,
  private val wireArtifactValidator: FeatureTaskRuntimeWireArtifactValidator,
) {
  fun read(parentWorkflowId: String): GoalAssessmentRecordRead =
    database.read { unitOfWork ->
      val family = workflowFamilyFor(unitOfWork.workflowStates, parentWorkflowId)
      val record = family?.let { unitOfWork.workflowStates.get(it, parentWorkflowId) }
      if (record == null || !ARTIFACT.contains(record.artifacts)) {
        GoalAssessmentRecordRead.Absent
      } else {
        decode(ARTIFACT.value(record.artifacts))
      }
    }

  fun record(
    parentWorkflowId: String,
    assessment: GoalAssessmentRecord,
  ): Boolean {
    val wire = assessment.asGoalAssessmentRecordWireMap()
    wireArtifactValidator.validate(KIND, wire, ARTIFACT.label())
    return database.transaction { unitOfWork ->
      val family = workflowFamilyFor(unitOfWork.workflowStates, parentWorkflowId) ?: return@transaction false
      val record = unitOfWork.workflowStates.get(family, parentWorkflowId) ?: return@transaction false
      engine.saveRunnerArtifactPatch(unitOfWork.workflowStates, family, record, mapOf(ARTIFACT.entry(wire)))
      true
    }
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
