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
import skillbill.engine.featuretask.model.phase.AppendCheckpointIdentityArgs
import skillbill.engine.featuretask.persist.FeatureTaskRuntimeWorkflowPersistence
import skillbill.engine.featuretask.persist.RuntimeOwnedPersistenceBoundary
import skillbill.engine.featuretask.persist.acceptance.AcceptanceCriterionRepairLegacyAdoption
import skillbill.engine.featuretask.persist.acceptance.AcceptanceCriterionRepairPersistence
import skillbill.engine.featuretask.persist.sha256Hex
import skillbill.engine.featuretask.phase.record.FeatureTaskRuntimePhaseStateRecorder
import skillbill.engine.featuretask.testing.featureTaskRuntimeWireArtifactValidatorForTest
import skillbill.infrastructure.contracts.workflow.featuretask.ContractFeatureTaskRuntimePhaseOutputMigration
import skillbill.ports.diagnostics.NoopRuntimeDiagnostics
import skillbill.ports.taskruntime.AcceptanceCriterionRepairProjection
import skillbill.ports.taskruntime.FeatureTaskRuntimeRunInvariantsSource
import skillbill.ports.taskruntime.NoopFeatureTaskRuntimeWorkerSupervisor
import skillbill.ports.taskruntime.model.AcceptanceCriterionRepairProjectionRequest
import skillbill.ports.taskruntime.model.FeatureTaskRuntimeRunInvariantsRead
import skillbill.ports.workflow.gitops.model.WorkflowGitNameListResult
import skillbill.ports.workflow.model.WorkflowFamily
import skillbill.review.context.model.execution.CodeReviewExecutionMode
import skillbill.workflow.engine.model.DurableWorkflowArtifactFamily
import skillbill.workflow.engine.model.DurableWorkflowArtifacts
import skillbill.workflow.model.WorkflowStatus
import skillbill.workflow.model.WorkflowStepStatus
import skillbill.workflow.taskruntime.artifact.asRepairWireMap
import skillbill.workflow.taskruntime.artifact.asWorkflowArtifactEntry
import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionRepairMode
import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionRepairStage
import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionRepairState
import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionSourceDisposition
import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionValidationObligation
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimePhaseRecord
import skillbill.workflow.taskruntime.phaseartifacts.asPendingForOperatorResume
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import skillbill.contracts.workflow.featuretask.AcceptanceCriterionRepairPayloadKeys as Keys

class FeatureTaskRuntimeSerialRepairTest {
  @Test
  fun `partial progress keeps one reservation and plan until independent verification accepts the repair`() {
    val accounting = AtomicReference<AcceptanceCriterionRepairRunState?>()
    val git = AtomicReference<RecordingWorkflowGitOperations>()
    val reservations = mutableListOf<String>()
    var repairs = 0
    var plans = 0
    var audits = 0
    val launcher =
      RuntimeRecordingLauncher { request ->
        val phase = phaseIdFromPrompt(assertNotNull(request.skillRunRequest.promptOverride))
        val state = accounting.get()?.snapshot
        facts(
          when (phase) {
            "audit" -> {
              audits++
              continuingAuditOutput(state, audits)
            }
            "audit_plan_fix" -> {
              plans++
              defaultPhaseOutput(request)
            }
            "audit_implement_fix" -> {
              repairs++
              reservations += assertNotNull(state?.reservation?.reservationId)
              check(repairs <= 5) { "The completed repair did not reach independent verification." }
              git.get().contentIdentities["src/Foo.kt"] = "repair-$repairs"
              assertEquals(0, assertNotNull(state).unsuccessfulTotals.getOrDefault("AC-001", 0))
              assertTrue(state.outcomes.isEmpty())
              assertEquals(
                AcceptanceCriterionSourceDisposition.SOURCE_UNRESOLVED,
                state.criteria.first().sourceDisposition,
              )
              defaultPhaseOutput(request).replace(
                "\"status\": \"completed\",",
                "\"status\": \"completed\", \"verdict\": \"${if (repairs < 5) "progress" else "satisfied"}\",",
              )
            }
            else -> defaultPhaseOutput(request)
          },
        )
      }
    val harness =
      runnerHarness(RuntimeHarnessConfig(launcher = launcher, codeReviewMode = CodeReviewExecutionMode.INLINE))
    git.set(harness.gitOperations)
    harness.gitOperations.repositoryFingerprintValue = "unchanged-source"
    runOwnedSerial(harness) { admitted, worker, scope ->
      accounting.set(scope)
      val report =
        harness.runner.run(
          harness.request().copy(admittedExecution = admitted, acceptanceCriterionRepair = scope),
          worker,
        )
      assertIs<FeatureTaskRuntimeRunReport.Completed>(report, "$report")
      assertEquals(5, repairs)
      assertEquals(1, plans)
      assertEquals(1, reservations.distinct().size)
      assertTrue(assertNotNull(scope.snapshot).unsuccessfulTotals.isEmpty())
      assertEquals(AcceptanceCriterionRepairStage.ACCEPTED, scope.snapshot?.stage)
      assertEquals(1, scope.snapshot?.outcomes?.size)
      assertContains(harness.launchOrder(), "review")
      assertContains(harness.launchOrder(), "validate")
    }
  }

