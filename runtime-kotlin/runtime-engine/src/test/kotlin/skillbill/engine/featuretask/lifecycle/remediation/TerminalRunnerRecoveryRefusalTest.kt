package skillbill.engine.featuretask.lifecycle.remediation

import skillbill.engine.RecordingWorkflowGitOperations
import skillbill.engine.featuretask.lifecycle.execution.ExecutionPlanAdmissionFixture
import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeRunReport
import skillbill.engine.featuretask.runner.WORKFLOW_ID
import skillbill.engine.featuretask.runner.defaultPhaseAwareLauncher
import skillbill.engine.featuretask.runner.goalContinuationHarness
import skillbill.engine.featuretask.slot.validJsonOutput
import skillbill.workflow.model.FeatureTaskWorkflowMode
import skillbill.workflow.model.WorkflowStatus
import skillbill.workflow.taskruntime.model.skeleton.SkeletonDefinition
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class TerminalRunnerRecoveryRefusalTest {
  @Test
  fun `full runner refuses terminal receipt recovery before rewriting records or replaying finalization`() {
    WorkflowStatus.terminalStatuses.forEach { status ->
      listOf("pending", "running", "completed").forEach { finalizationStatus ->
        val root = Files.createTempDirectory("terminal-runner-refusal")
        try {
          val git = RecordingWorkflowGitOperations(currentBranchValue = "feat/existing-runtime-branch")
          val harness = goalContinuationHarness(root, git, defaultPhaseAwareLauncher())
          val execution =
            ExecutionPlanAdmissionFixture(
              definition = SkeletonDefinition.GOAL_CHILD,
              specPath = harness.request().runInvariants.specReference,
            )
          execution.seed(harness.repository, WORKFLOW_ID, harness.request().issueKey)
          harness.seedReviewPhase("completed", 3, validJsonOutput("review"), 1)
          harness.seedPhase("commit_push", finalizationStatus, 2, "original-agent", validJsonOutput("commit_push"))
          harness.seedLegacyCheckpointIdentityStore()
          val row = assertNotNull(harness.repository.getFeatureTaskWorkflow(WORKFLOW_ID))
          harness.repository.saveFeatureTaskWorkflow(
            row.copy(workflowStatus = status.wireValue, finishedAt = "2026-09-28T00:00:00Z"),
            FeatureTaskWorkflowMode.RUNTIME,
          )
          val before = assertNotNull(harness.repository.getFeatureTaskWorkflow(WORKFLOW_ID))
          val records = harness.recorder.loadPhaseRecords(WORKFLOW_ID)
          val ledger = harness.recorder.loadPhaseLedger(WORKFLOW_ID)

          val result = harness.runner.run(harness.request())

          assertIs<FeatureTaskRuntimeRunReport.Blocked>(result)
          assertEquals("Terminal workflows cannot resume execution or regenerate receipts.", result.blockedReason)
          assertEquals(before, harness.repository.getFeatureTaskWorkflow(WORKFLOW_ID))
          assertEquals(records, harness.recorder.loadPhaseRecords(WORKFLOW_ID))
          assertEquals(ledger, harness.recorder.loadPhaseLedger(WORKFLOW_ID))
          assertTrue(harness.launcher.requests.isEmpty())
          assertTrue(git.createCommitMessages.isEmpty())
          assertTrue(git.amendCommitMessages.isEmpty())
          assertTrue(git.pushedBranches.isEmpty())
          assertTrue(git.updateCheckpointRefCalls.isEmpty())
          assertTrue(git.resetSoftToCommitCalls.isEmpty())
          assertTrue(git.resetHardToCommitCalls.isEmpty())
        } finally {
          root.toFile().deleteRecursively()
        }
      }
    }
  }
}
