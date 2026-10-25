package skillbill.workflow.taskruntime.model.audit.interaction

import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionProseAudit
import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionRepairAdmission
import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionRepairCriterion
import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionRepairOutcome
import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionRepairOutcomeKind
import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionRepairStage
import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionRepairState
import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionSourceDisposition
import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionValidationObligation
import skillbill.workflow.taskruntime.model.audit.originalAcceptanceCriterionAlias
import skillbill.workflow.taskruntime.model.audit.recordProseAudit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull

class AcceptanceCriterionInteractionTraceTest {
  private val scenario = AcceptanceCriterionInteractionTarget("IS-001", listOf("AC-001", "AC-002"))
  private val pair = AcceptanceCriterionInteractionTarget("AC-001+AC-002", listOf("AC-001", "AC-002"))

  @Test
  fun `parser accepts the three verdict shapes and ignores unlisted scenarios`() {
    val prose =
      """
      Traced the flows.
      Interaction IS-001: broken owner AC-002 trace src/Flow.kt:10 then src/Store.kt:44-50
      **Interaction AC-001+AC-002: holds trace src/Shared.kt:7**
      Interaction IS-009: holds trace src/Other.kt:1
      """.trimIndent()

    val parsed =
      assertIs<AcceptanceCriterionInteractionTraceParse.Parsed>(
        AcceptanceCriterionInteractionTraceParser.parse(prose, listOf(scenario, pair)),
      )

    assertEquals(
      listOf(
        AcceptanceCriterionInteractionEntry(
          "IS-001",
          AcceptanceCriterionInteractionVerdict.BROKEN,
          "AC-002",
          "src/Flow.kt:10 then src/Store.kt:44-50",
        ),
        AcceptanceCriterionInteractionEntry(
          "AC-001+AC-002",
          AcceptanceCriterionInteractionVerdict.HOLDS,
          null,
          "src/Shared.kt:7",
        ),
      ),
      parsed.entries,
    )
    assertEquals(listOf("IS-009"), parsed.ignoredRefs)
  }

  @Test
  fun `parser rejects owners outside the scenario missing citations missing lines and duplicates`() {
    listOf(
      "Interaction IS-001: broken owner AC-003 trace src/A.kt:1",
      "Interaction IS-001: broken owner AC-001 trace no citation here",
      "Interaction IS-001: holds trace nothing cited",
      "Interaction IS-001: unknown",
      "no scenario line at all",
      "Interaction IS-001: holds trace src/A.kt:1\nInteraction IS-001: holds trace src/B.kt:2",
    ).forEach { prose ->
      assertIs<AcceptanceCriterionInteractionTraceParse.Malformed>(
        AcceptanceCriterionInteractionTraceParser.parse(prose, listOf(scenario)),
        prose,
      )
    }
  }

  @Test
  fun `a broken owner reopens its criterion and a later hold repairs the scenario`() {
    val accepted = acceptedState()
    val stamped = accepted.requireInteractionTrace(emptyList())
    assertEquals(AcceptanceCriterionRepairStage.ACCEPTED, stamped.stage)
    assertEquals(accepted.specDigest, stamped.interactionTrace.tracedSpecDigest)
    assertEquals(accepted.revision + 1, stamped.revision)

    val required = accepted.requireInteractionTrace(listOf(scenario))
    assertEquals(AcceptanceCriterionRepairStage.INTERACTION_TRACE_REQUIRED, required.stage)
    assertEquals(listOf("IS-001"), required.interactionTrace.pendingScenarioRefs)
    assertEquals(required, AcceptanceCriterionRepairState.restore(required))

    val broken =
      traceSettlement(
        "trace:1",
        AcceptanceCriterionInteractionEntry(
          "IS-001",
          AcceptanceCriterionInteractionVerdict.BROKEN,
          "AC-002",
          "src/Flow.kt:10",
        ),
      )
    val reopened = assertNotNull(required.recordInteractionTrace(broken))
    assertEquals(AcceptanceCriterionRepairStage.DISCOVERY_ADMITTED, reopened.stage)
    assertEquals(listOf("AC-002"), reopened.unresolvedQueue)
    assertEquals(listOf("IS-001"), reopened.interactionTrace.brokenScenarioRefs)
    assertEquals(reopened, AcceptanceCriterionRepairState.restore(reopened))
    assertEquals(reopened, reopened.recordInteractionTrace(broken))

    val repaired =
      assertNotNull(
        assertNotNull(reopened.reserve("reservation-1"))
          .completeRepairAwaitingTargetedVerification("reservation-1", "repair:1", "d".repeat(64), "source:2"),
      )
    val closed =
      assertNotNull(
        repaired.record(
          AcceptanceCriterionRepairOutcome(
            outcomeId = "outcome-1",
            reservationId = "reservation-1",
            criterionRef = "AC-002",
            digest = "b".repeat(64),
            kind = AcceptanceCriterionRepairOutcomeKind.TARGETED_SUCCESS,
            evidenceRef = "AC-002 fixed at src/Store.kt:44",
          ),
        ),
      )
    assertEquals(AcceptanceCriterionRepairStage.ACCEPTED, closed.stage)
    val retrace = closed.requireInteractionTrace(listOf(scenario))
    assertEquals(listOf("IS-001"), retrace.interactionTrace.pendingScenarioRefs)
    val holds =
      assertNotNull(
        retrace.recordInteractionTrace(
          traceSettlement(
            "trace:2",
            AcceptanceCriterionInteractionEntry(
              "IS-001",
              AcceptanceCriterionInteractionVerdict.HOLDS,
              null,
              "src/Flow.kt:10",
            ),
          ),
        ),
      )
    assertEquals(AcceptanceCriterionRepairStage.ACCEPTED, holds.stage)
    assertEquals(listOf("IS-001"), holds.interactionTrace.repairedScenarioRefs)
    assertEquals(emptyList(), holds.interactionTrace.brokenScenarioRefs)
  }

