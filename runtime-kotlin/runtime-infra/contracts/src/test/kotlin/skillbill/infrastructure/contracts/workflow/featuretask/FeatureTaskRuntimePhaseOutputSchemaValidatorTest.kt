package skillbill.infrastructure.contracts.workflow.featuretask

import skillbill.contracts.workflow.featuretask.FEATURE_TASK_RUNTIME_CONTRACT_VERSION
import skillbill.contracts.workflow.featuretask.FEATURE_TASK_RUNTIME_PREVIOUS_CONTRACT_VERSION
import skillbill.error.featuretask.FeatureTaskRuntimePhaseOutputFailureCode
import skillbill.error.shellcontent.InvalidFeatureTaskRuntimePhaseOutputSchemaError
import skillbill.infrastructure.contracts.FeatureTaskRuntimePhaseOutputSchemaValidator
import skillbill.workflow.taskruntime.artifact.envelopeWireMap
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimePhaseOutputValidationResult
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

private const val VERSION: String = FEATURE_TASK_RUNTIME_CONTRACT_VERSION

class FeatureTaskRuntimePhaseOutputWireSchemaTest {
  private val wellFormed =
    """
    contract_version: "$VERSION"
    phase_id: "plan"
    status: "completed"
    summary: "Produced an ordered implementation plan."
    produced_outputs:
      value: "Ordered implementation plan prose."
    """.trimIndent()

  @Test
  fun `well-formed phase output passes validation`() {
    FeatureTaskRuntimePhaseOutputWireSchema.validatePhaseOutputText(wellFormed, "plan")
  }

  @Test
  fun `a null optional prompt directive is normalized away instead of failing the gate`() {
    val nullPrompt =
      """
      {"contract_version":"$VERSION","phase_id":"plan","status":"completed","summary":"Planned.",
       "produced_outputs":{"value":"Ordered implementation plan prose.","prompt":null}}
      """.trimIndent()

    val normalized = FeatureTaskRuntimePhaseOutputWireSchema.normalizePhaseOutput(nullPrompt, "plan")

    assertEquals(
      mapOf("value" to "Ordered implementation plan prose."),
      normalized.envelopeWireMap()["produced_outputs"],
    )
  }

  @Test
  fun `a blank prompt directive still fails the gate`() {
    val blankPrompt =
      """
      {"contract_version":"$VERSION","phase_id":"plan","status":"completed","summary":"Planned.",
       "produced_outputs":{"value":"Ordered implementation plan prose.","prompt":" "}}
      """.trimIndent()

    assertFailsWith<InvalidFeatureTaskRuntimePhaseOutputSchemaError> {
      FeatureTaskRuntimePhaseOutputWireSchema.normalizePhaseOutput(blankPrompt, "plan")
    }
  }

  @Test
  fun `adapter repair result is followed by the existing phase schema path`() {
    val malformed =
      """{"contract_version":"$VERSION","phase_id":"plan","status":"completed","summary":"ok",""" +
        """"produced_outputs":{"value":"Plan prose."}}]"""

    val normalized = FeatureTaskRuntimePhaseOutputSchemaValidator().normalizePhaseOutput(malformed, "plan")

    assertContains(normalized.canonicalJson, "\"phase_id\":\"plan\"")
    assertEquals("plan", normalized.envelopeWireMap()["phase_id"])
  }

  @Test
  fun `blocked output accepts a typed non-retryable disposition`() {
    val blocked =
      wellFormed
        .replace("status: \"completed\"", "status: \"blocked\"") +
        "\nfailure_disposition: \"non_retryable_policy_conflict\""

    FeatureTaskRuntimePhaseOutputWireSchema.validatePhaseOutputText(blocked, "plan")
  }

  @Test
  fun `unknown failure disposition fails validation`() {
    val blocked =
      wellFormed
        .replace("status: \"completed\"", "status: \"blocked\"") +
        "\nfailure_disposition: \"try_forever\""

    assertFailsWith<InvalidFeatureTaskRuntimePhaseOutputSchemaError> {
      FeatureTaskRuntimePhaseOutputWireSchema.validatePhaseOutputText(blocked, "plan")
    }
  }

  @Test
  fun `audit output carrying legacy sibling keys still validates when verdict is satisfied`() {
    val legacy =
      """
      contract_version: "$VERSION"
      phase_id: "audit"
      status: "completed"
      summary: "Every criterion met."
      verdict: "satisfied"
      produced_outputs:
        value: "{\"gaps\":[]}"
        failing_criteria:
          - acceptance_criterion_ref: "AC-001"
            message: "Integration coverage is missing."
        unmet_criteria: []
        audit_repair_plan: {}
      """.trimIndent()

    FeatureTaskRuntimePhaseOutputWireSchema.validatePhaseOutputText(legacy, "audit")
  }

  @Test
  fun `empty object fails validation`() {
    assertFailsWith<InvalidFeatureTaskRuntimePhaseOutputSchemaError> {
      FeatureTaskRuntimePhaseOutputWireSchema.validatePhaseOutputText("{}", "plan")
    }
  }

