package skillbill.workflow.model.goalreview

import skillbill.error.shellcontent.InvalidGoalSubtaskReviewStateSchemaError
import skillbill.review.context.model.execution.CodeReviewExecutionMode
import skillbill.workflow.engine.model.FEATURE_TASK_RUNTIME_GOAL_CONTINUATION_ARTIFACT_KEY
import skillbill.workflow.engine.model.GOAL_SUBTASK_REVIEW_RESULTS_ARTIFACT_KEY
import skillbill.workflow.engine.model.GOAL_SUBTASK_REVIEW_STATE_ARTIFACT_KEY
import skillbill.workflow.model.validation.FeatureTaskRuntimeVerdict
import skillbill.workflow.taskruntime.model.persistence.FeatureTaskRuntimeGoalContinuationArtifact
import skillbill.workflow.taskruntime.model.persistence.GoalSubtaskReviewArtifactDecoder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GoalSubtaskReviewStateTest {
  @Test
  fun `completed passes record immutable execution modes while legacy records remain byte stable`() {
    val initial =
      GoalSubtaskReviewState.initial(
        reviewBaseSha = "a".repeat(40),
        baselineUntrackedPaths = emptyList(),
        codeReviewMode = CodeReviewExecutionMode.INLINE,
      )
    assertEquals(
      initial.toArtifactMap(),
      GoalSubtaskReviewState.fromArtifactMap(initial.toArtifactMap()).toArtifactMap(),
    )

    val completed =
      initial.reserveNextPass().completeReservedPass(
        verdict = FeatureTaskRuntimeVerdict.APPROVED,
        unresolvedFindingCount = 0,
        findings = emptyList(),
      )
    assertEquals(CodeReviewExecutionMode.INLINE, completed.passResults.single().executedMode)
    val recordedPass = (completed.toArtifactMap()["pass_results"] as List<*>).single() as Map<*, *>
    assertEquals("inline", recordedPass["executed_mode"])

    val legacyCompletedArtifact =
      completed.toArtifactMap().toMutableMap().apply {
        put(
          "pass_results",
          (getValue("pass_results") as List<*>).map { rawPass ->
            (rawPass as Map<*, *>).filterKeys { it != "executed_mode" }
          },
        )
      }
    assertEquals(
      legacyCompletedArtifact,
      GoalSubtaskReviewState.fromArtifactMap(legacyCompletedArtifact).toArtifactMap(),
    )
  }

  @Test
  fun `a recorded mode outside the immutable sequence fails loudly`() {
    val initial =
      GoalSubtaskReviewState.initial(
        reviewBaseSha = "b".repeat(40),
        baselineUntrackedPaths = emptyList(),
        codeReviewMode = CodeReviewExecutionMode.INLINE,
      )
    assertFailsWith<InvalidGoalSubtaskReviewStateSchemaError> {
      GoalSubtaskReviewState.fromArtifactMap(
        initial.toArtifactMap() +
          mapOf(
            "completed_pass_count" to 1,
            "pass_results" to
              listOf(
                mapOf(
                  "pass_number" to 1,
                  "verdict" to "approved",
                  "review_result_artifact" to "goal_subtask_review_results.1",
                  "unresolved_finding_count" to 0,
                  "findings" to emptyList<Any>(),
                  "executed_mode" to "delegated",
                ),
              ),
          ),
      )
    }
  }

  @Test
  fun `an existing reservation is reused after interruption`() {
    val reserved =
      GoalSubtaskReviewState.initial(
        reviewBaseSha = "c".repeat(40),
        baselineUntrackedPaths = emptyList(),
        codeReviewMode = CodeReviewExecutionMode.AUTO,
      ).reserveNextPass()

    assertEquals(reserved, reserved.reserveNextPass())
    assertEquals(1, reserved.reservedPassNumber)
    assertEquals(0, reserved.completedPassCount)
  }

  @Test
  fun `completing a review without a reservation fails`() {
    assertFailsWith<InvalidGoalSubtaskReviewStateSchemaError> {
      GoalSubtaskReviewState.initial(
        reviewBaseSha = "d".repeat(40),
        baselineUntrackedPaths = emptyList(),
        codeReviewMode = CodeReviewExecutionMode.INLINE,
      ).completeReservedPass(
        verdict = FeatureTaskRuntimeVerdict.APPROVED,
        unresolvedFindingCount = 0,
        findings = emptyList(),
      )
    }
  }

  @Test
  fun `completing a stored pass without a reservation keeps that pass`() {
    val stored =
      GoalSubtaskReviewState.initial(
        reviewBaseSha = "e".repeat(40),
        baselineUntrackedPaths = emptyList(),
        codeReviewMode = CodeReviewExecutionMode.INLINE,
      ).reserveNextPass().completeReservedPass(
        verdict = FeatureTaskRuntimeVerdict.APPROVED,
        unresolvedFindingCount = 0,
        findings = emptyList(),
      )

    val again =
      stored.completeReservedPass(
        verdict = FeatureTaskRuntimeVerdict.CHANGES_REQUESTED,
        unresolvedFindingCount = 1,
        findings = listOf(GoalSubtaskReviewCompactFinding("blocker", "Repository", "Unsafe mutation")),
      )

    assertEquals(stored, again)
  }

  @Test
  fun `a completed pass prevents another review reservation`() {
    val state =
      GoalSubtaskReviewState.initial(
        reviewBaseSha = "a".repeat(40),
        baselineUntrackedPaths = listOf("preexisting.tmp"),
        codeReviewMode = CodeReviewExecutionMode.INLINE,
      ).reserveNextPass().completeReservedPass(
        verdict = FeatureTaskRuntimeVerdict.CHANGES_REQUESTED,
        unresolvedFindingCount = 1,
        findings = listOf(GoalSubtaskReviewCompactFinding("blocker", "Repository", "Unsafe mutation")),
      )

    assertEquals(1, state.completedPassCount)
    assertEquals(state, state.reserveNextPass())
    assertNull(state.reservedPassNumber)
    assertFalse(state.reviewCapReached)
    assertFalse(state.pausedForOperatorDecision)
  }

  @Test
  fun `the single review pass settles when blocker-free`() {
    val settled =
      GoalSubtaskReviewState.initial(
        reviewBaseSha = "9".repeat(40),
        baselineUntrackedPaths = emptyList(),
        codeReviewMode = CodeReviewExecutionMode.INLINE,
      ).reserveNextPass().completeReservedPass(
        verdict = FeatureTaskRuntimeVerdict.APPROVED,
        unresolvedFindingCount = 0,
        findings = emptyList(),
      )

    assertEquals(1, settled.completedPassCount)
    assertEquals(FeatureTaskRuntimeVerdict.APPROVED, settled.passResults.single().verdict)
    assertFalse(settled.reviewCapReached)
    assertFalse(settled.pausedForOperatorDecision)
  }

  @Test
  fun `settled approval is tied to the reviewed target and tree`() {
    val revision = GoalSubtaskReviewedRevision("b".repeat(40), "c".repeat(40))
    val settled =
      GoalSubtaskReviewState.initial(
        reviewBaseSha = "a".repeat(40),
        codeReviewMode = CodeReviewExecutionMode.INLINE,
      ).reserveNextPass().completeReservedPass(
        verdict = FeatureTaskRuntimeVerdict.APPROVED,
        unresolvedFindingCount = 0,
        findings = emptyList(),
        revision = GoalSubtaskReviewRevision(reviewedRevision = revision),
      )
    val decoded = GoalSubtaskReviewState.fromArtifactMap(settled.toArtifactMap())

    assertTrue(decoded.approvalCovers(revision))
    assertTrue(decoded.approvalInvalidatedBy(revision.copy(treeSha = "d".repeat(40))))
    assertEquals(revision.targetSha, decoded.reviewedTargetSha)
    assertEquals(revision.treeSha, decoded.reviewedTreeSha)
  }

  @Test
  fun `pass one reservation survives serialization and resume`() {
    val reserved =
      GoalSubtaskReviewState.initial(
        reviewBaseSha = "7".repeat(40),
        baselineUntrackedPaths = emptyList(),
        codeReviewMode = CodeReviewExecutionMode.INLINE,
      ).reserveNextPass()

    val decoded = GoalSubtaskReviewState.fromArtifactMap(reserved.toArtifactMap())

    assertEquals(reserved, decoded)
    assertEquals(1, decoded.reservedPassNumber)
    assertEquals(0, decoded.completedPassCount)
  }

  @Test
  fun `a non-positive pass number is rejected`() {
    listOf(0, -1).forEach { passNumber ->
      assertFailsWith<IllegalArgumentException> {
        GoalSubtaskReviewPassResult(
          passNumber = passNumber,
          verdict = FeatureTaskRuntimeVerdict.APPROVED,
          reviewResultArtifact = "$GOAL_SUBTASK_REVIEW_RESULT_ARTIFACT_PREFIX.$passNumber",
          unresolvedFindingCount = 0,
          findings = emptyList(),
        )
      }
    }
  }

  @Test
  fun `review_cap_reached can be recorded against the single completed pass`() {
    val capped =
      GoalSubtaskReviewState.initial(
        reviewBaseSha = "e".repeat(40),
        baselineUntrackedPaths = emptyList(),
        codeReviewMode = CodeReviewExecutionMode.INLINE,
      ).reserveNextPass().completeReservedPass(
        verdict = FeatureTaskRuntimeVerdict.CHANGES_REQUESTED,
        unresolvedFindingCount = 1,
        findings = listOf(GoalSubtaskReviewCompactFinding("major", "Service", "Missing behavior")),
      ).copy(disposition = GoalSubtaskReviewDisposition.REVIEW_CAP_REACHED)

    assertTrue(capped.reviewCapReached)
    assertEquals(FeatureTaskRuntimeVerdict.CHANGES_REQUESTED, capped.passResults.single().verdict)
  }

  @Test
  fun `review_cap_reached is rejected when the last completed pass carries no Blocker or Major`() {
    val state =
      GoalSubtaskReviewState.initial(
        reviewBaseSha = "f".repeat(40),
        baselineUntrackedPaths = emptyList(),
        codeReviewMode = CodeReviewExecutionMode.INLINE,
      )

    assertFailsWith<IllegalArgumentException> {
      state.copy(
        completedPassCount = 2,
        disposition = GoalSubtaskReviewDisposition.REVIEW_CAP_REACHED,
        passResults =
          (1..2).map { passNumber ->
            GoalSubtaskReviewPassResult(
              passNumber = passNumber,
              verdict = FeatureTaskRuntimeVerdict.CHANGES_REQUESTED,
              reviewResultArtifact = "$GOAL_SUBTASK_REVIEW_RESULT_ARTIFACT_PREFIX.$passNumber",
              unresolvedFindingCount = 1,
              findings = listOf(GoalSubtaskReviewCompactFinding("minor", "Service", "Naming polish")),
            )
          },
      )
    }
  }

  @Test
  fun `a user-directed review skip is durable and prevents later review reservation`() {
    val skipped =
      GoalSubtaskReviewState.initial(
        reviewBaseSha = "d".repeat(40),
        baselineUntrackedPaths = emptyList(),
        codeReviewMode = CodeReviewExecutionMode.AUTO,
      ).reserveNextPass().completeReservedPass(
        verdict = FeatureTaskRuntimeVerdict.REVIEW_SKIPPED_BY_USER,
        unresolvedFindingCount = 0,
        findings = emptyList(),
      )

    assertTrue(skipped.reviewSkippedByUser)
    assertEquals(skipped, skipped.reserveNextPass())
  }

  @Test
  fun `strict artifact decoding rejects unknown and unsupported verdict fields`() {
    val state =
      GoalSubtaskReviewState.initial(
        reviewBaseSha = "b".repeat(40),
        baselineUntrackedPaths = emptyList(),
        codeReviewMode = CodeReviewExecutionMode.AUTO,
      )

    assertFailsWith<InvalidGoalSubtaskReviewStateSchemaError> {
      GoalSubtaskReviewState.fromArtifactMap(state.toArtifactMap() + ("unexpected" to true))
    }
    assertFailsWith<InvalidGoalSubtaskReviewStateSchemaError> {
      GoalSubtaskReviewState.fromArtifactMap(
        state.toArtifactMap() + (
          "pass_results" to
            listOf(
              mapOf(
                "pass_number" to 1,
                "verdict" to "arbitrary",
                "review_result_artifact" to "goal_subtask_review_results.1",
                "unresolved_finding_count" to 0,
                "findings" to emptyList<Any>(),
              ),
            )
        ) + ("completed_pass_count" to 1),
      )
    }
  }

  @Test
  fun `goal review persistence requires paired continuation and review state artifacts`() {
    val state =
      GoalSubtaskReviewState.initial(
        reviewBaseSha = "c".repeat(40),
        baselineUntrackedPaths = emptyList(),
        codeReviewMode = CodeReviewExecutionMode.AUTO,
      )
    val continuation =
      FeatureTaskRuntimeGoalContinuationArtifact(
        issueKey = "SKILL-119",
        subtaskId = 2,
        suppressPr = true,
        goalBranch = "feat/SKILL-119",
        codeReviewMode = CodeReviewExecutionMode.AUTO,
      )

    assertEquals(null, GoalSubtaskReviewArtifactDecoder.decode(emptyMap<String, Any?>()))
    assertFailsWith<InvalidGoalSubtaskReviewStateSchemaError> {
      GoalSubtaskReviewArtifactDecoder.decode(
        mapOf(GOAL_SUBTASK_REVIEW_STATE_ARTIFACT_KEY to state.toArtifactMap()),
      )
    }
    assertFailsWith<InvalidGoalSubtaskReviewStateSchemaError> {
      GoalSubtaskReviewArtifactDecoder.decode(
        mapOf(FEATURE_TASK_RUNTIME_GOAL_CONTINUATION_ARTIFACT_KEY to continuation.toArtifactMap()),
      )
    }
    assertFailsWith<InvalidGoalSubtaskReviewStateSchemaError> {
      GoalSubtaskReviewArtifactDecoder.decode(
        mapOf(
          FEATURE_TASK_RUNTIME_GOAL_CONTINUATION_ARTIFACT_KEY to continuation.toArtifactMap(),
          GOAL_SUBTASK_REVIEW_STATE_ARTIFACT_KEY to state.toArtifactMap(),
          GOAL_SUBTASK_REVIEW_RESULTS_ARTIFACT_KEY to mapOf("1" to "stale raw review result"),
        ),
      )
    }
  }

  @Test
  fun `review invalidation clears raw results through an empty map that stays decodable`() {
    val continuation =
      FeatureTaskRuntimeGoalContinuationArtifact(
        issueKey = "SKILL-135",
        subtaskId = 3,
        suppressPr = true,
        goalBranch = "feat/SKILL-135",
        codeReviewMode = CodeReviewExecutionMode.INLINE,
      )
    val completed =
      GoalSubtaskReviewState.initial(
        reviewBaseSha = "d".repeat(40),
        baselineUntrackedPaths = emptyList(),
        codeReviewMode = CodeReviewExecutionMode.INLINE,
      ).reserveNextPass().completeReservedPass(
        verdict = FeatureTaskRuntimeVerdict.CHANGES_REQUESTED,
        unresolvedFindingCount = 0,
        findings = emptyList(),
      )
    val invalidated =
      GoalSubtaskReviewState.initial(
        reviewBaseSha = completed.reviewBaseSha,
        baselineUntrackedPaths = completed.baselineUntrackedPaths,
        codeReviewMode = completed.codeReviewMode,
      )

    val artifacts =
      mapOf(
        FEATURE_TASK_RUNTIME_GOAL_CONTINUATION_ARTIFACT_KEY to continuation.toArtifactMap(),
        GOAL_SUBTASK_REVIEW_STATE_ARTIFACT_KEY to invalidated.toArtifactMap(),
        GOAL_SUBTASK_REVIEW_RESULTS_ARTIFACT_KEY to emptyMap<String, String>(),
      )

    val decoded = assertNotNull(GoalSubtaskReviewArtifactDecoder.decode(artifacts))
    assertEquals(0, decoded.state.completedPassCount)
    assertEquals(emptyMap(), decoded.rawResults)
  }

  @Test
  fun `Major-only itemised findings with a positive unresolved count block advance`() {
    val pass =
      GoalSubtaskReviewPassResult(
        passNumber = 1,
        verdict = FeatureTaskRuntimeVerdict.CHANGES_REQUESTED,
        reviewResultArtifact = "$GOAL_SUBTASK_REVIEW_RESULT_ARTIFACT_PREFIX.1",
        unresolvedFindingCount = 1,
        findings = listOf(GoalSubtaskReviewCompactFinding("major", "Service", "Missing behavior")),
      )
    assertTrue(pass.blocksAdvance)
    assertFalse(pass.findings.single().isBlocker)
    assertTrue(pass.findings.single().blocksAdvance)
  }

  @Test
  fun `Minor-only itemised findings do not block advance`() {
    val pass =
      GoalSubtaskReviewPassResult(
        passNumber = 1,
        verdict = FeatureTaskRuntimeVerdict.APPROVED,
        reviewResultArtifact = "$GOAL_SUBTASK_REVIEW_RESULT_ARTIFACT_PREFIX.1",
        unresolvedFindingCount = 1,
        findings = listOf(GoalSubtaskReviewCompactFinding("minor", "Naming", "Prefer clearer name")),
      )
    assertFalse(pass.blocksAdvance)
    assertFalse(pass.findings.single().blocksAdvance)
  }

  @Test
  fun `positive unresolvedFindingCount with empty findings remains blocking`() {
    val pass =
      GoalSubtaskReviewPassResult(
        passNumber = 1,
        verdict = FeatureTaskRuntimeVerdict.CHANGES_REQUESTED,
        reviewResultArtifact = "$GOAL_SUBTASK_REVIEW_RESULT_ARTIFACT_PREFIX.1",
        unresolvedFindingCount = 2,
        findings = emptyList(),
      )
    assertTrue(pass.blocksAdvance)
  }

  @Test
  fun `toArtifactMap and fromArtifactMap preserve durable key set including blocker_dispositions`() {
    val state =
      GoalSubtaskReviewState.initial(
        reviewBaseSha = "e".repeat(40),
        baselineUntrackedPaths = emptyList(),
        codeReviewMode = CodeReviewExecutionMode.INLINE,
      ).reserveNextPass().completeReservedPass(
        verdict = FeatureTaskRuntimeVerdict.CHANGES_REQUESTED,
        unresolvedFindingCount = 1,
        findings = listOf(GoalSubtaskReviewCompactFinding("blocker", "Repository", "Unsafe mutation")),
        blockerDispositions =
          listOf(
            GoalSubtaskBlockerDisposition(
              findingId = "F-001",
              verdict = GoalSubtaskBlockerDispositionVerdict.UNRESOLVED,
              evidence = listOf("still open"),
            ),
          ),
      )
    val encoded = state.toArtifactMap()
    assertTrue("blocker_dispositions" in encoded)
    assertTrue(
      (encoded["pass_results"] as List<*>).all { pass ->
        "unresolved_finding_count" in (pass as Map<*, *>)
      },
    )
    val roundTripped = GoalSubtaskReviewState.fromArtifactMap(encoded).toArtifactMap()
    assertEquals(encoded.keys, roundTripped.keys)
    assertEquals(encoded, roundTripped)
  }

  @Test
  fun `a review state carrying receipts round-trips and a record without the key still decodes`() {
    val receipt =
      FeatureTaskRuntimeRepairReceipt(
        roundNumber = 1,
        preFixCheckpointSha = "e".repeat(40),
        entries =
          listOf(
            FeatureTaskRuntimeRepairReceiptEntry(
              outcome = FeatureTaskRuntimeRepairOutcome.ADDRESSED,
              findingId = "F-001",
            ),
          ),
      )
    val withReceipts =
      GoalSubtaskReviewState.initial(
        reviewBaseSha = "e".repeat(40),
        baselineUntrackedPaths = emptyList(),
        codeReviewMode = CodeReviewExecutionMode.INLINE,
      ).upsertRepairReceipt(receipt)
    val encoded = withReceipts.toArtifactMap()
    assertEquals(encoded, GoalSubtaskReviewState.fromArtifactMap(encoded).toArtifactMap())

    val withoutKey = encoded.toMutableMap().apply { remove("repair_receipts") }
    val decoded = GoalSubtaskReviewState.fromArtifactMap(withoutKey)
    assertEquals(emptyList(), decoded.repairReceipts)
  }

  @Test
  fun `upserting a receipt for a round already present replaces that round and leaves list length unchanged`() {
    val first =
      FeatureTaskRuntimeRepairReceipt(
        roundNumber = 1,
        preFixCheckpointSha = "e".repeat(40),
        entries =
          listOf(
            FeatureTaskRuntimeRepairReceiptEntry(
              outcome = FeatureTaskRuntimeRepairOutcome.NO_EDIT_REQUIRED,
              findingId = "F-001",
              noEditReason = "construct already matched the finding",
            ),
          ),
      )
    val replacement =
      first.copy(
        entries =
          listOf(
            FeatureTaskRuntimeRepairReceiptEntry(
              outcome = FeatureTaskRuntimeRepairOutcome.ADDRESSED,
              findingId = "F-001",
            ),
          ),
      )
    val initial =
      GoalSubtaskReviewState.initial(
        reviewBaseSha = "e".repeat(40),
        baselineUntrackedPaths = emptyList(),
        codeReviewMode = CodeReviewExecutionMode.INLINE,
      )
    val once = initial.upsertRepairReceipt(first)
    val twice = once.upsertRepairReceipt(replacement)
    assertEquals(1, twice.repairReceipts.size)
    assertEquals(FeatureTaskRuntimeRepairOutcome.ADDRESSED, twice.repairReceipts.single().entries.single().outcome)
  }
}
