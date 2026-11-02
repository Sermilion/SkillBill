package skillbill.engine.featuretask.runner

import skillbill.contracts.JsonCodec
import skillbill.engine.RecordingWorkflowGitOperations
import skillbill.engine.featuretask.phase.record.openTestWorkflow
import skillbill.engine.featuretask.slot.validJsonOutput
import skillbill.workflow.model.WorkflowStepStatus
import skillbill.workflow.taskruntime.model.skeleton.SkeletonDefinition
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FeatureTaskRuntimeGoalChildHistoryDraftGateTest {
  @Test
  fun `a draft missing its closing brace is retried instead of blocking the goal`() {
    val settled = settleDraft(DRAFT.dropLast(1))

    assertEquals(WorkflowStepStatus.COMPLETED, settled.writeHistoryStatus)
    assertEquals(2, settled.writeHistoryPrompts.size)
    assertTrue("must be one JSON object" in settled.writeHistoryPrompts.last(), settled.writeHistoryPrompts.last())
  }

  @Test
  fun `a draft without its history text is retried instead of blocking the goal`() {
    val settled = settleDraft("""{"disposition":"draft","boundary":"runtime","history":" ","decisions":""}""")

    assertEquals(WorkflowStepStatus.COMPLETED, settled.writeHistoryStatus)
    assertEquals(2, settled.writeHistoryPrompts.size)
    assertTrue("non-blank boundary and history" in settled.writeHistoryPrompts.last())
  }

  @Test
  fun `a draft that stays malformed blocks once the retry budget runs out`() {
    val settled = settleDraft(DRAFT.dropLast(1), validDraftAfterRejection = false)

    assertEquals(WorkflowStepStatus.BLOCKED, settled.writeHistoryStatus)
    assertTrue("exhausted its retryable-failure budget" in settled.blockedReason.orEmpty(), settled.blockedReason)
    assertTrue("must be one JSON object" in settled.blockedReason.orEmpty(), settled.blockedReason)
  }

  private fun settleDraft(
    rejected: String,
    validDraftAfterRejection: Boolean = true,
  ): SettledDraft {
    val repoRoot = Files.createTempDirectory("skillbill-history-draft-gate")
    try {
      var writeHistoryLaunches = 0
      val launcher =
        RuntimeRecordingLauncher { request ->
          val phaseId = phaseIdFromPrompt(requireNotNull(request.skillRunRequest.promptOverride))
          if (phaseId == "write_history") {
            writeHistoryLaunches += 1
            facts(draftEnvelope(if (writeHistoryLaunches > 1 && validDraftAfterRejection) DRAFT else rejected))
          } else {
            facts(defaultPhaseOutput(request))
          }
        }
      val git =
        RecordingWorkflowGitOperations(currentBranchValue = GOAL_BRANCH).also {
          it.headCommitShaValue = "f".repeat(40)
        }
      val harness =
        runnerHarness(
          RuntimeHarnessConfig(
            definition = SkeletonDefinition.GOAL_CHILD_REVISION_5,
            branchSetup = BranchSetupTestConfig(gitOperations = git),
            repoRoot = repoRoot,
          ),
          core = RunnerHarnessCore(launcher = launcher, agentAssignment = phasePerAgentAssignment()),
        )
      harness.recorder.openTestWorkflow(WORKFLOW_ID, SESSION_ID)
      harness.seedPhase("preplan", "completed", 1, phaseAgent("preplan"), PREPLAN_OUTPUT)
      harness.seedPhase("plan", "completed", 1, phaseAgent("plan"), PLAN_OUTPUT)
      harness.seedPhase("implement", "completed", 1, phaseAgent("implement"), IMPLEMENT_OUTPUT)
      harness.seedPhase("simplify", "completed", 1, phaseAgent("simplify"), SIMPLIFY_OUTPUT)
      harness.seedPhase("audit", "completed", 1, phaseAgent("audit"), VALID_AUDIT_OUTPUT)
      harness.seedReviewPhase("completed", 1, VALID_REVIEW_OUTPUT, reviewPassNumber = 1)
      harness.seedPhase("verify_findings", "completed", 1, phaseAgent("verify_findings"), VALID_VERIFY_FINDINGS_OUTPUT)
      harness.seedPhase("validate", "completed", 1, phaseAgent("validate"), validJsonOutput("validate"))

      harness.runner.run(harness.request())

      val record = harness.recorder.loadPhaseRecords(WORKFLOW_ID).orEmpty()["write_history"]
      return SettledDraft(
        writeHistoryStatus = record?.status,
        blockedReason = record?.blockedReason,
        writeHistoryPrompts =
          launcher.requests
            .map { request -> requireNotNull(request.skillRunRequest.promptOverride) }
            .filter { prompt -> phaseIdFromPrompt(prompt) == "write_history" },
      )
    } finally {
      repoRoot.toFile().deleteRecursively()
    }
  }

  private fun draftEnvelope(value: String): String =
    JsonCodec.mapToJsonString(
      mapOf(
        "contract_version" to "0.7",
        "phase_id" to "write_history",
        "status" to "completed",
        "summary" to "Drafted one history entry.",
        "produced_outputs" to mapOf("value" to value),
      ),
    )

  private data class SettledDraft(
    val writeHistoryStatus: WorkflowStepStatus?,
    val blockedReason: String?,
    val writeHistoryPrompts: List<String>,
  )

  private companion object {
    const val GOAL_BRANCH = "feat/history-draft-gate"
    const val DRAFT =
      """{"disposition":"draft","boundary":"runtime","history":"A durable entry.","decisions":"A decision."}"""
  }
}