  @Test
  fun `progress reports that change no source spend the criterion budget instead of continuing indefinitely`() {
    val accounting = AtomicReference<AcceptanceCriterionRepairRunState?>()
    val reservations = mutableListOf<String>()
    var repairs = 0
    var plans = 0
    var audits = 0
    val launcher =
      RuntimeRecordingLauncher { request ->
        val phase = phaseIdFromPrompt(assertNotNull(request.skillRunRequest.promptOverride))
        val state = accounting.get()?.snapshot
        facts(
          when (phase) {
            "audit" -> {
              audits++
              continuingAuditOutput(state, audits)
            }
            "audit_plan_fix" -> {
              plans++
              defaultPhaseOutput(request)
            }
            "audit_implement_fix" -> {
              repairs++
              reservations += assertNotNull(state?.reservation?.reservationId)
              check(repairs <= 3) { "Idle progress reports bypassed the criterion budget." }
              assertEquals(repairs - 1, assertNotNull(state).unsuccessfulTotals.getOrDefault("AC-001", 0))
              defaultPhaseOutput(request).replace(
                "\"status\": \"completed\",",
                "\"status\": \"completed\", \"verdict\": \"progress\",",
              )
            }
            else -> defaultPhaseOutput(request)
          },
        )
      }
    val harness =
      runnerHarness(RuntimeHarnessConfig(launcher = launcher, codeReviewMode = CodeReviewExecutionMode.INLINE))
    harness.gitOperations.repositoryFingerprintValue = "unchanged-source"
    runOwnedSerial(harness) { admitted, worker, scope ->
      accounting.set(scope)
      val report =
        harness.runner.run(
          harness.request().copy(admittedExecution = admitted, acceptanceCriterionRepair = scope),
          worker,
        )
      assertIs<FeatureTaskRuntimeRunReport.Blocked>(report, "$report")
      assertContains(report.blockedReason, "three unsuccessful repair attempts")
      assertEquals(3, repairs)
      assertEquals(3, plans)
      assertEquals(3, reservations.distinct().size)
      assertEquals(3, assertNotNull(scope.snapshot).unsuccessfulTotals["AC-001"])
      assertEquals(AcceptanceCriterionRepairStage.BUDGET_BLOCKED, scope.snapshot?.stage)
      assertFalse("review" in harness.launchOrder())
    }
  }

