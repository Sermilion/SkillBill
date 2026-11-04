package skillbill.engine.featuretask.slot.codereview.verify

import skillbill.contracts.JsonCodec
import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeResolvedPhaseAgent
import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeRunRequest
import skillbill.engine.featuretask.runloop.core.PhaseRun
import skillbill.engine.featuretask.runner.phaseDeclaration
import skillbill.engine.featuretask.slot.DiffCommentTestStore
import skillbill.engine.featuretask.slot.InterfaceStubs
import skillbill.engine.featuretask.slot.attempt.PhaseStepOutputContext
import skillbill.engine.featuretask.slot.state.PhaseVerifyFindingsStepBinding
import skillbill.goalrunner.model.UNADDRESSED_FINDING_REJECTED_DISPOSITION
import skillbill.review.context.model.execution.CodeReviewExecutionMode
import skillbill.review.diffcomment.model.DiffCommentOutcome
import skillbill.review.diffcomment.model.DiffCommentThreadState
import skillbill.review.model.ParallelReviewMergedFinding
import skillbill.review.model.ParallelReviewSeverity
import skillbill.workflow.decomposition.model.SpecSource
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimeWorkflowArtifactMap
import skillbill.workflow.taskruntime.model.handoff.task.FeatureTaskRuntimeFeatureSize
import skillbill.workflow.taskruntime.model.handoff.task.FeatureTaskRuntimeRunInvariants
import skillbill.workflow.taskruntime.model.skeleton.PhaseStepPolicy
import skillbill.workflow.taskruntime.phase.task.FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_REVIEW
import skillbill.workflow.taskruntime.phase.task.FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_VERIFY_FINDINGS
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class VerifyFindingsNonGoalRefutationTest {
  private val repoRoot: Path = Files.createTempDirectory("skillbill-verify-refutation-repo")
  private val storeRoot: Path = Files.createTempDirectory("skillbill-verify-refutation-store")
  private val store = DiffCommentTestStore()
  private val diagnostics = DiffCommentTestStore.RecordingDiagnostics()
  private val stubs = InterfaceStubs()

  @AfterTest
  fun cleanUp() {
    repoRoot.toFile().deleteRecursively()
    storeRoot.toFile().deleteRecursively()
    store.close()
  }

  @Test
  fun `a non-goal verification refutes the rejected finding's first pass thread without touching the ledger`() {
    store.service.recordReviewFindings(repoRoot, PASS_PREFIX, AGENT, listOf(reviewFinding(1), reviewFinding(2)))

    VerifyFindingsEvidence.recordRejectedFindings(
      nonGoalRun(),
      stubs.stub<PhaseStepOutputContext> { name, _ ->
        if (name == "getDiagnostics") diagnostics else InterfaceStubs.Unstubbed
      },
      verifyBinding(),
      envelope(VERIFY_ENVELOPE),
      store.service,
    )

    val confirmed = store.threadBySourceRef(repoRoot, "$PASS_PREFIX:F-001")
    val rejected = store.threadBySourceRef(repoRoot, "$PASS_PREFIX:F-002")
    assertEquals(DiffCommentThreadState.DECLINED, rejected.state)
    assertEquals(DiffCommentOutcome.REFUTED, rejected.comments.last().outcome)
    assertTrue(rejected.comments.last().body.startsWith("Refuted: $REFUTATION_REASON"), rejected.comments.last().body)
    assertEquals(2, rejected.comments.size)
    assertEquals(DiffCommentThreadState.OPEN, confirmed.state)
    assertEquals(1, confirmed.comments.size)
    assertEquals(emptyList(), diagnostics.warnings)
    assertEquals(emptyList(), stubs.unexpectedCalls)
  }

  private fun verifyBinding(): PhaseVerifyFindingsStepBinding =
    stubs.stub { name, args ->
      when (name) {
        "completedStepEnvelope" -> if (args.single() == PHASE_REVIEW) envelope(REVIEW_ENVELOPE) else null
        "recordedFindingVerdicts" -> emptyList<Any>()
        else -> InterfaceStubs.Unstubbed
      }
    }

  private fun nonGoalRun(): PhaseRun =
    PhaseRun(
      phaseId = PHASE_VERIFY_FINDINGS,
      declaration = phaseDeclaration(PHASE_VERIFY_FINDINGS, FeatureTaskRuntimeFeatureSize.MEDIUM, emptySet()),
      resolvedAgent = FeatureTaskRuntimeResolvedPhaseAgent(PHASE_VERIFY_FINDINGS, AGENT, null),
      modelDirective = null,
      compaction = null,
      request =
        FeatureTaskRuntimeRunRequest(
          issueKey = "SKILL-451",
          workflowId = WORKFLOW_ID,
          sessionId = "sess-verify-refutation",
          runInvariants =
            FeatureTaskRuntimeRunInvariants(
              specReference = "SKILL-451 review threads",
              featureSize = FeatureTaskRuntimeFeatureSize.MEDIUM,
              acceptanceCriteria = listOf("Refuted findings decline their thread."),
              mandatesAndOverrides = emptyList(),
              codeReviewMode = CodeReviewExecutionMode.INLINE,
            ),
          invokedAgentId = AGENT,
          repoRoot = repoRoot,
        ),
      specSource = SpecSource.LOCAL,
      policy =
        PhaseStepPolicy(
          mutating = false,
          singleAgentSession = true,
          readOnlyIdle = true,
          fileMutating = false,
          generationScoped = false,
        ),
      projectStoreRoot = storeRoot,
    )

  private fun envelope(json: String): FeatureTaskRuntimeWorkflowArtifactMap =
    FeatureTaskRuntimeWorkflowArtifactMap.from(requireNotNull(JsonCodec.anyToStringAnyMap(JsonCodec.parseValue(json))))

  private fun reviewFinding(index: Int) =
    ParallelReviewMergedFinding(
      fNumber = "F-00$index",
      agentIds = listOf(AGENT),
      severity = ParallelReviewSeverity.MAJOR,
      confidence = "high",
      location = "src/Main.kt:$index",
      description = "Defect F-00$index",
      repositoryPath = "src/Main.kt",
      line = index,
    )

  private companion object {
    const val AGENT = "claude"
    const val WORKFLOW_ID = "wfl-verify-refutation"
    const val PASS_PREFIX = "review:$WORKFLOW_ID:pass-1"
    const val REFUTATION_REASON = "the tenant id is already part of the key at src/Main.kt:2"

    val REVIEW_ENVELOPE =
      """
      {
        "contract_version": "0.7",
        "phase_id": "review",
        "status": "completed",
        "summary": "Review produced a validated output.",
        "produced_outputs": {
          "value": "two findings",
          "findings": [
            {"severity": "major", "finding_id": "F-001", "message": "Defect F-001", "location": "src/Main.kt:1"},
            {"severity": "major", "finding_id": "F-002", "message": "Defect F-002", "location": "src/Main.kt:2"}
          ],
          "blocker_dispositions": []
        }
      }
      """.trimIndent()

    val VERIFY_ENVELOPE =
      """
      {
        "contract_version": "0.7",
        "phase_id": "verify_findings",
        "status": "completed",
        "summary": "Verified F-001 and rejected F-002.",
        "produced_outputs": {
          "value": "F-002 rejected",
          "finding_dispositions": [
            {"finding_id": "F-001", "disposition": "verified", "boundary_context_unavailable": true},
            {
              "finding_id": "F-002",
              "disposition": "$UNADDRESSED_FINDING_REJECTED_DISPOSITION",
              "reason": "$REFUTATION_REASON",
              "boundary_context_unavailable": true
            }
          ]
        }
      }
      """.trimIndent()
  }
}
