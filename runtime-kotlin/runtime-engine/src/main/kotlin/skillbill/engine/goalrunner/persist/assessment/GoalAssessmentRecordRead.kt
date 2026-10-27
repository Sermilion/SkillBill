package skillbill.engine.goalrunner.persist.assessment

import skillbill.goalrunner.assessment.model.GoalAssessmentRecord

sealed interface GoalAssessmentRecordRead {
  data object Absent : GoalAssessmentRecordRead

  data class Recorded(
    val record: GoalAssessmentRecord,
  ) : GoalAssessmentRecordRead

  data class Corrupt(
    val reason: String,
  ) : GoalAssessmentRecordRead
}