  @Test
  fun `output missing a required field fails validation`() {
    val missingSummary =
      """
      contract_version: "$VERSION"
      phase_id: "plan"
      status: "completed"
      produced_outputs:
        value: "Plan prose."
      """.trimIndent()
    assertFailsWith<InvalidFeatureTaskRuntimePhaseOutputSchemaError> {
      FeatureTaskRuntimePhaseOutputWireSchema.validatePhaseOutputText(missingSummary, "plan")
    }
  }

  @Test
  fun `output with an unknown extra field fails validation`() {
    val extraField =
      """
      contract_version: "$VERSION"
      phase_id: "plan"
      status: "completed"
      summary: "ok"
      produced_outputs:
        value: "Plan prose."
      rogue_field: "nope"
      """.trimIndent()
    assertFailsWith<InvalidFeatureTaskRuntimePhaseOutputSchemaError> {
      FeatureTaskRuntimePhaseOutputWireSchema.validatePhaseOutputText(extraField, "plan")
    }
  }

  @Test
  fun `output with the wrong contract version fails validation`() {
    val wrongVersion = wellFormed.replace("\"$VERSION\"", "\"9.9\"")
    assertFailsWith<InvalidFeatureTaskRuntimePhaseOutputSchemaError> {
      FeatureTaskRuntimePhaseOutputWireSchema.validatePhaseOutputText(wrongVersion, "plan")
    }
  }

  @Test
  fun `output whose phase id does not match the executing phase fails validation`() {
    val wrongPhase = wellFormed.replace("phase_id: \"plan\"", "phase_id: \"implement\"")

    assertFailsWith<InvalidFeatureTaskRuntimePhaseOutputSchemaError> {
      FeatureTaskRuntimePhaseOutputWireSchema.validatePhaseOutputText(wrongPhase, "plan")
    }
  }

  @Test
  fun `plan output with empty produced_outputs fails because value is required`() {
    val emptyProducedOutputs =
      """
      contract_version: "$VERSION"
      phase_id: "plan"
      status: "completed"
      summary: "ok"
      produced_outputs: {}
      """.trimIndent()
    assertFailsWith<InvalidFeatureTaskRuntimePhaseOutputSchemaError> {
      FeatureTaskRuntimePhaseOutputWireSchema.validatePhaseOutputText(emptyProducedOutputs, "plan")
    }
  }

  @Test
  fun `completed validate output requires only the prose value and still decodes pre-change records`() {
    listOf("{}", "{value: ' '}").forEach { produced ->
      val output =
        """
        contract_version: "$VERSION"
        phase_id: validate
        status: completed
        summary: Checks finished.
        produced_outputs: $produced
        """.trimIndent()
      assertFailsWith<InvalidFeatureTaskRuntimePhaseOutputSchemaError> {
        FeatureTaskRuntimePhaseOutputWireSchema.validatePhaseOutputText(output, "validate")
      }
    }
    listOf("{value: details}", "{value: details, validation_passed: true}").forEach { produced ->
      val output =
        """
        contract_version: "$VERSION"
        phase_id: validate
        status: completed
        summary: Checks finished.
        produced_outputs: $produced
        """.trimIndent()
      FeatureTaskRuntimePhaseOutputWireSchema.validatePhaseOutputText(output, "validate")
    }
  }

  @Test
  fun `output with a non-empty produced_outputs object passes validation`() {
    val populated =
      """
      contract_version: "$VERSION"
      phase_id: "plan"
      status: "completed"
      summary: "ok"
      produced_outputs:
        value: "Plan prose."
        owner: "agent"
      """.trimIndent()
    FeatureTaskRuntimePhaseOutputWireSchema.validatePhaseOutputText(populated, "plan")
  }

  @Test
  fun `output with a non-empty derived_notes string passes validation`() {
    val withNotes = wellFormed + "\nderived_notes: \"a useful note\""
    FeatureTaskRuntimePhaseOutputWireSchema.validatePhaseOutputText(withNotes, "plan")
  }

  @Test
  fun `review output carrying a top-level verdict and findings passes validation`() {
    val reviewWithVerdict =
      """
      contract_version: "$VERSION"
      phase_id: "review"
      status: "completed"
      summary: "Reviewed the change and requested fixes."
      verdict: "changes_requested"
      produced_outputs:
        findings:
          - severity: "blocker"
            message: "Foo.kt leaks a connection"
      """.trimIndent()
    FeatureTaskRuntimePhaseOutputWireSchema.validatePhaseOutputText(reviewWithVerdict, "review")
  }

  @Test
  fun `census-only verify_findings with verdict validates and ignores extra disposition and produced_outputs keys`() {
    val verifyFindings =
      """
      contract_version: "$VERSION"
      phase_id: "verify_findings"
      status: "completed"
      summary: "Verified findings."
      verdict: "findings_verified"
      produced_outputs:
        finding_dispositions:
          - finding_id: "F-001"
            disposition: "verified"
            reason: "ignored"
            severity: "major"
            location: "ignored"
            message: "ignored"
        legacy_sibling: "ignored"
      """.trimIndent()
    FeatureTaskRuntimePhaseOutputWireSchema.validatePhaseOutputText(verifyFindings, "verify_findings")
  }

