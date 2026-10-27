package skillbill.goalrunner.assessment.model

data class GoalAssessmentRoundDecision(
  val fix: Boolean,
  val exit: GoalAssessmentExitReason?,
)
