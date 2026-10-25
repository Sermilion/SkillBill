package skillbill.workflow.taskruntime.model.validation

import skillbill.error.shellcontent.FeatureTaskRuntimeFailureCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class ValidationTestProbeParserTest {
  @Test
  fun `a survived line is stored as survived and not repaired`() {
    val parsed =
      parse("Checked the guard.\nTest probe CartTest.adds: survived guards Cart.kt:12 repaired no reason still passes")

    val probe = parsed.probes.single()
    assertEquals(ValidationTestProbeResult.SURVIVED, probe.result)
    assertEquals(false, probe.repaired)
    assertEquals("CartTest.adds", probe.testRef)
    assertEquals("Cart.kt:12", probe.guardedRef)
  }

  @Test
  fun `a valid report keeps every item in order`() {
    val parsed =
      parse(
        """
        Test probe a: killed guards G1 repaired yes reason strengthened then killed
        Test probe b: not_run guards G2 repaired no reason no targeted command
        Test probe c: killed guards G3 repaired no reason failed as expected
        """.trimIndent(),
      )

    assertEquals(listOf("a", "b", "c"), parsed.probes.map { it.testRef })
    assertEquals(
      listOf(ValidationTestProbeResult.KILLED, ValidationTestProbeResult.NOT_RUN, ValidationTestProbeResult.KILLED),
      parsed.probes.map { it.result },
    )
    assertEquals(listOf(true, false, false), parsed.probes.map { it.repaired })
  }

  @Test
  fun `malformed reports are rejected with the probe failure code`() {
    val oversized = (1..ValidationTestProbeShape.MAX_PROBES + 1).joinToString("\n") { line("t$it", "killed", "no") }
    val rejected =
      mapOf(
        "no probe lines" to "All checks passed.",
        "bad grammar" to "Test probe t1 looks fine",
        "unknown result" to "Test probe t1: passed guards G repaired no reason ok",
        "duplicate test ref" to "${line("t1", "killed", "no")}\n${line("t1", "survived", "no")}",
        "more than the cap" to oversized,
        "repaired on a survivor" to line("t1", "survived", "yes"),
        "repaired on a not_run" to line("t1", "not_run", "yes"),
      )

    rejected.forEach { (label, prose) ->
      val result = ValidationTestProbeParser.parse(prose)
      val malformed = assertIs<ValidationTestProbeParse.Malformed>(result, label)
      assertEquals(FeatureTaskRuntimeFailureCode.INVALID_VALIDATION_TEST_PROBES, malformed.code, label)
    }
  }

  private fun parse(prose: String): ValidationTestProbeParse.Parsed =
    assertIs<ValidationTestProbeParse.Parsed>(ValidationTestProbeParser.parse(prose))

  private fun line(
    ref: String,
    result: String,
    repaired: String,
  ) = "Test probe $ref: $result guards Guarded.kt:1 repaired $repaired reason some reason"
}
