package skillbill.infrastructure.contracts.phaseoutput

import skillbill.error.featuretask.FeatureTaskRuntimePhaseOutputFailureCode
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimePhaseOutputFormat
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimePhaseOutputRepairOperation
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FeatureTaskRuntimePhaseOutputStructuralRepairTest {
  private val validJson =
    """{"phase_id":"plan","status":"completed","summary":"Plan output.","produced_outputs":{"value":"Plan prose."}}"""

  private fun inspect(text: String) = FeatureTaskRuntimePhaseOutputStructuralRepair.inspectWholeDocument(text, "plan")

  @Test
  fun `malformed YAML whose snippet mentions a duplicate key is classified malformed`() {
    val failure =
      assertIs<StrictParse.Failure>(
        StrictPhaseOutputParser.parseStrict(
          "summary: [duplicate key\nstatus: completed\n",
          FeatureTaskRuntimePhaseOutputFormat.YAML,
        ),
      )

    assertEquals(FeatureTaskRuntimePhaseOutputFailureCode.MALFORMED, failure.code)
  }

  @Test
  fun `valid JSON is accepted unchanged and is not rewritten`() {
    val accepted = assertIs<FeatureTaskRuntimePhaseOutputStructuralRepairDecision.Accepted>(inspect(validJson))

    assertNull(accepted.evidence)
    assertEquals(validJson, accepted.text)
  }

  @Test
  fun `observed extra closing delimiter is removed outside strings`() {
    val malformed = "$validJson]"

    val repaired = assertIs<FeatureTaskRuntimePhaseOutputStructuralRepairDecision.Accepted>(inspect(malformed))

    val evidence = requireNotNull(repaired.evidence)
    assertEquals(FeatureTaskRuntimePhaseOutputFormat.JSON, evidence.format)
    assertEquals(FeatureTaskRuntimePhaseOutputRepairOperation.REMOVE_EXTRA_CLOSING_DELIMITER, evidence.operation)
    assertEquals(sha256(malformed), evidence.originalDigest)
    assertEquals(sha256(validJson), evidence.repairedDigest)
    assertEquals(validJson.length, evidence.sourceLocation.offset)
  }

  @Test
  fun `one missing closing delimiter is added and reparsed`() {
    val malformed = validJson.dropLast(1)

    val repaired = assertIs<FeatureTaskRuntimePhaseOutputStructuralRepairDecision.Accepted>(inspect(malformed))

    val evidence = requireNotNull(repaired.evidence)
    assertEquals(FeatureTaskRuntimePhaseOutputRepairOperation.ADD_MISSING_CLOSING_DELIMITER, evidence.operation)
    assertEquals(sha256(malformed), evidence.originalDigest)
    assertEquals(sha256(validJson), evidence.repairedDigest)
  }

  @Test
  fun `one missing nested delimiter is inserted before the existing outer closer`() {
    val validNestedJson =
      """{"phase_id":"plan","status":"completed","summary":"Plan output.",""" +
        """"produced_outputs":{"value":"Plan prose.","notes":[{"id":"task-1"}]}}"""
    val malformed = validNestedJson.replace("[{\"id\":\"task-1\"}]}}", "[{\"id\":\"task-1\"}}}")

    val repaired = assertIs<FeatureTaskRuntimePhaseOutputStructuralRepairDecision.Accepted>(inspect(malformed))

    val evidence = requireNotNull(repaired.evidence)
    assertEquals(FeatureTaskRuntimePhaseOutputRepairOperation.ADD_MISSING_CLOSING_DELIMITER, evidence.operation)
    assertEquals(sha256(validNestedJson), evidence.repairedDigest)
  }

  @Test
  fun `structural characters inside JSON strings remain unchanged`() {
    val payload =
      """{"phase_id":"plan","status":"completed","summary":"literal } ] and escaped \"quote\"",""" +
        """"produced_outputs":{"value":"Plan prose."}}"""

    val accepted = assertIs<FeatureTaskRuntimePhaseOutputStructuralRepairDecision.Accepted>(inspect(payload))

    assertNull(accepted.evidence)
    assertEquals("literal } ] and escaped \"quote\"", accepted.node.path("summary").asText())
  }

  @Test
  fun `duplicate keys are never repaired`() {
    val duplicate = validJson.replace("\"phase_id\":\"plan\",", "\"phase_id\":\"plan\",\"phase_id\":\"audit\",")

    val rejected = assertIs<FeatureTaskRuntimePhaseOutputStructuralRepairDecision.Rejected>(inspect(duplicate))

    assertEquals(FeatureTaskRuntimePhaseOutputFailureCode.DUPLICATE_KEY, rejected.code)
  }

  @Test
  fun `conservative YAML flow repair preserves quoted scalar content`() {
    val malformed =
      "{phase_id: \"plan\", status: \"completed\", summary: \"brace } in a scalar\", produced_outputs: {value: \"x\"}"
    val repairedText = "$malformed}"

    val repaired = assertIs<FeatureTaskRuntimePhaseOutputStructuralRepairDecision.Accepted>(inspect(malformed))

    val evidence = requireNotNull(repaired.evidence)
    assertEquals(FeatureTaskRuntimePhaseOutputFormat.YAML, evidence.format)
    assertEquals(sha256(malformed), evidence.originalDigest)
    assertEquals(sha256(repairedText), evidence.repairedDigest)
    assertEquals("brace } in a scalar", repaired.node.path("summary").asText())
  }

  @Test
  fun `many unmatched closing delimiters stop candidate generation at the bounded limit`() {
    val malformed = "{\"phase_id\":\"plan\"" + "]".repeat(9)

    val rejected = assertIs<FeatureTaskRuntimePhaseOutputStructuralRepairDecision.Rejected>(inspect(malformed))

    assertEquals(FeatureTaskRuntimePhaseOutputFailureCode.REPAIR_LIMIT_EXCEEDED, rejected.code)
  }

  @Test
  fun `malformed and empty documents return stable rejection codes`() {
    val malformed = assertIs<FeatureTaskRuntimePhaseOutputStructuralRepairDecision.Rejected>(inspect("{\"a\": :}"))
    assertEquals(FeatureTaskRuntimePhaseOutputFailureCode.MALFORMED, malformed.code)

    val truncated = assertIs<FeatureTaskRuntimePhaseOutputStructuralRepairDecision.Rejected>(inspect("{\"a\":\"b\","))
    assertEquals(FeatureTaskRuntimePhaseOutputFailureCode.NO_REPAIR_CANDIDATE, truncated.code)

    val empty = assertIs<FeatureTaskRuntimePhaseOutputStructuralRepairDecision.Rejected>(inspect(" "))
    assertEquals(FeatureTaskRuntimePhaseOutputFailureCode.MALFORMED, empty.code)
  }

  @Test
  fun `multiple strictly parseable delimiter candidates are rejected as ambiguous repair`() {
    val first = validJson.replace("Plan output.", "first")
    val second = validJson.replace("Plan output.", "second")
    val decision =
      StructuralRepairCandidateEngine.evaluateCandidates(
        candidates =
          listOf(
            Candidate(first, FeatureTaskRuntimePhaseOutputFormat.JSON, 4),
            Candidate(second, FeatureTaskRuntimePhaseOutputFormat.JSON, 8),
          ),
        originalText = "malformed",
        sourceLabel = "plan",
        sourceOffset = 0,
        sourceText = "malformed",
      )

    val rejected = assertIs<FeatureTaskRuntimePhaseOutputStructuralRepairDecision.Rejected>(decision)
    assertEquals(FeatureTaskRuntimePhaseOutputFailureCode.AMBIGUOUS_REPAIR, rejected.code)
  }

  @Test
  fun `unsupported block YAML is rejected without guessed structural edits`() {
    val blockYaml =
      """
      phase_id: "audit"
      status: "completed"
      summary: "SKILL187-UNSUPPORTED-YAML"
      produced_outputs:
        value: "x"
      """.trimIndent()

    val decision = StructuralRepairCandidateEngine.repairExactText(blockYaml, "audit")

    val rejected =
      assertIs<FeatureTaskRuntimePhaseOutputStructuralRepairDecision.Rejected>(
        requireNotNull(decision) { "non-conservative YAML must produce an explicit repair rejection" },
      )
    assertEquals(FeatureTaskRuntimePhaseOutputFailureCode.UNSUPPORTED_REPAIR, rejected.code)
    assertTrue(rejected.reason.contains("conservative flow"))
    assertFalse(rejected.reason.contains("SKILL187-UNSUPPORTED-YAML"))
  }

  private fun sha256(value: String): String =
    MessageDigest.getInstance("SHA-256")
      .digest(value.toByteArray(Charsets.UTF_8))
      .joinToString("") { byte -> "%02x".format(byte) }
}