  @Test
  fun `a rejected full no-change claim cannot charge another criterion's gap to the focused audit`() {
    val accounting = AtomicReference<AcceptanceCriterionRepairRunState?>()
    var repaired = false
    val focused = mutableListOf<String>()
    val launcher =
      RuntimeRecordingLauncher { request ->
        val prompt = assertNotNull(request.skillRunRequest.promptOverride)
        val phase = phaseIdFromPrompt(prompt)
        val state = accounting.get()?.snapshot
        facts(
          when (phase) {
            "audit" ->
              when {
                state == null -> serialDiscoveryOutput().replace("advance", "no_change_rejected")
                state.mode == AcceptanceCriterionRepairMode.INITIAL_DISCOVERY ->
                  auditRemainingAcOutput("AC-002 has no production caller. AC-001 is satisfied.")
                    .replace(
                      "\"status\": \"completed\",",
                      "\"status\": \"completed\", \"verdict\": \"no_change_rejected\",",
                    )
                else -> {
                  val criterion = assertNotNull(state.currentCriterionRef)
                  focused += criterion
                  assertFalse(prompt.contains("Judge every criterion in the claim"))
                  assertContains(prompt, "($criterion)")
                  if (criterion == "AC-001" || repaired) {
                    settledAuditSatisfiedRecord()
                  } else {
                    auditRemainingAcOutput("AC-002 has no production caller.")
                      .replace("\"status\": \"completed\",", "\"status\": \"completed\", \"verdict\": \"advance\",")
                  }
                }
              }
            "audit_plan_fix", "audit_implement_fix" -> {
              assertEquals("AC-002", state?.currentCriterionRef)
              if (phase == "audit_implement_fix") repaired = true
              defaultPhaseOutput(request)
            }
            else -> defaultPhaseOutput(request)
          },
        )
      }
    val harness =
      runnerHarness(RuntimeHarnessConfig(launcher = launcher, codeReviewMode = CodeReviewExecutionMode.INLINE))
    harness.gitOperations.repositoryFingerprintValue = "unchanged-source"
    harness.seedPhase("preplan", "completed", 1, "claude", PREPLAN_OUTPUT)
    harness.seedPhase("plan", "completed", 1, "claude", PLAN_OUTPUT)
    harness.seedPhase("implement", "completed", 1, "claude", noChangeImplementationOutput())

    runOwnedSerial(harness) { admitted, worker, scope ->
      accounting.set(scope)
      val report =
        harness.runner.run(
          harness.request().copy(admittedExecution = admitted, acceptanceCriterionRepair = scope),
          worker,
        )

      assertIs<FeatureTaskRuntimeRunReport.Completed>(report, "$report")
      assertEquals(listOf("AC-001", "AC-002", "AC-002", "AC-002"), focused)
      assertTrue(assertNotNull(scope.snapshot).unsuccessfulTotals.isEmpty())
      assertEquals(AcceptanceCriterionRepairStage.ACCEPTED, scope.snapshot?.stage)
    }
  }

  @Test
  fun `confirmed no-change source acceptance commits before review and continues to validation`() {
    val accounting = AtomicReference<AcceptanceCriterionRepairRunState?>()
    val launcher =
      RuntimeRecordingLauncher { request ->
        val phase = phaseIdFromPrompt(assertNotNull(request.skillRunRequest.promptOverride))
        if (phase == "audit") {
          val state = accounting.get()?.snapshot
          val output =
            if (state == null) {
              serialDiscoveryOutput().replace(
                "source_unresolved",
                "source_closed",
              ).replace("advance", "no_change_confirmed")
            } else {
              serialStructuredAuditOutput(2, state, "All authored production criteria are satisfied.")
                .replace("satisfied\"", "no_change_confirmed\"")
            }
          facts(output)
        } else {
          facts(defaultPhaseOutput(request))
        }
      }
    val harness =
      runnerHarness(RuntimeHarnessConfig(launcher = launcher, codeReviewMode = CodeReviewExecutionMode.INLINE))
    harness.gitOperations.repositoryFingerprintValue = "unchanged-source"
    harness.seedPhase("preplan", "completed", 1, "claude", PREPLAN_OUTPUT)
    harness.seedPhase("plan", "completed", 1, "claude", PLAN_OUTPUT)
    harness.seedPhase("implement", "completed", 1, "claude", noChangeImplementationOutput())

    runOwnedSerial(harness) { admitted, worker, scope ->
      accounting.set(scope)
      val report =
        harness.runner.run(
          harness.request().copy(admittedExecution = admitted, acceptanceCriterionRepair = scope),
          worker,
        )

      assertIs<FeatureTaskRuntimeRunReport.Completed>(report, "$report")
      assertEquals(AcceptanceCriterionRepairStage.ACCEPTED, scope.snapshot?.stage)
      assertContains(harness.launchOrder(), "review")
      assertContains(harness.launchOrder(), "validate")
      assertEquals(null, harness.recorder.loadNoChangePause(WORKFLOW_ID))
    }
  }

