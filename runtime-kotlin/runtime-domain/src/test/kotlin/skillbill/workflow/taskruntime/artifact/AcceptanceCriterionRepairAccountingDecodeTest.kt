package skillbill.workflow.taskruntime.artifact

import skillbill.error.core.SkillBillRuntimeException
import skillbill.error.shellcontent.FeatureTaskRuntimeFailureCode
import skillbill.workflow.taskruntime.artifact.historical.toHistoricalImplementationProgress
import skillbill.workflow.taskruntime.artifact.historical.toHistoricalRepairState
import skillbill.workflow.taskruntime.artifact.historical.upgradeRepairStateFromPrevious
import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionProseAudit
import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionRepairAdmission
import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionRepairCriterion
import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionRepairDependency
import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionRepairOutcome
import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionRepairOutcomeKind
import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionRepairState
import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionSourceDisposition
import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionValidationObligation
import skillbill.workflow.taskruntime.model.audit.captureForTest
import skillbill.workflow.taskruntime.model.audit.recordIndependently
import skillbill.workflow.taskruntime.model.audit.recordProseAudit
import skillbill.workflow.taskruntime.model.audit.terminal
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimeWorkflowArtifactMap
import skillbill.workflow.taskruntime.model.persistence.FeatureTaskRuntimeImplementationProgress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import skillbill.contracts.workflow.featuretask.AcceptanceCriterionProgressPayloadKeys as ProgressKeys
import skillbill.contracts.workflow.featuretask.AcceptanceCriterionRepairPayloadKeys as Keys
import skillbill.contracts.workflow.featuretask.FeatureTaskRuntimeImplementationProgressPayloadKeys as ImplKeys

class AcceptanceCriterionRepairAccountingDecodeTest {
  @Test
  fun `malformed nested evidence reports the enclosing durable artifact failure code`() {
    val repair = admittedState().asRepairWireMap()
    val implementation =
      FeatureTaskRuntimeImplementationProgress.initial(listOf("AC-001")).asImplementationProgressWireMap()
    val malformedRows = listOf(null, listOf(emptyMap<String, Any?>()))
    for (rows in malformedRows) {
      for (key in listOf(ProgressKeys.COMPLETED_MUTATIONS, ProgressKeys.INDEPENDENT_CHECKS)) {
        assertInvalid(repair + (key to rows))
        val failure =
          assertFailsWith<SkillBillRuntimeException> {
            FeatureTaskRuntimeWorkflowArtifactMap.from(implementation + (key to rows))
              .toImplementationProgress("nested-implementation-evidence")
          }
        assertEquals(FeatureTaskRuntimeFailureCode.INVALID_IMPLEMENTATION_PROGRESS_SCHEMA, failure.code)
        assertNotNull(failure.cause)
      }
    }
    val failure =
      assertFailsWith<SkillBillRuntimeException> {
        FeatureTaskRuntimeWorkflowArtifactMap.from(
          implementation + (ProgressKeys.REPAIR_ACCOUNTING to emptyList<Any>()),
        ).toImplementationProgress("inconsistent-implementation-accounting")
      }
    assertEquals(FeatureTaskRuntimeFailureCode.INVALID_IMPLEMENTATION_PROGRESS_SCHEMA, failure.code)
  }

  @Test
  fun `durable counters and revisions cannot truncate or wrap into an admissible state`() {
    val state = admittedState()
    val wire = state.asRepairWireMap()
    val criterion = assertNotNull((wire[Keys.CRITERIA] as? List<*>)?.single() as? Map<*, *>)
    val corruptions =
      listOf(
        wire + (Keys.REVISION to 0.5),
        wire + (Keys.REVISION to Long.MAX_VALUE.toDouble()),
        wire + (Keys.UNSUCCESSFUL_TOTALS to mapOf("AC-001" to 4_294_967_296L)),
        wire + (Keys.CRITERIA to listOf(criterion + (Keys.ORDINAL to 4_294_967_297L))),
        wire + ("unexpected" to true),
        wire - ProgressKeys.COMPLETED_MUTATIONS,
        wire - ProgressKeys.INDEPENDENT_CHECKS,
        wire - ProgressKeys.REPAIR_BUDGETS,
        wire - ProgressKeys.REPAIR_ACCOUNTING,
        wire + (
          ProgressKeys.REPAIR_BUDGETS to
            listOf(
              mapOf(
                Keys.CRITERION_REF to "AC-001",
                ProgressKeys.RETRY_IDENTITY to "operator-retry",
                ProgressKeys.START_MUTATION_INDEX to 4_294_967_296L,
              ),
            )
        ),
      )
    assertEquals(state, wire.toAcceptanceCriterionRepairState("valid"))
    for (corrupt in corruptions) {
      assertInvalid(corrupt)
    }
  }

