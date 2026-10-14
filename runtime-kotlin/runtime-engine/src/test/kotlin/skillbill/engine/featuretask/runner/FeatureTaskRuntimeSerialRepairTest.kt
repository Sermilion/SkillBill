package skillbill.engine.featuretask.runner

import skillbill.application.testHarnessClock
import skillbill.engine.RecordingWorkflowGitOperations
import skillbill.engine.featuretask.lifecycle.core.AcquiredFeatureTaskRuntimeWorker
import skillbill.engine.featuretask.lifecycle.core.FeatureTaskRuntimeWorkerCoordinator
import skillbill.engine.featuretask.lifecycle.execution.ExecutionPlanAdmissionFixture
import skillbill.engine.featuretask.model.audit.AcceptanceCriterionRepairRunState
import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeRunReport
import skillbill.engine.featuretask.model.execution.AdmittedFeatureTaskRuntimeExecution
import skillbill.engine.featuretask.persist.FeatureTaskRuntimeWorkflowPersistence
import skillbill.engine.featuretask.persist.RuntimeOwnedPersistenceBoundary
import skillbill.engine.featuretask.persist.acceptance.AcceptanceCriterionRepairPersistence
import skillbill.engine.featuretask.phase.record.FeatureTaskRuntimePhaseStateRecorder
import skillbill.engine.featuretask.runloop.core.FeatureTaskRuntimeRunLoop
import skillbill.engine.featuretask.runloop.core.FeatureTaskRuntimeRunLoopContext
import skillbill.infrastructure.contracts.FeatureTaskRuntimeWireArtifactValidator
import skillbill.ports.diagnostics.NoopRuntimeDiagnostics
import skillbill.ports.taskruntime.AcceptanceCriterionRepairProjection
import skillbill.ports.taskruntime.FeatureTaskRuntimeRunInvariantsSource
import skillbill.ports.taskruntime.NoopFeatureTaskRuntimeWorkerSupervisor
import skillbill.ports.taskruntime.model.AcceptanceCriterionRepairProjectionRequest
import skillbill.ports.taskruntime.model.FeatureTaskRuntimeRunInvariantsRead
import skillbill.ports.workflow.gitops.WorkflowGitOperations
import skillbill.ports.workflow.gitops.model.WorkflowGitOperationResult
import skillbill.review.context.model.execution.CodeReviewExecutionMode
import skillbill.workflow.model.ValidationDepth
import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionRepairStage
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull

class FeatureTaskRuntimeSerialRepairTest {
  @Test
  fun `a failed repair returns to focused planning once before the next authored criterion and final audit`() {
    val repairs = AtomicInteger()
    val launcher = serialLauncher(repairs)
    val harness =
      runnerHarness(
        RuntimeHarnessConfig(
          allowOwningWrites = true,
          launcher = launcher,
          codeReviewMode = CodeReviewExecutionMode.INLINE,
        ),
      )
    harness.gitOperations.repositoryFingerprintValue = "unchanged-source"
    runOwnedSerial(harness) { admitted, worker, scope ->
      val report =
        harness.runner.run(
          harness.request().copy(admittedExecution = admitted, acceptanceCriterionRepair = scope),
          worker,
        )
      assertIs<FeatureTaskRuntimeRunReport.Completed>(report, "$report")
      assertEquals(AcceptanceCriterionRepairStage.FINAL_ACCEPTED, scope.snapshot?.stage)
      assertEquals(1, scope.snapshot?.unsuccessfulTotals?.get("AC-001"))
      assertEquals(2, repairs.get())
      assertEquals(
        listOf(
          "audit",
          "audit_plan_fix",
          "audit_implement_fix",
          "audit_plan_fix",
          "audit_implement_fix",
          "audit",
          "audit",
          "audit",
        ),
        harness.launchedPromptPhaseOrder().filter { it.startsWith("audit") },
      )
    }
  }

  @Test
  fun `a failed fingerprint after final acceptance blocks instead of reporting completion`() {
    val git = RecordingWorkflowGitOperations().apply { repositoryFingerprintValue = "unchanged-source" }
    var finalAccepted = { false }
    var acceptedReads = 0
    val failingGit =
      object : WorkflowGitOperations by git {
        override fun repositoryFingerprint(repoRoot: Path): WorkflowGitOperationResult =
          if (finalAccepted() && ++acceptedReads > 1) {
            WorkflowGitOperationResult.Failed("Fingerprint unavailable")
          } else {
            git.repositoryFingerprint(repoRoot)
          }
      }
    val harness =
      runnerHarness(
        RuntimeHarnessConfig(
          allowOwningWrites = true,
          branchSetup = BranchSetupTestConfig(gitOperations = git),
          codeReviewMode = CodeReviewExecutionMode.INLINE,
        ),
      )
    val runner =
      harness.withEntry(
        object : TestFeatureTaskRuntimeRunLoopEntry(gitOperations = failingGit) {
          override fun run(
            context: FeatureTaskRuntimeRunLoopContext,
            beforeDrive: (FeatureTaskRuntimeRunLoop) -> Unit,
          ): FeatureTaskRuntimeRunReport = super.run(this.context(context.request, context.runState), beforeDrive)
        },
      )
    runOwnedSerial(harness) { admitted, worker, scope ->
      finalAccepted = { scope.snapshot?.stage == AcceptanceCriterionRepairStage.FINAL_ACCEPTED }
      val report =
        runner.run(
          harness.request().copy(admittedExecution = admitted, acceptanceCriterionRepair = scope),
          worker,
        )
      val blocked = assertIs<FeatureTaskRuntimeRunReport.Blocked>(report)
      assertEquals("audit", blocked.lastIncompletePhase)
      assertContains(blocked.blockedReason, "source fingerprint could not be verified")
      assertContains(blocked.blockedReason, "Fingerprint unavailable")
      assertEquals(
        1,
        harness.recorder.loadPhaseLedger(WORKFLOW_ID).orEmpty().count {
          it.blockedReason == blocked.blockedReason
        },
      )
    }
  }

