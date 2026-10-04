package skillbill.featurespec

import skillbill.error.core.FeatureSpecPreparationFailureCode
import skillbill.error.core.SkillBillRuntimeException
import skillbill.featurespec.model.FeatureSpecPreparationIntake
import skillbill.featurespec.model.FeatureSpecPreparationMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class FeatureSpecPreparationPolicyTest {
  @Test
  fun `prepare loud-fails when issue key is missing`() {
    val error =
      assertFailsWith<SkillBillRuntimeException> {
        FeatureSpecPreparationPolicy.prepare(validIntake().copy(issueKey = " "))
      }

    assertEquals(FeatureSpecPreparationFailureCode.INVALID_REQUEST, error.code)
    assertTrue(error.message.orEmpty().contains("'issue_key'"))
  }

  @Test
  fun `prepare loud-fails when acceptance criteria are missing`() {
    val error =
      assertFailsWith<SkillBillRuntimeException> {
        FeatureSpecPreparationPolicy.prepare(validIntake().copy(acceptanceCriteria = emptyList()))
      }

    assertEquals(FeatureSpecPreparationFailureCode.INVALID_REQUEST, error.code)
    assertTrue(error.message.orEmpty().contains("'acceptance_criteria'"))
  }

  @Test
  fun `prepare loud-fails when constraints are missing`() {
    val error =
      assertFailsWith<SkillBillRuntimeException> {
        FeatureSpecPreparationPolicy.prepare(validIntake().copy(constraints = emptyList()))
      }

    assertEquals(FeatureSpecPreparationFailureCode.INVALID_REQUEST, error.code)
    assertTrue(error.message.orEmpty().contains("'constraints'"))
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
