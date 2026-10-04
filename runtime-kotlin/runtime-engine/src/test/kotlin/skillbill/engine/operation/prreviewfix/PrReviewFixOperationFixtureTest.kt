package skillbill.engine.operation.prreviewfix

import skillbill.engine.featuretask.runner.SlotBaselineTestResources
import skillbill.engine.operation.core.OperationArguments
import skillbill.engine.operation.core.OperationOutcome
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class PrReviewFixOperationFixtureTest {
  @Test
  fun `the analysis and confirm outputs match the committed fixture`() {
    val live =
      PrReviewFixHarness().use { harness ->
        val analysis = assertIs<OperationOutcome.AwaitingConfirmation>(harness.invoke())
        val confirm =
          assertIs<OperationOutcome.Completed>(
            harness.invoke(OperationArguments(confirm = analysis.token, select = "all-recommended")),
          )
        "## analysis: awaiting_confirmation\n${analysis.proposalSummary}\n" +
          "## confirm select:all-recommended: completed\n${confirm.text}"
      }
    val fixture = SlotBaselineTestResources.resolve(FIXTURE)
    if (System.getenv(CAPTURE_ENV) == "1") Files.writeString(fixture, live)

    assertEquals(
      Files.readString(fixture),
      live,
      "$FIXTURE drifted; regenerate with $CAPTURE_ENV=1 after reviewing the diff",
    )
  }

  private companion object {
    const val CAPTURE_ENV = "SKILL_BILL_PR_REVIEW_FIX_CAPTURE"
    const val FIXTURE = "operation/pr-review-fix/outcomes.md"
  }
}
