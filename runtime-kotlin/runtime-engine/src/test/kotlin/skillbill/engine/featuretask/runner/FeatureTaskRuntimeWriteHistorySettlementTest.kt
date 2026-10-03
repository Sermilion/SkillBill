package skillbill.engine.featuretask.runner

import skillbill.contracts.JsonCodec
import skillbill.contracts.SharedPayloadKeys
import skillbill.contracts.workflow.identity.task.FeatureTaskRuntimeMeasuredFactKeys
import skillbill.engine.RecordingWorkflowGitOperations
import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeGoalContinuationContext
import skillbill.engine.featuretask.phase.record.openTestWorkflow
import skillbill.engine.featuretask.slot.validJsonOutput
import skillbill.ports.workflow.gitops.model.GoalSubtaskReviewBaseline
import skillbill.workflow.model.WorkflowStepStatus
import skillbill.workflow.taskruntime.model.handoff.task.FeatureTaskRuntimeFeatureSize
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FeatureTaskRuntimeWriteHistorySettlementTest {
  @Test
  fun `a small trivial change settles write_history without a history entry and continues to commit_push`() {
    val settled = settleWriteHistory(FeatureTaskRuntimeFeatureSize.SMALL, changedPaths = emptyList())

    assertTrue("feature_size: SMALL" in settled.prompt, settled.prompt)
    assertTrue("Skip only for trivial `SMALL` changes" in settled.prompt, settled.prompt)
    assertEquals(false, settled.facts[FeatureTaskRuntimeMeasuredFactKeys.HISTORY_WRITTEN], settled.facts.toString())
    assertEquals(WorkflowStepStatus.COMPLETED, settled.commitPushStatus)
  }

  @Test
  fun `a medium change settles write_history with one entry of the skill's shape`() {
    val settled = settleWriteHistory(FeatureTaskRuntimeFeatureSize.MEDIUM, changedPaths = listOf(HISTORY_PATH))

    assertTrue("feature_size: MEDIUM" in settled.prompt, settled.prompt)
    assertTrue("Always write for `MEDIUM` and `LARGE` features." in settled.prompt, settled.prompt)
    assertTrue("## Write/Skip Rules" in settled.prompt, settled.prompt)
    assertTrue("### Supersession and delete" in settled.prompt, settled.prompt)
    assertTrue("## [<date>] <feature-name>\nAreas: <list of affected modules/packages/areas>" in settled.prompt)
    assertEquals(listOf(HISTORY_PATH), settled.facts[FeatureTaskRuntimeMeasuredFactKeys.CHANGED_PATHS])
    assertEquals(true, settled.facts[FeatureTaskRuntimeMeasuredFactKeys.HISTORY_WRITTEN], settled.facts.toString())
    assertEquals(WorkflowStepStatus.COMPLETED, settled.commitPushStatus)
  }

  private fun settleWriteHistory(
    featureSize: FeatureTaskRuntimeFeatureSize,
    changedPaths: List<String>,
  ): SettledWriteHistory {
    val repoRoot = Files.createTempDirectory("skillbill-write-history-settlement")
    try {
      val git =
        RecordingWorkflowGitOperations(currentBranchValue = GOAL_BRANCH).also {
          it.headCommitShaValue = "f".repeat(40)
          it.runtimePhaseHeadCommitSequence.addAll(listOf("validate-sha", "validate-sha", "before-sha", "after-sha"))
          it.changedPathsBetweenCommitsValue = changedPaths
        }
      val harness =
        runnerHarness(
          RuntimeHarnessConfig(
            branchSetup = BranchSetupTestConfig(gitOperations = git, featureSize = featureSize),
            repoRoot = repoRoot,
            goalContinuation = goalChild(),
          ),
          core = RunnerHarnessCore(agentAssignment = phasePerAgentAssignment()),
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

      val prompt =
        harness.launcher.requests
          .map { request -> requireNotNull(request.skillRunRequest.promptOverride) }
          .single { prompt -> phaseIdFromPrompt(prompt) == "write_history" }
      assertFalse("Invoke " in prompt, "write_history must invoke no skill: $prompt")
      val records = harness.recorder.loadPhaseRecords(WORKFLOW_ID).orEmpty()
      return SettledWriteHistory(
        prompt = prompt,
        facts = measuredFacts(records["write_history"]?.outputArtifact.orEmpty()),
        commitPushStatus = records["commit_push"]?.status,
      )
    } finally {
      repoRoot.toFile().deleteRecursively()
    }
  }

  private fun goalChild(): FeatureTaskRuntimeGoalContinuationContext =
    FeatureTaskRuntimeGoalContinuationContext(
      parentIssueKey = RUNNER_TEST_ISSUE_KEY,
      subtaskId = 11,
      subtaskName = "runtime-owned write_history rules",
      goalBranch = GOAL_BRANCH,
      suppressPr = true,
      parentWorkflowId = "wfl-parent",
      reviewBaseline = GoalSubtaskReviewBaseline("0".repeat(40), emptyList()),
    )

  private fun measuredFacts(outputArtifact: String): Map<String, Any?> {
    val envelope = JsonCodec.anyToStringAnyMap(JsonCodec.parseValue(outputArtifact))
    val produced = JsonCodec.anyToStringAnyMap(envelope?.get(SharedPayloadKeys.PRODUCED_OUTPUTS))
    return JsonCodec.anyToStringAnyMap(produced?.get(FeatureTaskRuntimeMeasuredFactKeys.MEASURED_FACTS)).orEmpty()
  }

  private data class SettledWriteHistory(
    val prompt: String,
    val facts: Map<String, Any?>,
    val commitPushStatus: WorkflowStepStatus?,
  )

  private companion object {
    const val GOAL_BRANCH = "feat/existing-runtime-branch"
    const val HISTORY_PATH = "runtime-kotlin/agent/history.md"
  }
}