  @Test
  fun `completed verify_findings missing envelope verdict fails validation`() {
    val missingVerdict =
      """{"contract_version":"$VERSION","phase_id":"verify_findings","status":"completed",""" +
        """"summary":"verify","produced_outputs":{"finding_dispositions":""" +
        """[{"finding_id":"F-001","disposition":"verified"}]}}"""
    assertFailsWith<InvalidFeatureTaskRuntimePhaseOutputSchemaError> {
      FeatureTaskRuntimePhaseOutputWireSchema.validatePhaseOutputText(missingVerdict, "verify_findings")
    }
  }

  @Test
  fun `completed verify_findings with off-vocabulary envelope verdict fails validation`() {
    val badVerdict =
      """{"contract_version":"$VERSION","phase_id":"verify_findings","status":"completed",""" +
        """"summary":"verify","verdict":"mostly_verified",""" +
        """"produced_outputs":{"finding_dispositions":[{"finding_id":"F-001","disposition":"verified"}]}}"""
    assertFailsWith<InvalidFeatureTaskRuntimePhaseOutputSchemaError> {
      FeatureTaskRuntimePhaseOutputWireSchema.validatePhaseOutputText(badVerdict, "verify_findings")
    }
  }

  @Test
  fun `pre-0_6 verify_findings envelope fails validation`() {
    val legacy =
      """{"contract_version":"0.5","phase_id":"verify_findings","status":"completed",""" +
        """"summary":"verify","verdict":"findings_verified",""" +
        """"produced_outputs":{"finding_dispositions":[{"finding_id":"F-001","disposition":"verified"}]}}"""
    assertFailsWith<InvalidFeatureTaskRuntimePhaseOutputSchemaError> {
      FeatureTaskRuntimePhaseOutputWireSchema.validatePhaseOutputText(legacy, "verify_findings")
    }
  }

  @Test
  fun `verify_findings disposition accepts boundary provenance and unavailable flag`() {
    val verifyFindings =
      """
      contract_version: "$VERSION"
      phase_id: "verify_findings"
      status: "completed"
      summary: "Verified findings against spec intent."
      verdict: "findings_verified"
      produced_outputs:
        finding_dispositions:
          - finding_id: "F-001"
            disposition: "verified"
            boundary_context_unavailable: false
            selected_boundary_headings:
              - heading_id: "runtime-kotlin/agent/history.md#abc"
                source_path: "runtime-kotlin/agent/history.md"
          - finding_id: "F-002"
            disposition: "verified"
            boundary_context_unavailable: true
      """.trimIndent()
    FeatureTaskRuntimePhaseOutputWireSchema.validatePhaseOutputText(verifyFindings, "verify_findings")
  }

  @Test
  fun `verify_findings disposition rejects boundary selections when context is unavailable`() {
    val verifyFindings =
      """
      contract_version: "$VERSION"
      phase_id: "verify_findings"
      status: "completed"
      summary: "Verified findings against spec intent."
      verdict: "findings_verified"
      produced_outputs:
        finding_dispositions:
          - finding_id: "F-001"
            disposition: "verified"
            reason: "Intent-only path with no eligible boundary."
            severity: "minor"
            location: "Foo.kt"
            message: "Out-of-tree reference"
            boundary_context_unavailable: true
            selected_boundary_headings:
              - heading_id: "runtime-kotlin/agent/history.md#abc"
                source_path: "runtime-kotlin/agent/history.md"
      """.trimIndent()
    assertFailsWith<InvalidFeatureTaskRuntimePhaseOutputSchemaError> {
      FeatureTaskRuntimePhaseOutputWireSchema.validatePhaseOutputText(verifyFindings, "verify_findings")
    }
  }

  @Test
  fun `output omitting the optional verdict still validates`() {
    FeatureTaskRuntimePhaseOutputWireSchema.validatePhaseOutputText(wellFormed, "plan")
  }

  @Test
  fun `output with a null verdict fails validation`() {
    val nullVerdict = wellFormed + "\nverdict: null"
    assertFailsWith<InvalidFeatureTaskRuntimePhaseOutputSchemaError> {
      FeatureTaskRuntimePhaseOutputWireSchema.validatePhaseOutputText(nullVerdict, "plan")
    }
  }

  @Test
  fun `output with a null derived_notes fails validation`() {
    val nullNotes = wellFormed + "\nderived_notes: null"
    assertFailsWith<InvalidFeatureTaskRuntimePhaseOutputSchemaError> {
      FeatureTaskRuntimePhaseOutputWireSchema.validatePhaseOutputText(nullNotes, "plan")
    }
  }

  @Test
  fun `output with an empty derived_notes string fails validation`() {
    val emptyNotes = wellFormed + "\nderived_notes: \"\""
    assertFailsWith<InvalidFeatureTaskRuntimePhaseOutputSchemaError> {
      FeatureTaskRuntimePhaseOutputWireSchema.validatePhaseOutputText(emptyNotes, "plan")
    }
  }

