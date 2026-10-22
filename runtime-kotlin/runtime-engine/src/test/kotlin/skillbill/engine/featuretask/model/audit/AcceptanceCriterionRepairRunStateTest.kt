package skillbill.engine.featuretask.model.audit

import skillbill.engine.featuretask.phase.prompt.compose.PROMPT_COMPOSER_ISSUE_KEY
import skillbill.engine.featuretask.phase.prompt.compose.composePhasePrompt
import skillbill.engine.featuretask.phase.prompt.compose.promptComposerBriefingFor
import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionProseAudit
import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionRepairAdmission
import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionRepairCriterion
import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionRepairOutcome
import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionRepairOutcomeKind
import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionRepairState
import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionSourceDisposition
import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionValidationObligation
import skillbill.workflow.taskruntime.model.audit.recordProseAudit
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertNotNull

class AcceptanceCriterionRepairRunStateTest {
  @Test
  fun `later repair prompts retain earlier rejection reasons across budget reset without leaking other criteria`() {
    var state = initialState()
    val reasons =
      listOf(
        "A display title is not the original finding section.",
        "Synthetic markdown changes the original report text.",
        "Legacy metadata cannot reconstruct unavailable prose.",
        "Readable retained report text must precede metadata fallback.",
        "Receipt JSON is not an original prose report.",
      )
    reasons.forEachIndexed { index, reason ->
      if (index == 3) {
        val beforeResume = context(state)
        state = state.resetBudgetForOperatorResume()
        val afterResume = context(assertNotNull(AcceptanceCriterionRepairState.restore(state)))
        reasons.take(3).forEach {
          assertContains(beforeResume, it)
          assertContains(afterResume, it)
        }
      }
      state = outcome(state, index + 1, reason, AcceptanceCriterionRepairOutcomeKind.TARGETED_FAILURE)
    }
    val current = context(state)
    reasons.take(
      2,
    ).forEach { assertFalse(current.contains(it), "older history stays outside the three recent outcomes") }
    reasons.drop(2).forEach { assertContains(current, it) }
    listOf("audit_plan_fix", "audit_implement_fix").forEach { phase ->
      val prompt =
        composePhasePrompt(PROMPT_COMPOSER_ISSUE_KEY, promptComposerBriefingFor(phase)) {
          copy(
            currentAcceptanceCriterion = scope(state).currentCriterionContext,
            priorAcceptanceRepairFailures = scope(state).priorRepairFailures,
          )
        }
      reasons.drop(2).forEach { assertContains(prompt, it) }
    }

    state =
      outcome(
        state,
        6,
        "The original report reaches the consumer unchanged.",
        AcceptanceCriterionRepairOutcomeKind.TARGETED_SUCCESS,
      )
    val next = context(state)
    assertContains(next, "criterion AC-002")
    reasons.forEach { assertFalse(next.contains(it), "AC-001 history must not become AC-002 repair scope") }
  }

  private fun scope(state: AcceptanceCriterionRepairState): AcceptanceCriterionRepairRunState =
    AcceptanceCriterionRepairRunState(AcceptanceCriterionRepairHydration.Present(state))

  private fun context(state: AcceptanceCriterionRepairState): String =
    scope(state).let { assertNotNull(it.priorRepairFailures ?: it.currentCriterionContext) }

  private fun initialState(): AcceptanceCriterionRepairState {
    val criteria = listOf("Preserve original report text.", "Display the report.")
    val state =
      assertNotNull(
        AcceptanceCriterionRepairState.admit(
          AcceptanceCriterionRepairAdmission(
            "catalog",
            "execution",
            "a".repeat(64),
            criteria,
            criteria.mapIndexed { index, text ->
              AcceptanceCriterionRepairCriterion(
                "AC-%03d".format(index + 1),
                null,
                text,
                index + 1,
                AcceptanceCriterionSourceDisposition.SOURCE_UNRESOLVED,
                AcceptanceCriterionValidationObligation.DEFERRED,
                true,
                "a".repeat(64),
              )
            },
            emptyList(),
          ),
        ),
      )
    return assertNotNull(
      state.recordProseAudit(
        AcceptanceCriterionProseAudit(
          "discovery",
          state.revision + 1,
          null,
          false,
          "b".repeat(64),
          "Open criteria: AC-001, AC-002",
          "c".repeat(64),
          "checkpoint",
        ),
      ),
    )
  }

  private fun outcome(
    state: AcceptanceCriterionRepairState,
    sequence: Int,
    evidence: String,
    kind: AcceptanceCriterionRepairOutcomeKind,
  ): AcceptanceCriterionRepairState {
    val reservation = "repair-$sequence"
    val reserved = assertNotNull(state.reserve(reservation))
    val awaiting =
      assertNotNull(
        reserved.completeRepairAwaitingTargetedVerification(
          reservation,
          "Repair claim $sequence",
          "d".repeat(64),
          "tree-$sequence",
        ),
      )
    return assertNotNull(
      awaiting.record(
        AcceptanceCriterionRepairOutcome(
          "outcome-$sequence",
          reservation,
          assertNotNull(state.currentCriterionRef),
          "e".repeat(64),
          kind,
          evidence,
        ),
      ),
    )
  }
}