  @Test
  fun `oversized accounting fails before durable restoration`() {
    val state = admittedState()
    val oversized =
      state.copy(
        criteria =
          state.criteria.map {
            it.copy(evidenceRef = "x".repeat(AcceptanceCriterionRepairState.MAX_DURABLE_UTF8_BYTES))
          },
      )
    assertFailsWith<SkillBillRuntimeException> { oversized.asRepairWireMap() }
    assertInvalid(
      state.asRepairWireMap() + (
        Keys.CRITERIA to
          oversized.criteria.map {
            (state.asRepairWireMap()[Keys.CRITERIA] as List<*>).single().let { value ->
              (value as Map<*, *>) + (Keys.EVIDENCE_REF to it.evidenceRef)
            }
          }
      ),
    )
  }

  @Test
  fun `fractional nested reservation revisions cannot masquerade as retained identities`() {
    val reserved = assertNotNull(admittedState().reserve("worker-1"))
    val wire = reserved.asRepairWireMap()
    val reservation = wire[Keys.RESERVATION] as Map<*, *>
    for (key in listOf(Keys.REVISION, Keys.STAGE_REVISION)) {
      assertInvalid(wire + (Keys.RESERVATION to (reservation + (key to 1.5))))
      assertInvalid(wire + (Keys.RESERVATION to (reservation + (key to Long.MAX_VALUE.toDouble()))))
    }
  }

  @Test
  fun `direct restoration rejects unknown fields in retained history and live reservation objects`() {
    val retained = retainedHistory()
    val wire = retained.asRepairWireMap()
    assertEquals(retained, wire.toAcceptanceCriterionRepairState("retained-history"))
    assertEquals(wire, wire.toAcceptanceCriterionRepairState("retained-history").asRepairWireMap())
    for (key in listOf(Keys.CRITERIA, Keys.DEPENDENCIES, Keys.OUTCOMES, Keys.SOURCE_TRANSITIONS, Keys.PROSE_AUDITS)) {
      val rows = wire[key] as List<*>
      val first = rows.first() as Map<*, *>
      assertInvalid(wire + (key to (listOf(first + ("unexpected" to true)) + rows.drop(1))))
    }
    val trace = wire[Keys.INTERACTION_TRACE] as Map<*, *>
    assertInvalid(wire + (Keys.INTERACTION_TRACE to (trace + ("unexpected" to true))))
    val reserved = assertNotNull(admittedState().reserve("live-worker")).asRepairWireMap()
    val reservation = reserved[Keys.RESERVATION] as Map<*, *>
    assertInvalid(reserved + (Keys.RESERVATION to (reservation + ("unexpected" to true))))
  }

  @Test
  fun `malformed prose history refuses while absent legacy history stays readable`() {
    val initial = admittedState()
    val initialWire = initial.asRepairWireMap()
    assertEquals(
      initial,
      FeatureTaskRuntimeWorkflowArtifactMap.from(initialWire - Keys.PROSE_AUDITS)
        .toAcceptanceCriterionRepairState("absent-legacy-prose"),
    )
    val populatedWire = retainedHistory().asRepairWireMap()
    val malformedValues =
      listOf(
        null,
        "not-an-array",
        1,
        emptyMap<String, Any?>(),
        listOf(null),
        listOf("not-an-object"),
      )
    for (malformed in malformedValues) {
      assertInvalid(initialWire + (Keys.PROSE_AUDITS to malformed))
      assertInvalid(populatedWire + (Keys.PROSE_AUDITS to malformed))
    }
    assertInvalid(populatedWire + (ProgressKeys.REPAIR_ACCOUNTING to emptyList<Any>()))
  }

