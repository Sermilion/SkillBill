package skillbill.engine.goalrunner.execution.core

import skillbill.goalrunner.subtaskreview.GoalSubtaskReviewSummaryReducer
import skillbill.goalrunner.subtaskreview.model.UnaddressedFindingLedgerScope
import skillbill.review.model.ParallelReviewLaneResult
import skillbill.review.model.ParallelReviewRawFinding
import skillbill.review.model.ParallelReviewSeverity
import skillbill.review.model.ReviewClaimVerdict
import skillbill.review.model.ReviewFindingCitation
import skillbill.review.model.ReviewFindingVerdict
import skillbill.review.model.ReviewStage
import skillbill.review.parallel.ParallelReviewMerger
import skillbill.workflow.model.ValidationDepth
import skillbill.workflow.model.goalreview.GoalSubtaskBlockerDispositionVerdict
import skillbill.workflow.model.validation.FeatureTaskRuntimeVerdict
import skillbill.workflow.taskruntime.handoff.FeatureTaskRuntimeHandoffProjectionValidator
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimeRepositoryCheckpoint
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimeRepositoryCheckpointPolicy
import skillbill.workflow.taskruntime.model.handoff.PhaseHandoffProjectionDeclaration
import skillbill.workflow.taskruntime.model.handoff.PhaseHandoffProjectionDelivery
import skillbill.workflow.taskruntime.model.handoff.PhaseHandoffProjectionShape
import skillbill.workflow.taskruntime.model.handoff.assembly.FeatureTaskRuntimeHandoffProjectionInputs
import skillbill.workflow.taskruntime.model.handoff.assembly.FeatureTaskRuntimePhaseOutput
import skillbill.workflow.taskruntime.model.handoff.assembly.FeatureTaskRuntimeResolvedUpstreamOutputs
import skillbill.workflow.taskruntime.model.handoff.task.FeatureTaskRuntimeHandoffProjectionBudget
import skillbill.workflow.taskruntime.model.handoff.task.FeatureTaskRuntimeHandoffProjectionValue
import skillbill.workflow.taskruntime.model.handoff.task.FeatureTaskRuntimeHandoffPromptVisibility
import skillbill.workflow.taskruntime.model.handoff.task.FeatureTaskRuntimeHandoffSourceRef
import skillbill.workflow.taskruntime.model.handoff.task.FeatureTaskRuntimeRunInvariants
import skillbill.workflow.taskruntime.phase.task.FeatureTaskRuntimePhaseWorkflowDefinition
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class VerdictAwareRegisterAndConsumersTest {
  @Test
  fun `a refuted finding stays in the register, leaves the implement_fix projection, and stores claim_verdict`() {
    val citation = ReviewFindingCitation("Auth.kt", 10)
    val recordedVerdicts =
      listOf(
        ReviewFindingVerdict(
          stage = ReviewStage.VERIFICATION,
          findingRef = "F-001",
          claimVerdict = ReviewClaimVerdict.REFUTED,
          citations = listOf(citation),
          recordedAt = "2026-08-14T00:00:00Z",
        ),
      )
    val assembled = ParallelReviewMerger.withRecordedVerdicts(tokenLoggedLaneMerge(), recordedVerdicts)
    assertTrue(assembled.formattedOutput.contains("[F-001]"))
    assertTrue(assembled.formattedOutput.contains("Token logged"))
    assertTrue(assembled.formattedOutput.contains("Refuted"))
    assertTrue(assembled.formattedOutput.contains("claim_verdict=refuted"))

    val output = refutedFindingProducerOutput()
    val compact = GoalSubtaskReviewSummaryReducer.fromOutput(output, recordedVerdicts)
    val outcome = GoalSubtaskReviewSummaryReducer.outcomeFor(output, compact)
    assertTrue(compact.isEmpty())
    assertEquals(FeatureTaskRuntimeVerdict.APPROVED, outcome.verdict)
    assertEquals(0, outcome.unresolvedFindingCount)

    val ledger =
      GoalSubtaskReviewSummaryReducer.unaddressedFindings(
        output,
        UnaddressedFindingLedgerScope("SKILL-191", 6, "workflow", 1),
        recordedVerdicts,
      )
    assertEquals(1, ledger.size)
    assertEquals(ReviewClaimVerdict.REFUTED, ledger.single().claimVerdict)
    assertEquals(listOf(citation), ledger.single().citations)

    val projected =
      assertIs<FeatureTaskRuntimeHandoffProjectionValue.Text>(
        implementFixReviewRepairEnvelope(recordedVerdicts).projections.single().fields.first().value,
      )
    assertFalse(projected.text.contains("F-001"))
    assertFalse(projected.text.contains("Token logged"))
  }

  @Test
  fun `a refuted Blocker on a remediation pass becomes superseded with verification evidence`() {
    val location = "Auth.kt:10"
    val summary = "Token logged"
    val citation = ReviewFindingCitation("Auth.kt", 10)
    val recordedVerdicts =
      listOf(
        ReviewFindingVerdict(
          stage = ReviewStage.VERIFICATION,
          findingRef = "F-007",
          claimVerdict = ReviewClaimVerdict.REFUTED,
          citations = listOf(citation),
          recordedAt = "2026-08-14T00:00:00Z",
        ),
      )
    val prior =
      GoalSubtaskReviewSummaryReducer.unaddressedFindings(
        mapOf(
          "produced_outputs" to
            mapOf(
              "findings" to
                listOf(
                  mapOf(
                    "id" to "F-001",
                    "severity" to "blocker",
                    "location" to location,
                    "message" to summary,
                  ),
                ),
            ),
        ),
        UnaddressedFindingLedgerScope("SKILL-191", 6, "workflow", 1),
      )
    val current =
      GoalSubtaskReviewSummaryReducer.unaddressedFindings(
        mapOf(
          "produced_outputs" to
            mapOf(
              "findings" to
                listOf(
                  mapOf(
                    "id" to "F-007",
                    "severity" to "blocker",
                    "location" to location,
                    "message" to summary,
                  ),
                ),
            ),
        ),
        UnaddressedFindingLedgerScope("SKILL-191", 6, "workflow", 2),
        recordedVerdicts,
      )
    val superseded = GoalSubtaskReviewSummaryReducer.refutedBlockerSupersedes(prior, current, recordedVerdicts)
    assertEquals("F-001", superseded.single().findingId)
    assertEquals(GoalSubtaskBlockerDispositionVerdict.RESOLVED, superseded.single().verdict)
    assertEquals(listOf("Auth.kt:10"), superseded.single().evidence)
  }

  private fun tokenLoggedLaneMerge() =
    ParallelReviewMerger.merge(
      ParallelReviewLaneResult(
        "claude",
        listOf(
          ParallelReviewRawFinding(
            ParallelReviewSeverity.BLOCKER,
            "High",
            "Auth.kt:10",
            "Token logged",
            repositoryPath = "Auth.kt",
            line = 10,
          ),
        ),
      ),
      ParallelReviewLaneResult("codex", emptyList()),
    )

  private fun refutedFindingProducerOutput(): Map<String, Any?> =
    mapOf(
      "verdict" to FeatureTaskRuntimeVerdict.CHANGES_REQUESTED.wireValue,
      "produced_outputs" to
        mapOf(
          "review_run_id" to "rvw-191",
          "findings" to
            listOf(
              mapOf(
                "id" to "F-001",
                "finding_id" to "F-001",
                "severity" to "blocker",
                "location" to "Auth.kt:10",
                "message" to "Token logged",
              ),
            ),
        ),
    )

  private fun implementFixReviewRepairEnvelope(recordedVerdicts: List<ReviewFindingVerdict>) =
    FeatureTaskRuntimeHandoffProjectionValidator.validate(
      FeatureTaskRuntimeHandoffProjectionInputs(
        consumerPhaseId = FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_IMPLEMENT_FIX,
        declarations =
          listOf(
            PhaseHandoffProjectionDeclaration(
              consumerPhaseId = FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_IMPLEMENT_FIX,
              sourceRef =
                FeatureTaskRuntimeHandoffSourceRef.UpstreamPhaseOutput(
                  FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_VERIFY_FINDINGS,
                ),
              shape =
                PhaseHandoffProjectionShape(
                  projectionName = "review_repair_request",
                  projectionContractId =
                    FeatureTaskRuntimePhaseWorkflowDefinition.PhaseProjectionContract.REVIEW_REPAIR_REQUEST,
                  projectionContractVersion = "0.1",
                  promptVisibility = FeatureTaskRuntimeHandoffPromptVisibility.PROMPT_VISIBLE,
                  budget = FeatureTaskRuntimeHandoffProjectionBudget.PHASE_RECEIPT,
                  declaredFieldNames = listOf("value", "repository_checkpoint"),
                ),
              delivery =
                PhaseHandoffProjectionDelivery(
                  checkpointPolicy = FeatureTaskRuntimeRepositoryCheckpointPolicy.MUST_MATCH,
                  required = true,
                ),
            ),
          ),
        resolvedUpstream =
          FeatureTaskRuntimeResolvedUpstreamOutputs(
            mapOf(
              FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_VERIFY_FINDINGS to
                FeatureTaskRuntimePhaseOutput(
                  FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_VERIFY_FINDINGS,
                  1,
                  """{"produced_outputs":{"finding_dispositions":[]}}""",
                ),
            ),
          ),
        runInvariants =
          FeatureTaskRuntimeRunInvariants(
            specReference = ".feature-specs/SKILL-191/spec.md",
            acceptanceCriteria = listOf("AC-005"),
            mandatesAndOverrides = emptyList(),
          ),
        resolvedCheckpoint = FeatureTaskRuntimeRepositoryCheckpoint("reviewed-tree"),
        expectedCheckpoint = FeatureTaskRuntimeRepositoryCheckpoint("reviewed-tree"),
        workflowId = "wftr-1",
        validationDepth = ValidationDepth.DEFAULT,
        recordedFindingVerdicts = recordedVerdicts,
      ),
    )
}
