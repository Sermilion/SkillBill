package skillbill.review.spec

import skillbill.review.spec.model.InteractionScenarioParseResult
import skillbill.review.spec.model.InteractionScenarioSpecContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class InteractionScenarioSectionParserTest {
  @Test
  fun `a parent spec yields a catalog with presence flags and cited subtasks`() {
    val spec =
      spec(
        scenarios(scenario(subtasks = "Subtasks: 1, 2. ")),
        regressions("- TR-001. Behavior: the legacy path is off. Disabled by: subtask 1. Restored by: subtask 2."),
      )

    val parsed =
      assertIs<InteractionScenarioParseResult.Parsed>(parse(spec, InteractionScenarioSpecContext.Parent(IDS)))

    assertTrue(parsed.scenariosPresent)
    assertTrue(parsed.regressionsPresent)
    val scenario = parsed.catalog.scenarios.single()
    assertEquals("IS-001", scenario.scenarioRef)
    assertEquals(listOf("AC-001", "AC-002"), scenario.criterionRefs)
    assertEquals(listOf(1, 2), scenario.subtaskRefs)
    val regression = parsed.catalog.transitionalRegressions.single()
    assertEquals(1 to 2, regression.disabledBySubtask to regression.restoredBySubtask)
  }

  @Test
  fun `a spec without either section parses empty with both flags unset`() {
    val parsed = assertIs<InteractionScenarioParseResult.Parsed>(parse(spec(), InteractionScenarioSpecContext.Subtask))

    assertFalse(parsed.scenariosPresent)
    assertFalse(parsed.regressionsPresent)
    assertTrue(parsed.catalog.scenarios.isEmpty())
  }

  @Test
  fun `a scenario citing a criterion absent from the spec is rejected with the reference named`() {
    val spec = spec(scenarios(scenario(criteria = "AC-001, AC-009")))

    val rejected =
      assertIs<InteractionScenarioParseResult.Rejected>(parse(spec, InteractionScenarioSpecContext.Subtask))

    val issue = rejected.issues.single()
    assertEquals("scenarios[0].criterion_refs", issue.fieldPath)
    assertTrue("AC-009" in issue.reason)
  }

  @Test
  fun `malformed scenarios and regressions are rejected with a field path per defect`() {
    val spec =
      spec(
        scenarios(
          "- IS-001. Criteria: AC-001. Subtasks: 1, 2. Entry point: a. Preconditions: b. Steps: c. Outcome: d.",
          "- IS-001. Criteria: AC-001, AC-002. Subtasks: 1, 2. Entry point: a. Preconditions: b. Steps: c.",
        ),
        regressions("- TR-001. Behavior: gone. Disabled by: subtask 2. Restored by: subtask 1."),
      )

    val rejected =
      assertIs<InteractionScenarioParseResult.Rejected>(parse(spec, InteractionScenarioSpecContext.Parent(IDS)))

    val paths = rejected.issues.map { it.fieldPath }
    assertTrue("scenarios[0].criterion_refs" in paths)
    assertTrue("scenarios[1]" in paths)
    assertTrue("scenarios[1].outcome" in paths)
    assertTrue("transitional_regressions[0].restored_by_subtask" in paths)
  }

  @Test
  fun `a subtask spec refuses a Subtasks field and transitional regressions`() {
    val spec =
      spec(
        scenarios(scenario(subtasks = "Subtasks: 1, 2. ")),
        regressions("- TR-001. Behavior: gone. Disabled by: subtask 1. Restored by: subtask 2."),
      )

    val rejected =
      assertIs<InteractionScenarioParseResult.Rejected>(parse(spec, InteractionScenarioSpecContext.Subtask))

    val paths = rejected.issues.map { it.fieldPath }
    assertTrue("scenarios[0].subtask_refs" in paths)
    assertTrue("transitional_regressions[0]" in paths)
  }

  @Test
  fun `a label after a double space is read and the other item type's labels stay in the value`() {
    val spec =
      spec(
        scenarios(
          "- IS-001. Criteria: AC-001, AC-002.  Entry point: the operator runs the command. " +
            "Preconditions: a prepared bundle exists. Steps: run it. Behavior: unchanged. Outcome: exits.",
        ),
      )

    val parsed = assertIs<InteractionScenarioParseResult.Parsed>(parse(spec, InteractionScenarioSpecContext.Subtask))

    val scenario = parsed.catalog.scenarios.single()
    assertEquals(listOf("AC-001", "AC-002"), scenario.criterionRefs)
    assertEquals("the operator runs the command.", scenario.entryPoint)
    assertEquals("run it. Behavior: unchanged.", scenario.steps)
    assertEquals("exits.", scenario.outcome)
  }

  private fun parse(
    spec: String,
    context: InteractionScenarioSpecContext,
  ) = InteractionScenarioSectionParser.parse(spec, DIGEST, context)

  private fun spec(vararg sections: String): String =
    (listOf("# Spec", "", "## Acceptance Criteria", "", "1. First outcome.", "2. Second outcome.", "") + sections)
      .joinToString("\n")

  private fun scenarios(vararg items: String): String =
    listOf("## Interaction Scenarios", "").plus(items).plus("").joinToString("\n")

  private fun regressions(vararg items: String): String =
    listOf("## Transitional Regressions", "").plus(items).plus("").joinToString("\n")

  private fun scenario(
    criteria: String = "AC-001, AC-002",
    subtasks: String = "",
  ): String =
    "- IS-001. Criteria: $criteria. ${subtasks}Entry point: the operator runs the command. " +
      "Preconditions: a prepared bundle exists. Steps: run it, then rerun it. Outcome: both runs agree."

  private companion object {
    const val DIGEST_LENGTH = 64
    val DIGEST = "a".repeat(DIGEST_LENGTH)
    val IDS = setOf(1, 2)
  }
}
