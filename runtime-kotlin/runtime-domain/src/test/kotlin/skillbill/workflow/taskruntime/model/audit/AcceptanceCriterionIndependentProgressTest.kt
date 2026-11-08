package skillbill.workflow.taskruntime.model.audit

import skillbill.error.core.SkillBillRuntimeException
import skillbill.error.shellcontent.FeatureTaskRuntimeFailureCode
import skillbill.workflow.taskruntime.artifact.AcceptanceCriterionEvidenceWire
import skillbill.workflow.taskruntime.artifact.asImplementationProgressWireMap
import skillbill.workflow.taskruntime.artifact.toImplementationProgress
import skillbill.workflow.taskruntime.model.persistence.FeatureTaskRuntimeImplementationProgress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class AcceptanceCriterionIndependentProgressTest {
  private val resolved =
    AcceptanceCriterionResolvedObligation(
      "transaction-fence",
      "Production writes validate the transaction fence",
      listOf("before:src/Store.kt:10"),
      listOf("after:src/Store.kt:12"),
    )
  private val preserved =
    AcceptanceCriterionPreservedObligation(
      "run-identity",
      "Production writes preserve the run identity",
      listOf("after:src/Store.kt:5"),
    )
  private val established =
    listOf(
      AcceptanceCriterionEstablishedObligation(
        preserved.obligationId,
        preserved.obligation,
        listOf("before:src/Store.kt:5"),
      ),
    )
  private val comparison =
    AcceptanceCriterionIndependentProgress(
      "AC-001", "reservation", "a".repeat(64), "b".repeat(64), "before", "after", 2, "c".repeat(64),
      listOf(resolved), listOf(preserved), listOf("Rollback still needs a fence"),
      AcceptanceCriterionProgressClassification.VERIFIED_PARTIAL_PROGRESS,
    )
  private val context =
    AcceptanceCriterionProgressContext(
      "catalog", "d".repeat(64), "execution", "AC-001", "reservation", "worker", "e".repeat(64),
      comparison.baselineTreeDigest, "before", comparison.currentTreeDigest, "after", 2, comparison.auditBindingDigest,
      "checker", "checker-output", "f".repeat(64), true,
      (resolved.baselineCitations + established.flatMap { it.currentCitations }).toSet(),
      (resolved.currentCitations + preserved.currentCitations).toSet(), established,
      listOf(resolved), listOf(preserved),
      AcceptanceCriterionCompletedMutation(
        "catalog", "d".repeat(64), "execution", "AC-001", "reservation", "worker", "worker-output", "e".repeat(64),
        comparison.baselineTreeDigest, "before", comparison.currentTreeDigest, "after", established,
        AcceptanceCriterionMutationDisposition.COMPLETE,
      ),
    )

  @Test
  fun `only a bound independent production comparison earns progress`() {
    assertIs<AcceptanceCriterionProgressAdmission.Admitted>(comparison.admit(context, false))
    assertIs<AcceptanceCriterionProgressAdmission.ClarificationRequired>(comparison.admit(null, false))
    assertIs<AcceptanceCriterionProgressAdmission.ClarificationRequired>(
      comparison.admit(context.copy(completedMutation = null), false),
    )
    val mutation = assertNotNull(context.completedMutation)
    listOf(
      context.copy(completedMutation = mutation.copy(workerOutputId = context.checkerOutputId)),
      context.copy(completedMutation = mutation.copy(workerOutputDigest = "0".repeat(64))),
      context.copy(completedMutation = mutation.copy(establishedObligations = emptyList())),
      context.copy(criterionRef = "AC-002"),
      context.copy(reservationId = "other-attempt"),
      context.copy(baselineTreeDigest = "0".repeat(64)),
      context.copy(currentCheckpointId = "stale"),
      context.copy(verificationRevision = 3),
      context.copy(auditBindingDigest = "0".repeat(64)),
      context.copy(checkerExecutionId = "worker"),
      context.copy(checkerExecutionId = ""),
      context.copy(sourceUnchangedDuringCheck = false),
      context.copy(baselineCitations = emptySet()),
      context.copy(currentCitations = emptySet()),
      context.copy(baselineCitations = context.currentCitations, currentCitations = context.baselineCitations),
      context.copy(establishedObligations = established.map { it.copy(currentCitations = emptyList()) }),
      context.copy(verifiedResolvedObligations = emptyList()),
      context.copy(verifiedPreservedObligations = emptyList()),
    ).forEach { counterfeit ->
      val failure = assertFailsWith<SkillBillRuntimeException> { comparison.admit(counterfeit, false) }
      assertEquals(FeatureTaskRuntimeFailureCode.INVALID_ACCEPTANCE_CRITERION_AUDIT_RESULT_SCHEMA, failure.code)
    }
  }

  @Test
  fun `missing preservation citations and recycled obligations cannot earn relief`() {
    listOf(
      comparison.copy(preservedObligations = emptyList()),
      comparison.copy(preservedObligations = listOf(preserved.copy(currentCitations = emptyList()))),
      comparison.copy(resolvedObligations = listOf(resolved.copy(obligationId = preserved.obligationId))),
      comparison.copy(resolvedObligations = emptyList()),
      comparison.copy(remainingGaps = listOf(" ")),
      comparison.copy(classification = AcceptanceCriterionProgressClassification.NO_PROGRESS),
      comparison.copy(classification = AcceptanceCriterionProgressClassification.SATISFIED),
    ).forEach { counterfeit ->
      assertFailsWith<SkillBillRuntimeException> { counterfeit.admit(context, false) }
    }
  }

  @Test
  fun `even an independent checker cannot drop an established obligation`() {
    val regression = comparison.copy(preservedObligations = emptyList())
    val attestedRegression = context.copy(verifiedPreservedObligations = emptyList())
    assertFailsWith<SkillBillRuntimeException> { regression.admit(attestedRegression, false) }
    val renamed = preserved.copy(obligation = "Writes may replace the run identity")
    assertFailsWith<SkillBillRuntimeException> {
      comparison.copy(preservedObligations = listOf(renamed))
        .admit(context.copy(verifiedPreservedObligations = listOf(renamed)), false)
    }
  }

  @Test
  fun `cursor closure requires a retained independent check and survives reload`() {
    val pending =
      assertNotNull(FeatureTaskRuntimeImplementationProgress.initial(listOf("AC-001")).reserveWorker("reservation"))
    assertNull(pending.closeCurrent())
    val mutation = assertNotNull(context.completedMutation)
    val captured = assertNotNull(pending.retainCompletedMutation(mutation))
    assertNull(captured.closeCurrent())
    val satisfied =
      comparison.copy(
        remainingGaps = emptyList(),
        classification = AcceptanceCriterionProgressClassification.SATISFIED,
      )
    val checked = AcceptanceCriterionCheckedMutation(mutation, satisfied, context)
    val admitted = assertNotNull(captured.retainIndependentCheck(checked))
    val restored = admitted.asImplementationProgressWireMap().toImplementationProgress("retained-check")
    assertEquals(admitted, restored)
    assertEquals(listOf("AC-001"), assertNotNull(restored.closeCurrent()).closedIds)
    assertEquals(checked, AcceptanceCriterionEvidenceWire.checked(AcceptanceCriterionEvidenceWire.checked(checked)))
  }

  @Test
  fun `classification follows obligations rather than changed tree digests`() {
    val stagnant =
      comparison.copy(
        resolvedObligations = emptyList(),
        classification = AcceptanceCriterionProgressClassification.NO_PROGRESS,
      )
    val stagnantContext = context.copy(verifiedResolvedObligations = emptyList())
    assertIs<AcceptanceCriterionProgressAdmission.Admitted>(stagnant.admit(stagnantContext, false))
    val satisfied =
      comparison.copy(
        remainingGaps = emptyList(),
        classification = AcceptanceCriterionProgressClassification.SATISFIED,
      )
    assertIs<AcceptanceCriterionProgressAdmission.Admitted>(satisfied.admit(context, true))
    assertFailsWith<SkillBillRuntimeException> { satisfied.admit(context, false) }
  }
}