  @Test
  fun `historical readers preserve populated outcomes and pending repair without inventing terminal evidence`() {
    val closed = retainedHistory()
    for (version in listOf("0.1", "0.2")) {
      val wire = historicalWire(closed, version)
      val original = wire.toString()
      val read = wire.toHistoricalRepairState("populated-old-store")
      assertEquals(closed.outcomes, read.state.outcomes)
      assertEquals(closed.sourceTransitions, read.state.sourceTransitions)
      assertEquals(closed.proseAudits, read.state.proseAudits)
      assertEquals(closed.budgetStartOutcomeIndex, read.state.budgetStartOutcomeIndex)
      assertEquals(null, read.completedMutations)
      assertEquals(null, read.independentChecks)
      assertEquals(read, wire.toHistoricalRepairState("repeated-read"))
      assertEquals(original, wire.toString())
      assertFailsWith<SkillBillRuntimeException> { wire.toAcceptanceCriterionRepairState("current-only") }
    }
    val reserved = assertNotNull(admittedState().reserve("pending-worker"))
    val pending =
      assertNotNull(
        reserved.captureForTest(
          "pending-worker",
          "worker-output",
          "c".repeat(64),
          "pending-checkpoint",
        ),
      )
    val original = historicalWire(pending, "0.1")
    val read = original.toHistoricalRepairState("pending-old-store")
    assertEquals("pending-worker", read.state.reservation?.reservationId)
    assertEquals("pending-checkpoint", read.state.admittedCheckpointId)
    assertEquals(null, read.completedMutations)
    val upgraded = assertNotNull(original.upgradeRepairStateFromPrevious())
    assertEquals("0.2", upgraded.toHistoricalRepairState("upgraded-old-store").contractVersion)
    val bad = FeatureTaskRuntimeWorkflowArtifactMap.from(original + (Keys.REVISION to 0.5))
    assertFailsWith<SkillBillRuntimeException> { bad.upgradeRepairStateFromPrevious() }
  }

  @Test
  fun `old comparisons retain source facts without acquiring terminal or launch authority`() {
    val current = retainedHistory().asRepairWireMap()
    val mutations = (current[ProgressKeys.COMPLETED_MUTATIONS] as List<*>).map { historicalMutation(it) }
    val checks =
      (current[ProgressKeys.INDEPENDENT_CHECKS] as List<*>).map {
        val row = it as Map<*, *>
        row + (ProgressKeys.MUTATION to historicalMutation(row[ProgressKeys.MUTATION]))
      }
    val previous =
      FeatureTaskRuntimeWorkflowArtifactMap.from(
        historicalWire(retainedHistory(), "0.2") +
          mapOf(
            ProgressKeys.COMPLETED_MUTATIONS to mutations,
            ProgressKeys.INDEPENDENT_CHECKS to checks,
          ),
      )
    val read = previous.toHistoricalRepairState("retained-old-comparisons")
    assertEquals(2, read.completedMutations?.size)
    assertFalse(assertNotNull(read.completedMutations).first().terminal)
    assertEquals(null, read.completedMutations.first().launchAttribution)
    assertEquals(2, read.independentChecks?.size)
    assertEquals(
      retainedHistory().independentChecks.map { it.comparison },
      read.independentChecks?.map { it.comparison },
    )
    assertEquals(emptyList(), read.state.completedMutations)
    assertEquals(emptyList(), read.state.independentChecks)
    val progress =
      FeatureTaskRuntimeWorkflowArtifactMap.from(
        mapOf(
          ImplKeys.CONTRACT_VERSION to "0.1",
          ImplKeys.CRITERION_IDS to listOf("AC-001", "AC-002"),
          ImplKeys.CURRENT_INDEX to 1,
          ImplKeys.WORKER_COUNT to 1,
          ImplKeys.LAUNCH_COUNT to 4,
          ImplKeys.CLOSED_IDS to listOf("AC-001"),
          ProgressKeys.COMPLETED_MUTATIONS to mutations,
          ProgressKeys.INDEPENDENT_CHECKS to checks,
        ),
      )
    val decoded = progress.toHistoricalImplementationProgress("populated-old-progress")
    assertEquals(listOf("AC-001"), decoded.closedIds)
    assertEquals(read.completedMutations, decoded.completedMutations)
    assertEquals(read.independentChecks, decoded.independentChecks)
    val badPrefix = FeatureTaskRuntimeWorkflowArtifactMap.from(progress + (ImplKeys.CLOSED_IDS to emptyList<String>()))
    assertFailsWith<SkillBillRuntimeException> { badPrefix.toHistoricalImplementationProgress("bad-prefix") }
    val forged =
      FeatureTaskRuntimeWorkflowArtifactMap.from(
        previous + (
          ProgressKeys.COMPLETED_MUTATIONS to current[ProgressKeys.COMPLETED_MUTATIONS]
        ),
      )
    assertFailsWith<SkillBillRuntimeException> { forged.toHistoricalRepairState("invented-terminal-facts") }
  }

