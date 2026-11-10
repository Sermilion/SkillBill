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
import skillbill.workflow.taskruntime.model.audit.admitQaReaudit
import skillbill.workflow.taskruntime.model.audit.recordProseAudit
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertNotNull

class AcceptanceCriterionRepairRunStateTest {
  @Test
  fun `repair prompts omit earlier rejection reports and expose only focused inputs`() {
    var state = initialState()
    val older = "A display title is not the original finding section."
    val latest = "The original report still loses its body."
    state = outcome(state, 1, older, AcceptanceCriterionRepairOutcomeKind.TARGETED_FAILURE)
    state = outcome(state, 2, latest, AcceptanceCriterionRepairOutcomeKind.TARGETED_FAILURE)
    val focus = assertNotNull(scope(state.resetBudgetForOperatorResume()).promptFocus)
    listOf("audit_plan_fix", "audit_implement_fix").forEach { phase ->
      val prompt =
        composePhasePrompt(PROMPT_COMPOSER_ISSUE_KEY, promptComposerBriefingFor(phase)) {
          copy(acceptanceRepairFocus = focus)
        }
      assertContains(prompt, "AC-001")
      assertContains(prompt, "AC-001.G1: Preserve the report body. @ src/Report.kt:1")
      assertContains(prompt, latest)
      assertContains(prompt, AcceptanceRepairPromptFocus.CHECKPOINT_GUIDANCE)
      assertContains(prompt, AcceptanceRepairPromptFocus.VERIFY_GUIDANCE)
      assertFalse(prompt.contains(older))
      assertFalse(prompt.contains("AC-001.G2: Render the title."))
      assertFalse(prompt.contains("AC-002.G1:"))
      assertFalse(prompt.contains("charged attempts:"))
    }
  }

  @Test
  fun `a reopened criterion excludes verify evidence from its earlier pass`() {
    var state =
      outcome(initialState(), 1, "Earlier failed verify.", AcceptanceCriterionRepairOutcomeKind.TARGETED_FAILURE)
    state = outcome(state, 2, "First criterion closed.", AcceptanceCriterionRepairOutcomeKind.TARGETED_SUCCESS)
    state = outcome(state, 3, "Second criterion closed.", AcceptanceCriterionRepairOutcomeKind.TARGETED_SUCCESS)
    val rechecking = assertNotNull(state.admitQaReaudit(listOf("AC-001")))
    val reopened =
      assertNotNull(
        rechecking.recordProseAudit(
          AcceptanceCriterionProseAudit(
            "reopen",
            rechecking.revision + 1,
            "AC-001",
            false,
            "f".repeat(64),
            "The report body regressed.",
            "c".repeat(64),
            "reopen-checkpoint",
          ),
        ),
      )
    val prompt = assertNotNull(scope(reopened).promptFocus).render()
    assertContains(prompt, "No verify has run for this criterion yet.")
    assertFalse(prompt.contains("Earlier failed verify."))
  }

  private fun scope(state: AcceptanceCriterionRepairState): AcceptanceCriterionRepairRunState =
    AcceptanceCriterionRepairRunState(AcceptanceCriterionRepairHydration.Present(state))

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
          "Open criteria: AC-001, AC-002\n" +
            "AC-001.G1: Preserve the report body. @ src/Report.kt:1\n" +
            "AC-001.G2: Render the title. @ src/Report.kt:2\n" +
            "AC-002.G1: Display the report. @ src/Display.kt:1",
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
          if (kind == AcceptanceCriterionRepairOutcomeKind.TARGETED_SUCCESS) {
            "$evidence\n${state.currentCriterionRef}.G1: closed @ src/Report.kt:1\n" +
              if (state.currentCriterionRef == "AC-001") "AC-001.G2: closed @ src/Report.kt:2" else ""
          } else {
            "$evidence\nAC-001.G2: closed @ src/Report.kt:2"
          },
        ),
      ),
    )
  }
}
