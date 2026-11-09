package skillbill.workflow.taskruntime.model.audit

import skillbill.contracts.workflow.featuretask.AcceptanceCriterionProgressPayloadKeys
import skillbill.error.core.SkillBillRuntimeException
import skillbill.workflow.model.repair.FocusedRepairAction
import skillbill.workflow.model.repair.FocusedRepairStage
import skillbill.workflow.model.repair.FocusedRepairStrategy
import skillbill.workflow.taskruntime.artifact.asImplementationProgressWireMap
import skillbill.workflow.taskruntime.artifact.asRepairWireMap
import skillbill.workflow.taskruntime.artifact.toAcceptanceCriterionRepairState
import skillbill.workflow.taskruntime.artifact.toImplementationProgress
import skillbill.workflow.taskruntime.model.audit.interaction.AcceptanceCriterionInteractionEntry
import skillbill.workflow.taskruntime.model.audit.interaction.AcceptanceCriterionInteractionTarget
import skillbill.workflow.taskruntime.model.audit.interaction.AcceptanceCriterionInteractionTraceSettlement
import skillbill.workflow.taskruntime.model.audit.interaction.AcceptanceCriterionInteractionVerdict
import skillbill.workflow.taskruntime.model.audit.interaction.recordInteractionTrace
import skillbill.workflow.taskruntime.model.audit.interaction.requireInteractionTrace
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimeWorkflowArtifactMap
import skillbill.workflow.taskruntime.model.persistence.FeatureTaskRuntimeImplementationProgress
import skillbill.workflow.taskruntime.model.persistence.FeatureTaskRuntimeImplementationTurnState
import skillbill.workflow.taskruntime.model.persistence.featureTaskRuntimeImplementationTurnState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AcceptanceCriterionRepairAccountingTest {
  @Test
  fun `unfinished and no-change terminal rounds await independent checks and replay counts once`() {
    val reserved = assertNotNull(initial().reserve("attempt-1"))
    val mutation = mutation("attempt-1", AcceptanceCriterionMutationDisposition.INCOMPLETE)
    val captured = assertNotNull(reserved.retainCompletedMutation(mutation))
    assertEquals(AcceptanceCriterionRepairStage.REPAIR_COMPLETED_AWAITING_TARGETED_VERIFICATION, captured.stage)
    assertEquals(listOf("attempt-1"), captured.repairAccounting("AC-001").completedAttemptIds)
    assertEquals(0, captured.repairAccounting("AC-001").noProgressOutcomes)
    assertNull(captured.reserve("attempt-2"))
    assertEquals(captured, captured.retainCompletedMutation(mutation))
    assertEquals(captured, captured.asRepairWireMap().toAcceptanceCriterionRepairState("pending-check"))
    assertIs<FocusedRepairAction.Verify>(
      FocusedRepairStrategy.next(
        listOf(captured.repairAccounting("AC-001").target()),
        FocusedRepairStage.IMPLEMENTATION,
      ),
    )
    val settled = settle(captured, AcceptanceCriterionProgressClassification.NO_PROGRESS)
    assertEquals(1, settled.repairAccounting("AC-001").noProgressOutcomes)
    val noChange = assertNotNull(settled.reserve("attempt-2"))
    val capturedNoChange =
      assertNotNull(
        noChange.retainCompletedMutation(
          mutation(
            "attempt-2",
            AcceptanceCriterionMutationDisposition.NO_CHANGE,
          ),
        ),
      )
    assertEquals(2, capturedNoChange.repairAccounting("AC-001").completedAttemptIds.size)
  }

  @Test
  fun `partial progress preserves cumulative stagnation and two unmet rounds require escalation`() {
    var state = finish(initial(), "attempt-1", AcceptanceCriterionProgressClassification.NO_PROGRESS)
    state = finish(state, "attempt-2", AcceptanceCriterionProgressClassification.VERIFIED_PARTIAL_PROGRESS)
    val accounting = state.repairAccounting("AC-001")
    assertEquals(1, accounting.noProgressOutcomes)
    assertTrue(accounting.escalationRequired)
    assertTrue(accounting.escalatedOpportunityOwed)
    assertEquals(listOf("attempt-1", "attempt-2"), accounting.triggeringAttemptIds)
    state = finish(state, "attempt-3", AcceptanceCriterionProgressClassification.NO_PROGRESS, reasoning = true)
    assertEquals(2, state.repairAccounting("AC-001").noProgressOutcomes)
    assertFalse(state.repairAccounting("AC-001").escalatedOpportunityOwed)
    assertTrue(state.repairAccounting("AC-001").escalationRequired)
    assertEquals(state, state.asRepairWireMap().toAcceptanceCriterionRepairState("partial-progress"))
  }

  @Test
  fun `owed escalated mutation and its check precede exhaustion then explicit retry retains escalation`() {
    var state = initial()
    for (number in 1..3) {
      state =
        finish(
          state,
          "attempt-$number",
          AcceptanceCriterionProgressClassification.NO_PROGRESS,
          reasoning = number >= 3,
        )
    }
    state = state.withUnknownLastLaunch()
    assertEquals(3, state.unsuccessfulTotals["AC-001"])
    assertEquals(AcceptanceCriterionRepairStage.DISCOVERY_ADMITTED, state.stage)
    assertEquals(state, AcceptanceCriterionRepairState.restore(state))
    val reserved = assertNotNull(state.reserve("escalated"))
    val captured = assertNotNull(reserved.retainCompletedMutation(mutation("escalated", reasoning = true)))
    assertEquals(captured, captured.asRepairWireMap().toAcceptanceCriterionRepairState("exhausted-pending"))
    val blocked = settle(captured, AcceptanceCriterionProgressClassification.NO_PROGRESS)
    assertEquals(AcceptanceCriterionRepairStage.BUDGET_BLOCKED, blocked.stage)
    assertNull(blocked.reserve("forbidden"))
    val renewed = blocked.resetBudgetForOperatorResume("operator-retry")
    assertEquals(blocked.completedMutations, renewed.completedMutations)
    assertEquals(blocked.independentChecks, renewed.independentChecks)
    assertEquals(0, renewed.repairAccounting("AC-001").noProgressOutcomes)
    assertTrue(renewed.repairAccounting("AC-001").escalationRequired)
    assertEquals("operator-retry", renewed.repairAccounting("AC-001").budgetIdentity)
    assertEquals(renewed, renewed.resetBudgetForOperatorResume("operator-retry"))
    assertEquals(renewed, renewed.asRepairWireMap().toAcceptanceCriterionRepairState("operator-retry"))
    val satisfied = finish(renewed, "satisfied", AcceptanceCriterionProgressClassification.SATISFIED, reasoning = true)
    assertFalse(satisfied.repairAccounting("AC-001").escalationRequired)
    assertEquals(AcceptanceCriterionRepairStage.ACCEPTED, satisfied.stage)
  }

  @Test
  fun `initial worker reservation binds capture and pending check wins over markers and budget decisions`() {
    val reserved =
      assertNotNull(FeatureTaskRuntimeImplementationProgress.initial(listOf("AC-001")).reserveWorker("worker-1"))
    assertNull(reserved.retainCompletedMutation(mutation("unrelated")))
    assertNull(reserved.copy(workerReservationId = null).reserveWorker("missing-capture"))
    val captured = assertNotNull(reserved.retainCompletedMutation(mutation("worker-1")))
    assertNull(captured.reserveWorker("worker-2"))
    assertNull(captured.reopenCurrent("retry-before-check"))
    assertIs<FeatureTaskRuntimeImplementationTurnState.CheckOwed>(
      featureTaskRuntimeImplementationTurnState(
        captured,
        emptyList(),
      ),
    )
    val check = checked(captured.completedMutations.single(), 1, AcceptanceCriterionProgressClassification.NO_PROGRESS)
    val settled = assertNotNull(captured.retainIndependentCheck(check))
    assertEquals(settled, settled.asImplementationProgressWireMap().toImplementationProgress("initial-check"))
    val renewed = assertNotNull(settled.reopenCurrent("retry-after-check"))
    assertEquals(1, renewed.workerCount)
    assertEquals(settled.completedMutations, renewed.completedMutations)
    assertEquals(0, renewed.accounting("AC-001").noProgressOutcomes)
    assertNotNull(renewed.reserveWorker("worker-2"))
    assertIs<FeatureTaskRuntimeImplementationTurnState.Incoherent>(
      featureTaskRuntimeImplementationTurnState(
        reserved,
        emptyList(),
      ),
    )
  }

  @Test
  fun `initial repairs allow a fourth worker after partial progress and retain exhaustion on reload`() {
    var progress = FeatureTaskRuntimeImplementationProgress.initial(listOf("AC-001", "AC-002"))
    for (number in 1..4) {
      val id = "initial-$number"
      val reserved = assertNotNull(progress.reserveWorker(id))
      val inventory = progress.independentChecks.lastOrNull()?.establishedObligations().orEmpty()
      val capture = mutation(id, reasoning = number >= 3, inventory = inventory)
      val captured = assertNotNull(reserved.retainCompletedMutation(capture))
      val classification =
        if (number == 2) {
          AcceptanceCriterionProgressClassification.VERIFIED_PARTIAL_PROGRESS
        } else {
          AcceptanceCriterionProgressClassification.NO_PROGRESS
        }
      val check = checked(capture, number.toLong(), classification)
      progress = assertNotNull(captured.retainIndependentCheck(check))
      assertEquals(progress, progress.retainIndependentCheck(check))
    }
    assertEquals(4, progress.workerCount)
    assertEquals(3, progress.accounting("AC-001").noProgressOutcomes)
    assertEquals(0, progress.accounting("AC-002").noProgressOutcomes)
    assertNull(progress.reserveWorker("initial-5"))
    assertIs<FeatureTaskRuntimeImplementationTurnState.Exhausted>(
      featureTaskRuntimeImplementationTurnState(progress, emptyList()),
    )
    val restored = progress.asImplementationProgressWireMap().toImplementationProgress("initial-exhausted")
    assertEquals(progress, restored)
    val renewed = assertNotNull(restored.reopenCurrent("initial-retry"))
    assertEquals(progress.completedMutations, renewed.completedMutations)
    assertTrue(renewed.accounting("AC-001").escalationRequired)
    assertNotNull(renewed.reserveWorker("initial-5"))
  }

  @Test
  fun `forged persisted escalation and missing terminal disposition cannot restore authority`() {
    val state = finish(initial(), "attempt-1", AcceptanceCriterionProgressClassification.NO_PROGRESS)
    val wire = state.asRepairWireMap()
    val keys = AcceptanceCriterionProgressPayloadKeys
    val accounting = (wire[keys.REPAIR_ACCOUNTING] as List<*>).single() as Map<*, *>
    val forgedAccounting = accounting + (keys.ESCALATION_REQUIRED to true)
    val forged =
      FeatureTaskRuntimeWorkflowArtifactMap.from(
        wire + (keys.REPAIR_ACCOUNTING to listOf(forgedAccounting)),
      )
    assertFailsWith<SkillBillRuntimeException> { forged.toAcceptanceCriterionRepairState("forged-escalation") }
    val historical =
      mutation("unknown").copy(
        terminalDisposition = AcceptanceCriterionMutationDisposition.HISTORICAL_UNKNOWN,
      )
    val unknown = state.copy(completedMutations = state.completedMutations + historical)
    assertTrue(unknown.repairAccounting("AC-001").evidenceMissing)
    assertNull(unknown.reserve("another-mutation"))
  }

  @Test
  fun `another exhausted criterion does not block the queue head but blocks when selected`() {
    var state = finish(initial(2), "first-satisfied", AcceptanceCriterionProgressClassification.SATISFIED)
    for (number in 1..3) {
      state =
        finish(
          state,
          "second-$number",
          AcceptanceCriterionProgressClassification.NO_PROGRESS,
          reasoning = number >= 3,
        )
    }
    state = state.withUnknownLastLaunch()
    state = finish(state, "second-satisfied", AcceptanceCriterionProgressClassification.SATISFIED, reasoning = true)
    val targets =
      listOf(
        AcceptanceCriterionInteractionTarget("IS-001", listOf("AC-001")),
        AcceptanceCriterionInteractionTarget("IS-002", listOf("AC-002")),
      )
    val trace =
      AcceptanceCriterionInteractionTraceSettlement(
        "regression-trace",
        "e".repeat(64),
        "d".repeat(64),
        "regression-checkpoint",
        targets.map { target ->
          AcceptanceCriterionInteractionEntry(
            target.scenarioRef,
            AcceptanceCriterionInteractionVerdict.BROKEN,
            target.allowedOwners.single(),
            "src/Store.kt:1",
          )
        },
      )
    val reopened = assertNotNull(state.requireInteractionTrace(targets).recordInteractionTrace(trace))
    assertEquals("AC-001", reopened.currentCriterionRef)
    assertEquals(AcceptanceCriterionRepairStage.DISCOVERY_ADMITTED, reopened.stage)
    assertEquals(reopened, AcceptanceCriterionRepairState.restore(reopened))
    val selected = finish(reopened, "first-repaired", AcceptanceCriterionProgressClassification.SATISFIED)
    assertEquals("AC-002", selected.currentCriterionRef)
    assertEquals(AcceptanceCriterionRepairStage.BUDGET_BLOCKED, selected.stage)
    assertEquals(selected, selected.asRepairWireMap().toAcceptanceCriterionRepairState("next-exhausted-criterion"))
    assertNull(selected.reserve("second-forbidden"))
  }

  @Test
  fun `conflicting terminal captures and checks fail without replacing lifetime evidence`() {
    val captured =
      assertNotNull(assertNotNull(initial().reserve("worker-1")).retainCompletedMutation(mutation("worker-1")))
    val original = captured.completedMutations.single()
    assertFailsWith<SkillBillRuntimeException> {
      captured.retainCompletedMutation(original.copy(currentCheckpointId = "different-checkpoint"))
    }
    val check = checked(original, captured.revision, AcceptanceCriterionProgressClassification.NO_PROGRESS)
    val retained = assertNotNull(captured.retainIndependentCheck(check))
    assertFailsWith<SkillBillRuntimeException> {
      retained.retainIndependentCheck(check.copy(context = check.context.copy(checkerOutputDigest = "a".repeat(64))))
    }
    val progress =
      assertNotNull(FeatureTaskRuntimeImplementationProgress.initial(listOf("AC-001")).reserveWorker("worker-1"))
    val implemented = assertNotNull(progress.retainCompletedMutation(original))
    assertFailsWith<SkillBillRuntimeException> {
      implemented.retainCompletedMutation(original.copy(currentCheckpointId = "different-checkpoint"))
    }
    val checkedProgress = assertNotNull(implemented.retainIndependentCheck(check))
    assertFailsWith<SkillBillRuntimeException> {
      checkedProgress.retainIndependentCheck(
        check.copy(context = check.context.copy(checkerOutputDigest = "a".repeat(64))),
      )
    }
    assertEquals(listOf(original), captured.completedMutations)
    assertEquals(listOf(check), checkedProgress.independentChecks)
  }

  @Test
  fun `ordinal overflow and terminal history capacity refuse instead of wrapping or discarding work`() {
    assertFailsWith<SkillBillRuntimeException> { initial().copy(revision = Long.MAX_VALUE).reserve("overflow") }
    val progress = FeatureTaskRuntimeImplementationProgress.initial(listOf("AC-001"))
    assertFailsWith<SkillBillRuntimeException> { progress.copy(workerCount = Int.MAX_VALUE).reserveWorker("overflow") }
    assertFailsWith<SkillBillRuntimeException> { progress.copy(launchCount = Int.MAX_VALUE).launched() }
    val retained = (1..AcceptanceCriterionRepairState.MAX_REPAIR_OUTCOMES).map { mutation("historical-$it") }
    val state = initial().copy(completedMutations = retained)
    assertFailsWith<SkillBillRuntimeException> { state.retainCompletedMutation(mutation("new-worker")) }
    val fullProgress = progress.copy(completedMutations = retained)
    assertFailsWith<SkillBillRuntimeException> { fullProgress.retainCompletedMutation(mutation("new-worker")) }
    assertEquals(retained, state.completedMutations)
    assertEquals(retained, fullProgress.completedMutations)
  }

  private fun AcceptanceCriterionRepairState.withUnknownLastLaunch(): AcceptanceCriterionRepairState {
    val historical = completedMutations.last().copy(launchAttribution = null)
    return copy(
      completedMutations = completedMutations.dropLast(1) + historical,
      independentChecks =
        independentChecks.map {
          if (it.mutation.repairAttemptId == historical.repairAttemptId) {
            it.copy(mutation = historical, context = it.context.copy(completedMutation = historical))
          } else {
            it
          }
        },
      stage = AcceptanceCriterionRepairStage.DISCOVERY_ADMITTED,
    )
  }

  private fun initial(count: Int = 1): AcceptanceCriterionRepairState {
    val criteria =
      (1..count).map { ordinal ->
        AcceptanceCriterionRepairCriterion(
          "AC-${ordinal.toString().padStart(3, '0')}",
          null,
          "Repair the production fence $ordinal",
          ordinal,
          AcceptanceCriterionSourceDisposition.SOURCE_UNRESOLVED,
          AcceptanceCriterionValidationObligation.DEFERRED,
          true,
          "source-audit",
        )
      }
    return assertNotNull(
      AcceptanceCriterionRepairState.admit(
        AcceptanceCriterionRepairAdmission(
          "catalog",
          "execution",
          "a".repeat(64),
          criteria.map { it.originalText },
          criteria,
          emptyList(),
        ),
      ),
    ).copy(mode = AcceptanceCriterionRepairMode.CURRENT_CRITERION_TARGETED)
  }

  private fun mutation(
    id: String,
    disposition: AcceptanceCriterionMutationDisposition = AcceptanceCriterionMutationDisposition.COMPLETE,
    reasoning: Boolean = false,
    inventory: List<AcceptanceCriterionEstablishedObligation> = emptyList(),
  ): AcceptanceCriterionCompletedMutation =
    AcceptanceCriterionCompletedMutation(
      "catalog", "a".repeat(64), "execution", "AC-001", id, "worker:$id", "worker-output:$id", "b".repeat(64),
      "c".repeat(64),
      "baseline:$id",
      if (disposition == AcceptanceCriterionMutationDisposition.NO_CHANGE) "c".repeat(64) else "d".repeat(64),
      "terminal:$id", inventory, disposition,
      AcceptanceCriterionRepairLaunch(
        id,
        "provider",
        "configured-model",
        "configured-effort",
        "execution-plan",
        "launch:$id",
        reasoning,
      ),
    )

  private fun finish(
    state: AcceptanceCriterionRepairState,
    id: String,
    classification: AcceptanceCriterionProgressClassification,
    reasoning: Boolean = false,
  ): AcceptanceCriterionRepairState {
    val ref = assertNotNull(state.currentCriterionRef)
    val inventory =
      state.independentChecks.lastOrNull { it.mutation.criterionRef == ref }
        ?.establishedObligations().orEmpty()
    val reserved = assertNotNull(state.reserve(id))
    val captured =
      assertNotNull(
        reserved.retainCompletedMutation(
          mutation(
            id,
            reasoning = reasoning,
            inventory = inventory,
          ).copy(criterionRef = ref),
        ),
      )
    return settle(captured, classification)
  }

  private fun settle(
    state: AcceptanceCriterionRepairState,
    classification: AcceptanceCriterionProgressClassification,
  ): AcceptanceCriterionRepairState {
    val mutation = state.completedMutations.last()
    val check = checked(mutation, state.revision, classification)
    val retained = assertNotNull(state.retainIndependentCheck(check))
    assertEquals(retained, retained.asRepairWireMap().toAcceptanceCriterionRepairState("checked-before-settlement"))
    val outcome =
      AcceptanceCriterionRepairOutcome(
        check.context.checkerOutputId,
        mutation.repairAttemptId,
        mutation.criterionRef,
        check.context.checkerOutputDigest,
        if (classification == AcceptanceCriterionProgressClassification.SATISFIED) {
          AcceptanceCriterionRepairOutcomeKind.TARGETED_SUCCESS
        } else {
          AcceptanceCriterionRepairOutcomeKind.TARGETED_FAILURE
        },
        "independent source evidence",
        state.revision,
        mutation.currentTreeDigest,
        mutation.currentCheckpointId,
        check.comparison.auditBindingDigest,
      )
    return assertNotNull(retained.record(outcome)).also { assertEquals(it, it.record(outcome)) }
  }

  private fun checked(
    mutation: AcceptanceCriterionCompletedMutation,
    revision: Long,
    classification: AcceptanceCriterionProgressClassification,
  ): AcceptanceCriterionCheckedMutation {
    val resolved =
      if (classification == AcceptanceCriterionProgressClassification.NO_PROGRESS) {
        emptyList()
      } else {
        listOf(
          AcceptanceCriterionResolvedObligation(
            "obligation:${mutation.repairAttemptId}",
            "Production fence closes",
            listOf("before:Store.kt:1"),
            listOf("after:Store.kt:2"),
          ),
        )
      }
    val preserved =
      mutation.establishedObligations.map {
        AcceptanceCriterionPreservedObligation(
          it.obligationId,
          it.obligation,
          listOf("after:Store.kt:3"),
        )
      }
    val comparison =
      AcceptanceCriterionIndependentProgress(
        mutation.criterionRef, mutation.repairAttemptId, mutation.baselineTreeDigest, mutation.currentTreeDigest,
        mutation.baselineCheckpointId, mutation.currentCheckpointId, revision, "e".repeat(64), resolved, preserved,
        if (classification == AcceptanceCriterionProgressClassification.SATISFIED) {
          emptyList()
        } else {
          listOf("Rollback fence remains open")
        },
        classification,
      )
    val baselineCitations =
      resolved.flatMap { it.baselineCitations } +
        mutation.establishedObligations.flatMap { it.currentCitations }
    val context =
      AcceptanceCriterionProgressContext(
        mutation.catalogId, mutation.specDigest, mutation.executionId, mutation.criterionRef, mutation.repairAttemptId,
        mutation.workerExecutionId,
        mutation.workerOutputDigest,
        mutation.baselineTreeDigest,
        mutation.baselineCheckpointId,
        mutation.currentTreeDigest, mutation.currentCheckpointId, revision, comparison.auditBindingDigest,
        "checker:${mutation.repairAttemptId}", "checker-output:${mutation.repairAttemptId}", "f".repeat(64), true,
        baselineCitations.toSet(),
        (resolved.flatMap { it.currentCitations } + preserved.flatMap { it.currentCitations }).toSet(),
        mutation.establishedObligations,
        resolved, preserved, mutation,
      )
    return AcceptanceCriterionCheckedMutation(mutation, comparison, context)
  }
}
