package skillbill.cli.phase

import com.github.ajalt.clikt.core.UsageError
import skillbill.cli.kernel.cli.CliRunState
import skillbill.error.core.SkillBillRuntimeException
import skillbill.error.featuretask.PhaseSlotFailureCode
import skillbill.error.shellcontent.missingValidationGate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class PhaseRunErrorMappingTest {
  @Test
  fun `a review target that names no commit is a usage error`() {
    val state = CliRunState(stdinText = null)

    val error =
      assertFailsWith<UsageError> {
        runPhase(state) {
          throw SkillBillRuntimeException(
            PhaseSlotFailureCode.UNKNOWN_PHASE_REVIEW_TARGET,
            "Review target 'no-such-branch' does not name a commit in this repository; expected HEAD, " +
              "uncommitted, pr, " +
              "staged, unstaged, or a commit sha, branch, or tag.",
          )
        }
      }

    assertEquals(
      "Review target 'no-such-branch' does not name a commit in this repository; expected HEAD, " +
        "uncommitted, pr, " +
        "staged, unstaged, or a commit sha, branch, or tag.",
      error.message,
    )
    assertNull(state.result, "a usage error writes no phase result")
  }

  @Test
  fun `any other contract error prints its message and exits 1`() {
    val state = CliRunState(stdinText = null)

    val result = runPhase(state) { throw missingValidationGate("The dominant platform pack has no gate.") }

    assertNull(result)
    assertEquals(1, state.result?.exitCode)
    assertEquals("The dominant platform pack has no gate.", state.result?.stdout)
  }
}
