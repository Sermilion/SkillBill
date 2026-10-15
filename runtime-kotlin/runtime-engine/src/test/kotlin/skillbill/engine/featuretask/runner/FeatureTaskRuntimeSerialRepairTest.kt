package skillbill.engine.featuretask.runner

import skillbill.application.testHarnessClock
import skillbill.contracts.JsonCodec
import skillbill.contracts.SharedPayloadKeys
import skillbill.contracts.workflow.featuretask.ACCEPTANCE_CRITERION_AUDIT_RESULT_CONTRACT_VERSION
import skillbill.contracts.workflow.featuretask.FEATURE_TASK_RUNTIME_CONTRACT_VERSION
import skillbill.engine.RecordingWorkflowGitOperations
import skillbill.engine.featuretask.lifecycle.core.AcquiredFeatureTaskRuntimeWorker
import skillbill.engine.featuretask.lifecycle.core.FeatureTaskRuntimeWorkerCoordinator
import skillbill.engine.featuretask.lifecycle.execution.ExecutionPlanAdmissionFixture
import skillbill.engine.featuretask.model.audit.AcceptanceCriterionRepairBinding
import skillbill.engine.featuretask.model.audit.AcceptanceCriterionRepairRunState
import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeRunReport
import skillbill.engine.featuretask.model.execution.AdmittedFeatureTaskRuntimeExecution
import skillbill.engine.featuretask.persist.FeatureTaskRuntimeWorkflowPersistence
import skillbill.engine.featuretask.persist.RuntimeOwnedPersistenceBoundary
import skillbill.engine.featuretask.persist.acceptance.AcceptanceCriterionRepairLegacyAdoption
import skillbill.engine.featuretask.persist.acceptance.AcceptanceCriterionRepairPersistence
import skillbill.engine.featuretask.persist.sha256Hex
import skillbill.engine.featuretask.phase.record.FeatureTaskRuntimePhaseStateRecorder
import skillbill.engine.featuretask.runloop.core.FeatureTaskRuntimeRunLoop
import skillbill.engine.featuretask.runloop.core.FeatureTaskRuntimeRunLoopContext
import skillbill.engine.featuretask.testing.featureTaskRuntimeWireArtifactValidatorForTest
import skillbill.infrastructure.contracts.workflow.featuretask.ContractFeatureTaskRuntimePhaseOutputMigration
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
import skillbill.workflow.taskruntime.artifact.asRepairWireMap
import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionRepairMode
import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionRepairStage
import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionRepairState
import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionSourceDisposition
import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionValidationObligation
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import skillbill.contracts.workflow.featuretask.AcceptanceCriterionRepairPayloadKeys as Keys