  @Test
  fun `a settlement that does not cover the pending scenarios is refused`() {
    val required = acceptedState().requireInteractionTrace(listOf(scenario, pair))
    val partial =
      traceSettlement(
        "trace:1",
        AcceptanceCriterionInteractionEntry(
          "IS-001",
          AcceptanceCriterionInteractionVerdict.HOLDS,
          null,
          "src/Flow.kt:10",
        ),
      )

    assertEquals(null, required.recordInteractionTrace(partial))
  }

  @Test
  fun `closed criteria that cite the same production file form a pair and test files do not`() {
    fun closed(
      ref: String,
      evidence: String,
    ) = criterion(ref, ref.takeLast(1).toInt(), ref).copy(
      sourceDisposition = AcceptanceCriterionSourceDisposition.SOURCE_CLOSED,
      closureEvidenceRef = evidence,
    )
    val criteria =
      listOf(
        closed("AC-001", "AC-001 at src/Store.kt:10 and src/StoreTest.kt:3"),
        closed("AC-002", "AC-002 at src/Store.kt:44"),
        closed("AC-003", "AC-003 at src/StoreTest.kt:9"),
      )

    val pairs = AcceptanceCriterionEvidencePaths.sharedFilePairs(criteria, { it.endsWith("Test.kt") })

    assertEquals(listOf("AC-001+AC-002"), pairs.map { it.pairRef })
    assertEquals(listOf("AC-001", "AC-002"), pairs.single().target().allowedOwners)
  }

  private fun traceSettlement(
    outputId: String,
    vararg entries: AcceptanceCriterionInteractionEntry,
  ) = AcceptanceCriterionInteractionTraceSettlement(
    outputId,
    "e".repeat(64),
    "f".repeat(64),
    "checkpoint:$outputId",
    entries.toList(),
  )

  private fun acceptedState(): AcceptanceCriterionRepairState {
    val initial =
      assertNotNull(
        AcceptanceCriterionRepairState.admit(
          AcceptanceCriterionRepairAdmission(
            "catalog",
            "execution",
            "a".repeat(64),
            listOf("first", "second"),
            listOf(criterion("AC-001", 1, "first"), criterion("AC-002", 2, "second")),
            emptyList(),
          ),
        ),
      )
    val discovery =
      AcceptanceCriterionProseAudit(
        "full:1",
        initial.revision + 1,
        null,
        false,
        "b".repeat(64),
        "AC-001 and AC-002 need a focused audit.",
        "c".repeat(64),
        "source:1",
      )
    var state = assertNotNull(initial.recordProseAudit(discovery))
    listOf("AC-001", "AC-002").forEach { ref ->
      state =
        assertNotNull(
          state.recordProseAudit(
            discovery.copy(
              id = "focused:$ref",
              revision = state.revision + 1,
              criterionRef = ref,
              satisfied = true,
              prose = "$ref met at src/Flow.kt:10",
            ),
          ),
        )
    }
    assertEquals(AcceptanceCriterionRepairStage.ACCEPTED, state.stage)
    return state
  }

  private fun criterion(
    ref: String,
    ordinal: Int,
    text: String,
  ) = AcceptanceCriterionRepairCriterion(
    canonicalRef = ref,
    originalAlias = originalAcceptanceCriterionAlias(text),
    originalText = text,
    ordinal = ordinal,
    sourceDisposition = AcceptanceCriterionSourceDisposition.SOURCE_UNRESOLVED,
    validationObligation = AcceptanceCriterionValidationObligation.REQUIRED,
    validationOwnerAvailable = true,
    evidenceRef = "audit:$ref",
  )
}
