package skillbill.engine.operation.core

import skillbill.engine.featuretask.runner.SlotBaselineTestResources
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

class ChecklistOperationFixtureTest {
  @Test
  fun `each checklist operation's directive and outcome match the committed fixture`() {
    val live = liveCapture()
    if (System.getenv(CAPTURE_ENV) == "1") {
      live.forEach { (name, text) -> Files.writeString(SlotBaselineTestResources.resolve("$FIXTURE_ROOT/$name"), text) }
    }

    live.forEach { (name, text) ->
      assertEquals(
        Files.readString(SlotBaselineTestResources.resolve("$FIXTURE_ROOT/$name")),
        text,
        "$name drifted from its fixture; regenerate with $CAPTURE_ENV=1 after reviewing the diff",
      )
    }
  }

  private fun liveCapture(): Map<String, String> =
    ChecklistOperationHarness().use { harness ->
      harness.write("src/main/kotlin/Checkout.kt", "class Checkout\n")
      val noTests = harness.invoke("unit-test-value-check")
      harness.write("src/test/kotlin/CheckoutTest.kt", "class CheckoutTest\n")
      harness.invoke("unit-test-value-check")
      val guard = harness.invoke("feature-guard", "Guard the new checkout flow.")
      val cleanup = harness.invoke("feature-guard-cleanup", "Remove feature-new-checkout.")
      val (review, guardProposal, cleanupProposal) = harness.runner.inputs.map { input -> input.directive + "\n" }
      val outcomes =
        listOf(
          "feature-guard" to guard,
          "feature-guard-cleanup" to cleanup,
          "unit-test-value-check (no unit tests)" to noTests,
        ).joinToString("\n") { (name, outcome) -> "## $name: ${describe(outcome)}" }
      mapOf(
        "unit-test-value-check.review-prompt.md" to review,
        "feature-guard.proposal-prompt.md" to guardProposal,
        "feature-guard-cleanup.proposal-prompt.md" to cleanupProposal,
        "outcomes.md" to outcomes,
      )
    }

  private fun describe(outcome: OperationOutcome): String =
    when (outcome) {
      is OperationOutcome.AwaitingConfirmation -> "awaiting_confirmation\n${outcome.proposalSummary}"
      is OperationOutcome.Completed -> "completed\n${outcome.text}"
      else -> "$outcome\n"
    }

  private companion object {
    const val CAPTURE_ENV = "SKILL_BILL_CHECKLIST_OPERATION_CAPTURE"
    const val FIXTURE_ROOT = "operation/checklist"
  }
}
