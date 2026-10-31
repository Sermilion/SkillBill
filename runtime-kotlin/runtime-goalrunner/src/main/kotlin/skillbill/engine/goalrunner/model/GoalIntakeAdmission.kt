package skillbill.engine.goalrunner.model

/** Outcome of reading goal intake before launch: a goal to run, or the input still needed to start new work. */
public sealed interface GoalIntakeAdmission {
  /** Intake names an existing goal or spec, or carries everything new work needs. */
  public data class Admitted(public val issueKey: String) : GoalIntakeAdmission

  /** New work cannot start until the operator supplies [missing]; [issueKey] is null when no key was found. */
  public data class NeedsInput(
    public val missing: GoalIntakeMissingInput,
    public val issueKey: String?,
  ) : GoalIntakeAdmission
}

public enum class GoalIntakeMissingInput {
  ISSUE_KEY,
  REQUIREMENTS,
  DESCRIPTION,
}
