package skillbill.engine.operation.core

import skillbill.engine.operation.featureguard.FeatureGuardPromptRules
import skillbill.engine.operation.featureguardcleanup.FeatureGuardCleanupPromptRules
import skillbill.engine.operation.unittestvalue.UnitTestValueCheckPromptRules
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ChecklistOperationsTest {
  private val harnesses = mutableListOf<ChecklistOperationHarness>()

  @AfterTest
  fun cleanUp() {
    harnesses.forEach(ChecklistOperationHarness::close)
  }

  @Test
  fun `a guard operation edits nothing before confirm, and confirm applies exactly the stored proposal once`() {
    GUARDS.forEach { guard ->
      val harness = harness()
      val proposal =
        assertIs<OperationOutcome.AwaitingConfirmation>(harness.invoke(guard.id, "guard the checkout"), guard.id)

      assertEquals("", harness.status(), "${guard.id} changed the worktree before confirm")
      assertEquals(listOf(guard.proposalStep), harness.runner.inputs.map { it.stepName }, guard.id)
      assertContains(harness.runner.inputs.single().directive, guard.directiveHeading)
      val stored = harness.runner.proposalValue

      harness.runner.proposalValue = "A recomputed plan.\n"
      val confirmed =
        harness.invoke(guard.id, "also rewrite the billing module", OperationArguments(confirm = proposal.token))

      assertIs<OperationOutcome.Completed>(confirmed, guard.id)
      assertEquals(listOf(guard.proposalStep, guard.applyStep), harness.runner.inputs.map { it.stepName }, guard.id)
      assertEquals(mapOf(guard.proposalStep to stored), harness.runner.inputs.last().priorValues, guard.id)
      assertEquals(null, harness.runner.inputs.last().operatorInstructions, "${guard.id} forwarded confirm-time text")
      assertContains(harness.status(), "edited-by-agent.txt")
      assertEquals(guard.validations, harness.validations.map { it.definitionId }, guard.id)
    }
  }

  @Test
  fun `a proposal step that edits the worktree fails and leaves no proposal or token`() {
    GUARDS.forEach { guard ->
      val harness = harness()
      harness.write("Checkout.kt", "class Checkout\n")
      harness.commitAll("checkout")
      harness.write("Checkout.kt", "class Checkout // dirty before the proposal\n")
      harness.runner.editDuringProposal = true

      val outcome = assertIs<OperationOutcome.Failed>(harness.invoke(guard.id, "guard the checkout"), guard.id)

      assertContains(outcome.reason, "'${guard.proposalStep}' is read-only but changed the worktree")
      assertEquals(0, harness.proposalRowCount(), guard.id)
    }
  }

  @Test
  fun `with no unit tests in the current changes the check says so and launches no agent`() {
    val harness = harness()
    harness.write("src/main/kotlin/Checkout.kt", "class Checkout\n")

    val outcome = assertIs<OperationOutcome.Completed>(harness.invoke(UNIT_TEST_VALUE_CHECK))

    assertEquals(
      "No unit tests in scope (current staged, unstaged, and untracked changes); nothing to review.\n",
      outcome.text,
    )
    assertTrue(harness.runner.inputs.isEmpty())
  }

  @Test
  fun `a unit test review step that edits the worktree fails the check`() {
    val harness = harness()
    harness.write("src/test/kotlin/CheckoutTest.kt", "class CheckoutTest\n")
    harness.runner.editDuringProposal = true

    val outcome = assertIs<OperationOutcome.Failed>(harness.invoke(UNIT_TEST_VALUE_CHECK))

    assertContains(
      outcome.reason,
      "'${UnitTestValueCheckPromptRules.REVIEW_STEP}' is read-only but changed the worktree",
    )
  }

  @Test
  fun `the default scope reviews only the changed unit tests that still exist, not every test in the repository`() {
    val harness = harness()
    harness.write("src/test/kotlin/UnchangedTest.kt", "class UnchangedTest\n")
    harness.write("src/test/kotlin/CheckoutTest.kt", "class CheckoutTest\n")
    harness.write("src/test/kotlin/ObsoleteTest.kt", "class ObsoleteTest\n")
    harness.commitAll("tests")
    harness.write("src/test/kotlin/CheckoutTest.kt", "class CheckoutTest // changed\n")
    harness.write("src/main/kotlin/Checkout.kt", "class Checkout\n")
    Files.delete(harness.repo.resolve("src/test/kotlin/ObsoleteTest.kt"))

    assertIs<OperationOutcome.Completed>(harness.invoke(UNIT_TEST_VALUE_CHECK))

    val review = harness.runner.inputs.single()
    assertEquals(UnitTestValueCheckPromptRules.REVIEW_STEP, review.stepName)
    assertContains(review.directive, "Unit tests in scope:\n- src/test/kotlin/CheckoutTest.kt\n\n")
    assertContains(review.directive, "## Criticality Weighting")
    assertFalse("UnchangedTest" in review.directive, review.directive)
  }

  private fun harness(): ChecklistOperationHarness = ChecklistOperationHarness().also(harnesses::add)

  private data class Guard(
    val id: String,
    val proposalStep: String,
    val applyStep: String,
    val validations: List<String>,
    val directiveHeading: String,
  )

  private companion object {
    const val UNIT_TEST_VALUE_CHECK = "unit-test-value-check"

    val GUARDS =
      listOf(
        Guard(
          "feature-guard",
          FeatureGuardPromptRules.PROPOSAL_STEP,
          FeatureGuardPromptRules.APPLY_STEP,
          validations = emptyList(),
          directiveHeading = "## DON'T: Scatter Flag Checks",
        ),
        Guard(
          "feature-guard-cleanup",
          FeatureGuardCleanupPromptRules.PROPOSAL_STEP,
          FeatureGuardCleanupPromptRules.APPLY_STEP,
          validations = listOf("validation"),
          directiveHeading = "### Step 2: Verify Safety",
        ),
      )
  }
}
