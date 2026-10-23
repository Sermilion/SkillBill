package skillbill.engine.goalrunner.model

/** Outcome of reading goal intake before launch: a goal to run, or the input still needed to start new work. */
sealed interface GoalIntakeAdmission {
  /** Intake names an existing goal or spec, or carries everything new work needs. */
  data class Admitted(val issueKey: String) : GoalIntakeAdmission

  /**
   * Intake resolves to an active spec whose bundle also sits under the `done/` archive of the project store
   * `specs/` root (`skill-bill project path` prints the store) or of a legacy `.feature-specs/` directory, so the
   * work already finished and [activeSpecPath] is a stale copy; both paths are store-relative or
   * repository-relative.
   */
  data class ArchivedSpec(
    val issueKey: String,
    val activeSpecPath: String,
    val archivedSpecPath: String,
  ) : GoalIntakeAdmission

  /** New work cannot start until the operator supplies [missing]; [issueKey] is null when no key was found. */
  data class NeedsInput(
    val missing: GoalIntakeMissingInput,
    val issueKey: String?,
  ) : GoalIntakeAdmission
}

enum class GoalIntakeMissingInput {
  ISSUE_KEY,
  REQUIREMENTS,
  DESCRIPTION,
}
