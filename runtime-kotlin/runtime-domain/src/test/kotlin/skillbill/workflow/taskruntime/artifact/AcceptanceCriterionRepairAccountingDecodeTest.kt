package skillbill.workflow.taskruntime.artifact

import skillbill.error.core.SkillBillRuntimeException
import skillbill.error.shellcontent.FeatureTaskRuntimeFailureCode
import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionGap
import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionGapOrigin
import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionGapStatus
import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionLateFinding
import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionRepairAdmission
import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionRepairCriterion
import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionRepairOutcome
import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionRepairOutcomeKind
import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionRepairStage
import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionRepairState
import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionSourceDisposition
import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionValidationObligation
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimeWorkflowArtifactMap
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import skillbill.contracts.workflow.featuretask.AcceptanceCriterionRepairPayloadKeys as Keys

class AcceptanceCriterionRepairAccountingDecodeTest {
  @Test
  fun `wire round trip keeps gaps findings changed paths and charged outcomes`() {
    val awaiting = awaitingSecondCriterion()
    assertEquals(AcceptanceCriterionRepairStage.REPAIR_COMPLETED_AWAITING_TARGETED_VERIFICATION, awaiting.stage)
    val wire = awaiting.asRepairWireMap()

    assertEquals(awaiting, wire.toAcceptanceCriterionRepairState("round-trip"))
    val missingCitation =
      wire + (
        Keys.LATE_FINDINGS to
          listOf(
            mapOf(
              Keys.CRITERION_REF to "AC-002",
              Keys.FINDING_TEXT to "Missing citation key",
              Keys.REVISION to 1,
            ),
          )
      )
    assertInvalid(missingCitation)
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
        wire + (Keys.CHARGED_ATTEMPTS to mapOf("AC-001" to 4_294_967_296L)),
        wire + (Keys.CRITERIA to listOf(criterion + (Keys.ORDINAL to 4_294_967_297L))),
      )
    assertEquals(state, wire.toAcceptanceCriterionRepairState("valid"))
    for (corrupt in corruptions) {
      assertInvalid(corrupt)
    }
  }

  @Test
  fun `late finding revisions cannot truncate fractional durable numbers`() {
    val finding = AcceptanceCriterionLateFinding("AC-001", "The retry path loses evidence.", null, 1)
    val state = admittedState().copy(revision = 2, lateFindings = listOf(finding))
    val wire = state.asRepairWireMap()
    val findings = assertIs<List<*>>(wire[Keys.LATE_FINDINGS])
    val storedFinding = assertIs<Map<*, *>>(findings.single())

    assertEquals(state, wire.toAcceptanceCriterionRepairState("valid-finding"))
    for (revision in listOf(1.5, 2.5, Long.MAX_VALUE.toDouble())) {
      assertInvalid(wire + (Keys.LATE_FINDINGS to listOf(storedFinding + (Keys.REVISION to revision))))
    }
  }

  @Test
  fun `gap closure revisions must be positive integers no later than state revision`() {
    val gap =
      AcceptanceCriterionGap(
        "AC-001.G1",
        "The retry path loses evidence.",
        "src/Retry.kt:42",
        AcceptanceCriterionGapStatus.CLOSED,
        AcceptanceCriterionGapOrigin.REPAIR,
        1,
      )
    val initial = admittedState()
    val state =
      initial.copy(
        revision = 2,
        criteria = listOf(initial.criteria.single().copy(gaps = listOf(gap))),
      )
    val wire = state.asRepairWireMap()
    val criteria = assertIs<List<*>>(wire[Keys.CRITERIA])
    val criterion = assertIs<Map<*, *>>(criteria.single())
    val gaps = assertIs<List<*>>(criterion[Keys.GAPS])
    val storedGap = assertIs<Map<*, *>>(gaps.single())

    assertEquals(state, wire.toAcceptanceCriterionRepairState("valid-closure"))
    for (revision in listOf(-1, 0, 3, 0.5, Long.MAX_VALUE.toDouble())) {
      val invalidGap = storedGap + (Keys.GAP_CLOSED_REVISION to revision)
      val invalidCriterion = criterion + (Keys.GAPS to listOf(invalidGap))
      assertInvalid(wire + (Keys.CRITERIA to listOf(invalidCriterion)))
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
    assertInvalid(oversized.asRepairWireMap())
  }

  private fun assertInvalid(wire: Map<String, Any?>) {
    val failure =
      assertFailsWith<SkillBillRuntimeException> {
        FeatureTaskRuntimeWorkflowArtifactMap.from(wire).toAcceptanceCriterionRepairState("corrupt")
      }
    assertEquals(FeatureTaskRuntimeFailureCode.INVALID_ACCEPTANCE_CRITERION_REPAIR_STATE_SCHEMA, failure.code)
  }

  private fun admittedState(): AcceptanceCriterionRepairState =
    assertNotNull(
      AcceptanceCriterionRepairState.admit(
        AcceptanceCriterionRepairAdmission(
          "catalog",
          "execution",
          "a".repeat(64),
          listOf("first"),
          listOf(
            AcceptanceCriterionRepairCriterion(
              "AC-001",
              null,
              "first",
              1,
              AcceptanceCriterionSourceDisposition.SOURCE_UNRESOLVED,
              AcceptanceCriterionValidationObligation.DEFERRED,
              true,
              "audit:1",
            ),
          ),
          emptyList(),
        ),
      ),
    )

  private fun awaitingSecondCriterion(): AcceptanceCriterionRepairState {
    val criteria =
      listOf(
        criterion("AC-001", 1, "first"),
        criterion("AC-002", 2, "second"),
      )
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
    state = complete(state, "first-failure", successful = false)
    state = complete(state, "first-success", successful = true)
    return assertNotNull(state.reserve("second-reservation"))
      .completeRepairAwaitingTargetedVerification(
        "second-reservation",
        "repair:second",
        "d".repeat(64),
        "checkpoint:second",
      )!!
      .copy(
        criteria =
          state.criteria.map { criterion ->
            if (criterion.canonicalRef == "AC-002") {
              criterion.copy(
                gaps =
                  listOf(
                    AcceptanceCriterionGap(
                      "AC-002.G1",
                      "The retry path drops the stored plan.",
                      "src/Retry.kt:42",
                      AcceptanceCriterionGapStatus.OPEN,
                      AcceptanceCriterionGapOrigin.REPAIR,
                      null,
                    ),
                  ),
              )
            } else {
              criterion
            }
          },
        lateFindings =
          listOf(
            AcceptanceCriterionLateFinding("AC-002", "Status read omits the old version.", null, 1),
          ),
        repairChangedPaths = listOf("src/Retry.kt"),
      )
  }

  private fun criterion(
    ref: String,
    ordinal: Int,
    text: String,
  ) = AcceptanceCriterionRepairCriterion(
    ref,
    null,
    text,
    ordinal,
    AcceptanceCriterionSourceDisposition.SOURCE_UNRESOLVED,
    AcceptanceCriterionValidationObligation.DEFERRED,
    true,
    "audit:$ref",
  )

  private fun complete(
    state: AcceptanceCriterionRepairState,
    id: String,
    successful: Boolean,
  ): AcceptanceCriterionRepairState {
    val reservationId = "$id-reservation"
    val awaiting =
      state.reserve(reservationId)!!
        .completeRepairAwaitingTargetedVerification(
          reservationId,
          "repair:$id",
          "e".repeat(64),
          "checkpoint:$id",
        )!!
    return awaiting.record(
      AcceptanceCriterionRepairOutcome(
        id,
        reservationId,
        "AC-001",
        "f".repeat(64),
        if (successful) {
          AcceptanceCriterionRepairOutcomeKind.TARGETED_SUCCESS
        } else {
          AcceptanceCriterionRepairOutcomeKind.TARGETED_FAILURE
        },
        "evidence:$id",
      ),
    )!!
  }
}
