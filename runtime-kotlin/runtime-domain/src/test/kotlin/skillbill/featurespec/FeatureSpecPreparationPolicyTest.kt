package skillbill.featurespec

import skillbill.error.core.InvalidFeatureSpecPreparationRequestError
import skillbill.featurespec.model.FeatureSpecPreparationIntake
import skillbill.featurespec.model.FeatureSpecPreparationMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class FeatureSpecPreparationPolicyTest {
  @Test
  fun `prepare loud-fails when issue key is missing`() {
    val error =
      assertFailsWith<InvalidFeatureSpecPreparationRequestError> {
        FeatureSpecPreparationPolicy.prepare(validIntake().copy(issueKey = " "))
      }

    assertEquals("issue_key", error.fieldPath)
  }

  @Test
  fun `prepare loud-fails when acceptance criteria are missing`() {
    val error =
      assertFailsWith<InvalidFeatureSpecPreparationRequestError> {
        FeatureSpecPreparationPolicy.prepare(validIntake().copy(acceptanceCriteria = emptyList()))
      }

    assertEquals("acceptance_criteria", error.fieldPath)
  }

  @Test
  fun `prepare loud-fails when constraints are missing`() {
    val error =
      assertFailsWith<InvalidFeatureSpecPreparationRequestError> {
        FeatureSpecPreparationPolicy.prepare(validIntake().copy(constraints = emptyList()))
      }

    assertEquals("constraints", error.fieldPath)
  }

  @Test
  fun `prepare classifies explicit single_spec mode`() {
    val decision =
      FeatureSpecPreparationPolicy.prepare(
        validIntake().copy(intendedOutcome = "single_spec"),
      )

    assertEquals(FeatureSpecPreparationMode.SINGLE_SPEC, decision.mode)
  }

  @Test
  fun `prepare classifies explicit decomposed mode`() {
    val decision =
      FeatureSpecPreparationPolicy.prepare(
        validIntake().copy(intendedOutcome = "decomposed"),
      )

    assertEquals(FeatureSpecPreparationMode.DECOMPOSED, decision.mode)
  }

  @Test
  fun `six criteria require decomposition even when intake requests single_spec`() {
    val criteria = (1..6).map { "Production outcome $it." }
    val intake = validIntake().copy(intendedOutcome = "single_spec", acceptanceCriteria = criteria)

    val decision = FeatureSpecPreparationPolicy.prepare(intake)

    assertEquals(FeatureSpecPreparationMode.DECOMPOSED, decision.mode)
    assertEquals(criteria, decision.acceptanceCriteria)
    assertEquals(
      FeatureSpecPreparationMode.SINGLE_SPEC,
      FeatureSpecPreparationPolicy.prepare(intake.copy(acceptanceCriteria = criteria.take(5))).mode,
    )
  }

  @Test
  fun `prepare classifies decomposed when intent signals resumable subtasks`() {
    val decision =
      FeatureSpecPreparationPolicy.prepare(
        validIntake().copy(
          intendedOutcome = "Prepare multiple resumable subtasks for parallel implementation.",
        ),
      )

    assertEquals(FeatureSpecPreparationMode.DECOMPOSED, decision.mode)
  }

  private fun validIntake(): FeatureSpecPreparationIntake =
    FeatureSpecPreparationIntake(
      issueKey = "SKILL-59",
      intendedOutcome = "Prepare one implementation-ready spec.",
      acceptanceCriteria = listOf("AC1"),
      constraints = listOf("Keep runtime layering intact."),
      nonGoals = listOf("No platform-pack changes."),
    )
}
