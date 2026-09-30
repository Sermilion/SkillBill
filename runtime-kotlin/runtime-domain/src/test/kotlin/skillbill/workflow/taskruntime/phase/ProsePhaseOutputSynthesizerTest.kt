package skillbill.workflow.taskruntime.phase

import skillbill.contracts.JsonCodec
import skillbill.contracts.workflow.featuretask.FEATURE_TASK_RUNTIME_CONTRACT_VERSION
import skillbill.workflow.taskruntime.model.handoff.envelope.SettlementEnvelopeRequest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProsePhaseOutputSynthesizerTest {
  @Test
  fun `implementation_receipt sibling becomes stuffed value`() {
    val raw =
      """
      {
        "contract_version": "$FEATURE_TASK_RUNTIME_CONTRACT_VERSION",
        "phase_id": "implement",
        "status": "completed",
        "summary": "Did the work.",
        "produced_outputs": {
          "implementation_receipt": {
            "projection_kind": "implementation_receipt",
            "completed_task_ids": ["task-1"]
          }
        }
      }
      """.trimIndent()

    val envelope = assertNotNull(ProsePhaseOutputSynthesizer.trySynthesize(raw, "implement"))
    val map = envelopeMap(envelope)
    val produced = assertNotNull(JsonCodec.anyToStringAnyMap(map["produced_outputs"]))
    val value = assertNotNull(produced["value"] as? String)
    assertTrue(value.contains("implementation_receipt") || value.contains("completed_task_ids"))
    assertEquals("completed", map["status"])
    assertEquals("implement", map["phase_id"])
    assertNull(produced["implementation_receipt"])
  }

  @Test
  fun `direct value beside a mistyped optional field synthesizes a clean envelope`() {
    val raw =
      """
      {
        "contract_version": "$FEATURE_TASK_RUNTIME_CONTRACT_VERSION",
        "phase_id": "implement",
        "status": "completed",
        "summary": "Consolidated the run loop.",
        "produced_outputs": { "value": "{\"changed\":[\"RunLoop.kt\"]}" },
        "derived_notes": ["validate owes the compile", "counts came from git ls-tree"]
      }
      """.trimIndent()

    val map = envelopeMap(assertNotNull(ProsePhaseOutputSynthesizer.trySynthesize(raw, "implement")))
    val produced = assertNotNull(JsonCodec.anyToStringAnyMap(map["produced_outputs"]))
    assertEquals("{\"changed\":[\"RunLoop.kt\"]}", produced["value"])
    assertEquals("completed", map["status"])
    assertNull(map["derived_notes"])
  }

  @Test
  fun `direct value with no status or summary synthesizes a completed envelope`() {
    val raw =
      """
      Final static verification is complete.

      ```json
      {"contract_version":"$FEATURE_TASK_RUNTIME_CONTRACT_VERSION","phase_id":"implement",
       "produced_outputs":{"value":"{\"summary\":\"Deleted the dead ports.\",\"tests_executed\":[]}"}}
      ```
      """.trimIndent()

    val map = envelopeMap(assertNotNull(ProsePhaseOutputSynthesizer.trySynthesize(raw, "implement")))
    val produced = assertNotNull(JsonCodec.anyToStringAnyMap(map["produced_outputs"]))
    assertEquals("{\"summary\":\"Deleted the dead ports.\",\"tests_executed\":[]}", produced["value"])
    assertEquals("completed", map["status"])
    assertTrue((map["summary"] as? String).orEmpty().isNotBlank())
  }

  @Test
  fun `simplification_receipt sibling persists as bounded simplify prose`() {
    val raw =
      """
      {
        "contract_version": "$FEATURE_TASK_RUNTIME_CONTRACT_VERSION",
        "phase_id": "simplify",
        "status": "completed",
        "summary": "Reduced local excess.",
        "produced_outputs": {
          "simplification_receipt": {
            "projection_kind": "simplification_receipt",
            "contract_version": "0.1",
            "changed_paths": ["src/Foo.kt"],
            "reductions": [{"path": "src/Foo.kt", "outcome": "addressed"}],
            "unresolved_items": []
          }
        }
      }
      """.trimIndent()

    val envelope = assertNotNull(ProsePhaseOutputSynthesizer.trySynthesize(raw, "simplify"))
    val map = envelopeMap(envelope)
    val produced = assertNotNull(JsonCodec.anyToStringAnyMap(map["produced_outputs"]))

    assertEquals("simplify", map["phase_id"])
    assertTrue((produced["value"] as? String).orEmpty().contains("simplification_receipt"))
    assertNull(produced["simplification_receipt"])
  }

  @Test
  fun `minimal final object for a step with no workflow recovers a stamped envelope`() {
    val raw =
      """
      Done with the custom step.

      {"status":"blocked","summary":"Needs a credential.","value":"The deploy key is missing.",
       "verdict":"no_progress","failure_disposition":"needs_user_action"}
      """.trimIndent()

    val map = assertNotNull(ProsePhaseOutputSynthesizer.recoverFinalObject(raw, "some_custom_step"))
    val produced = assertNotNull(JsonCodec.anyToStringAnyMap(map["produced_outputs"]))

    assertEquals(FEATURE_TASK_RUNTIME_CONTRACT_VERSION, map["contract_version"])
    assertEquals("some_custom_step", map["phase_id"])
    assertEquals("blocked", map["status"])
    assertEquals("Needs a credential.", map["summary"])
    assertEquals("The deploy key is missing.", produced["value"])
    assertEquals("no_progress", map["verdict"])
    assertEquals("needs_user_action", map["failure_disposition"])
    assertNull(ProsePhaseOutputSynthesizer.trySynthesize(raw, "some_custom_step"))
  }

  @Test
  fun `blank value rejects`() {
    val raw =
      """
      {
        "contract_version": "$FEATURE_TASK_RUNTIME_CONTRACT_VERSION",
        "phase_id": "plan",
        "status": "completed",
        "summary": "Empty.",
        "produced_outputs": { "value": "   " }
      }
      """.trimIndent()

    assertNull(ProsePhaseOutputSynthesizer.trySynthesize(raw, "plan"))
  }

  @Test
  fun `bare non-json prose rejects`() {
    assertNull(ProsePhaseOutputSynthesizer.trySynthesize("not a json object", "implement"))
  }

  @Test
  fun `wrong phase_id rejects instead of rewriting identity`() {
    val raw =
      """
      {
        "contract_version": "$FEATURE_TASK_RUNTIME_CONTRACT_VERSION",
        "phase_id": "plan",
        "status": "completed",
        "summary": "Wrong slot.",
        "produced_outputs": { "value": "plan prose" }
      }
      """.trimIndent()

    assertNull(ProsePhaseOutputSynthesizer.trySynthesize(raw, "preplan"))
  }

  @Test
  fun `incompatible contract version rejects instead of rewriting it`() {
    val raw =
      """
      {
        "contract_version": "9.9",
        "phase_id": "plan",
        "status": "completed",
        "summary": "Future contract.",
        "produced_outputs": { "value": "plan prose" }
      }
      """.trimIndent()

    assertNull(ProsePhaseOutputSynthesizer.trySynthesize(raw, "plan"))
  }

  @Test
  fun `unsupported status rejects instead of defaulting`() {
    val raw =
      """
      {
        "contract_version": "$FEATURE_TASK_RUNTIME_CONTRACT_VERSION",
        "phase_id": "plan",
        "status": "queued",
        "summary": "Bad status.",
        "produced_outputs": { "value": "plan prose" }
      }
      """.trimIndent()

    assertNull(ProsePhaseOutputSynthesizer.trySynthesize(raw, "plan"))
  }

  @Test
  fun `produced_outputs as a name-value list under a near-miss status recovers instead of blocking`() {
    val raw =
      """
      {
        "contract_version": "$FEATURE_TASK_RUNTIME_CONTRACT_VERSION",
        "phase_id": "implement",
        "status": "complete",
        "summary": "Applied every plan task.",
        "tests_executed": [],
        "produced_outputs": [ { "name": "implementation_receipt", "value": "implementation prose" } ]
      }
      """.trimIndent()

    val envelope = assertNotNull(ProsePhaseOutputSynthesizer.trySynthesize(raw, "implement"))
    val map = envelopeMap(envelope)
    val produced = assertNotNull(JsonCodec.anyToStringAnyMap(map["produced_outputs"]))
    assertEquals("implementation prose", produced["value"])
    assertEquals("completed", map["status"])
    assertNull(map["tests_executed"])
  }

  @Test
  fun `historical prose status aliases retain canonical output`() {
    listOf("complete" to "completed", "block" to "blocked", "fail" to "failed").forEach { (input, output) ->
      val raw =
        """
        {
          "phase_id": "implement",
          "status": "$input",
          "summary": "Applied the plan.",
          "produced_outputs": [ { "name": "implementation_receipt", "value": "implementation receipt" } ]
        }
        """.trimIndent()

      val envelope = assertNotNull(ProsePhaseOutputSynthesizer.trySynthesize(raw, "implement"))
      assertEquals(output, envelopeMap(envelope)["status"])
    }
  }

  @Test
  fun `a supplied verdict is carried for a completed step`() {
    val raw =
      """
      {
        "contract_version": "$FEATURE_TASK_RUNTIME_CONTRACT_VERSION",
        "phase_id": "audit",
        "status": "completed",
        "summary": "All good.",
        "verdict": "satisfied",
        "produced_outputs": { "value": "all criteria met" }
      }
      """.trimIndent()

    val envelope = assertNotNull(ProsePhaseOutputSynthesizer.trySynthesize(raw, "audit"))
    assertEquals("satisfied", envelopeMap(envelope)["verdict"])
  }

  @Test
  fun `blocked external settlement without a verdict omits it and keeps the failure disposition`() {
    val envelope =
      ProsePhaseOutputSynthesizer.envelopeFromSettlement(
        SettlementEnvelopeRequest(
          phaseId = "audit",
          status = "blocked",
          value = "Planning criterion list unreadable.",
          summary = "Audit blocked on external dependency.",
          failureDisposition = "needs_user_action",
        ),
      )
    val map = envelopeMap(envelope)
    assertEquals("blocked", map["status"])
    assertNull(map["verdict"])
    assertEquals("needs_user_action", map["failure_disposition"])
  }

  private fun envelopeMap(envelope: Any): Map<String, Any?> =
    JsonCodec.anyToStringAnyMap(envelope) ?: error("expected object envelope")
}
