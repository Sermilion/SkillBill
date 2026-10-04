package skillbill.engine.featuretask.lifecycle.execution

import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeRunReport
import skillbill.engine.featuretask.model.phase.AppendCheckpointIdentityArgs
import skillbill.engine.featuretask.runloop.state.FeatureTaskRuntimeAttemptBudgets
import skillbill.engine.featuretask.runner.PLAN_OUTPUT
import skillbill.engine.featuretask.runner.PREPLAN_OUTPUT
import skillbill.engine.featuretask.runner.RuntimeHarnessConfig
import skillbill.engine.featuretask.runner.RuntimeRecordingLauncher
import skillbill.engine.featuretask.runner.WORKFLOW_ID
import skillbill.engine.featuretask.runner.facts
import skillbill.engine.featuretask.runner.phaseIdFromPrompt
import skillbill.engine.featuretask.runner.runnerHarness
import skillbill.ports.agentrun.model.AgentRunLaunchFacts
import skillbill.ports.agentrun.model.AgentRunTermination
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class FeatureTaskAdmittedRunnerReconstructionTest {
  @Test
  fun `resume preserves completed evidence and grants a fresh process failure budget`() {
    val launcher =
      RuntimeRecordingLauncher { request ->
        assertEquals("implement", phaseIdFromPrompt(requireNotNull(request.skillRunRequest.promptOverride)))
        (
          facts(
            "",
          ) as AgentRunLaunchFacts
        ).copy(
          termination = AgentRunTermination.SpawnFailed,
          processStarted = false,
          stderr = "process stopped",
        )
      }
    val harness = runnerHarness(RuntimeHarnessConfig(launcher = launcher))
    val execution = ExecutionPlanAdmissionFixture(specPath = harness.request().runInvariants.specReference)
    execution.seed(harness.repository, WORKFLOW_ID, harness.request().issueKey)
    harness.seedPhase("preplan", "completed", 3, "retained-preplanner", PREPLAN_OUTPUT)
    harness.seedPhase("plan", "completed", 4, "retained-planner", PLAN_OUTPUT)
    harness.seedPhase("implement", "running", 1, "prior-implementer", null)
    assertTrue(
      harness.recorder.appendCheckpointIdentity(
        AppendCheckpointIdentityArgs(
          workflowId = WORKFLOW_ID, issueKey = "SKILL-384", subtaskId = "2", branch = "feat/SKILL-384",
          phaseId = "plan", loopId = null, generation = 0,
          parentSha =
            "a".repeat(
              40,
            ),
          ownedPaths = listOf("src/Main.kt"),
          commitSha = "b".repeat(40),
        ),
      ),
    )
    val records = assertNotNull(harness.recorder.loadPhaseRecords(WORKFLOW_ID))
    val checkpoints = harness.recorder.loadCheckpointIdentities(WORKFLOW_ID)
    val admitted =
      harness.io.database.transaction {
        execution.admission.admit(it.workflowStates, WORKFLOW_ID, execution.inputs)
      }
    val request = harness.request().copy(admittedExecution = admitted)
    val cap = FeatureTaskRuntimeAttemptBudgets.MAX_PROCESS_FAILURE_ATTEMPTS

    var previousAttempt = 1
    repeat(cap + 1) { invocation ->
      val readmitted =
        harness.io.database.transaction {
          execution.admission.admit(it.workflowStates, WORKFLOW_ID, execution.inputs)
        }
      val result =
        assertIs<FeatureTaskRuntimeRunReport.Blocked>(
          harness.runner.run(request.copy(admittedExecution = readmitted)),
        )
      assertEquals("implement", result.lastIncompletePhase)
      assertEquals(invocation + 1, launcher.requests.size, result.blockedReason)
      val attempt = assertNotNull(harness.recorder.loadPhaseRecords(WORKFLOW_ID)?.get("implement")).attemptCount
      assertTrue(attempt > previousAttempt)
      previousAttempt = attempt
      assertContentEquals(execution.encoded, execution.codec.encode(readmitted.plan))
    }
    val resumed = assertNotNull(harness.recorder.loadPhaseRecords(WORKFLOW_ID))
    assertEquals(records["preplan"], resumed["preplan"])
    assertEquals(records["plan"], resumed["plan"])
    assertEquals(checkpoints, harness.recorder.loadCheckpointIdentities(WORKFLOW_ID))
    assertEquals(0, harness.gitOperations.createCommitMessages.size)
    assertEquals(0, harness.gitOperations.pushedBranches.size)
  }
}
