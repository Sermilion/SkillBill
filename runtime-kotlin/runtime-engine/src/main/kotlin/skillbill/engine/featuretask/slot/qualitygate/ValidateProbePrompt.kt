package skillbill.engine.featuretask.slot.qualitygate

import skillbill.engine.featuretask.model.audit.ValidateProbeScope
import skillbill.workflow.taskruntime.model.validation.ValidationTestProbeParser
import skillbill.workflow.taskruntime.model.validation.ValidationTestProbeShape

internal object ValidateProbePrompt {
  private const val NONE = "none"

  const val INSTRUCTIONS: String =
    "Test probes: after every required check passes, probe at most ${ValidationTestProbeShape.MAX_PROBES} " +
      "tests this subtask added or changed: first tests covering a listed broken-then-repaired scenario, then " +
      "one test per listed required criterion, then the remaining changed tests in diff order. For each test, " +
      "name the production line or branch it guards. Copy the current checkout, uncommitted changes included, " +
      "to a new directory under ${'$'}TMPDIR, apply there the smallest change that removes " +
      "that behavior, run only that test, then delete the copy; never mutate the workflow checkout. Record " +
      "each probe as killed when the test fails, survived when it still passes, or not_run, with a reason. " +
      "Strengthen a survived test inside this existing repair session, for example by asserting the specific " +
      "outcome instead of only success, driving the named entry point instead of an internal helper, or " +
      "keeping setup alive through the assertion, then re-probe it once and report repaired yes only when " +
      "that re-probe kills it. Never change production code to make a probe pass, and never weaken an " +
      "assertion. Every listed repaired scenario needs one probed test that drives its entry point; write " +
      "one when none exists. When no copy can be made or no targeted test command exists, record every " +
      "probe as not_run with the reason and finish on the normal checks; when no test was added or changed, " +
      "record one not_run probe that says so."

  const val VALUE_CONTENT: String =
    "Report each probe in ordinary prose and end with exactly one test probe line per probe. Survivors are " +
      "reported, not blocking. ${ValidationTestProbeParser.LINE_GRAMMAR}"

  fun directive(scope: ValidateProbeScope): String =
    "$INSTRUCTIONS\n\nBroken-then-repaired scenarios: ${scenarios(scope)}\n" +
      "Required criteria: ${criteria(scope)}\n${ValidationTestProbeParser.LINE_GRAMMAR}"

  private fun scenarios(scope: ValidateProbeScope): String =
    scope.repairedScenarios
      .joinToString("; ") { "${it.scenarioRef} (entry point ${it.entryPoint}; outcome ${it.outcome})" }
      .ifEmpty { NONE }

  private fun criteria(scope: ValidateProbeScope): String =
    scope.requiredCriteria.joinToString("; ") { "${it.criterionRef}: ${it.text}" }.ifEmpty { NONE }
}
