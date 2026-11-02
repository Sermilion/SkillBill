package skillbill.engine.goalrunner.intake

import me.tatarka.inject.annotations.Inject
import skillbill.engine.goal.GoalNewWorkAdmission
import skillbill.engine.goal.GoalNewWorkAdmissionPort
import skillbill.engine.goalrunner.manifest.GoalRunnerManifestStore
import skillbill.engine.goalrunner.model.GoalIntakeAdmission
import skillbill.engine.goalrunner.model.GoalIntakeMissingInput
import skillbill.error.core.InvalidFeatureSpecPreparationRequestError
import java.nio.file.Path

@Inject
public class GoalIntakeAdmissionService(
  private val manifestStore: GoalRunnerManifestStore,
  internal val intakePreparation: GoalIntakePreparation,
) : GoalNewWorkAdmissionPort {
  override fun admit(
    intake: String,
    repoRoot: Path,
  ): GoalNewWorkAdmission =
    try {
      when (val result = admission(intake, repoRoot)) {
        is GoalIntakeAdmission.Admitted -> GoalNewWorkAdmission.Admitted(result.issueKey)
        is GoalIntakeAdmission.NeedsInput ->
          when (result.missing) {
            GoalIntakeMissingInput.ISSUE_KEY -> GoalNewWorkAdmission.NoIssueKey
            GoalIntakeMissingInput.REQUIREMENTS ->
              GoalNewWorkAdmission.Refused(result.issueKey, "Add what ${result.issueKey} should do, then start again.")
            GoalIntakeMissingInput.DESCRIPTION ->
              GoalNewWorkAdmission.Refused(
                result.issueKey,
                "Add a short description with English letters or digits after ${result.issueKey}, then start again.",
              )
          }
      }
    } catch (failure: InvalidFeatureSpecPreparationRequestError) {
      GoalNewWorkAdmission.Refused(null, failure.reason)
    }

  public fun admission(
    intake: String,
    repoRoot: Path,
  ): GoalIntakeAdmission {
    val trimmed = intake.trim()
    existingSpecAdmission(trimmed, repoRoot)?.let { return it }
    if (trimmed.isNotBlank() && trimmed.none(Char::isWhitespace) && !trimmed.contains('/')) {
      manifestStore.readByIssueKeyIfPresent(trimmed, repoRoot)?.let {
        return GoalIntakeAdmission.Admitted(it.manifest.issueKey)
      }
    }
    val parsed =
      GoalIntake.parseOrNull(trimmed)
        ?: return GoalIntakeAdmission.NeedsInput(GoalIntakeMissingInput.ISSUE_KEY, issueKey = null)
    val missing =
      if (manifestStore.readByIssueKeyIfPresent(parsed.issueKey, repoRoot) == null) {
        intakePreparation.missingNewWorkInput(parsed, repoRoot)
      } else {
        null
      }
    return missing?.let { GoalIntakeAdmission.NeedsInput(it, parsed.issueKey) }
      ?: GoalIntakeAdmission.Admitted(parsed.issueKey)
  }

  private fun existingSpecAdmission(
    intake: String,
    repoRoot: Path,
  ): GoalIntakeAdmission? =
    intakePreparation.issueKeyForExistingSpec(intake, repoRoot)?.let(GoalIntakeAdmission::Admitted)
}
