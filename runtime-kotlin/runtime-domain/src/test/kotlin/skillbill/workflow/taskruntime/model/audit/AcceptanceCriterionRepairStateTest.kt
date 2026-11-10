package skillbill.workflow.taskruntime.model.audit

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class AcceptanceCriterionRepairStateTest {
  @Test
  fun `gap progress controls charging and replay leaves the budget unchanged`() {
    val initial = admittedWithTwoGaps()
    val firstReservation = assertNotNull(initial.reserve("reservation-1"))
    val firstVerification =
      assertNotNull(
        firstReservation.completeRepairAwaitingTargetedVerification(
          "reservation-1",
          "repair-1",
          "b".repeat(64),
          "checkpoint-1",
          listOf("src/Foo.kt"),
        ),
      )
    val progress =
      assertNotNull(
        firstVerification.record(
          AcceptanceCriterionRepairOutcome(
            "outcome-1",
            "reservation-1",
            "AC-001",
            "c".repeat(64),
            AcceptanceCriterionRepairOutcomeKind.TARGETED_FAILURE,
            "AC-001.G1: closed @ src/Foo.kt:1\nAC-001.G2: open @ src/Foo.kt:2",
          ),
        ),
      )

    assertEquals(false, progress.outcomes.single().charged)
    assertEquals(2, progress.outcomes.single().openGapsBefore)
    assertEquals(1, progress.outcomes.single().openGapsAfter)
    assertEquals(0, progress.chargedAttempts.getOrDefault("AC-001", 0))
    assertEquals(AcceptanceCriterionRepairStage.DISCOVERY_ADMITTED, progress.stage)
    assertNotNull(AcceptanceCriterionRepairState.restore(progress))

    val secondReservation = assertNotNull(progress.reserve("reservation-2"))
    val secondVerification =
      assertNotNull(
        secondReservation.completeRepairAwaitingTargetedVerification(
          "reservation-2",
          "repair-2",
          "d".repeat(64),
          "checkpoint-2",
          listOf("src/Foo.kt"),
        ),
      )
    val noProgressOutcome =
      AcceptanceCriterionRepairOutcome(
        "outcome-2",
        "reservation-2",
        "AC-001",
        "e".repeat(64),
        AcceptanceCriterionRepairOutcomeKind.TARGETED_FAILURE,
        "AC-001.G2: open @ src/Foo.kt:2",
      )
    val noProgress = assertNotNull(secondVerification.record(noProgressOutcome))

    assertEquals(true, noProgress.outcomes.last().charged)
    assertEquals(1, noProgress.chargedAttempts["AC-001"])
    assertEquals(noProgress, noProgress.record(noProgressOutcome))
    assertEquals(1, noProgress.chargedAttempts["AC-001"])
  }

  private fun admittedWithTwoGaps(): AcceptanceCriterionRepairState {
    val gaps =
      listOf(
        AcceptanceCriterionGap(
          "AC-001.G1",
          "first gap",
          "src/Foo.kt:1",
          AcceptanceCriterionGapStatus.OPEN,
          AcceptanceCriterionGapOrigin.DISCOVERY,
          null,
        ),
        AcceptanceCriterionGap(
          "AC-001.G2",
          "second gap",
          "src/Foo.kt:2",
          AcceptanceCriterionGapStatus.OPEN,
          AcceptanceCriterionGapOrigin.DISCOVERY,
          null,
        ),
      )
    return assertNotNull(
      AcceptanceCriterionRepairState.admit(
        AcceptanceCriterionRepairAdmission(
          "catalog",
          "execution",
          "a".repeat(64),
          listOf("first"),
          listOf(criterion("AC-001", 1, "first").copy(gaps = gaps)),
          emptyList(),
        ),
      ),
    )
  }

  @Test
  fun `operator retry resets only the blocked criterion and retains its evidence`() {
    val criteria = listOf(criterion("AC-001", 1, "first"), criterion("AC-002", 2, "second"))
    var state =
      assertNotNull(
        AcceptanceCriterionRepairState.admit(
          AcceptanceCriterionRepairAdmission(
            "catalog",
            "execution",
            "a".repeat(64),
            listOf("first", "second"),
            criteria,
            emptyList(),
          ),
        ),
      ).recordProseAudit(
        AcceptanceCriterionProseAudit(
          "discovery",
          1,
          null,
          false,
          "b".repeat(64),
          "Open criteria: AC-001, AC-002",
          "c".repeat(64),
          "checkpoint",
        ),
      )!!
    repeat(3) { index ->
      val reservation = "reservation-${index + 1}"
      val verifying =
        assertNotNull(state.reserve(reservation))
          .completeRepairAwaitingTargetedVerification(
            reservation,
            "repair-$index",
            "d".repeat(64),
            "checkpoint-$index",
          )!!
      state = assertNotNull(verifying.record(outcome("outcome-$index", reservation, "AC-001", successful = false)))
    }
    val otherCriterionOutcome =
      AcceptanceCriterionRepairOutcome(
        "other-criterion-outcome",
        "other-criterion-reservation",
        "AC-002",
        "e".repeat(64),
        AcceptanceCriterionRepairOutcomeKind.BLOCKED,
        "external dependency",
        charged = true,
      )
    val preservedGaps = openAndClosedGaps(state.criteria)
    val blocked =
      state.copy(
        outcomes = state.outcomes + otherCriterionOutcome,
        chargedAttempts = state.chargedAttempts + ("AC-002" to 1),
        criteria = preservedGaps,
      )
    val reset = blocked.resetBudgetForOperatorResume()

    assertEquals(AcceptanceCriterionRepairStage.DISCOVERY_ADMITTED, reset.stage)
    assertEquals(0, reset.chargedAttempts.getOrDefault("AC-001", 0))
    assertEquals(1, reset.chargedAttempts["AC-002"])
    assertEquals(blocked.outcomes, reset.outcomes)
    assertEquals(blocked.criteria, reset.criteria)
    assertEquals(blocked.revision + 1, reset.revision)
  }

  private fun openAndClosedGaps(
    criteria: List<AcceptanceCriterionRepairCriterion>,
  ): List<AcceptanceCriterionRepairCriterion> {
    return criteria.map { criterion ->
      criterion.copy(
        gaps =
          listOf(
            AcceptanceCriterionGap(
              "${criterion.canonicalRef}.G1",
              "open gap",
              "src/Foo.kt:1",
              AcceptanceCriterionGapStatus.OPEN,
              AcceptanceCriterionGapOrigin.DISCOVERY,
              null,
            ),
            AcceptanceCriterionGap(
              "${criterion.canonicalRef}.G2",
              "closed gap",
              "src/Foo.kt:2",
              AcceptanceCriterionGapStatus.CLOSED,
              AcceptanceCriterionGapOrigin.REPAIR,
              1,
            ),
          ),
      )
    }
  }

  @Test
  fun `an in-flight repair without retained gaps preserves the verifier conclusion`() {
    val initial =
      assertNotNull(
        AcceptanceCriterionRepairState.admit(
          AcceptanceCriterionRepairAdmission(
            "catalog",
            "execution",
            "a".repeat(64),
            listOf("first"),
            listOf(criterion("AC-001", 1, "first")),
            emptyList(),
          ),
        ),
      )
    val verifying =
      assertNotNull(initial.reserve("reservation"))
        .completeRepairAwaitingTargetedVerification("reservation", "repair", "b".repeat(64), "checkpoint")!!
    val criterion = verifying.criteria.single()
    val report = "The required source behavior is still missing."

    assertEquals(
      AcceptanceCriterionRepairOutcomeKind.TARGETED_FAILURE,
      AcceptanceGapVerificationResolver.resolve(verifying, criterion, report, treeMatches = true).outcomeKind,
    )
    val failed = assertNotNull(verifying.record(outcome("failed", "reservation", "AC-001", successful = false)))
    assertEquals(AcceptanceCriterionSourceDisposition.SOURCE_UNRESOLVED, failed.criteria.single().sourceDisposition)
    assertEquals(1, failed.chargedAttempts["AC-001"])
    assertEquals(failed, AcceptanceCriterionRepairState.restore(failed))
    assertEquals(AcceptanceRepairStep.Discover, AcceptanceRepairDecision.next(failed))

    val accepted = assertNotNull(verifying.record(outcome("passed", "reservation", "AC-001", successful = true)))
    assertEquals(AcceptanceRepairStep.Accepted, AcceptanceRepairDecision.next(accepted))
    assertEquals(accepted, AcceptanceCriterionRepairState.restore(accepted))
  }

  @Test
  fun `decision routes discovery plan repair verification and acceptance from state`() {
    val initial = admitSingleCriterion()
    assertEquals(AcceptanceRepairStep.Discover, AcceptanceRepairDecision.next(initial))
    val discovered =
      assertNotNull(
        initial.recordProseAudit(
          AcceptanceCriterionProseAudit(
            "audit:1",
            initial.revision + 1,
            null,
            false,
            "b".repeat(64),
            "Open criteria: AC-001\nAC-001.G1: missing behavior @ src/Main.kt:12",
            "c".repeat(64),
            "checkpoint:1",
          ),
        ),
      )
    assertEquals(AcceptanceRepairStep.Plan("AC-001"), AcceptanceRepairDecision.next(discovered))
    val planned = assertNotNull(discovered.completePlan("AC-001", 1))
    assertEquals(AcceptanceRepairStep.Repair("AC-001"), AcceptanceRepairDecision.next(planned))
    assertNull(discovered.completePlan("AC-001", 2))
    assertNull(discovered.completePlan("AC-002", 1))
    val escalated =
      planned.copy(
        criteria = planned.criteria.map { it.copy(escalated = true) },
        chargedAttempts = mapOf("AC-001" to 2),
      )
    assertEquals(AcceptanceRepairStep.Plan("AC-001"), AcceptanceRepairDecision.next(escalated))
    val escalatedPlan = assertNotNull(escalated.completePlan("AC-001", 2))
    assertEquals(AcceptanceRepairStep.Repair("AC-001"), AcceptanceRepairDecision.next(escalatedPlan))
    val reserved = assertNotNull(planned.reserve("reservation-1"))
    assertEquals(AcceptanceRepairStep.Repair("AC-001"), AcceptanceRepairDecision.next(reserved))
    val verify =
      assertNotNull(
        reserved.completeRepairAwaitingTargetedVerification(
          "reservation-1",
          "repair:1",
          "d".repeat(64),
          "checkpoint:2",
        ),
      )
    assertEquals(AcceptanceRepairStep.Verify("AC-001"), AcceptanceRepairDecision.next(verify))
    val accepted =
      assertNotNull(
        verify.record(
          AcceptanceCriterionRepairOutcome(
            "outcome:1",
            "reservation-1",
            "AC-001",
            "e".repeat(64),
            AcceptanceCriterionRepairOutcomeKind.TARGETED_SUCCESS,
            "AC-001.G1: closed @ src/Main.kt:12",
          ),
        ),
      )
    assertEquals(AcceptanceRepairStep.Accepted, AcceptanceRepairDecision.next(accepted))
  }

  @Test
  fun `gap parser flags numbering holes while retaining other criteria reports`() {
    val report =
      AcceptanceCriterionGapReportParser.parse(
        """
        Open criteria: AC-001, AC-002
        AC-001.G1: first gap @ src/One.kt:1
        AC-001.G3: skipped number @ src/One.kt:3
        AC-002.G1: second criterion gap @ src/Two.kt:7-9
        """.trimIndent(),
        setOf("AC-001", "AC-002"),
      )

    assertEquals(setOf("AC-001"), report.malformedCriteria)
    assertNotNull(report.malformed)
    assertEquals(listOf("AC-001", "AC-002"), report.entries.map { it.criterionRef })
    assertEquals("src/Two.kt:7-9", report.entries.last().citation)
  }

  @Test
  fun `decision honors trace clarification audit budget and terminal stages`() {
    val admission =
      AcceptanceCriterionRepairAdmission(
        "catalog",
        "execution",
        "a".repeat(64),
        listOf("first"),
        listOf(criterion("AC-001", 1, "first")),
        emptyList(),
      )
    val initial = assertNotNull(AcceptanceCriterionRepairState.admit(admission))
    val openGap =
      AcceptanceCriterionGap(
        "AC-001.G1",
        "missing behavior",
        "src/Main.kt:1",
        AcceptanceCriterionGapStatus.OPEN,
        AcceptanceCriterionGapOrigin.DISCOVERY,
        null,
      )
    val admitted =
      initial.copy(
        criteria = listOf(initial.criteria.single().copy(gaps = listOf(openGap))),
        mode = AcceptanceCriterionRepairMode.CURRENT_CRITERION_TARGETED,
      )

    assertEquals(
      AcceptanceRepairStep.ReadOnlyAudit,
      AcceptanceRepairDecision.next(initial.copy(stage = AcceptanceCriterionRepairStage.INTERACTION_TRACE_REQUIRED)),
    )
    assertEquals(
      AcceptanceRepairStep.ReadOnlyAudit,
      AcceptanceRepairDecision.next(assertNotNull(initial.retainClarification("Clarify the report."))),
    )
    assertEquals(
      AcceptanceRepairStep.ReadOnlyAudit,
      AcceptanceRepairDecision.next(admitted.copy(stage = AcceptanceCriterionRepairStage.READ_ONLY_AUDIT_REQUIRED)),
    )
    assertEquals(
      AcceptanceRepairStep.Blocked("AC-001"),
      AcceptanceRepairDecision.next(admitted.copy(stage = AcceptanceCriterionRepairStage.BUDGET_BLOCKED)),
    )
    assertEquals(
      AcceptanceRepairStep.Blocked("AC-001"),
      AcceptanceRepairDecision.next(
        admitted.copy(
          stage = AcceptanceCriterionRepairStage.DISCOVERY_ADMITTED,
          chargedAttempts = mapOf("AC-001" to 3),
        ),
      ),
    )
    assertEquals(
      AcceptanceRepairStep.Accepted,
      AcceptanceRepairDecision.next(initial.copy(stage = AcceptanceCriterionRepairStage.ACCEPTED)),
    )
    assertEquals(
      AcceptanceRepairStep.Accepted,
      AcceptanceRepairDecision.next(
        initial.copy(
          criteria =
            initial.criteria.map {
              it.copy(sourceDisposition = AcceptanceCriterionSourceDisposition.SOURCE_CLOSED)
            },
          unresolvedQueue = emptyList(),
          currentCriterionRef = null,
          stage = AcceptanceCriterionRepairStage.DISCOVERY_ADMITTED,
        ),
      ),
    )
  }

  @Test
  fun `a focused re-audit settles criteria alone without reopening a full audit`() {
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
        true,
        "b".repeat(64),
        "Every production criterion is met.\nOpen criteria: none",
        "c".repeat(64),
        "source:1",
      )
    val initiallyAccepted = assertNotNull(initial.recordProseAudit(discovery))
    val queued = assertNotNull(initiallyAccepted.admitQaReaudit(listOf("AC-001", "AC-002")))
    assertEquals(listOf("AC-001", "AC-002"), queued.pendingAuditRefs)
    assertEquals(AcceptanceCriterionRepairStage.READ_ONLY_AUDIT_REQUIRED, queued.stage)

    fun AcceptanceCriterionRepairState.focusedAccept(ref: String) =
      assertNotNull(
        recordProseAudit(
          discovery.copy(id = "focused:$ref", revision = revision + 1, criterionRef = ref, satisfied = true),
        ),
      )

    val first = queued.focusedAccept("AC-001")
    assertEquals(listOf("AC-002"), first.pendingAuditRefs)
    assertEquals(AcceptanceCriterionRepairStage.READ_ONLY_AUDIT_REQUIRED, first.stage)
    val accepted = first.focusedAccept("AC-002")
    assertEquals(AcceptanceCriterionRepairStage.ACCEPTED, accepted.stage)
    assertEquals(AcceptanceCriterionRepairMode.ACCEPTED, accepted.mode)
    assertEquals(accepted, AcceptanceCriterionRepairState.restore(accepted))
    assertNull(
      accepted.recordProseAudit(discovery.copy(id = "full:2", revision = accepted.revision + 1)),
      "an accepted queue trusts its settled criteria and admits no later full re-check",
    )
  }

  @Test
  fun `discovery accepts unnamed criteria and each named gap is repaired and verified once before moving on`() {
    val initial =
      assertNotNull(
        AcceptanceCriterionRepairState.admit(
          AcceptanceCriterionRepairAdmission(
            "catalog",
            "execution",
            "a".repeat(64),
            listOf("first", "second", "third"),
            listOf(
              criterion("AC-001", 1, "first"),
              criterion("AC-002", 2, "second"),
              criterion("AC-003", 3, "third"),
            ),
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
        "AC-001 and AC-003 are met. AC-002 lets a second caller bypass the reservation.\nOpen criteria: AC-002",
        "c".repeat(64),
        "source:1",
      )

    val queued = assertNotNull(initial.recordProseAudit(discovery))

    assertEquals(listOf("AC-002"), queued.unresolvedQueue)
    assertEquals("AC-002", queued.currentCriterionRef)
    assertEquals(emptyList(), queued.pendingAuditRefs, "no focused audit precedes the repair")
    assertEquals(AcceptanceCriterionRepairStage.DISCOVERY_ADMITTED, queued.stage)
    assertEquals(
      listOf(AcceptanceCriterionSourceDisposition.SOURCE_CLOSED, AcceptanceCriterionSourceDisposition.SOURCE_CLOSED),
      queued.criteria.filter { it.canonicalRef != "AC-002" }.map { it.sourceDisposition },
    )
    assertEquals(queued, AcceptanceCriterionRepairState.restore(queued))

    val repaired =
      assertNotNull(
        assertNotNull(queued.reserve("reservation-1"))
          .completeRepairAwaitingTargetedVerification("reservation-1", "repair:1", "d".repeat(64), "checkpoint:1"),
      )
    val accepted = assertNotNull(repaired.record(outcome("outcome-1", "reservation-1", "AC-002", successful = true)))

    assertEquals(AcceptanceCriterionRepairStage.ACCEPTED, accepted.stage, "verification is the only post-repair audit")
    assertEquals(accepted, AcceptanceCriterionRepairState.restore(accepted))
  }

  @Test
  fun `a discovery without a readable open criteria line falls back to auditing every criterion`() {
    assertNull(openCriteriaNamedIn("AC-002 remains open."))
    assertEquals(emptySet(), openCriteriaNamedIn("Open criteria: none"))
    assertEquals(setOf("AC-002", "AC-005"), openCriteriaNamedIn("Gaps below.\n**Open criteria:** ac-002, AC-005"))
  }

  @Test
  fun `an initial discovery that finds every criterion met is accepted at once`() {
    val initial =
      assertNotNull(
        AcceptanceCriterionRepairState.admit(
          AcceptanceCriterionRepairAdmission(
            "catalog",
            "execution",
            "a".repeat(64),
            listOf("first"),
            listOf(criterion("AC-001", 1, "first")),
            emptyList(),
          ),
        ),
      )
    val accepted =
      assertNotNull(
        initial.recordProseAudit(
          AcceptanceCriterionProseAudit(
            "full:1",
            initial.revision + 1,
            null,
            true,
            "b".repeat(64),
            "Every production criterion is met.\nOpen criteria: none",
            "c".repeat(64),
            "source:1",
          ),
        ),
      )
    assertEquals(AcceptanceCriterionRepairStage.ACCEPTED, accepted.stage)
    assertEquals(emptyList(), accepted.pendingAuditRefs)
    assertEquals(accepted, AcceptanceCriterionRepairState.restore(accepted))
  }

  @Test
  fun `admission keeps composite criterion identity and orders prerequisites before ordinal ties`() {
    val state =
      AcceptanceCriterionRepairState.admit(
        AcceptanceCriterionRepairAdmission(
          catalogId = "catalog-1",
          executionId = "execution-1",
          specDigest = "a".repeat(64),
          authoredCriteria = listOf("first", "S1-002. First part. Second part.", "third"),
          criteria =
            listOf(
              criterion("AC-002", 2, "S1-002. First part. Second part."),
              criterion("AC-003", 3, "third"),
              criterion("AC-001", 1, "first"),
            ),
          dependencies = listOf(dependency("AC-002", "AC-003")),
        ),
      )

    assertNotNull(state)
    assertEquals(listOf("AC-001", "AC-003", "AC-002"), state.unresolvedQueue)
    assertEquals("S1-002. First part. Second part.", state.criteria[1].originalText)
    assertNull(
      AcceptanceCriterionRepairState.admit(
        AcceptanceCriterionRepairAdmission(
          catalogId = "catalog-1",
          executionId = "execution-1",
          specDigest = "a".repeat(64),
          authoredCriteria = listOf("first"),
          criteria = listOf(criterion("AC-001", 1, "first")),
          dependencies = listOf(dependency("AC-001", "AC-001")),
        ),
      ),
    )
  }

  @Test
  fun `success and identical replay preserve unsuccessful budget and do not advance twice`() {
    val initial =
      AcceptanceCriterionRepairState.admit(
        AcceptanceCriterionRepairAdmission(
          catalogId = "catalog-1",
          executionId = "execution-1",
          specDigest = "a".repeat(64),
          authoredCriteria = listOf("first", "second"),
          criteria = listOf(criterion("AC-001", 1, "first"), criterion("AC-002", 2, "second")),
          dependencies = emptyList(),
        ),
      )!!
    val reserved = initial.reserve("reservation-1")!!
    val verified =
      reserved.completeRepairAwaitingTargetedVerification(
        "reservation-1",
        "repair:1",
        "d".repeat(64),
        "checkpoint:1",
      )!!
    val failed = verified.record(outcome("outcome-1", "reservation-1", "AC-001", successful = false))!!
    assertEquals(failed, failed.record(outcome("outcome-1", "reservation-1", "AC-001", successful = false)))
    val reservedAgain = failed.reserve("reservation-2")!!
    val success = outcome("outcome-2", "reservation-2", "AC-001", successful = true)
    val repaired =
      reservedAgain.completeRepairAwaitingTargetedVerification(
        "reservation-2",
        "repair:2",
        "d".repeat(64),
        "checkpoint:2",
      )!!
    val closed = repaired.record(success)!!

    assertEquals(1, closed.chargedAttempts["AC-001"])
    assertEquals("AC-002", closed.currentCriterionRef)
    assertEquals(closed, closed.record(success))
    assertNull(closed.record(success.copy(digest = "a".repeat(64))))
  }

  @Test
  fun `successful verification closes open gaps at the new state revision and records charge facts`() {
    val initial =
      assertNotNull(
        AcceptanceCriterionRepairState.admit(
          AcceptanceCriterionRepairAdmission(
            "catalog",
            "execution",
            "a".repeat(64),
            listOf("first"),
            listOf(criterion("AC-001", 1, "first")),
            emptyList(),
          ),
        ),
      ).copy(
        criteria =
          listOf(
            criterion("AC-001", 1, "first").copy(
              gaps =
                listOf(
                  AcceptanceCriterionGap(
                    "AC-001.G1",
                    "The required source check is missing.",
                    "spec.md:12",
                    AcceptanceCriterionGapStatus.OPEN,
                    AcceptanceCriterionGapOrigin.DISCOVERY,
                    null,
                  ),
                ),
            ),
          ),
      )
    val verified =
      initial.reserve("reservation")!!
        .completeRepairAwaitingTargetedVerification(
          "reservation",
          "repair:evidence",
          "b".repeat(64),
          "checkpoint",
        )!!
    val closed = assertNotNull(verified.record(outcome("outcome", "reservation", "AC-001", successful = true)))
    val gap = closed.criteria.single().gaps.single()

    assertEquals(AcceptanceCriterionGapStatus.CLOSED, gap.status)
    assertEquals(closed.revision, gap.closedRevision)
    assertEquals(false, closed.outcomes.single().charged)
    assertEquals(1, closed.outcomes.single().openGapsBefore)
    assertEquals(0, closed.outcomes.single().openGapsAfter)
  }

  @Test
  fun `coherence rejects open gaps on closed criteria and unbacked charged attempts`() {
    val initial =
      AcceptanceCriterionRepairState.admit(
        AcceptanceCriterionRepairAdmission(
          "catalog",
          "execution",
          "a".repeat(64),
          listOf("first"),
          listOf(criterion("AC-001", 1, "first")),
          emptyList(),
        ),
      )!!
    val openGap =
      AcceptanceCriterionGap(
        "AC-001.G1",
        "The required source check is missing.",
        "spec.md:12",
        AcceptanceCriterionGapStatus.OPEN,
        AcceptanceCriterionGapOrigin.REPAIR,
        null,
      )
    val withGap = initial.copy(criteria = listOf(initial.criteria.single().copy(gaps = listOf(openGap))))
    assertNotNull(AcceptanceCriterionRepairState.restore(withGap))
    assertNull(
      AcceptanceCriterionRepairState.restore(
        withGap.copy(
          criteria =
            listOf(
              withGap.criteria.single().copy(
                sourceDisposition = AcceptanceCriterionSourceDisposition.SOURCE_CLOSED,
              ),
            ),
          unresolvedQueue = emptyList(),
          currentCriterionRef = null,
          mode = AcceptanceCriterionRepairMode.ACCEPTED,
          stage = AcceptanceCriterionRepairStage.ACCEPTED,
        ),
      ),
    )
    assertNull(AcceptanceCriterionRepairState.restore(withGap.copy(chargedAttempts = mapOf("AC-001" to 1))))
  }

  @Test
  fun `five criteria advance without spending another criterion's three failure budget`() {
    val criteria =
      (1..5).map { ordinal ->
        criterion("AC-00$ordinal", ordinal, "criterion $ordinal")
      }
    var state =
      assertNotNull(
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
      )
    state =
      assertNotNull(
        state.recordProseAudit(
          AcceptanceCriterionProseAudit(
            "discovery",
            state.revision + 1,
            null,
            false,
            "b".repeat(64),
            "Open criteria: ${criteria.joinToString { it.canonicalRef }}",
            "c".repeat(64),
            "checkpoint",
          ),
        ),
      )
    assertEquals(emptyMap(), state.chargedAttempts)

    fun repair(
      ref: String,
      attempt: Int,
      successful: Boolean,
    ) {
      val reservationId = "$ref:$attempt"
      val reserved = assertNotNull(state.reserve(reservationId))
      val completed =
        assertNotNull(
          reserved.completeRepairAwaitingTargetedVerification(
            reservationId,
            "repair:$reservationId",
            "d".repeat(64),
            "checkpoint:$reservationId",
          ),
        )
      state = assertNotNull(completed.record(outcome(reservationId, reservationId, ref, successful)))
      state = assertNotNull(AcceptanceCriterionRepairState.restore(state))
    }

    listOf(0, 1, 2, 1).forEachIndexed { index, failures ->
      val ref = criteria[index].canonicalRef
      assertEquals(ref, state.currentCriterionRef)
      assertEquals(0, state.chargedAttempts.getOrDefault(ref, 0))
      repeat(failures) { attempt ->
        repair(ref, attempt + 1, successful = false)
        assertEquals(attempt + 1, state.chargedAttempts[ref])
        assertEquals(AcceptanceCriterionRepairStage.DISCOVERY_ADMITTED, state.stage)
      }
      repair(ref, failures + 1, successful = true)
      assertEquals(failures, state.chargedAttempts.getOrDefault(ref, 0))
      assertEquals(criteria[index + 1].canonicalRef, state.currentCriterionRef)
      assertEquals(0, state.chargedAttempts.getOrDefault(assertNotNull(state.currentCriterionRef), 0))
      assertEquals(AcceptanceCriterionRepairStage.DISCOVERY_ADMITTED, state.stage)
    }

    assertEquals(4, state.chargedAttempts.values.sum())
    repeat(3) { attempt ->
      repair("AC-005", attempt + 1, successful = false)
    }
    assertEquals(3, state.chargedAttempts["AC-005"])
    assertEquals(true, state.criteria.last().escalated)
    assertEquals("AC-005", state.currentCriterionRef)
    assertEquals(AcceptanceCriterionRepairStage.BUDGET_BLOCKED, state.stage)
    assertNull(state.reserve("AC-005:4"))
    assertNull(
      AcceptanceCriterionRepairState.restore(
        state.copy(criteria = state.criteria.dropLast(1) + state.criteria.last().copy(escalated = false)),
      ),
    )
  }

  @Test
  fun `deferred unavailable prerequisites refuse admission while source closed prerequisites remain usable`() {
    val prerequisite =
      criterion("AC-001", 1, "first").copy(
        validationObligation = AcceptanceCriterionValidationObligation.DEFERRED,
        validationOwnerAvailable = false,
      )
    val admission =
      AcceptanceCriterionRepairAdmission(
        "catalog",
        "execution",
        "a".repeat(64),
        listOf("first", "second"),
        listOf(prerequisite, criterion("AC-002", 2, "second")),
        listOf(dependency("AC-002", "AC-001")),
      )
    for (disposition in AcceptanceCriterionSourceDisposition.entries) {
      val candidate =
        admission.copy(
          criteria =
            listOf(
              prerequisite.copy(sourceDisposition = disposition),
              admission.criteria[1],
            ),
        )
      val admitted = AcceptanceCriterionRepairState.admit(candidate)
      if (disposition == AcceptanceCriterionSourceDisposition.SOURCE_CLOSED) {
        assertEquals(listOf("AC-002"), assertNotNull(admitted).unresolvedQueue)
      } else {
        assertNull(admitted)
      }
    }
  }

  @Test
  fun `a discovery with nothing unresolved is accepted and restorable`() {
    val discovered =
      assertNotNull(
        AcceptanceCriterionRepairState.admit(
          AcceptanceCriterionRepairAdmission(
            "catalog",
            "execution",
            "a".repeat(64),
            listOf("first", "second"),
            listOf(criterion("AC-001", 1, "first"), criterion("AC-002", 2, "second")).map {
              it.copy(sourceDisposition = AcceptanceCriterionSourceDisposition.SOURCE_CLOSED)
            },
            emptyList(),
          ),
        ),
      )
    assertEquals(AcceptanceCriterionRepairMode.ACCEPTED, discovered.mode)
    assertEquals(AcceptanceCriterionRepairStage.ACCEPTED, discovered.stage)
    assertEquals(discovered, AcceptanceCriterionRepairState.restore(discovered))
  }

  @Test
  fun `a QA re-audit reopens only the named accepted criteria and refuses foreign or empty scopes`() {
    val accepted =
      assertNotNull(
        AcceptanceCriterionRepairState.admit(
          AcceptanceCriterionRepairAdmission(
            "catalog",
            "execution",
            "a".repeat(64),
            listOf("first", "second", "third"),
            listOf(
              criterion("AC-001", 1, "first"),
              criterion("AC-002", 2, "second"),
              criterion("AC-003", 3, "third"),
            ).map { it.copy(sourceDisposition = AcceptanceCriterionSourceDisposition.SOURCE_CLOSED) },
            emptyList(),
          ),
        ),
      )
    assertEquals(emptyList(), accepted.qaReauditRefs(emptySet()))
    assertNull(accepted.qaReauditRefs(setOf("AC-009")))
    assertNull(accepted.admitQaReaudit(emptyList()))
    assertNull(accepted.admitQaReaudit(listOf("AC-003", "AC-001")))

    val refs = assertNotNull(accepted.qaReauditRefs(setOf("AC-003", "AC-001")))
    val reaudit = assertNotNull(accepted.admitQaReaudit(refs))

    assertEquals(listOf("AC-001", "AC-003"), reaudit.pendingAuditRefs)
    assertEquals("AC-001", reaudit.currentCriterionRef)
    assertEquals(AcceptanceCriterionRepairStage.READ_ONLY_AUDIT_REQUIRED, reaudit.stage)
    assertEquals(accepted.criteria, reaudit.criteria)
    assertEquals(reaudit, AcceptanceCriterionRepairState.restore(reaudit))
    assertNull(reaudit.qaReauditRefs(setOf("AC-002")))
  }

  @Test
  fun `initial discovery stays restorable across clarification and progress until the first audit lands`() {
    val discovered =
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
    val clarified = assertNotNull(discovered.retainClarification("Report a verdict for the full audit."))
    val progressed = clarified.retainProseProgress()

    assertEquals(AcceptanceCriterionRepairMode.INITIAL_DISCOVERY, progressed.mode)
    assertEquals(2L, progressed.revision)
    assertEquals(clarified, AcceptanceCriterionRepairState.restore(clarified))
    assertEquals(progressed, AcceptanceCriterionRepairState.restore(progressed))

    val audited =
      assertNotNull(
        progressed.clearPendingClarification().recordProseAudit(
          AcceptanceCriterionProseAudit(
            "audit:3",
            3,
            null,
            false,
            "b".repeat(64),
            "Open criteria: AC-002\nAC-002.G1: missing implementation @ src/Feature.kt:10",
            "c".repeat(64),
            "checkpoint:3",
          ),
        ),
      )
    assertEquals(AcceptanceCriterionRepairStage.DISCOVERY_ADMITTED, audited.stage)
    assertEquals("AC-002.G1", audited.criteria.single { it.canonicalRef == "AC-002" }.gaps.single().id)
    assertEquals(audited, AcceptanceCriterionRepairState.restore(audited))
    assertNull(
      AcceptanceCriterionRepairState.restore(
        audited.copy(
          mode = AcceptanceCriterionRepairMode.INITIAL_DISCOVERY,
          stage = AcceptanceCriterionRepairStage.DISCOVERY_ADMITTED,
          pendingAuditRefs = emptyList(),
        ),
      ),
    )
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

  private fun dependency(
    dependent: String,
    prerequisite: String,
  ) = AcceptanceCriterionRepairDependency(
    dependentRef = dependent,
    prerequisiteRef = prerequisite,
    reason = "declared by audit output",
    declaringOutputRef = "output:1",
    sourceEvidenceRef = "source:1",
  )

  private fun outcome(
    id: String,
    reservationId: String,
    criterionRef: String,
    successful: Boolean,
  ) = AcceptanceCriterionRepairOutcome(
    outcomeId = id,
    reservationId = reservationId,
    criterionRef = criterionRef,
    digest = if (successful) "b".repeat(64) else "c".repeat(64),
    kind =
      if (successful) {
        AcceptanceCriterionRepairOutcomeKind.TARGETED_SUCCESS
      } else {
        AcceptanceCriterionRepairOutcomeKind.TARGETED_FAILURE
      },
    evidenceRef = if (successful) "$criterionRef.G1: closed @ source.kt:1" else "evidence:$id",
  )

  private fun admitSingleCriterion(): AcceptanceCriterionRepairState =
    assertNotNull(
      AcceptanceCriterionRepairState.admit(
        AcceptanceCriterionRepairAdmission(
          "catalog",
          "execution",
          "a".repeat(64),
          listOf("first"),
          listOf(criterion("AC-001", 1, "first")),
          emptyList(),
        ),
      ),
    )
}
