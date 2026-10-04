package skillbill.cli.goal.run

import skillbill.engine.goalrunner.model.GoalIntakeAdmission
import skillbill.engine.goalrunner.model.GoalIntakeMissingInput

fun goalIntakeRequestText(request: GoalIntakeAdmission.NeedsInput): String {
  val subject = request.issueKey?.let { "To start new work on $it" } ?: "To start new work"
  val ask =
    when (request.missing) {
      GoalIntakeMissingInput.ISSUE_KEY ->
        "add a tracker issue key or link before the requirements. " +
          "Skill Bill does not assign a local workflow identity."
      GoalIntakeMissingInput.REQUIREMENTS -> "add the requirements after the tracker issue key or link."
      GoalIntakeMissingInput.DESCRIPTION -> "add a short description after the tracker issue key."
    }
  return "$subject, $ask Nothing was started.\n"
}
