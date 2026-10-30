package skillbill.engine.goalrunner.assessment

import skillbill.engine.featuretask.phase.prompt.directives.MATERIALITY_RULE
import skillbill.review.spec.model.InteractionScenario
import skillbill.review.spec.model.TransitionalRegression

internal object GoalAssessmentPromptRenderer {
  fun render(
    bundle: GoalAssessmentBundle,
    focus: GoalAssessmentFocus,
  ): String =
    listOfNotNull(
      "# Goal assessment: ${bundle.issueKey}",
      "Head commit: `${bundle.headSha}`.",
      scopeSection(bundle.scopeFiles),
      "## Parent spec\n\n${bundle.parentSpec.trim()}",
      declaredSection(bundle),
      subtaskSection(bundle),
      roleSection(bundle, focus),
    ).joinToString("\n\n")

  private fun scopeSection(files: List<String>): String? {
    if (files.isEmpty()) return null
    val shown = files.take(MAX_SCOPE_FILES).joinToString("\n") { "- `$it`" }
    val omitted = files.size - MAX_SCOPE_FILES
    return "## Files changed by the goal\n\n$shown" + if (omitted > 0) "\n- ... and $omitted more." else ""
  }

  private fun declaredSection(bundle: GoalAssessmentBundle): String? {
    val declared = bundle.declared ?: return null
    val scenarios = declared.scenarios.joinToString("\n\n", transform = ::scenarioText)
    val regressions = declared.transitionalRegressions.joinToString("\n", transform = ::regressionText)
    return listOfNotNull(
      scenarios.takeIf(String::isNotBlank)?.let { "## Declared interaction scenarios\n\n$it" },
      regressions.takeIf(String::isNotBlank)?.let { "## Declared transitional regressions\n\n$it" },
    ).joinToString("\n\n").takeIf(String::isNotBlank)
  }

  private fun scenarioText(scenario: InteractionScenario): String =
    "- ${scenario.scenarioRef} (subtasks ${scenario.subtaskRefs.joinToString(", ")}): " +
      "entry point ${scenario.entryPoint} Preconditions: ${scenario.preconditions} " +
      "Steps: ${scenario.steps} Outcome: ${scenario.outcome}"

  private fun regressionText(regression: TransitionalRegression): String =
    "- ${regression.regressionRef}: ${regression.behavior} Disabled by subtask ${regression.disabledBySubtask}; " +
      "restored by subtask ${regression.restoredBySubtask}."

  private fun subtaskSection(bundle: GoalAssessmentBundle): String =
    "## Subtasks\n\n" +
      bundle.subtasks.joinToString("\n\n") { subtask ->
        "### Subtask ${subtask.id}: ${subtask.name}\n\n${rangeText(subtask.range)}\n\n" +
          (subtask.specText?.trim() ?: "The spec of this subtask could not be read.")
      }

  private fun rangeText(range: GoalAssessmentCommitRange): String =
    when (range) {
      is GoalAssessmentCommitRange.Committed ->
        "Commit range: `${range.fromSha}..${range.toSha}`. Use it with `git diff` and `git log` to see what " +
          "this subtask changed; judge the result at the head commit."
      GoalAssessmentCommitRange.NoCommit -> "This subtask produced no commit; there is no change to assess."
      is GoalAssessmentCommitRange.Unavailable -> "Commit range unavailable: ${range.reason}."
    }

  private fun roleSection(
    bundle: GoalAssessmentBundle,
    focus: GoalAssessmentFocus,
  ): String =
    "## Your role\n\n" +
      when (focus) {
        is GoalAssessmentFocus.Subtask -> subtaskRole(bundle, focus.subtaskId) + reassessmentText(bundle)
        GoalAssessmentFocus.CrossSubtask -> CROSS_SUBTASK_ROLE + reassessmentText(bundle)
        is GoalAssessmentFocus.Verifier -> verifierRole(focus.candidates)
      }

  private fun reassessmentText(bundle: GoalAssessmentBundle): String =
    bundle.reassessmentScope?.let { files ->
      " This is a re-assessment after a fix round: inspect only these files the fix changed, and report only " +
        "defects the fix introduced or left in them.\n\n" + files.joinToString("\n") { "- `$it`" }
    }.orEmpty()

  private fun subtaskRole(
    bundle: GoalAssessmentBundle,
    subtaskId: Int,
  ): String {
    val name = bundle.subtasks.firstOrNull { it.id == subtaskId }?.name.orEmpty()
    return "Assess subtask $subtaskId ($name): judge each of its criteria against its spec and the parent spec " +
      "in the code at the head commit, not at its own commit. Use its commit range to find what it changed. " +
      "Report defects in the code it touched as it stands at the head commit, and tests it added that would " +
      "still pass if the behavior they name broke. Report only material defects. $MATERIALITY_RULE " +
      "Answer with `Finding:` and `Test value:` lines only; write `Finding: none` when you find no defect."
  }

  private fun verifierRole(candidates: List<GoalAssessmentVerifierCandidate>): String =
    "Verify each candidate defect below against the code at the head commit. Try to refute it: re-derive the " +
      "failure scenario from the code and settle it as confirmed or refuted with file and line evidence. " +
      "Refute a candidate that is not material, citing what makes it unreachable or behavior-neutral. " +
      "$MATERIALITY_RULE " +
      "Answer with one `Verify <GA-NNN>:` line per candidate.\n\n" +
      candidates.joinToString("\n") { "- ${it.findingRef} ${it.path}:${it.line} - ${it.failureScenario}" }

  private const val MAX_SCOPE_FILES = 200

  private const val CROSS_SUBTASK_ROLE: String =
    "Assess how the subtasks fit together in the code at the head commit. Settle every declared interaction " +
      "scenario with a `Scenario <IS-NNN>:` line and every declared transitional regression with a " +
      "`Regression <TR-NNN>:` line. Report each broken scenario and each defect that only appears when " +
      "subtasks interact as a `Finding:` line. Report only material defects. $MATERIALITY_RULE " +
      "Write `Finding: none` when you find no such defect."
}