  @Test
  fun `output with an invalid status enum fails validation`() {
    val badStatus = wellFormed.replace("status: \"completed\"", "status: \"halfway\"")
    assertFailsWith<InvalidFeatureTaskRuntimePhaseOutputSchemaError> {
      FeatureTaskRuntimePhaseOutputWireSchema.validatePhaseOutputText(badStatus, "plan")
    }
  }

  @Test
  fun `malformed yaml fails validation`() {
    assertFailsWith<InvalidFeatureTaskRuntimePhaseOutputSchemaError> {
      FeatureTaskRuntimePhaseOutputWireSchema.validatePhaseOutputText(
        "contract_version: \"0.1\"\n  : broken",
        "plan",
      )
    }
  }

  @Test
  fun `non-object root fails validation`() {
    assertFailsWith<InvalidFeatureTaskRuntimePhaseOutputSchemaError> {
      FeatureTaskRuntimePhaseOutputWireSchema.validatePhaseOutputText("- just-a-list", "plan")
    }
  }

  @Test
  fun `raw json object passes validation`() {
    val rawJson =
      """{"contract_version":"$VERSION","phase_id":"plan","status":"completed",""" +
        """"summary":"ok","produced_outputs":{"value":"Plan prose."}}"""
    FeatureTaskRuntimePhaseOutputWireSchema.validatePhaseOutputText(rawJson, "plan")
  }

  @Test
  fun `json inside a fenced json block passes validation`() {
    val fenced =
      """
      Here is the plan output.

      ```json
      {"contract_version":"$VERSION","phase_id":"plan","status":"completed",
       "summary":"ok","produced_outputs":{"value":"Plan prose."}}
      ```

      Done.
      """.trimIndent()
    FeatureTaskRuntimePhaseOutputWireSchema.validatePhaseOutputText(fenced, "plan")
  }
}

class FeatureTaskRuntimePhaseOutputWireSchemaEnvelopeTest {
  @Test
  fun `json with surrounding prose passes validation`() {
    val withProse =
      """
      I planned the work as follows:
      {"contract_version":"$VERSION","phase_id":"plan","status":"completed",
       "summary":"ok","produced_outputs":{"value":"Plan prose."}}
      Let me know if you need anything else.
      """.trimIndent()
    FeatureTaskRuntimePhaseOutputWireSchema.validatePhaseOutputText(withProse, "plan")
  }

  @Test
  fun `multiple conflicting fenced envelopes fail loudly`() {
    val twoBlocks =
      """
      For reference the shape is:
      ```json
      {"contract_version":"$VERSION","phase_id":"plan","status":"completed","summary":"example",
       "produced_outputs":{"value":"Example plan prose."}}
      ```
      Here is the real output:
      ```json
      {"contract_version":"$VERSION","phase_id":"plan","status":"completed",
       "summary":"real","produced_outputs":{"value":"Plan prose."}}
      ```
      """.trimIndent()
    val error =
      assertFailsWith<InvalidFeatureTaskRuntimePhaseOutputSchemaError> {
        FeatureTaskRuntimePhaseOutputWireSchema.validatePhaseOutputText(twoBlocks, "plan")
      }
    assertContains(error.reason, "multiple conflicting schema-valid envelopes")
  }

  @Test
  fun `multiple conflicting prose wrapped envelopes fail loudly`() {
    val twoObjects =
      """
      For reference the shape is:
      {"contract_version":"$VERSION","phase_id":"audit","status":"completed","summary":"example",
       "verdict":"satisfied","produced_outputs":{"value":"{\"gaps\":[]}"}}
      Here is the real output:
      {"contract_version":"$VERSION","phase_id":"audit","status":"completed",
       "summary":"every criterion met","verdict":"satisfied","produced_outputs":{"value":"{\"gaps\":[]}"}}
      """.trimIndent()
    val error =
      assertFailsWith<InvalidFeatureTaskRuntimePhaseOutputSchemaError> {
        FeatureTaskRuntimePhaseOutputWireSchema.validatePhaseOutputText(twoObjects, "audit")
      }
    assertContains(error.reason, "multiple conflicting schema-valid envelopes")
  }

  @Test
  fun `completed audit without envelope verdict accepts explicit empty remaining list`() {
    val emptyRemainingList =
      """{"contract_version":"$VERSION","phase_id":"audit","status":"completed","summary":"audit",""" +
        """"produced_outputs":{"value":"[]"}}"""
    FeatureTaskRuntimePhaseOutputWireSchema.validatePhaseOutputText(emptyRemainingList, "audit")
  }

  @Test
  fun `completed audit keeps a supplied verdict through normalization`() {
    val suppliedVerdict =
      """{"contract_version":"$VERSION","phase_id":"audit","status":"completed",""" +
        """"summary":"audit","verdict":"remediation_required","produced_outputs":{"value":"- AC-002 still open"}}"""
    val normalized = FeatureTaskRuntimePhaseOutputWireSchema.normalizePhaseOutput(suppliedVerdict, "audit")
    assertEquals("remediation_required", normalized.envelopeWireMap()["verdict"])
    assertEquals(
      "- AC-002 still open",
      normalized.envelopeWireMap()["produced_outputs"].let {
        (it as Map<*, *>)["value"]
      },
    )
  }