  @Test
  fun `a failed audited implementation checkpoint still advances to review`() {
    val accounting = AtomicReference<AcceptanceCriterionRepairRunState?>()
    val launcher =
      RuntimeRecordingLauncher { request ->
        val phase = phaseIdFromPrompt(assertNotNull(request.skillRunRequest.promptOverride))
        if (phase == "audit") {
          val state = accounting.get()?.snapshot
          val output =
            if (state == null) {
              serialDiscoveryOutput().replace(
                "source_unresolved",
                "source_closed",
              ).replace("advance", "no_change_confirmed")
            } else {
              serialStructuredAuditOutput(2, state, "All authored production criteria are satisfied.")
                .replace("satisfied\"", "no_change_confirmed\"")
            }
          facts(output)
        } else {
          facts(defaultPhaseOutput(request))
        }
      }
    val harness =
      runnerHarness(RuntimeHarnessConfig(launcher = launcher, codeReviewMode = CodeReviewExecutionMode.INLINE))
    harness.gitOperations.repositoryFingerprintValue = "unchanged-source"
    harness.seedResolvedBranch("feat/existing-runtime-branch", "main", created = false)
    var auditCheckpointFailures = 0
    harness.gitOperations.onStagedPathsRead = {
      val beforeReview = "review" !in harness.launchOrder()
      if (beforeReview) auditCheckpointFailures++
      harness.gitOperations.stagedPathsResult =
        WorkflowGitNameListResult.Failed("index.lock exists").takeIf { beforeReview }
    }
    harness.seedPhase("preplan", "completed", 1, "claude", PREPLAN_OUTPUT)
    harness.seedPhase("plan", "completed", 1, "claude", PLAN_OUTPUT)
    harness.seedPhase("implement", "completed", 1, "claude", noChangeImplementationOutput())

    runOwnedSerial(harness) { admitted, worker, scope ->
      accounting.set(scope)
      val report =
        harness.runner.run(
          harness.request().copy(admittedExecution = admitted, acceptanceCriterionRepair = scope),
          worker,
        )

      assertIs<FeatureTaskRuntimeRunReport.Completed>(report, "$report")
      assertTrue(auditCheckpointFailures > 0)
      assertContains(harness.launchOrder(), "review")
    }
  }

