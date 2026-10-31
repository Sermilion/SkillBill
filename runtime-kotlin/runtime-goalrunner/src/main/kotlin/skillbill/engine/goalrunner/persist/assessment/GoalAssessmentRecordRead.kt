package skillbill.engine.goalrunner.persist.assessment

import skillbill.goalrunner.assessment.model.GoalAssessmentRecord

public sealed interface GoalAssessmentRecordRead {
  public data object Absent : GoalAssessmentRecordRead

  public data class Recorded(
    public val record: GoalAssessmentRecord,
  ) : GoalAssessmentRecordRead

  public data class Corrupt(
    public val reason: String,
  ) : GoalAssessmentRecordRead
}