  @Test
  fun `completed audit with blank value fails validation`() {
    val blankValue =
      """{"contract_version":"$VERSION","phase_id":"audit","status":"completed","summary":"audit",""" +
        """"verdict":"satisfied","produced_outputs":{"value":"   "}}"""
    assertFailsWith<InvalidFeatureTaskRuntimePhaseOutputSchemaError> {
      FeatureTaskRuntimePhaseOutputWireSchema.validatePhaseOutputText(blankValue, "audit")
    }
  }

  @Test
  fun `blocked audit omits verdict and carries failure disposition`() {
    val blocked =
      """
      contract_version: "$VERSION"
      phase_id: "audit"
      status: "blocked"
      summary: "Planning criterion list unreadable."
      failure_disposition: "needs_user_action"
      produced_outputs:
        value: "Planning criterion list unreadable."
      """.trimIndent()

    FeatureTaskRuntimePhaseOutputWireSchema.validatePhaseOutputText(blocked, "audit")
  }

  @Test
  fun `blocked audit without failure disposition fails validation`() {
    val blocked =
      """
      contract_version: "$VERSION"
      phase_id: "audit"
      status: "blocked"
      summary: "Planning criterion list unreadable."
      produced_outputs:
        value: "Planning criterion list unreadable."
      """.trimIndent()

    assertFailsWith<InvalidFeatureTaskRuntimePhaseOutputSchemaError> {
      FeatureTaskRuntimePhaseOutputWireSchema.validatePhaseOutputText(blocked, "audit")
    }
  }

  @Test
  fun `completed audit with satisfied verdict accepts malformed inner value`() {
    val malformedCases =
      listOf(
        """"verdict":"satisfied","produced_outputs":{"value":"AC-7 backtick `note` newline\nbreak"}""",
        """"verdict":"satisfied","produced_outputs":{"value":"${"x".repeat(1100)}"}""",
        """"verdict":"satisfied","produced_outputs":{"value":"{\"wrapper\":{\"gaps\":[{\"severity\":\"minor\"}]}}"}""",
        """"verdict":"satisfied","produced_outputs":{"value":"not-json prose still counts"}""",
        """"verdict":"satisfied","produced_outputs":{"value":"looks like AC-003 gap text","gaps":[]}""",
      )
    malformedCases.forEach { suffix ->
      val envelope =
        """{"contract_version":"$VERSION","phase_id":"audit","status":"completed","summary":"audit",$suffix}"""
      FeatureTaskRuntimePhaseOutputWireSchema.validatePhaseOutputText(envelope, "audit")
    }
  }

  @Test
  fun `audit envelope verdict is not cross-checked against inner value shape`() {
    val satisfiedGapLookingInner =
      """{"contract_version":"$VERSION","phase_id":"audit","status":"completed","summary":"audit",""" +
        """"verdict":"satisfied","produced_outputs":{"value":"AC-001: still missing wiring"}}"""
    FeatureTaskRuntimePhaseOutputWireSchema.validatePhaseOutputText(satisfiedGapLookingInner, "audit")
  }

  @Test
  fun `pre-0_5 audit output fails validation`() {
    val legacy =
      """{"contract_version":"0.4","phase_id":"audit","status":"completed","summary":"legacy",""" +
        """"verdict":"satisfied","produced_outputs":{"gaps":[]}}"""
    assertFailsWith<InvalidFeatureTaskRuntimePhaseOutputSchemaError> {
      FeatureTaskRuntimePhaseOutputWireSchema.validatePhaseOutputText(legacy, "audit")
    }
  }

  @Test
  fun `object trailed by prose containing a stray brace still validates`() {
    val withTrailingBrace =
      """
      {"contract_version":"$VERSION","phase_id":"plan","status":"completed",
       "summary":"ok","produced_outputs":{"value":"Plan prose."}}
      Note: the template placeholder } above is intentional.
      """.trimIndent()
    FeatureTaskRuntimePhaseOutputWireSchema.validatePhaseOutputText(withTrailingBrace, "plan")
  }

  @Test
  fun `a brace inside a string value does not split the object`() {
    val braceInString =
      """{"contract_version":"$VERSION","phase_id":"plan","status":"completed",""" +
        """"summary":"handles a literal } brace in a value","produced_outputs":{"value":"Plan prose."}}"""
    FeatureTaskRuntimePhaseOutputWireSchema.validatePhaseOutputText(braceInString, "plan")
  }

  @Test
  fun `a top-level json array of criteria still fails validation`() {
    assertFailsWith<InvalidFeatureTaskRuntimePhaseOutputSchemaError> {
      FeatureTaskRuntimePhaseOutputWireSchema.validatePhaseOutputText(
        """[{"criterion":"AC-1","met":true},{"criterion":"AC-2","met":false}]""",
        "audit",
      )
    }
  }