  private fun runOwnedSerial(
    harness: RunnerHarness,
    action: (
      AdmittedFeatureTaskRuntimeExecution,
      AcquiredFeatureTaskRuntimeWorker,
      AcceptanceCriterionRepairRunState,
    ) -> Unit,
  ) {
    val persistence = serialPersistence(harness)
    val admission =
      ExecutionPlanAdmissionFixture(
        database = harness.io.database,
        selectedStrategies = harness.strategies,
        specPath = harness.request().runInvariants.specReference,
      )
    val coordinator =
      FeatureTaskRuntimeWorkerCoordinator(
        harness.io.database,
        NoopFeatureTaskRuntimeWorkerSupervisor,
        testHarnessClock,
        admission.admission,
      )
    val inputs =
      admission.creationResolver().resolveInputs(
        harness.request().repoRoot,
        null,
        ValidationDepth.DEFAULT,
        null,
        WORKFLOW_ID,
      )
    coordinator.runOwned(
      WORKFLOW_ID,
      inputs,
      admission.identity(WORKFLOW_ID, harness.request().issueKey),
    ) { admitted, worker ->
      val scope =
        persistence.inspectExisting(
          worker,
          harness.request().runInvariants.acceptanceCriteria,
          "a".repeat(64),
        )
      action(admitted, worker, scope)
    }
  }

  private fun serialLauncher(repairs: AtomicInteger): RuntimeRecordingLauncher {
    var audit = 0
    return RuntimeRecordingLauncher { request ->
      val prompt = assertNotNull(request.skillRunRequest.promptOverride)
      when (phaseIdFromPrompt(prompt)) {
        "audit" -> {
          audit++
          if (audit <= 2) assertContains(prompt, "AC-1")
          if (audit == 3) assertContains(prompt, "AC-2")
          facts(
            if (audit == 1) {
              auditRemainingAcOutput("There is no caller for the first requirement.")
            } else {
              settledAuditSatisfiedRecord().replace(
                AUDIT_SATISFIED_VALUE,
                "Current production behavior meets this requirement. Tests remain pending.",
              )
            },
          )
        }
        "audit_plan_fix" -> {
          assertContains(prompt, "AC-1")
          facts(defaultPhaseOutput(request))
        }
        "audit_implement_fix" -> {
          assertContains(prompt, "AC-1")
          assertContains(prompt, "live cycle:")
          val repair = repairs.incrementAndGet()
          if (repair == 1) {
            facts(
              """{
            "contract_version":"0.7","phase_id":"audit_implement_fix","status":"blocked",
            "failure_disposition":"retryable","summary":"Partial repair",
            "produced_outputs":{"value":"The caller is partly connected. The remaining write path needs repair."}
          }""",
            )
          } else {
            facts(defaultPhaseOutput(request))
          }
        }
        else -> facts(defaultPhaseOutput(request))
      }
    }
  }

  private fun serialPersistence(harness: RunnerHarness): AcceptanceCriterionRepairPersistence {
    val database = harness.io.database
    val boundary = RuntimeOwnedPersistenceBoundary(database, NoopRuntimeDiagnostics)
    val persistence =
      FeatureTaskRuntimeWorkflowPersistence(
        database,
        NoopWorkflowSnapshotValidator,
      )
    return AcceptanceCriterionRepairPersistence(
      boundary,
      persistence,
      FeatureTaskRuntimePhaseStateRecorder(
        database,
        persistence,
        boundary,
        FeatureTaskRuntimeWireArtifactValidator(),
        testHarnessClock,
      ),
      object : AcceptanceCriterionRepairProjection {
        override fun write(request: AcceptanceCriterionRepairProjectionRequest) = Unit
      },
      NoopRuntimeDiagnostics,
      testHarnessClock,
      object : FeatureTaskRuntimeRunInvariantsSource {
        override fun read(specPath: Path) =
          FeatureTaskRuntimeRunInvariantsRead.Read(harness.request().runInvariants, "a".repeat(64))
      },
    )
  }
}
