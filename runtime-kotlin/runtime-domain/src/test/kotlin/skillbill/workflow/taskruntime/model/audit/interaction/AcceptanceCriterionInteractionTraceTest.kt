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
import skillbill.workflow.taskruntime.model.audit.interaction.AcceptanceCriterionRecheckVerdict.HOLDS
import skillbill.workflow.taskruntime.model.audit.interaction.AcceptanceCriterionRecheckVerdict.REGRESSED
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
  fun `extra rechecks do not prevent requested rechecks from settling`() {
    val prose =
      """
      Interaction IS-001: holds trace src/Flow.kt:10
      Recheck AC-001: holds evidence src/Flow.kt:10
      Recheck AC-003: holds evidence src/Other.kt:20
      """.trimIndent()
    val parsed =
      assertIs<AcceptanceCriterionInteractionTraceParse.Parsed>(
        AcceptanceCriterionInteractionTraceParser.parse(prose, listOf(scenario), listOf("AC-001")),
      )

    assertEquals(listOf("AC-003"), parsed.ignoredRefs)
    assertEquals(listOf(AcceptanceCriterionRecheckEntry("AC-001", HOLDS, "src/Flow.kt:10")), parsed.rechecks)
    assertIs<AcceptanceCriterionInteractionTraceParse.Malformed>(
      AcceptanceCriterionInteractionTraceParser.parse(prose, listOf(scenario), listOf("AC-002")),
    )
  }

  @Test
  fun `audit accepts clear prose with source citations`() {
    val prose =
      """
      IS-001 (tap Profiles) holds. Navigation reaches the right route at src/Flow.kt:10.
      AC-001 still holds. The selection is checked at src/Flow.kt:11 and src/State.kt:20.
      """.trimIndent()
    val parsed =
      assertIs<AcceptanceCriterionInteractionTraceParse.Parsed>(
        AcceptanceCriterionInteractionTraceParser.parse(prose, listOf(scenario), listOf("AC-001")),
      )

    assertEquals(AcceptanceCriterionInteractionVerdict.HOLDS, parsed.entries.single().verdict)
    assertEquals("src/Flow.kt:11, src/State.kt:20", parsed.rechecks.single().evidenceRef)
    assertIs<AcceptanceCriterionInteractionTraceParse.Malformed>(
      AcceptanceCriterionInteractionTraceParser.parse(
        "IS-001 (tap Profiles) holds, but the route is not known.\nAC-001 still holds.",
        listOf(scenario),
        listOf("AC-001"),
      ),
    )
    assertIs<AcceptanceCriterionInteractionTraceParse.Malformed>(
      AcceptanceCriterionInteractionTraceParser.parse(
        "AC-001 still regressed. See src/Flow.kt:11.\nRecheck AC-001: holds evidence src/Flow.kt:11",
        emptyList(),
        listOf("AC-001"),
      ),
    )
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

  @Test
  fun `a repair that touches a cited production file schedules a recheck and an uncited file does not`() {
    val state = acceptedState(::citedEvidence)
    val isTest = { path: String -> path.endsWith("Test.kt") }

    val touched = state.recheckUpdateAfter(listOf("src/Store.kt"), isTest)
    assertEquals(listOf("AC-001"), touched.recheckRefs)
    assertEquals(listOf("AC-002"), touched.recheckAnyChangeRefs)
    assertEquals(listOf("AC-002"), touched.newAnyChangeRefs)

    val uncited = state.recheckUpdateAfter(listOf("src/Other.kt"), isTest)
    assertEquals(emptyList(), uncited.recheckRefs)
    assertEquals(listOf("AC-002"), uncited.recheckAnyChangeRefs)

    val untouched = state.recheckUpdateAfter(emptyList(), isTest)
    assertEquals(emptyList(), untouched.recheckRefs + untouched.recheckAnyChangeRefs)

    val underRepair = state.copy(currentCriterionRef = "AC-001").recheckUpdateAfter(listOf("src/Store.kt"), isTest)
    assertEquals(emptyList(), underRepair.recheckRefs)
  }

  @Test
  fun `a holds recheck replaces closure evidence and a regressed recheck reopens the criterion`() {
    val required = recheckState(emptyList())
    assertEquals(AcceptanceCriterionRepairStage.INTERACTION_TRACE_REQUIRED, required.stage)
    assertEquals(listOf("AC-001"), required.interactionTrace.pendingRechecks)
    assertEquals(required, AcceptanceCriterionRepairState.restore(required))

    val holds =
      recheckSettlement("trace:1", emptyList(), AcceptanceCriterionRecheckEntry("AC-001", HOLDS, "src/Store.kt:12"))
    val replaced = assertNotNull(required.recordInteractionTrace(holds))
    assertEquals(AcceptanceCriterionRepairStage.ACCEPTED, replaced.stage)
    assertEquals("src/Store.kt:12", replaced.criterion("AC-001").closureEvidenceRef)
    assertEquals(emptyList(), replaced.interactionTrace.pendingRechecks)
    assertEquals(replaced, AcceptanceCriterionRepairState.restore(replaced))
    assertEquals(replaced, replaced.recordInteractionTrace(holds))

    val regressed =
      recheckSettlement(
        "trace:2",
        emptyList(),
        AcceptanceCriterionRecheckEntry("AC-001", REGRESSED, "src/Store.kt:12"),
      )
    val reopened = assertNotNull(required.recordInteractionTrace(regressed))
    assertEquals(AcceptanceCriterionRepairStage.DISCOVERY_ADMITTED, reopened.stage)
    assertEquals(listOf("AC-001"), reopened.unresolvedQueue)
    assertEquals(reopened, AcceptanceCriterionRepairState.restore(reopened))
  }

  @Test
  fun `the second malformed fallback keeps prior evidence and records pending scenarios as unknown`() {
    val required = recheckState(listOf(scenario))
    val prior = assertNotNull(required.criterion("AC-001").closureEvidenceRef)
    val fallback =
      recheckSettlement(
        "trace:1",
        listOf(
          AcceptanceCriterionInteractionEntry(
            "IS-001",
            AcceptanceCriterionInteractionVerdict.UNKNOWN,
            null,
            "malformed audit output",
          ),
        ),
        AcceptanceCriterionRecheckEntry("AC-001", HOLDS, prior),
      )

    val settled = assertNotNull(required.recordInteractionTrace(fallback))

    assertEquals(AcceptanceCriterionRepairStage.ACCEPTED, settled.stage)
    assertEquals(prior, settled.criterion("AC-001").closureEvidenceRef)
    assertEquals(
      listOf(AcceptanceCriterionUnknownScenario("IS-001", "malformed audit output")),
      settled.unknownScenarios(),
    )
    assertEquals(settled, AcceptanceCriterionRepairState.restore(settled))
  }

  private fun citedEvidence(ref: String): String =
    if (ref == "AC-001") "AC-001 met at src/Store.kt:10" else "AC-002 met per review notes"

  private fun recheckState(targets: List<AcceptanceCriterionInteractionTarget>): AcceptanceCriterionRepairState {
    val accepted = acceptedState(::citedEvidence)
    val scheduled = accepted.copy(interactionTrace = accepted.interactionTrace.copy(recheckRefs = listOf("AC-001")))
    return scheduled.requireInteractionTrace(targets)
  }

  private fun AcceptanceCriterionRepairState.criterion(ref: String): AcceptanceCriterionRepairCriterion =
    criteria.single { it.canonicalRef == ref }

  private fun recheckSettlement(
    outputId: String,
    entries: List<AcceptanceCriterionInteractionEntry>,
    vararg rechecks: AcceptanceCriterionRecheckEntry,
  ) = AcceptanceCriterionInteractionTraceSettlement(
    outputId,
    "e".repeat(64),
    "f".repeat(64),
    "checkpoint:$outputId",
    entries,
    rechecks.toList(),
  )

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

  private fun acceptedState(
    evidence: (String) -> String = { "$it met at src/Flow.kt:10" },
  ): AcceptanceCriterionRepairState {
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
              prose = evidence(ref),
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