  @Test
  fun `a restated earlier envelope never outranks the final envelope`() {
    val staleThenReal =
      """
      Earlier draft of the audit result:
      ```json
      {"contract_version":"$VERSION","phase_id":"audit","status":"completed","summary":"draft",
       "verdict":"satisfied","produced_outputs":{"value":"{\"gaps\":[]}"}}
      ```
      Corrected final answer:
      ```json
      {"contract_version":"$VERSION","phase_id":"audit","status":"completed",
      "verdict":"gaps_found",
        "produced_outputs":{"value":"{\"gaps\":[{\"criterion\":\"AC-128\",\"note\":\"Integration behavior is
        missing.\"}]}"}}
      ```
      """.trimIndent()

    val error =
      assertFailsWith<InvalidFeatureTaskRuntimePhaseOutputSchemaError> {
        FeatureTaskRuntimePhaseOutputWireSchema.validatePhaseOutputText(staleThenReal, "audit")
      }

    assertContains(error.reason, "summary")
  }

  @Test
  fun `the final envelope wins over an earlier discarded draft`() {
    val draftThenReal =
      """
      Discarded draft, missing its summary:
      ```json
      {"contract_version":"$VERSION","phase_id":"audit","status":"completed",
       "verdict":"satisfied","produced_outputs":{"value":"{\"gaps\":[]}"}}
      ```
      Corrected final answer:
      ```json
      {"contract_version":"$VERSION","phase_id":"audit","status":"completed","summary":"all criteria met",
       "verdict":"satisfied",
       "produced_outputs":{"value":"{\"gaps\":[]}"}}
      ```
      """.trimIndent()

    val normalized = FeatureTaskRuntimePhaseOutputSchemaValidator().normalizePhaseOutput(draftThenReal, "audit")

    assertEquals("satisfied", normalized.envelopeWireMap()["verdict"])
  }

  @Test
  fun `the same envelope restated with reordered keys is not a conflict`() {
    val reordered =
      """
      ```json
      {"contract_version":"$VERSION","phase_id":"plan","status":"completed","summary":"ok",
       "produced_outputs":{"value":"Plan prose.","notes":["note-1"]}}
      ```
      Restating the same envelope with the fields in a different order:
      ```json
      {"produced_outputs":{"notes":["note-1"],"value":"Plan prose."},"summary":"ok",
       "status":"completed","phase_id":"plan","contract_version":"$VERSION"}
      ```
      """.trimIndent()

    FeatureTaskRuntimePhaseOutputWireSchema.validatePhaseOutputText(reordered, "plan")
  }

  @Test
  fun `array element order still distinguishes conflicting envelopes`() {
    val reordered =
      """
      ```json
      {"contract_version":"$VERSION","phase_id":"plan","status":"completed","summary":"ok",
       "produced_outputs":{"value":"Plan prose A."}}
      ```
      ```json
      {"contract_version":"$VERSION","phase_id":"plan","status":"completed","summary":"ok",
       "produced_outputs":{"value":"Plan prose B."}}
      ```
      """.trimIndent()

    val error =
      assertFailsWith<InvalidFeatureTaskRuntimePhaseOutputSchemaError> {
        FeatureTaskRuntimePhaseOutputWireSchema.validatePhaseOutputText(reordered, "plan")
      }

    assertContains(error.reason, "multiple conflicting schema-valid envelopes")
  }

  @Test
  fun `prose with no json object still fails validation`() {
    val proseOnly =
      """
      ## Implementation Plan
      - **No existing injected clock seam**, so add one.
      - Wire the repository through the service.
      """.trimIndent()
    assertFailsWith<InvalidFeatureTaskRuntimePhaseOutputSchemaError> {
      FeatureTaskRuntimePhaseOutputWireSchema.validatePhaseOutputText(proseOnly, "plan")
    }
  }

  @Test
  fun `audit nested verdict under produced_outputs is accepted as payload`() {
    val nested =
      """{"contract_version":"$VERSION","phase_id":"audit","status":"completed","summary":"SKILL187-NESTED",""" +
        """"produced_outputs":{"value":"{\"gaps\":[]}","verdict":"satisfied"}}"""

    FeatureTaskRuntimePhaseOutputWireSchema.validatePhaseOutputText(nested, "audit")
  }

  @Test
  fun `lenient audit normalization still extracts envelope verdict from prose output`() {
    val innerValue = """{\"reconciliation_evidence\":{\"reconciled\":true}}"""
    val body =
      """{"contract_version":"$VERSION","phase_id":"audit","status":"completed","summary":"lenient-audit",""" +
        """"verdict":"satisfied","produced_outputs":{"value":"$innerValue"}}"""
    val lenient = FeatureTaskRuntimePhaseOutputWireSchema.normalizeVerifyingPhaseOutputLenient(body, "audit")

    assertEquals("audit", lenient.envelopeWireMap()["phase_id"])
    assertEquals("satisfied", lenient.envelopeWireMap()["verdict"])
  }