  @Test
  fun `a failed repair returns to focused planning once and an accepted queue moves to review without another audit`() {
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
      assertEquals(AcceptanceCriterionRepairStage.ACCEPTED, scope.snapshot?.stage)
      assertEquals(1, scope.snapshot?.unsuccessfulTotals?.get("AC-001"))
      assertEquals(2, repairs.get())
      assertEquals(
        listOf(
          "audit",
          "audit",
          "audit",
          "audit_plan_fix",
          "audit_implement_fix",
          "audit",
          "audit_plan_fix",
          "audit_implement_fix",
          "audit",
          "audit",
          "audit",
        ),
        harness.launchedPromptPhaseOrder().filter { it.startsWith("audit") },
      )
      val order = harness.launchOrder()
      assertEquals("review", order[order.indexOfLast { it == "audit" } + 1], "$order")
    }
  }

  @Test
  fun `audit slot steps run under a worker lease bound to their own phase`() {
    val repairs = AtomicInteger()
    val currentState = AtomicReference<AcceptanceCriterionRepairState?>()
    val launchedPhaseAndLeasePhase = mutableListOf<Pair<String, String?>>()
    lateinit var harness: RunnerHarness
    val launcher =
      serialLauncher(repairs, onPhase = { phase ->
        val lease = harness.io.database.read { it.workflowStates.getFeatureTaskRuntimeWorkerOwnership(WORKFLOW_ID) }
        launchedPhaseAndLeasePhase += phase to lease?.phaseId
      }) { currentState.get() }
    harness =
      runnerHarness(RuntimeHarnessConfig(launcher = launcher, codeReviewMode = CodeReviewExecutionMode.INLINE))
    harness.gitOperations.repositoryFingerprintValue = "unchanged-source"
    runOwnedSerial(harness) { admitted, worker, scope ->
      currentState.set(scope.snapshot)
      val report =
        harness.runner.run(
          harness.request().copy(admittedExecution = admitted, acceptanceCriterionRepair = scope),
          worker,
        )
      assertIs<FeatureTaskRuntimeRunReport.Completed>(report, "$report")
    }

    val auditSlotLaunches = launchedPhaseAndLeasePhase.filter { (phase, _) -> phase.startsWith("audit") }
    assertEquals(setOf("audit", "audit_plan_fix", "audit_implement_fix"), auditSlotLaunches.map { it.first }.toSet())
    assertEquals(auditSlotLaunches.map { it.first }, auditSlotLaunches.map { it.second })
  }

  @Test
  fun `a worker-owned checkpoint after a completed audit records its identity and refuses a stale attempt`() {
    val harness = runnerHarness(RuntimeHarnessConfig(codeReviewMode = CodeReviewExecutionMode.INLINE))
    runOwnedSerial(harness) { admitted, _, _ ->
      harness.io.database.transaction { unit ->
        val record = requireNotNull(unit.workflowStates.get(WorkflowFamily.TASK_RUNTIME, WORKFLOW_ID))
        val audit =
          FeatureTaskRuntimePhaseRecord(
            phaseId = "audit",
            status = WorkflowStepStatus.COMPLETED,
            attemptCount = 2,
            startedAt = testHarnessClock.instant(),
            resolvedAgentId = "codex",
          )
        unit.workflowStates.save(
          WorkflowFamily.TASK_RUNTIME,
          record.copy(
            artifacts =
              DurableWorkflowArtifacts.fromMap(
                record.artifacts +
                  DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_PHASE_RECORDS.entry(
                    mapOf("audit" to audit.asWorkflowArtifactEntry()),
                  ),
              ),
          ),
        )
      }
      val checkpoint =
        AppendCheckpointIdentityArgs(
          workflowId = WORKFLOW_ID,
          issueKey = "SKILL-1",
          subtaskId = "1",
          branch = "feat/SKILL-1",
          phaseId = "audit",
          attemptCount = 2,
          loopId = "audit_repair",
          generation = 0,
          parentSha = "a".repeat(40),
          ownedPaths = listOf("src/Foo.kt"),
          commitSha = "b".repeat(40),
        )

      assertFalse(harness.recorder.appendOwnedCheckpointIdentity(checkpoint.copy(attemptCount = 1), admitted))
      assertTrue(harness.recorder.appendOwnedCheckpointIdentity(checkpoint, admitted))
      assertEquals(listOf("b".repeat(40)), harness.recorder.loadCheckpointIdentities(WORKFLOW_ID)?.map { it.commitSha })
    }
  }

  @Test
  fun `later phase edits leave accepted phases complete on execution and resume`() {
    val git = RecordingWorkflowGitOperations().apply { repositoryFingerprintValue = "unchanged-source" }
    val currentState = AtomicReference<AcceptanceCriterionRepairState?>()
    val changedPhases = mutableListOf<String>()
    val launcher =
      serialLauncher(AtomicInteger(), validationFailures = 1, onPhase = { phase ->
        if (phase in setOf("validate", "write_history")) {
          changedPhases += phase
          git.repositoryFingerprintValue = "changed-by-$phase"
        }
      }) { currentState.get() }
    val harness =
      runnerHarness(
        RuntimeHarnessConfig(
          launcher = launcher,
          branchSetup = BranchSetupTestConfig(gitOperations = git),
          codeReviewMode = CodeReviewExecutionMode.INLINE,
        ),
      )
    runOwnedSerial(harness) { admitted, worker, scope ->
      currentState.set(scope.snapshot)
      val request = harness.request().copy(admittedExecution = admitted, acceptanceCriterionRepair = scope)
      val report = harness.runner.run(request, worker)
      assertEquals("validate", assertIs<FeatureTaskRuntimeRunReport.Blocked>(report, "$report").lastIncompletePhase)
      assertEquals(listOf("validate"), changedPhases)
      assertEquals(AcceptanceCriterionRepairStage.ACCEPTED, scope.snapshot?.stage)
      val finishedPhases = harness.launchedPromptPhaseOrder().toList()
      val resumed = harness.runner.run(request, worker)
      assertIs<FeatureTaskRuntimeRunReport.Completed>(resumed, "$resumed")
      assertEquals(finishedPhases + listOf("validate", "write_history"), harness.launchedPromptPhaseOrder())
      assertEquals(listOf("validate", "validate", "write_history"), changedPhases)
    }
  }

  @Test
  fun `each authored criterion can succeed on its third repair without sharing the previous criterion budget`() {
    assertIndependentRepairBudgets(secondCriterionSucceeds = true)
  }

  @Test
  fun `the third unsuccessful repair blocks only after the current criterion spends its own budget`() {
    assertIndependentRepairBudgets(secondCriterionSucceeds = false)
  }

  @Test
  fun `operator resume restores audit evidence and executes each new plan instead of reusing a completed repair`() =
    assertOperatorResumeAuditReceipt(settlementAvailable = true)

  @Test
  fun `operator resume cannot plan from an older audit when the matching settlement is missing`() =
    assertOperatorResumeAuditReceipt(settlementAvailable = false)

  private fun assertOperatorResumeAuditReceipt(settlementAvailable: Boolean) {
    val accounting = AtomicReference<AcceptanceCriterionRepairRunState?>()
    val repairs = linkedMapOf<String, Int>()
    val launcher =
      independentBudgetLauncher(
        accounting,
        repairs,
        secondCriterionSucceeds = false,
        initialOpenCriteria = "AC-002",
      )
    val harness =
      runnerHarness(RuntimeHarnessConfig(launcher = launcher, codeReviewMode = CodeReviewExecutionMode.INLINE))
    harness.gitOperations.repositoryFingerprintValue = "unchanged-source"
    lateinit var exhausted: AcceptanceCriterionRepairState
    runOwnedSerial(harness) { admitted, worker, scope ->
      accounting.set(scope)
      assertIs<FeatureTaskRuntimeRunReport.Blocked>(
        harness.runner.run(
          harness.request().copy(admittedExecution = admitted, acceptanceCriterionRepair = scope),
          worker,
        ),
      )
      exhausted = assertNotNull(scope.snapshot)
    }
    assertEquals("AC-002", exhausted.currentCriterionRef)
    assertEquals(AcceptanceCriterionRepairStage.BUDGET_BLOCKED, exhausted.stage)
    val reopened = exhausted.resetBudgetForOperatorResume()
    reopenSerialAudit(harness, reopened)
    if (!settlementAvailable) {
      val attempt = assertNotNull(harness.recorder.loadPhaseRecords(WORKFLOW_ID)).getValue("audit").attemptCount
      assertNotNull(launcher.settlement).clear(WORKFLOW_ID, "audit", attempt)
    }
    val beforeResume = harness.launchedPromptPhaseOrder().size

    runOwnedSerial(harness) { admitted, worker, scope ->
      accounting.set(scope)
      val report =
        assertIs<FeatureTaskRuntimeRunReport.Blocked>(
          harness.runner.run(
            harness.request().copy(
              admittedExecution = admitted,
              acceptanceCriterionRepair = scope,
              goalContinuation = assertNotNull(harness.request().goalContinuation).copy(lastResumableStep = "audit"),
            ),
            worker,
          ),
        )
      val resumed = assertNotNull(scope.snapshot)
      if (settlementAvailable) {
        assertEquals("audit_plan_fix", harness.launchedPromptPhaseOrder().drop(beforeResume).firstOrNull(), "$report")
        assertContains(report.blockedReason, "three unsuccessful repair attempts")
        assertEquals(mapOf("AC-002" to 6), repairs)
        assertEquals(
          List(3) { listOf("audit_plan_fix", "audit_implement_fix") }.flatten(),
          harness.launchedPromptPhaseOrder().drop(beforeResume).filter {
            it in setOf("audit_plan_fix", "audit_implement_fix")
          },
        )
        assertEquals(3, resumed.unsuccessfulTotals["AC-002"])
      } else {
        assertContains(report.blockedReason, "requires upstream output(s) audit")
        assertEquals(beforeResume, harness.launchedPromptPhaseOrder().size)
        assertEquals(reopened, resumed)
      }
      assertEquals(exhausted.outcomes, resumed.outcomes.take(exhausted.outcomes.size))
      assertEquals(exhausted.criteria.first(), resumed.criteria.first())
    }
  }

  private fun reopenSerialAudit(
    harness: RunnerHarness,
    state: AcceptanceCriterionRepairState,
  ) {
    val records = assertNotNull(harness.recorder.loadPhaseRecords(WORKFLOW_ID)).toMutableMap()
    records["audit"] = records.getValue("audit").asPendingForOperatorResume()
    records["audit_plan_fix"] = records.getValue("audit_plan_fix").asPendingForOperatorResume()
    val artifacts = harness.repository.taskRuntimeArtifacts(WORKFLOW_ID).toMutableMap()
    artifacts[DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_ACCEPTANCE_CRITERION_REPAIR.label()] =
      state.asRepairWireMap()
    artifacts[DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_PHASE_RECORDS.label()] =
      records.mapValues { it.value.asWorkflowArtifactEntry() }
    harness.repository.replaceTaskRuntimeArtifacts(WORKFLOW_ID, artifacts)
    harness.repository.save(
      WorkflowFamily.TASK_RUNTIME,
      assertNotNull(harness.repository.get(WorkflowFamily.TASK_RUNTIME, WORKFLOW_ID))
        .copy(workflowStatus = WorkflowStatus.RUNNING),
    )
  }

  private fun assertIndependentRepairBudgets(secondCriterionSucceeds: Boolean) {
    val accounting = AtomicReference<AcceptanceCriterionRepairRunState?>()
    val repairsByCriterion = linkedMapOf<String, Int>()
    val launcher = independentBudgetLauncher(accounting, repairsByCriterion, secondCriterionSucceeds)
    val harness =
      runnerHarness(RuntimeHarnessConfig(launcher = launcher, codeReviewMode = CodeReviewExecutionMode.INLINE))
    harness.gitOperations.repositoryFingerprintValue = "unchanged-source"
    runOwnedSerial(harness) { admitted, worker, scope ->
      accounting.set(scope)
      val report =
        harness.runner.run(
          harness.request().copy(admittedExecution = admitted, acceptanceCriterionRepair = scope),
          worker,
        )
      assertEquals(mapOf("AC-001" to 3, "AC-002" to 3), repairsByCriterion, "$report; ${scope.snapshot}")
      assertEquals(2, scope.snapshot?.unsuccessfulTotals?.get("AC-001"))
      if (secondCriterionSucceeds) {
        assertIs<FeatureTaskRuntimeRunReport.Completed>(report, "$report")
        assertEquals(AcceptanceCriterionRepairStage.ACCEPTED, scope.snapshot?.stage)
        assertEquals(2, scope.snapshot?.unsuccessfulTotals?.get("AC-002"))
        assertContains(harness.launchOrder(), "review")
      } else {
        assertIs<FeatureTaskRuntimeRunReport.Blocked>(report, "$report")
        assertEquals(AcceptanceCriterionRepairStage.BUDGET_BLOCKED, scope.snapshot?.stage)
        assertEquals("AC-002", scope.snapshot?.currentCriterionRef)
        assertEquals(3, scope.snapshot?.unsuccessfulTotals?.get("AC-002"))
      }
    }
  }

  private fun independentBudgetLauncher(
    accounting: AtomicReference<AcceptanceCriterionRepairRunState?>,
    repairsByCriterion: MutableMap<String, Int>,
    secondCriterionSucceeds: Boolean,
    initialOpenCriteria: String? = null,
  ): RuntimeRecordingLauncher {
    var audit = 0
    return RuntimeRecordingLauncher { request ->
      val prompt = assertNotNull(request.skillRunRequest.promptOverride)
      val phase = phaseIdFromPrompt(prompt)
      val state = accounting.get()?.snapshot
      when (phase) {
        "audit" -> {
          audit++
          facts(
            when {
              state == null -> serialDiscoveryOutput()
              prompt.contains("Read-only repair-report admission") ->
                budgetAdmissionOutput(state.currentCriterionRef, repairsByCriterion, secondCriterionSucceeds)
              state.stage == AcceptanceCriterionRepairStage.REPAIR_COMPLETED_AWAITING_TARGETED_VERIFICATION ->
                serialStructuredAuditOutput(audit, state, "The selected production requirement is satisfied.")
              else -> {
                val refs = state.pendingAuditRefs.ifEmpty { state.unresolvedQueue }.joinToString()
                val prose =
                  if (state.mode == AcceptanceCriterionRepairMode.INITIAL_DISCOVERY && initialOpenCriteria != null) {
                    "Open criteria: $initialOpenCriteria"
                  } else {
                    "$refs remain open because their production callers are missing."
                  }
                auditRemainingAcOutput(prose).replace(
                  "\"status\": \"completed\",",
                  "\"status\": \"completed\", \"verdict\": \"advance\",",
                )
              }
            },
          )
        }
        "audit_plan_fix" -> {
          val current = assertNotNull(state?.currentCriterionRef)
          val used = state.unsuccessfulTotals.getOrDefault(current, 0)
          assertContains(prompt, current)
          assertContains(prompt, "retained failures: $used of 3")
          facts(defaultPhaseOutput(request))
        }
        "audit_implement_fix" -> {
          val current = assertNotNull(state?.currentCriterionRef)
          val attempt = repairsByCriterion.getOrDefault(current, 0) + 1
          repairsByCriterion[current] = attempt
          assertContains(prompt, current)
          if (repairAcceptedOnThirdAttempt(current, attempt, secondCriterionSucceeds)) {
            facts(defaultPhaseOutput(request))
          } else if (attempt == 1) {
            facts(
              defaultPhaseOutput(request).replace(
                "\"status\": \"completed\",",
                "\"status\": \"completed\", \"verdict\": \"no_progress\",",
              ),
            )
          } else {
            facts(
              """{
                  "contract_version":"0.7","phase_id":"audit_implement_fix","status":"blocked",
                  "failure_disposition":"retryable","summary":"The external dependency refused the repair.",
                  "produced_outputs":{"value":"The required external dependency for $current is unavailable."}
                }""",
            )
          }
        }
        else -> facts(defaultPhaseOutput(request))
      }
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
        NoopRuntimeDiagnostics,
      )
    val inputs =
      admission.creationResolver().resolveInputs(
        harness.request().repoRoot,
        admission.plan.qualityGateSelection,
        admission.inputs.validationDepth,
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

  private fun continuingAuditOutput(
    state: AcceptanceCriterionRepairState?,
    audits: Int,
  ): String =
    when {
      state == null -> serialDiscoveryOutput()
      state.stage == AcceptanceCriterionRepairStage.REPAIR_COMPLETED_AWAITING_TARGETED_VERIFICATION ->
        serialStructuredAuditOutput(audits, state, "The complete production repair is present.")
      else ->
        auditRemainingAcOutput("Open criteria: AC-001").replace(
          "\"status\": \"completed\",",
          "\"status\": \"completed\", \"verdict\": \"advance\",",
        )
    }

  private fun budgetAdmissionOutput(
    criterionRef: String?,
    repairsByCriterion: Map<String, Int>,
    secondCriterionSucceeds: Boolean,
  ): String =
    if (repairAcceptedOnThirdAttempt(
        criterionRef,
        repairsByCriterion.getOrDefault(criterionRef, 0),
        secondCriterionSucceeds,
      )
    ) {
      settledAuditSatisfiedRecord()
    } else {
      settledAuditSatisfiedRecord().replace("satisfied", "advance")
    }

  private fun repairAcceptedOnThirdAttempt(
    criterionRef: String?,
    attempt: Int,
    secondCriterionSucceeds: Boolean,
  ): Boolean = attempt == 3 && (criterionRef == "AC-001" || secondCriterionSucceeds)

  private fun serialLauncher(
    repairs: AtomicInteger,
    validationFailures: Int = 0,
    onPhase: (String) -> Unit = {},
    currentState: () -> AcceptanceCriterionRepairState?,
  ): RuntimeRecordingLauncher {
    var audit = 0
    var validations = 0
    return RuntimeRecordingLauncher { request ->
      val prompt = assertNotNull(request.skillRunRequest.promptOverride)
      val phase = phaseIdFromPrompt(prompt)
      onPhase(phase)
      when (phase) {
        "audit" -> {
          audit++
          if (audit <= 4) assertContains(prompt, "AC-1")
          if (audit == 7) assertContains(prompt, "AC-2")
          val state = currentState()
          if (audit == 1) {
            facts(serialDiscoveryOutput())
          } else if (state?.stage == AcceptanceCriterionRepairStage.REPAIR_COMPLETED_AWAITING_TARGETED_VERIFICATION) {
            facts(serialStructuredAuditOutput(audit, state, "Current production behavior meets this requirement."))
          } else {
            facts(
              if (audit <= 4) {
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
            "failure_disposition":"retryable","summary":"External dependency refused",
            "produced_outputs":{"value":"The external dependency refuses access and prevents the remaining write path repair."}
          }""",
            )
          } else {
            facts(defaultPhaseOutput(request))
          }
        }
        "validate" ->
          facts(
            if (validations++ < validationFailures) VALIDATE_BLOCKED_OUTPUT else defaultPhaseOutput(request),
          )
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
        AcceptanceCriterionRepairMode.INITIAL_DISCOVERY
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
        harnessPhaseSettlement(),
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