  private fun historicalMutation(raw: Any?): Map<*, *> =
    (raw as Map<*, *>) - ProgressKeys.TERMINAL_DISPOSITION - ProgressKeys.LAUNCH_ATTRIBUTION

  private fun historicalWire(
    state: AcceptanceCriterionRepairState,
    version: String,
  ): FeatureTaskRuntimeWorkflowArtifactMap {
    val omitted =
      setOf(
        ProgressKeys.COMPLETED_MUTATIONS,
        ProgressKeys.INDEPENDENT_CHECKS,
        ProgressKeys.REPAIR_BUDGETS,
        ProgressKeys.REPAIR_ACCOUNTING,
      ) +
        if (version == "0.1") setOf(Keys.INTERACTION_TRACE) else emptySet()
    val wire = state.asRepairWireMap().toMutableMap()
    omitted.forEach(wire::remove)
    wire[Keys.CONTRACT_VERSION] = version
    val reservation = wire[Keys.RESERVATION] as? Map<*, *>
    if (reservation != null) wire[Keys.RESERVATION] = reservation - Keys.REASONING_REQUIRED
    return FeatureTaskRuntimeWorkflowArtifactMap.from(wire)
  }

  private fun retainedHistory(): AcceptanceCriterionRepairState {
    val initial = admittedState(2)
    var state =
      assertNotNull(
        initial.recordProseAudit(
          AcceptanceCriterionProseAudit(
            "discovery",
            1,
            null,
            false,
            "b".repeat(64),
            "Open criteria: AC-001",
            "c".repeat(64),
            "discovery-checkpoint",
          ),
        ),
      )
    for (number in 1..2) {
      val reservation = "worker-$number"
      state = assertNotNull(state.reserve(reservation))
      state = assertNotNull(state.captureForTest(reservation, "terminal:$number", "d".repeat(64), "checkpoint:$number"))
      state =
        assertNotNull(
          state.recordIndependently(
            AcceptanceCriterionRepairOutcome(
              "check:$number",
              reservation,
              "AC-001",
              "e".repeat(64),
              if (number == 1) {
                AcceptanceCriterionRepairOutcomeKind.TARGETED_FAILURE
              } else {
                AcceptanceCriterionRepairOutcomeKind.TARGETED_SUCCESS
              },
              "checked:$number",
            ),
          ),
        )
      if (number == 1) state = state.resetBudgetForOperatorResume("operator-retry")
    }
    return state
  }

  private fun assertInvalid(wire: Map<String, Any?>) {
    val failure =
      assertFailsWith<SkillBillRuntimeException> {
        FeatureTaskRuntimeWorkflowArtifactMap.from(wire).toAcceptanceCriterionRepairState("corrupt")
      }
    assertEquals(FeatureTaskRuntimeFailureCode.INVALID_ACCEPTANCE_CRITERION_REPAIR_STATE_SCHEMA, failure.code)
  }

  private fun admittedState(count: Int = 1): AcceptanceCriterionRepairState =
    assertNotNull(
      AcceptanceCriterionRepairState.admit(
        AcceptanceCriterionRepairAdmission(
          "catalog",
          "execution",
          "a".repeat(64),
          listOf("first", "second").take(count),
          listOf("first", "second").take(count).mapIndexed { index, text ->
            AcceptanceCriterionRepairCriterion(
              "AC-00${index + 1}",
              null,
              text,
              index + 1,
              AcceptanceCriterionSourceDisposition.SOURCE_UNRESOLVED,
              AcceptanceCriterionValidationObligation.DEFERRED,
              true,
              "audit:${index + 1}",
            )
          },
          if (count == 2) {
            listOf(
              AcceptanceCriterionRepairDependency(
                "AC-002",
                "AC-001",
                "Requires the first fence",
                "discovery",
                "src/Store.kt:1",
              ),
            )
          } else {
            emptyList()
          },
        ),
      ),
    )
}
