
package skillbill.engine

import skillbill.workflow.taskruntime.model.repair.task.FeatureTaskRuntimeOperatorBlockRetry
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class FeatureTaskRuntimePhasePromptComposerRetryTest {
  @Test
  fun `a real schema failure still receives the schema-correction directive and not the terminal one`() {
    val retry =
      composePhasePrompt(
        PROMPT_COMPOSER_ISSUE_KEY,
        promptComposerBriefingFor("implement"),
      ) { copy(priorSchemaFailure = "produced_outputs must be an object.") }

    assertContains(retry, "REJECTED by the schema gate", false, "schema failure keeps its directive")
    assertTrue(!retry.contains("reported a retryable block"), "schema failure must not get the terminal directive")
  }

  @Test
  fun `an operator blocked-phase retry decision is delivered only to its matching phase`() {
    val reason = "Use fresh-process isolation for Codex CLI workers."
    val retry =
      FeatureTaskRuntimeOperatorBlockRetry(
        phaseId = "implement",
        reason = reason,
        retriedAt = "2026-07-21T16:30:00Z",
      )

    val prompt =
      composePhasePrompt(
        PROMPT_COMPOSER_ISSUE_KEY,
        promptComposerBriefingFor("implement"),
      ) { copy(operatorBlockRetry = retry) }

    assertContains(prompt, "Operator-applied blocked-phase retry decision")
    assertContains(prompt, reason)
    assertFailsWith<IllegalArgumentException> {
      composePhasePrompt(
        PROMPT_COMPOSER_ISSUE_KEY,
        promptComposerBriefingFor("audit"),
      ) { copy(operatorBlockRetry = retry) }
    }
  }

  @Test
  fun `audit restart ignores prior output correction and requires a full check`() {
    val prompt =
      composePhasePrompt(
        PROMPT_COMPOSER_ISSUE_KEY,
        promptComposerBriefingFor("audit"),
      ) { copy(priorSchemaFailure = "<root> must be an object.") }

    assertContains(prompt, "re-check the entire in-scope criterion list from the beginning")
    assertContains(prompt, "Audit is read-only: do not edit files or repair gaps")
    assertTrue(!prompt.contains("salvage"))
    assertTrue(!prompt.contains("do not redo the phase work"))
    assertTrue(!prompt.contains("non_blocking_findings"))
  }

  @Test
  fun `a review parse failure still receives its output correction skeleton`() {
    val prompt =
      composePhasePrompt(
        PROMPT_COMPOSER_ISSUE_KEY,
        promptComposerBriefingFor("review"),
      ) { copy(priorSchemaFailure = "<root> must be an object.") }

    assertContains(prompt, "could NOT parse a single JSON object")
    assertContains(prompt, "last salvage attempt")
    assertContains(prompt, "\"verdict\": \"approved\"")
    assertContains(prompt, "\"findings\": []")
  }

  @Test
  fun `an oversized reconciliation evidence field is told to compress rather than restate`() {
    val retry =
      composePhasePrompt(
        PROMPT_COMPOSER_ISSUE_KEY,
        promptComposerBriefingFor("implement"),
      ) {
        copy(
          priorSchemaFailure =
            "Projection validation failed: implement#produced_outputs: " +
              "\$.reconciliation_evidence.evidence: must be at most 4096 characters long",
        )
      }

    assertContains(retry, "The rejected evidence exceeded 4096 characters")
    assertContains(retry, "bounded SUMMARY, not a verification transcript")
    assertContains(retry, "rejected for length alone")
    assertContains(retry, "applied no edits")
    assertTrue(
      !retry.contains("bounded pointer, not an evidence container"),
      "the pointer-replacement advice belongs to artifact_ref/check_ref only",
    )
  }

  @Test
  fun `any other over-length field receives the compression guidance naming that field`() {
    val retry =
      composePhasePrompt(
        PROMPT_COMPOSER_ISSUE_KEY,
        promptComposerBriefingFor("implement"),
      ) {
        copy(priorSchemaFailure = "\$.deviations[0].note: must be at most 4096 characters long")
      }

    assertContains(retry, "The rejected note exceeded 4096 characters")
    assertContains(retry, "bounded SUMMARY, not a verification transcript")
  }

  @Test
  fun `a non-length field violation adds no compression guidance`() {
    val retry =
      composePhasePrompt(
        PROMPT_COMPOSER_ISSUE_KEY,
        promptComposerBriefingFor("implement"),
      ) {
        copy(
          priorSchemaFailure =
            "\$.reconciliation_evidence.evidence: property 'evidence' is not defined in the schema",
        )
      }

    assertTrue(!retry.contains("bounded SUMMARY"), "a missing/undefined property is not a length violation")
    assertTrue(!retry.contains("bounded pointer"), "no pointer advice either")
  }

  @Test
  fun `a length violation whose cap was truncated away adds no guidance and does not crash`() {
    val retry =
      composePhasePrompt(
        PROMPT_COMPOSER_ISSUE_KEY,
        promptComposerBriefingFor("implement"),
      ) {
        copy(priorSchemaFailure = "Projection validation failed: \$.reconciliation_evidence.ev… [truncated]")
      }

    assertContains(retry, "Previous attempt was REJECTED by the schema gate", false, "still corrects")
    assertTrue(!retry.contains("bounded SUMMARY"), "no length advice without a stated violation")
  }

  @Test
  fun `a maxLength violation with no readable figure still compresses without naming a cap`() {
    val retry =
      composePhasePrompt(
        PROMPT_COMPOSER_ISSUE_KEY,
        promptComposerBriefingFor("implement"),
      ) {
        copy(priorSchemaFailure = "\$.unresolved_items[0]: maxLength constraint violated")
      }

    assertContains(retry, "exceeded its declared limit")
    assertTrue(!retry.contains("exceeded -1 characters"), "the sentinel cap never reaches the prompt")
  }
}
