package skillbill.engine

import skillbill.application.decomposition.baseBranch
import skillbill.application.realFeatureTaskRuntimePhaseOutputValidator
import skillbill.contracts.SharedPayloadKeys
import skillbill.contracts.decomposition.DecompositionManifestPayloadKeys
import skillbill.contracts.workflow.identity.task.FeatureTaskRuntimeCommitPushPayloadKeys
import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeGoalContinuationContext
import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeRunReport
import skillbill.engine.featuretask.model.subtask.FeatureTaskRuntimeCommitPushReceipt
import skillbill.engine.featuretask.runloop.finalization.RuntimeCommitCycle
import skillbill.ports.workflow.gitops.model.GoalSubtaskReviewBaseline
import skillbill.workflow.taskruntime.artifact.envelopeWireMap
import skillbill.workflow.taskruntime.model.phase.requireAcceptedOutput
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class FeatureTaskRuntimeCommitPushCycleTest {
  @Test
  fun `runtime-owned commit_push output validates and carries only the sha`() {
    val sha = "a".repeat(40)
    val accepted =
      realFeatureTaskRuntimePhaseOutputValidator
        .validatePhaseOutput(
          RuntimeCommitCycle.runtimeOwnedCommitPushOutput(
            FeatureTaskRuntimeCommitPushReceipt(commitSha = sha, branch = "feat/x", baseBranch = "main", pushed = true),
          ),
          sourceLabel = "commit_push",
        ).requireAcceptedOutput("commit_push")
    val produced = accepted.normalizedOutput.envelopeWireMap()[SharedPayloadKeys.PRODUCED_OUTPUTS] as Map<*, *>
    val result = produced[FeatureTaskRuntimeCommitPushPayloadKeys.COMMIT_PUSH_RESULT] as Map<*, *>
    assertEquals(sha, result[DecompositionManifestPayloadKeys.COMMIT_SHA])
    assertFalse(result.containsKey(FeatureTaskRuntimeCommitPushPayloadKeys.MESSAGE))
  }

  @Test
  fun `commit_push does not launch an agent and still records commit_sha`() {
    val repoRoot = Files.createTempDirectory("skillbill-runtime-owned-commit-push")
    try {
      val git =
        RecordingWorkflowGitOperations(currentBranchValue = "feat/existing-runtime-branch")
          .also { it.headCommitShaValue = "f".repeat(40) }
      val harness =
        runnerHarness(
          RuntimeHarnessConfig(
            branchSetup = BranchSetupTestConfig(gitOperations = git),
            repoRoot = repoRoot,
            goalContinuation =
              FeatureTaskRuntimeGoalContinuationContext(
                parentIssueKey = RUNNER_TEST_ISSUE_KEY,
                subtaskId = 5,
                subtaskName = "one owner for every wire token",
                goalBranch = "feat/existing-runtime-branch",
                suppressPr = true,
                parentWorkflowId = "wfl-parent",
                reviewBaseline = GoalSubtaskReviewBaseline("0".repeat(40), emptyList()),
              ),
          ),
          core =
            RunnerHarnessCore(
              launcher =
                RuntimeRecordingLauncher { request ->
                  val phaseId = phaseIdFromPrompt(requireNotNull(request.skillRunRequest.promptOverride))
                  check(phaseId != "commit_push") { "commit_push must not launch an agent" }
                  facts(validJsonOutput(phaseId))
                },
              agentAssignment = phasePerAgentAssignment(),
            ),
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
      harness.seedPhase("write_history", "completed", 1, phaseAgent("write_history"), validJsonOutput("write_history"))

      val report = harness.runner.run(harness.request())

      assertIs<FeatureTaskRuntimeRunReport.Completed>(report, report.toString())
      assertFalse("commit_push" in harness.launchedPromptPhaseOrder())
      val output =
        harness.recorder
          .loadPhaseRecords(WORKFLOW_ID)
          ?.get("commit_push")
          ?.outputArtifact
          .orEmpty()
      assertTrue(output.contains("commit_sha"), output)
      assertTrue(
        git.createCommitMessages.any {
          it.startsWith("$RUNNER_TEST_ISSUE_KEY: one owner for every wire token")
        },
      )
    } finally {
      repoRoot.toFile().deleteRecursively()
    }
  }
}
