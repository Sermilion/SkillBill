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

fun goalArchivedSpecText(archived: GoalIntakeAdmission.ArchivedSpec): String {
  val staleBundle = archived.activeSpecPath.substringBeforeLast('/')
  return "goal ${archived.issueKey}: already complete — its spec is archived at ${archived.archivedSpecPath}; " +
    "nothing to run.\n" +
    "${archived.activeSpecPath} is a stale copy. Remove it: git rm -r $staleBundle\n"
}
