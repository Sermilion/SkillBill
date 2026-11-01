package skillbill.engine.featuretask.runner

import skillbill.application.testHarnessClock
import skillbill.engine.featuretask.lifecycle.core.FeatureTaskRuntimeWorkerCoordinator
import skillbill.engine.featuretask.lifecycle.core.absentDatabaseCheckpoint
import skillbill.engine.featuretask.lifecycle.execution.ExecutionPlanAdmissionFixture
import skillbill.ports.diagnostics.NoopRuntimeDiagnostics
import skillbill.ports.taskruntime.NoopFeatureTaskRuntimeWorkerSupervisor
import skillbill.ports.workflow.model.toSnapshot
import skillbill.workflow.model.WorkflowStepStatus
import java.util.concurrent.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class FeatureTaskRuntimeCancellationTest {
  @Test
  fun `cancellation during implement releases worker ownership without settling the phase`() {
    val cancelled = CancellationException("operator stop")
    lateinit var harness: RunnerHarness
    val launcher =
      RuntimeRecordingLauncher { request ->
        if (phaseIdFromPrompt(requireNotNull(request.skillRunRequest.promptOverride)) == "implement") {
          assertNotNull(harness.repository.getFeatureTaskRuntimeWorkerOwnership(WORKFLOW_ID))
          throw cancelled
        }
        facts(defaultPhaseOutput(request))
      }
    harness = runnerHarness(RuntimeHarnessConfig(launcher = launcher))
    val request = harness.request()
    val execution =
      ExecutionPlanAdmissionFixture(
        selectedStrategies = harness.strategies,
        specPath = request.runInvariants.specReference,
      )
    val inputs =
      execution.inputs.copy(
        packSlug = "kotlin",
        declaration = kotlinPackWithValidationGate().validationGate,
        validationDepth = requireNotNull(request.goalContinuation).validationDepth,
      )
    val coordinator =
      FeatureTaskRuntimeWorkerCoordinator(
        harness.io.database,
        NoopFeatureTaskRuntimeWorkerSupervisor,
        testHarnessClock,
        execution.admission,
        NoopRuntimeDiagnostics,
        absentDatabaseCheckpoint,
      )

    val failure =
      assertFailsWith<CancellationException> {
        coordinator.runOwned(
          WORKFLOW_ID,
          inputs,
          requireNotNull(harness.repository.getFeatureTaskExecutionIdentity(WORKFLOW_ID)),
        ) { admitted, _ ->
          harness.runner.run(request.copy(admittedExecution = admitted))
        }
      }
    assertSame(cancelled, failure)

    assertEquals(null, harness.io.database.read { it.workflowStates.getFeatureTaskRuntimeWorkerOwnership(WORKFLOW_ID) })
    val implement =
      assertNotNull(
        harness.io.database.read {
          it.workflowStates.getFeatureTaskWorkflow(WORKFLOW_ID)?.toSnapshot()?.step("implement")
        },
      )
    assertTrue(
      implement.status !in setOf(WorkflowStepStatus.FAILED, WorkflowStepStatus.BLOCKED),
      "$implement",
    )
  }
}