internal fun AcceptanceCriterionRepairState.captureForTest(
  reservationId: String,
  evidenceRef: String,
  treeDigest: String,
  checkpointId: String,
): AcceptanceCriterionRepairState? {
  val ref = reservation?.criterionRef ?: return null
  val inventory = independentChecks.lastOrNull { it.mutation.criterionRef == ref }?.establishedObligations().orEmpty()
  val mutation =
    AcceptanceCriterionCompletedMutation(
      catalogId, specDigest, executionId, ref, reservationId, "worker", "worker-output:$reservationId", "e".repeat(64),
      "a".repeat(64), "before", treeDigest, checkpointId, inventory, AcceptanceCriterionMutationDisposition.COMPLETE,
      AcceptanceCriterionRepairLaunch(
        reservationId,
        "provider",
        "model",
        "high",
        "plan",
        "launch:$reservationId",
        repairAccounting(ref).escalationRequired,
      ),
    )
  return retainCompletedMutation(mutation)?.completeRepairAwaitingTargetedVerification(
    reservationId,
    evidenceRef,
    treeDigest,
    checkpointId,
  )
}

internal fun AcceptanceCriterionRepairState.recordIndependently(
  outcome: AcceptanceCriterionRepairOutcome,
): AcceptanceCriterionRepairState? {
  if (outcomes.any { it.reservationId == outcome.reservationId }) return record(outcome)
  val tree = assertNotNull(admittedTreeDigest)
  val checkpoint = assertNotNull(admittedCheckpointId)
  val binding = outcome.auditBindingDigest ?: "a".repeat(64)
  val mutation = completedMutations.single { it.repairAttemptId == outcome.reservationId }
  val resolved =
    if (outcome.successful) {
      listOf(
        AcceptanceCriterionResolvedObligation(
          "obligation:${outcome.reservationId}",
          "Production caller closes ${outcome.criterionRef}",
          listOf("before:src/Store.kt:5"),
          listOf("after:src/Store.kt:6"),
        ),
      )
    } else {
      emptyList()
    }
  val preserved =
    mutation.establishedObligations.map {
      AcceptanceCriterionPreservedObligation(it.obligationId, it.obligation, listOf("after:src/Store.kt:4"))
    }
  val comparison =
    AcceptanceCriterionIndependentProgress(
      outcome.criterionRef, outcome.reservationId, mutation.baselineTreeDigest, tree, "before", checkpoint, revision,
      binding, resolved, preserved, if (outcome.successful) emptyList() else listOf("Production gap"),
      if (outcome.successful) {
        AcceptanceCriterionProgressClassification.SATISFIED
      } else {
        AcceptanceCriterionProgressClassification.NO_PROGRESS
      },
    )
  val baselineCitations =
    resolved.flatMap { it.baselineCitations } +
      mutation.establishedObligations.flatMap { it.currentCitations }
  val context =
    AcceptanceCriterionProgressContext(
      catalogId,
      specDigest,
      executionId,
      outcome.criterionRef,
      outcome.reservationId,
      "worker",
      mutation.workerOutputDigest,
      mutation.baselineTreeDigest,
      "before",
      tree,
      checkpoint,
      revision,
      binding,
      "checker",
      outcome.outcomeId,
      outcome.digest,
      true,
      baselineCitations.toSet(),
      (resolved.flatMap { it.currentCitations } + preserved.flatMap { it.currentCitations }).toSet(),
      mutation.establishedObligations, resolved, preserved, mutation,
    )
  return retainIndependentCheck(AcceptanceCriterionCheckedMutation(mutation, comparison, context))
    ?.record(outcome.copy(auditBindingDigest = binding))
}