  @Test
  fun `lenient verify_findings normalization accepts disposition fields the strict schema would reject`() {
    val longReason = "x".repeat(400)
    val body =
      """{"contract_version":"$VERSION","phase_id":"verify_findings","status":"completed",""" +
        """"summary":"lenient disposition","verdict":"findings_verified",""" +
        """"produced_outputs":{"finding_dispositions":[{"finding_id":"F-001",""" +
        """"disposition":"verified","reason":"$longReason","severity":"major",""" +
        """"location":"Example.kt","message":"Finding one","extra_field":"ignored"}]}}"""
    val lenient =
      FeatureTaskRuntimePhaseOutputWireSchema.normalizeVerifyingPhaseOutputLenient(
        body,
        "verify_findings",
      )

    assertEquals("verify_findings", lenient.envelopeWireMap()["phase_id"])
    assertEquals("findings_verified", lenient.envelopeWireMap()["verdict"])
  }

  @Test
  fun `completed implement_fix with a valid repair receipt validates`() {
    FeatureTaskRuntimePhaseOutputWireSchema.validatePhaseOutputText(
      implementFixEnvelope(validRepairReceiptJson()),
      "implement_fix",
    )
  }

  @Test
  fun `census-only implement_fix with repair_receipt 0_3 validates and ignores extra constructs and intent`() {
    val receipt =
      """{"contract_version":"0.3","entries":[{"finding_id":"F-001","outcome":"addressed",""" +
        """"constructs":[{"symbol":"Type.member"}],"intent":"ignored decoration"}]}"""
    FeatureTaskRuntimePhaseOutputWireSchema.validatePhaseOutputText(
      implementFixEnvelope(receipt),
      "implement_fix",
    )
  }

  @Test
  fun `pre-0_3 repair receipt fails validation`() {
    val legacyReceipt =
      """{"contract_version":"0.2","entries":[{"finding_id":"F-001","outcome":"addressed"}]}"""
    assertFailsWith<InvalidFeatureTaskRuntimePhaseOutputSchemaError> {
      FeatureTaskRuntimePhaseOutputWireSchema.validatePhaseOutputText(
        implementFixEnvelope(legacyReceipt),
        "implement_fix",
      )
    }
  }

  @Test
  fun `completed implement_fix omitting the repair receipt is rejected`() {
    val envelope =
      """{"contract_version":"$VERSION","phase_id":"implement_fix","status":"completed","summary":"fix",""" +
        """"produced_outputs":{"reconciled_state":{"reconciled":true,"evidence":"tree at target"}}}"""
    assertFailsWith<InvalidFeatureTaskRuntimePhaseOutputSchemaError> {
      FeatureTaskRuntimePhaseOutputWireSchema.validatePhaseOutputText(envelope, "implement_fix")
    }
  }

  @Test
  fun `blocked implement_fix without a repair receipt still validates`() {
    val envelope =
      """{"contract_version":"$VERSION","phase_id":"implement_fix","status":"blocked","summary":"blocked",""" +
        """"failure_disposition":"needs_user_action","produced_outputs":{"reason":"operator pause"}}"""
    FeatureTaskRuntimePhaseOutputWireSchema.validatePhaseOutputText(envelope, "implement_fix")
  }

  private fun implementFixEnvelope(receiptJson: String): String =
    """{"contract_version":"$VERSION","phase_id":"implement_fix","status":"completed","summary":"fix",""" +
      """"produced_outputs":{"reconciled_state":{"reconciled":true,"evidence":"tree at target"},""" +
      """"repair_receipt":$receiptJson}}"""

  private fun validRepairReceiptJson(): String =
    """{"contract_version":"0.3","round_number":1,"pre_fix_checkpoint_sha":"${"a".repeat(40)}",""" +
      """"entries":[{"finding_id":"F-001","outcome":"addressed"}]}"""

  @Test
  fun `completed build phase output validates nested build_receipt through production schema gate`() {
    val envelope =
      buildPhaseEnvelope(
        buildReceipt =
          """{"contract_version":"0.1","validation_status":"passed","checks":[],""" +
            """"repository_checkpoint":{"fingerprint":"fp"},"gate_run_count":1,""" +
            """"gate_runs":[{"duration_ms":1,"outcome":"passed","cache_mode":"cache_eligible",""" +
            """"executed_work_units":1}]}""",
      )
    FeatureTaskRuntimePhaseOutputSchemaValidator().validatePhaseOutputText(envelope, "build")
  }

  @Test
  fun `completed build phase output with malformed build_receipt is rejected before acceptance`() {
    val envelope =
      buildPhaseEnvelope(
        buildReceipt =
          """{"contract_version":"0.1","validation_status":"passed","checks":[],""" +
            """"repository_checkpoint":{"fingerprint":"fp"}}""",
      )
    val result = FeatureTaskRuntimePhaseOutputSchemaValidator().validatePhaseOutput(envelope, "build")
    val rejected = assertIs<FeatureTaskRuntimePhaseOutputValidationResult.Rejected>(result)
    assertEquals(FeatureTaskRuntimePhaseOutputFailureCode.SCHEMA_INVALID, rejected.code)
    assertFailsWith<InvalidFeatureTaskRuntimePhaseOutputSchemaError> {
      FeatureTaskRuntimePhaseOutputSchemaValidator().validatePhaseOutputText(envelope, "build")
    }
  }

