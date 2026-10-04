package skillbill.engine.featuretask.runloop.finalization

import skillbill.application.decomposition.baseBranch
import skillbill.contracts.SharedPayloadKeys
import skillbill.contracts.decomposition.DecompositionManifestPayloadKeys
import skillbill.contracts.workflow.identity.task.FeatureTaskRuntimeCommitPushPayloadKeys
import skillbill.engine.RecordingWorkflowGitOperations
import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeGoalContinuationContext
import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeRunReport
import skillbill.engine.featuretask.model.subtask.FeatureTaskRuntimeCommitPushReceipt
import skillbill.engine.featuretask.phase.record.openTestWorkflow
import skillbill.engine.featuretask.runner.BranchSetupTestConfig
import skillbill.engine.featuretask.runner.IMPLEMENT_OUTPUT
import skillbill.engine.featuretask.runner.PLAN_OUTPUT
import skillbill.engine.featuretask.runner.PREPLAN_OUTPUT
import skillbill.engine.featuretask.runner.RUNNER_TEST_ISSUE_KEY
import skillbill.engine.featuretask.runner.RunnerHarnessCore
import skillbill.engine.featuretask.runner.RuntimeHarnessConfig
import skillbill.engine.featuretask.runner.RuntimeRecordingLauncher
import skillbill.engine.featuretask.runner.SESSION_ID
import skillbill.engine.featuretask.runner.SIMPLIFY_OUTPUT
import skillbill.engine.featuretask.runner.VALID_AUDIT_OUTPUT
import skillbill.engine.featuretask.runner.VALID_REVIEW_OUTPUT
import skillbill.engine.featuretask.runner.VALID_VERIFY_FINDINGS_OUTPUT
import skillbill.engine.featuretask.runner.WORKFLOW_ID
import skillbill.engine.featuretask.runner.facts
import skillbill.engine.featuretask.runner.phaseAgent
import skillbill.engine.featuretask.runner.phaseIdFromPrompt
import skillbill.engine.featuretask.runner.phasePerAgentAssignment
import skillbill.engine.featuretask.runner.runnerHarness
import skillbill.engine.featuretask.slot.validJsonOutput
import skillbill.ports.workflow.gitops.model.GoalSubtaskReviewBaseline
import skillbill.workflow.taskruntime.artifact.envelopeWireMap
import skillbill.workflow.taskruntime.model.handoff.task.NormalizedFeatureTaskRuntimePhaseOutput
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
    val normalized =
      NormalizedFeatureTaskRuntimePhaseOutput.fromEnvelopeText(
        FeatureTaskRuntimeRunLoopCommitCycle.runtimeOwnedCommitPushOutput(
          "commit_push",
          FeatureTaskRuntimeCommitPushReceipt(commitSha = sha, branch = "feat/x", baseBranch = "main", pushed = true),
        ),
        "commit_push",
      )
    val produced = normalized.envelopeWireMap()[SharedPayloadKeys.PRODUCED_OUTPUTS] as Map<*, *>
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