class FeatureTaskRuntimeSerialRepairTest {
  @Test
  fun `a failed repair returns to focused planning once before the next authored criterion and final audit`() {
    val repairs = AtomicInteger()
    val currentState = AtomicReference<AcceptanceCriterionRepairState?>()
    val launcher = serialLauncher(repairs) { currentState.get() }
    val harness =
      runnerHarness(
        RuntimeHarnessConfig(
          launcher = launcher,
          codeReviewMode = CodeReviewExecutionMode.INLINE,
        ),
      )
    harness.gitOperations.repositoryFingerprintValue = "unchanged-source"
    runOwnedSerial(harness) { admitted, worker, scope ->
      currentState.set(scope.snapshot)
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
          "audit",
          "audit",
          "audit_plan_fix",
          "audit_implement_fix",
          "audit_plan_fix",
          "audit_implement_fix",
          "audit",
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
    val currentState = AtomicReference<AcceptanceCriterionRepairState?>()
    val launcher = serialLauncher(AtomicInteger()) { currentState.get() }
    val harness =
      runnerHarness(
        RuntimeHarnessConfig(
          launcher = launcher,
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
      currentState.set(scope.snapshot)
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
      val hydration =
        persistence.inspectExisting(
          worker,
          harness.request().runInvariants.acceptanceCriteria,
          "a".repeat(64),
        )
      val criteria = harness.request().runInvariants.acceptanceCriteria
      val scope =
        AcceptanceCriterionRepairRunState(
          hydration,
          AcceptanceCriterionRepairBinding(
            sha256Hex(JsonCodec.valueToJsonString(criteria)),
            worker.workflowId,
            "a".repeat(64),
            criteria,
          ),
        )
      action(admitted, worker, scope)
    }
  }

  private fun serialLauncher(
    repairs: AtomicInteger,
    currentState: () -> AcceptanceCriterionRepairState?,
  ): RuntimeRecordingLauncher {
    var audit = 0
    return RuntimeRecordingLauncher { request ->
      val prompt = assertNotNull(request.skillRunRequest.promptOverride)
      when (phaseIdFromPrompt(prompt)) {
        "audit" -> {
          audit++
          if (audit <= 3) assertContains(prompt, "AC-1")
          if (audit == 4) assertContains(prompt, "AC-2")
          val state = currentState()
          if (audit == 1) {
            facts(serialDiscoveryOutput())
          } else if (state?.stage == AcceptanceCriterionRepairStage.REPAIR_COMPLETED_AWAITING_TARGETED_VERIFICATION ||
            state?.stage == AcceptanceCriterionRepairStage.FINAL_AUDIT_REQUIRED
          ) {
            facts(serialStructuredAuditOutput(audit, state, "Current production behavior meets this requirement."))
          } else {
            facts(
              if (audit <= 3) {
                auditRemainingAcOutput("There is no caller for the first requirement.").replace(
                  "\"status\": \"completed\",",
                  "\"status\": \"completed\", \"verdict\": \"advance\",",
                )
              } else {
                settledAuditSatisfiedRecord().replace(
                  AUDIT_SATISFIED_VALUE,
                  "Current production behavior meets this requirement. Tests remain pending.",
                )
              },
            )
          }
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

  private fun serialDiscoveryOutput(): String {
    val authored = listOf("AC-1", "AC-2")
    val specDigest = "a".repeat(64)
    val audit =
      mapOf(
        SharedPayloadKeys.CONTRACT_VERSION to ACCEPTANCE_CRITERION_AUDIT_RESULT_CONTRACT_VERSION,
        Keys.MODE to AcceptanceCriterionRepairMode.INITIAL_DISCOVERY.wireValue,
        Keys.CATALOG_ID to sha256Hex(JsonCodec.valueToJsonString(authored)),
        Keys.SPEC_DIGEST to specDigest,
        Keys.EXECUTION_ID to WORKFLOW_ID,
        Keys.TREE_DIGEST to "d".repeat(64),
        Keys.CHECKPOINT_ID to "checkpoint:1",
        Keys.PRODUCING_OUTPUT_ID to "audit:discovery",
        Keys.PRODUCING_OUTPUT_DIGEST to "e".repeat(64),
        Keys.EXPECTED_REVISION to null,
        Keys.CURRENT_CRITERION_REF to null,
        Keys.RESERVATION_ID to null,
        Keys.CRITERIA to
          authored.mapIndexed { index, _ ->
            mapOf(
              Keys.CANONICAL_REF to "AC-%03d".format(index + 1),
              Keys.SOURCE_DISPOSITION to AcceptanceCriterionSourceDisposition.SOURCE_UNRESOLVED.wireValue,
              Keys.VALIDATION_OBLIGATION to AcceptanceCriterionValidationObligation.DEFERRED.wireValue,
              Keys.VALIDATION_OWNER_AVAILABLE to true,
              Keys.EVIDENCE_REF to specDigest,
            )
          },
        Keys.DEPENDENCIES to emptyList<Any>(),
        Keys.REGRESSED_REFS to emptyList<String>(),
        Keys.EDGE_CHANGES to emptyList<Any>(),
      )
    return JsonCodec.mapToJsonString(
      mapOf(
        SharedPayloadKeys.CONTRACT_VERSION to FEATURE_TASK_RUNTIME_CONTRACT_VERSION,
        SharedPayloadKeys.PHASE_ID to "audit",
        SharedPayloadKeys.STATUS to "completed",
        SharedPayloadKeys.SUMMARY to "Initial structured acceptance audit.",
        SharedPayloadKeys.VERDICT to "advance",
        SharedPayloadKeys.PRODUCED_OUTPUTS to
          mapOf(
            SharedPayloadKeys.VALUE to "Initial structured acceptance audit.",
            Keys.ACCEPTANCE_CRITERION_AUDIT_RESULT to audit,
          ),
      ),
    )
  }

  private fun serialStructuredAuditOutput(
    attempt: Int,
    state: AcceptanceCriterionRepairState?,
    prose: String,
  ): String {
    val current = assertNotNull(state)
    val tree = current.admittedTreeDigest ?: sha256Hex("unchanged-source")
    val checkpoint = current.admittedCheckpointId ?: "unchanged-source"
    val ref = "$WORKFLOW_ID:audit:$attempt:${current.revision}"
    val digest = sha256Hex("$WORKFLOW_ID:audit:$attempt:${current.revision}:$prose:$tree")
    val targeted = current.stage == AcceptanceCriterionRepairStage.REPAIR_COMPLETED_AWAITING_TARGETED_VERIFICATION
    val selected =
      if (targeted) {
        current.criteria.filter { it.canonicalRef == current.currentCriterionRef }
      } else {
        current.criteria.filter { it.sourceDisposition != AcceptanceCriterionSourceDisposition.NOT_APPLICABLE }
      }
    val mode =
      if (targeted) {
        AcceptanceCriterionRepairMode.CURRENT_CRITERION_TARGETED
      } else {
        AcceptanceCriterionRepairMode.FINAL_FULL
      }
    val auditResult =
      mapOf(
        SharedPayloadKeys.CONTRACT_VERSION to ACCEPTANCE_CRITERION_AUDIT_RESULT_CONTRACT_VERSION,
        Keys.MODE to mode.wireValue,
        Keys.CATALOG_ID to current.catalogId,
        Keys.SPEC_DIGEST to current.specDigest,
        Keys.EXECUTION_ID to current.executionId,
        Keys.TREE_DIGEST to tree,
        Keys.CHECKPOINT_ID to checkpoint,
        Keys.PRODUCING_OUTPUT_ID to ref,
        Keys.PRODUCING_OUTPUT_DIGEST to digest,
        Keys.EXPECTED_REVISION to current.revision.takeIf { targeted },
        Keys.CURRENT_CRITERION_REF to current.currentCriterionRef.takeIf { targeted },
        Keys.RESERVATION_ID to current.reservation?.reservationId.takeIf { targeted },
        Keys.CRITERIA to
          selected.map { criterion ->
            val disposition =
              if (targeted) {
                AcceptanceCriterionSourceDisposition.SOURCE_CLOSED
              } else {
                criterion.sourceDisposition
              }
            mapOf(
              Keys.CANONICAL_REF to criterion.canonicalRef,
              Keys.SOURCE_DISPOSITION to disposition.wireValue,
              Keys.VALIDATION_OBLIGATION to criterion.validationObligation.wireValue,
              Keys.VALIDATION_OWNER_AVAILABLE to criterion.validationOwnerAvailable,
              Keys.EVIDENCE_REF to prose,
            )
          },
        Keys.DEPENDENCIES to current.asRepairWireMap()[Keys.DEPENDENCIES],
        Keys.REGRESSED_REFS to emptyList<String>(),
        Keys.EDGE_CHANGES to emptyList<Any>(),
      )
    return JsonCodec.mapToJsonString(
      mapOf(
        SharedPayloadKeys.CONTRACT_VERSION to FEATURE_TASK_RUNTIME_CONTRACT_VERSION,
        SharedPayloadKeys.PHASE_ID to "audit",
        SharedPayloadKeys.STATUS to "completed",
        SharedPayloadKeys.SUMMARY to "Structured acceptance audit.",
        SharedPayloadKeys.VERDICT to "satisfied",
        SharedPayloadKeys.PRODUCED_OUTPUTS to
          mapOf(
            SharedPayloadKeys.VALUE to prose,
            Keys.ACCEPTANCE_CRITERION_AUDIT_RESULT to auditResult,
          ),
      ),
    )
  }

  private fun serialPersistence(harness: RunnerHarness): AcceptanceCriterionRepairPersistence {
    val database = harness.io.database
    val boundary = RuntimeOwnedPersistenceBoundary(database, NoopRuntimeDiagnostics)
    val validator = featureTaskRuntimeWireArtifactValidatorForTest()
    val execution = ExecutionPlanAdmissionFixture()
    val source =
      object : FeatureTaskRuntimeRunInvariantsSource {
        override fun read(specPath: Path) =
          FeatureTaskRuntimeRunInvariantsRead.Read(harness.request().runInvariants, "a".repeat(64))
      }
    val persistence =
      FeatureTaskRuntimeWorkflowPersistence(
        database,
        NoopWorkflowSnapshotValidator,
        validator,
      )
    return AcceptanceCriterionRepairPersistence(
      boundary,
      persistence,
      FeatureTaskRuntimePhaseStateRecorder(
        database,
        persistence,
        boundary,
        validator,
        testHarnessClock,
      ),
      validator,
      object : AcceptanceCriterionRepairProjection {
        override fun write(request: AcceptanceCriterionRepairProjectionRequest) = Unit
      },
      NoopRuntimeDiagnostics,
      testHarnessClock,
      AcceptanceCriterionRepairLegacyAdoption(
        execution.codec,
        execution.compatibility,
        ContractFeatureTaskRuntimePhaseOutputMigration(),
        harness.gitOperations,
        source,
      ),
    )
  }
}