  @Test
  fun `implement prose value without status or summary settles as a synthesized completed envelope`() {
    val envelope =
      """{"contract_version":"$VERSION","phase_id":"implement","produced_outputs":{"value":""" +
        """"{\"subtask_id\":\"3\",\"summary\":\"Deleted the dead ports.\",\"tests_executed\":[]}"}}"""
    val capture =
      "Final static verification is complete: every acceptance criterion was confirmed by reading the tree.\n\n" +
        "```json\n$envelope\n```\n"

    val normalized = FeatureTaskRuntimePhaseOutputSchemaValidator().normalizePhaseOutput(capture, "implement")

    val settled = normalized.envelopeWireMap()
    assertEquals("completed", settled["status"])
    assertTrue((settled["summary"] as? String).orEmpty().isNotBlank())
    assertEquals(
      mapOf("value" to """{"subtask_id":"3","summary":"Deleted the dead ports.","tests_executed":[]}"""),
      settled["produced_outputs"],
    )
  }

  @Test
  fun `completed preplan with non-blank value passes validation`() {
    val preplan =
      """
      contract_version: "$VERSION"
      phase_id: "preplan"
      status: "completed"
      summary: "Preplan prose ready for plan."
      produced_outputs:
        value: "Dense planning prose for the plan phase."
      """.trimIndent()
    FeatureTaskRuntimePhaseOutputWireSchema.validatePhaseOutputText(preplan, "preplan")
  }

  @Test
  fun `completed preplan with blank value fails validation`() {
    val preplan =
      """
      contract_version: "$VERSION"
      phase_id: "preplan"
      status: "completed"
      summary: "Preplan missing prose."
      produced_outputs:
        value: ""
      """.trimIndent()
    assertFailsWith<InvalidFeatureTaskRuntimePhaseOutputSchemaError> {
      FeatureTaskRuntimePhaseOutputWireSchema.validatePhaseOutputText(preplan, "preplan")
    }
  }

  @Test
  fun `completed preplan with whitespace-only value fails validation`() {
    val preplan =
      """
      contract_version: "$VERSION"
      phase_id: "preplan"
      status: "completed"
      summary: "Preplan whitespace-only prose."
      produced_outputs:
        value: "   "
      """.trimIndent()
    assertFailsWith<InvalidFeatureTaskRuntimePhaseOutputSchemaError> {
      FeatureTaskRuntimePhaseOutputWireSchema.validatePhaseOutputText(preplan, "preplan")
    }
  }

  @Test
  fun `completed preplan missing value fails validation`() {
    val preplan =
      """
      contract_version: "$VERSION"
      phase_id: "preplan"
      status: "completed"
      summary: "Preplan missing value."
      produced_outputs:
        prompt: "optional only"
      """.trimIndent()
    assertFailsWith<InvalidFeatureTaskRuntimePhaseOutputSchemaError> {
      FeatureTaskRuntimePhaseOutputWireSchema.validatePhaseOutputText(preplan, "preplan")
    }
  }

  @Test
  fun `write_history with the uniform output passes validation`() {
    val writeHistory =
      """
      contract_version: "$VERSION"
      phase_id: "write_history"
      status: "completed"
      summary: "Recorded the boundary history."
      verdict: "recorded"
      produced_outputs:
        value: "Appended one history entry for the touched boundary."
      """.trimIndent()
    FeatureTaskRuntimePhaseOutputWireSchema.validatePhaseOutputText(writeHistory, "write_history")
  }

  @Test
  fun `uniform step with a blank value fails validation`() {
    val blankValue =
      """
      contract_version: "$VERSION"
      phase_id: "write_history"
      status: "completed"
      summary: "Nothing recorded."
      produced_outputs:
        value: "  "
      """.trimIndent()
    assertFailsWith<InvalidFeatureTaskRuntimePhaseOutputSchemaError> {
      FeatureTaskRuntimePhaseOutputWireSchema.validatePhaseOutputText(blankValue, "write_history")
    }
  }

  @Test
  fun `blocked uniform step without a failure disposition fails validation`() {
    val blocked =
      """
      contract_version: "$VERSION"
      phase_id: "pr"
      status: "blocked"
      summary: "Remote rejected the push."
      produced_outputs:
        value: "The remote rejected the branch push."
      """.trimIndent()
    assertFailsWith<InvalidFeatureTaskRuntimePhaseOutputSchemaError> {
      FeatureTaskRuntimePhaseOutputWireSchema.validatePhaseOutputText(blocked, "pr")
    }
  }

  @Test
  fun `a stored previous-version record without the uniform output still decodes`() {
    val stored =
      """
      contract_version: "$FEATURE_TASK_RUNTIME_PREVIOUS_CONTRACT_VERSION"
      phase_id: "write_history"
      status: "blocked"
      summary: "History write skipped."
      produced_outputs: {}
      """.trimIndent()
    FeatureTaskRuntimePhaseOutputWireSchema.validatePhaseOutputText(stored, "write_history")
  }

  private fun buildPhaseEnvelope(buildReceipt: String): String =
    """{"contract_version":"$VERSION","phase_id":"build","status":"completed",""" +
      """"summary":"Build satisfied by runtime-owned gate execution.",""" +
      """"verdict":"satisfied","produced_outputs":{"build_receipt":$buildReceipt}}"""
}
