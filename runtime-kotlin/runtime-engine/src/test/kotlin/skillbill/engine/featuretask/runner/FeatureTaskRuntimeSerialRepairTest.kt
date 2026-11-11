package skillbill.engine.featuretask.runner

import skillbill.application.testHarnessClock
import skillbill.contracts.JsonCodec
import skillbill.contracts.SharedPayloadKeys
import skillbill.contracts.workflow.featuretask.ACCEPTANCE_CRITERION_AUDIT_RESULT_CONTRACT_VERSION
import skillbill.contracts.workflow.featuretask.FEATURE_TASK_RUNTIME_CONTRACT_VERSION
import skillbill.engine.RecordingWorkflowGitOperations
import skillbill.engine.featuretask.lifecycle.core.AcquiredFeatureTaskRuntimeWorker
import skillbill.engine.featuretask.lifecycle.core.FeatureTaskRuntimeWorkerCoordinator
import skillbill.engine.featuretask.lifecycle.core.absentDatabaseCheckpoint
import skillbill.engine.featuretask.lifecycle.execution.ExecutionPlanAdmissionFixture
import skillbill.engine.featuretask.model.audit.AcceptanceCriterionRepairBinding
import skillbill.engine.featuretask.model.audit.AcceptanceCriterionRepairRunState
import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeRunReport
import skillbill.engine.featuretask.model.execution.AdmittedFeatureTaskRuntimeExecution
import skillbill.engine.featuretask.model.phase.AppendCheckpointIdentityArgs
import skillbill.engine.featuretask.persist.FeatureTaskRuntimeWorkflowPersistence
import skillbill.engine.featuretask.persist.RuntimeOwnedPersistenceBoundary
import skillbill.engine.featuretask.persist.acceptance.AcceptanceCriterionRepairAuditAdmission
import skillbill.engine.featuretask.persist.acceptance.AcceptanceCriterionRepairLegacyAdoption
import skillbill.engine.featuretask.persist.acceptance.AcceptanceCriterionRepairPersistence
import skillbill.engine.featuretask.persist.sha256Hex
import skillbill.engine.featuretask.phase.planning.PlanBundleFeasibilityStateCodec
import skillbill.engine.featuretask.phase.record.FeatureTaskRuntimePhaseStateRecorder
import skillbill.engine.featuretask.runloop.qualitygate.MAX_VALIDATE_ATTEMPTS
import skillbill.engine.featuretask.testing.featureTaskRuntimeWireArtifactValidatorForTest
import skillbill.infrastructure.contracts.workflow.featuretask.ContractFeatureTaskRuntimePhaseOutputMigration
import skillbill.infrastructure.sqlite.workflow.artifact.SqliteWorkflowArtifactWireEncoder
import skillbill.ports.diagnostics.NoopRuntimeDiagnostics
import skillbill.ports.diagnostics.RuntimeDiagnostics
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
import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionGapStatus
import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionRepairCriterion
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
  fun `two repairs without closed gaps consume attempts before a third repair passes verification`() {
    val accounting = AtomicReference<AcceptanceCriterionRepairRunState?>()
    val git = AtomicReference<RecordingWorkflowGitOperations>()
    val reservations = mutableListOf<String>()
    val diagnostics = RepairLaunchDiagnostics()
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
              continuingAuditOutput(state, audits, satisfied = repairs >= 3)
            }
            "audit_plan_fix" -> {
              plans++
              defaultPhaseOutput(request)
            }
            "audit_implement_fix" -> {
              repairs++
              reservations += assertNotNull(state?.reservation?.reservationId)
              check(repairs <= 3) { "The completed repair did not reach independent verification." }
              git.get().contentIdentities["src/Foo.kt"] = "repair-$repairs"
              assertEquals(repairs - 1, assertNotNull(state).chargedAttempts.getOrDefault("AC-001", 0))
              assertEquals(repairs - 1, state.outcomes.size)
              assertEquals(
                AcceptanceCriterionSourceDisposition.SOURCE_UNRESOLVED,
                state.criteria.first().sourceDisposition,
              )
              defaultPhaseOutput(request).replace(
                "\"status\": \"completed\",",
                "\"status\": \"completed\", \"verdict\": \"${if (repairs < 3) "progress" else "satisfied"}\",",
              )
            }
            else -> defaultPhaseOutput(request)
          },
        )
      }
    val harness =
      runnerHarness(
        RuntimeHarnessConfig(
          launcher = launcher,
          codeReviewMode = CodeReviewExecutionMode.INLINE,
          diagnostics = diagnostics,
        ),
      )
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
      assertEquals(3, repairs)
      assertEquals(2, plans)
      diagnostics.assertSingleEscalationWarning()
      assertEquals(3, reservations.distinct().size)
      assertThirdRepairCompleted(assertNotNull(scope.snapshot))
      assertContains(harness.launchOrder(), "review")
      assertContains(harness.launchOrder(), "validate")
    }
  }

  private fun assertThirdRepairCompleted(state: AcceptanceCriterionRepairState) {
    assertEquals(2, state.chargedAttempts["AC-001"])
    assertEquals(AcceptanceCriterionRepairStage.ACCEPTED, state.stage)
    assertEquals(3, state.outcomes.size)
    assertTrue(state.interactionTrace.pendingRechecks.isEmpty())
  }

  private class RepairLaunchDiagnostics : RuntimeDiagnostics by NoopRuntimeDiagnostics {
    private val warnings = mutableListOf<String>()

    override fun warning(
      message: String,
      error: Throwable?,
    ) {
      warnings += message
    }

    fun assertSingleEscalationWarning() {
      val warning = warnings.filter { "seam=acceptance_repair_escalation" in it }.single()
      assertContains(warning, "workflow=$WORKFLOW_ID")
      assertContains(warning, "phase=audit_implement_fix")
      assertContains(warning, "criterion=AC-001")
      assertContains(warning, "agent=$INVOKED_AGENT")
    }
  }

  @Test
  fun `progress reports that change no source block after three attempts`() =
    assertProgressReportsBlock(changesSource = false)

  @Test
  fun `progress reports that change source block after three attempts`() =
    assertProgressReportsBlock(
      changesSource = true,
    )

  private fun assertProgressReportsBlock(changesSource: Boolean) {
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
              continuingAuditOutput(state, audits, satisfied = false)
            }
            "audit_plan_fix" -> {
              plans++
              defaultPhaseOutput(request)
            }
            "audit_implement_fix" -> {
              repairs++
              reservations += assertNotNull(state?.reservation?.reservationId)
              check(repairs <= 3) { "Unfinished repair reports bypassed the criterion budget." }
              if (changesSource) git.get().contentIdentities["src/Foo.kt"] = "repair-$repairs"
              assertEquals(repairs - 1, assertNotNull(state).chargedAttempts.getOrDefault("AC-001", 0))
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
    git.set(harness.gitOperations)
    harness.gitOperations.repositoryFingerprintValue = "unchanged-source"
    runOwnedSerial(harness) { admitted, worker, scope ->
      accounting.set(scope)
      val report =
        harness.runner.run(
          harness.request().copy(admittedExecution = admitted, acceptanceCriterionRepair = scope),
          worker,
        )
      assertIs<FeatureTaskRuntimeRunReport.Blocked>(report, "$report")
      assertContains(report.blockedReason, "three charged repair attempts")
      assertEquals(3, repairs)
      assertEquals(2, plans)
      assertEquals(3, reservations.distinct().size)
      assertEquals(3, assertNotNull(scope.snapshot).chargedAttempts["AC-001"])
      assertEquals(AcceptanceCriterionRepairStage.BUDGET_BLOCKED, scope.snapshot?.stage)
      assertFalse("review" in harness.launchOrder())
    }
  }

  @Test
  fun `audits that keep completing without a verdict block instead of relaunching indefinitely`() {
    val accounting = AtomicReference<AcceptanceCriterionRepairRunState?>()
    var audits = 0
    val launcher =
      RuntimeRecordingLauncher { request ->
        val phase = phaseIdFromPrompt(assertNotNull(request.skillRunRequest.promptOverride))
        facts(
          when (phase) {
            "audit" -> {
              audits++
              check(audits <= 3) { "Verdict-less audits relaunched without blocking." }
              if (accounting.get()?.snapshot == null) {
                verdictlessDiscoveryOutput()
              } else {
                serialGapDiscoveryProse(
                  "AC-001",
                  "The settlement tools are unavailable, so this prose is the phase value.",
                )
              }
            }
            else -> defaultPhaseOutput(request)
          },
        )
      }
    val harness =
      runnerHarness(RuntimeHarnessConfig(launcher = launcher, codeReviewMode = CodeReviewExecutionMode.INLINE))
    runOwnedSerial(harness) { admitted, worker, scope ->
      accounting.set(scope)
      val report =
        harness.runner.run(
          harness.request().copy(admittedExecution = admitted, acceptanceCriterionRepair = scope),
          worker,
        )
      assertIs<FeatureTaskRuntimeRunReport.Blocked>(report, "$report")
      assertTrue(report.blockedReason.startsWith("runtime: audit completed two rounds in a row without a verdict"))
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
                state == null -> serialDiscoveryOutput("AC-002").replace("advance", "no_change_rejected")
                state.stage == AcceptanceCriterionRepairStage.INTERACTION_TRACE_REQUIRED ->
                  serialInteractionOutput(
                    state,
                  )
                else -> {
                  val criterion = assertNotNull(state.currentCriterionRef)
                  focused += criterion
                  assertFalse(prompt.contains("Judge every criterion in the claim"))
                  assertContains(prompt, "($criterion)")
                  if (criterion == "AC-001" || repaired) {
                    serialStructuredAuditOutput(focused.size, state, "The production caller is implemented.")
                  } else {
                    auditRemainingAcOutput("AC-002 has no production caller.")
                      .replace("\"status\": \"completed\",", "\"status\": \"completed\", \"verdict\": \"advance\",")
                  }
                }
              }
            "audit_plan_fix", "audit_implement_fix" -> {
              assertEquals("AC-002", state?.currentCriterionRef)
              if (phase == "audit_implement_fix") {
                repaired = true
                completedRepairOutput(defaultPhaseOutput(request))
              } else {
                defaultPhaseOutput(request)
              }
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
      assertEquals(listOf("AC-002"), focused)
      assertTrue(assertNotNull(scope.snapshot).chargedAttempts.isEmpty())
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
  fun `repair planning and implementation carry only the latest verify result`() {
    val accounting = AtomicReference<AcceptanceCriterionRepairRunState?>()
    val firstRejection = "The replay reader selects an unrelated historical record."
    val secondRejection = "The writer uses an invented generation to avoid an identity collision."
    val repairPrompts = mutableListOf<String>()
    var repairs = 0
    var audits = 0
    val launcher =
      RuntimeRecordingLauncher { request ->
        val prompt = assertNotNull(request.skillRunRequest.promptOverride)
        val phase = phaseIdFromPrompt(prompt)
        val state = accounting.get()?.snapshot
        facts(
          when (phase) {
            "audit" -> {
              audits++
              repairRejectionAuditOutput(audits, state, repairs, listOf(firstRejection, secondRejection))
            }
            "audit_plan_fix", "audit_implement_fix" -> {
              if (state?.currentCriterionRef == "AC-002") {
                assertFalse(prompt.contains(firstRejection))
                assertFalse(prompt.contains(secondRejection))
              }
              if (repairs == 2) {
                assertFalse(prompt.contains(firstRejection))
                assertContains(prompt, secondRejection)
                repairPrompts += phase
              }
              if (phase == "audit_implement_fix") {
                repairs++
                completedRepairOutput(defaultPhaseOutput(request))
              } else {
                defaultPhaseOutput(request)
              }
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

      assertIs<FeatureTaskRuntimeRunReport.Completed>(report, "$report")
      assertEquals(listOf("audit_plan_fix", "audit_implement_fix"), repairPrompts)
      assertEquals(3, repairs)
      assertEquals(2, assertNotNull(scope.snapshot).chargedAttempts["AC-001"])
      assertEquals(AcceptanceCriterionRepairStage.ACCEPTED, scope.snapshot?.stage)
    }
  }

  private fun repairRejectionAuditOutput(
    audits: Int,
    state: AcceptanceCriterionRepairState?,
    repairs: Int,
    rejections: List<String>,
  ): String =
    when {
      state == null -> serialDiscoveryOutput()
      state.stage == AcceptanceCriterionRepairStage.INTERACTION_TRACE_REQUIRED -> serialInteractionOutput(state)
      state.stage == AcceptanceCriterionRepairStage.REPAIR_COMPLETED_AWAITING_TARGETED_VERIFICATION && repairs >= 3 ->
        serialStructuredAuditOutput(audits, state, "The production paths preserve the accepted identity.")
      else -> {
        val finding =
          rejections.getOrNull(
            repairs - 1,
          ) ?: "The selected criterion still has incomplete production paths."
        auditRemainingAcOutput(serialGapDiscoveryProse(assertNotNull(state.currentCriterionRef), finding)).replace(
          "\"status\": \"completed\",",
          "\"status\": \"completed\", " + "\"verdict\": \"advance\",",
        )
      }
    }

  @Test
  fun `a first failed repair reuses its plan and an accepted queue moves to review`() {
    val repairs = AtomicInteger()
    val currentState = AtomicReference<AcceptanceCriterionRepairRunState?>()
    val launcher = serialLauncher(repairs) { currentState.get()?.snapshot }
    val harness =
      runnerHarness(
        RuntimeHarnessConfig(
          launcher = launcher,
          codeReviewMode = CodeReviewExecutionMode.INLINE,
        ),
      )
    harness.gitOperations.repositoryFingerprintValue = "unchanged-source"
    runOwnedSerial(harness) { admitted, worker, scope ->
      currentState.set(scope)
      val report =
        harness.runner.run(
          harness.request().copy(admittedExecution = admitted, acceptanceCriterionRepair = scope),
          worker,
        )
      assertIs<FeatureTaskRuntimeRunReport.Completed>(report, "$report")
      assertEquals(AcceptanceCriterionRepairStage.ACCEPTED, scope.snapshot?.stage)
      assertEquals(1, scope.snapshot?.chargedAttempts?.get("AC-001"))
      assertEquals(2, repairs.get())
      assertEquals(
        listOf(
          "audit",
          "audit_plan_fix",
          "audit_implement_fix",
          "audit",
          "audit_implement_fix",
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
    val currentState = AtomicReference<AcceptanceCriterionRepairRunState?>()
    val launchedPhaseAndLeasePhase = mutableListOf<Pair<String, String?>>()
    lateinit var harness: RunnerHarness
    val launcher =
      serialLauncher(repairs, onPhase = { phase ->
        val lease = harness.io.database.read { it.workflowStates.getFeatureTaskRuntimeWorkerOwnership(WORKFLOW_ID) }
        launchedPhaseAndLeasePhase += phase to lease?.phaseId
      }) { currentState.get()?.snapshot }
    harness =
      runnerHarness(RuntimeHarnessConfig(launcher = launcher, codeReviewMode = CodeReviewExecutionMode.INLINE))
    harness.gitOperations.repositoryFingerprintValue = "unchanged-source"
    runOwnedSerial(harness) { admitted, worker, scope ->
      currentState.set(scope)
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
    val currentState = AtomicReference<AcceptanceCriterionRepairRunState?>()
    val changedPhases = mutableListOf<String>()
    val launcher =
      serialLauncher(AtomicInteger(), validationFailures = MAX_VALIDATE_ATTEMPTS, onPhase = { phase ->
        if (phase in setOf("validate", "write_history")) {
          changedPhases += phase
          git.repositoryFingerprintValue = "changed-by-$phase"
        }
      }) { currentState.get()?.snapshot }
    val harness =
      runnerHarness(
        RuntimeHarnessConfig(
          launcher = launcher,
          branchSetup = BranchSetupTestConfig(gitOperations = git),
          codeReviewMode = CodeReviewExecutionMode.INLINE,
        ),
      )
    runOwnedSerial(harness) { admitted, worker, scope ->
      currentState.set(scope)
      val request = harness.request().copy(admittedExecution = admitted, acceptanceCriterionRepair = scope)
      val report = harness.runner.run(request, worker)
      assertEquals("validate", assertIs<FeatureTaskRuntimeRunReport.Blocked>(report, "$report").lastIncompletePhase)
      assertEquals(List(MAX_VALIDATE_ATTEMPTS) { "validate" }, changedPhases)
      assertEquals(AcceptanceCriterionRepairStage.ACCEPTED, scope.snapshot?.stage)
      val finishedPhases = harness.launchedPromptPhaseOrder().toList()
      val resumed = harness.runner.run(request, worker)
      assertIs<FeatureTaskRuntimeRunReport.Completed>(resumed, "$resumed")
      assertEquals(finishedPhases + listOf("validate", "write_history"), harness.launchedPromptPhaseOrder())
      assertEquals(List(MAX_VALIDATE_ATTEMPTS + 1) { "validate" } + "write_history", changedPhases)
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
  fun `operator resume reuses the escalated plan and keeps the latest verify evidence`() =
    assertOperatorResumeAuditReceipt(settlementAvailable = true)

  @Test
  fun `operator resume uses committed repair state when the old audit settlement is missing`() =
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
    val launchesBeforeResume = launcher.requests.size

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
      assertResumedRepairEvidence(launcher, launchesBeforeResume, exhausted)
      assertEquals(
        "audit_implement_fix",
        harness.launchedPromptPhaseOrder().drop(beforeResume).firstOrNull(),
        "$report",
      )
      assertContains(report.blockedReason, "three charged repair attempts")
      assertEquals(mapOf("AC-002" to 6), repairs)
      assertEquals(
        List(3) { "audit_implement_fix" },
        harness.launchedPromptPhaseOrder().drop(beforeResume).filter {
          it in setOf("audit_plan_fix", "audit_implement_fix")
        },
      )
      assertEquals(3, resumed.chargedAttempts["AC-002"])
      assertEquals(exhausted.outcomes, resumed.outcomes.take(exhausted.outcomes.size))
      assertEquals(exhausted.criteria.first(), resumed.criteria.first())
    }
  }

  private fun assertResumedRepairEvidence(
    launcher: RuntimeRecordingLauncher,
    launchesBeforeResume: Int,
    exhausted: AcceptanceCriterionRepairState,
  ) {
    val prompts =
      launcher.requests.drop(launchesBeforeResume).mapNotNull { it.skillRunRequest.promptOverride }.filter {
        phaseIdFromPrompt(it) in setOf("audit_plan_fix", "audit_implement_fix")
      }.take(2)
    assertEquals(2, prompts.size)
    prompts.forEach { prompt ->
      assertFalse(prompt.contains("charged attempts:"))
      val failures = exhausted.outcomes.filter { it.criterionRef == "AC-002" && !it.successful }
      assertContains(prompt, failures.last().evidenceRef)
      failures.forEach { outcome -> assertFalse(prompt.contains(outcome.outcomeId)) }
    }
  }

  private fun reopenSerialAudit(
    harness: RunnerHarness,
    state: AcceptanceCriterionRepairState,
  ) {
    val records = assertNotNull(harness.recorder.loadPhaseRecords(WORKFLOW_ID)).toMutableMap()
    records["audit"] = records.getValue("audit").asPendingForOperatorResume()
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
      assertEquals(2, scope.snapshot?.chargedAttempts?.get("AC-001"))
      if (secondCriterionSucceeds) {
        assertIs<FeatureTaskRuntimeRunReport.Completed>(report, "$report")
        assertEquals(AcceptanceCriterionRepairStage.ACCEPTED, scope.snapshot?.stage)
        assertEquals(2, scope.snapshot?.chargedAttempts?.get("AC-002"))
        assertContains(harness.launchOrder(), "review")
      } else {
        assertIs<FeatureTaskRuntimeRunReport.Blocked>(report, "$report")
        assertEquals(AcceptanceCriterionRepairStage.BUDGET_BLOCKED, scope.snapshot?.stage)
        assertEquals("AC-002", scope.snapshot?.currentCriterionRef)
        assertEquals(3, scope.snapshot?.chargedAttempts?.get("AC-002"))
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
              state == null -> serialDiscoveryOutput(initialOpenCriteria ?: "AC-001, AC-002")
              state.stage == AcceptanceCriterionRepairStage.INTERACTION_TRACE_REQUIRED -> serialInteractionOutput(state)
              state.stage == AcceptanceCriterionRepairStage.REPAIR_COMPLETED_AWAITING_TARGETED_VERIFICATION ->
                budgetVerificationOutput(audit, state, repairsByCriterion, secondCriterionSucceeds)
              else -> {
                val refs = state.pendingAuditRefs.ifEmpty { state.unresolvedQueue }.joinToString()
                val prose = budgetDiscoveryProse(state, initialOpenCriteria, refs)
                auditRemainingAcOutput(prose).replace(
                  "\"status\": \"completed\",",
                  "\"status\": \"completed\", " + "\"verdict\": \"advance\",",
                )
              }
            },
          )
        }
        "audit_plan_fix" -> {
          val current = assertNotNull(state?.currentCriterionRef)
          assertContains(prompt, current)
          assertFalse(prompt.contains("charged attempts:"))
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

  private fun budgetDiscoveryProse(
    state: AcceptanceCriterionRepairState,
    initialOpenCriteria: String?,
    refs: String,
  ): String =
    serialGapDiscoveryProse(
      if (state.mode == AcceptanceCriterionRepairMode.INITIAL_DISCOVERY && initialOpenCriteria != null) {
        initialOpenCriteria
      } else {
        refs
      },
    )

  private fun budgetVerificationOutput(
    audit: Int,
    state: AcceptanceCriterionRepairState,
    repairsByCriterion: Map<String, Int>,
    secondCriterionSucceeds: Boolean,
  ): String =
    if (repairAcceptedOnThirdAttempt(
        state.currentCriterionRef,
        repairsByCriterion.getOrDefault(state.currentCriterionRef, 0),
        secondCriterionSucceeds,
      )
    ) {
      serialStructuredAuditOutput(audit, state, "The selected production requirement is satisfied.")
    } else {
      budgetFailedVerificationOutput(state.currentCriterionRef, repairsByCriterion, secondCriterionSucceeds)
    }

  private fun budgetFailedVerificationOutput(
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

  private fun verdictlessDiscoveryOutput(): String {
    val json = assertNotNull(JsonCodec.parseObjectOrNull(serialDiscoveryOutput()))
    val values = assertNotNull(JsonCodec.anyToStringAnyMap(JsonCodec.jsonElementToValue(json)))
    return JsonCodec.mapToJsonString(values - SharedPayloadKeys.VERDICT)
  }

  private fun serialVerificationOutput(
    audit: Int,
    state: AcceptanceCriterionRepairState,
    repairs: Int,
  ): String =
    serialStructuredAuditOutput(
      audit,
      state,
      "Current production behavior was inspected.",
      satisfied = repairs > 1,
    )

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

          val state = currentState()
          if (audit == 1) {
            facts(serialDiscoveryOutput())
          } else if (state?.stage == AcceptanceCriterionRepairStage.INTERACTION_TRACE_REQUIRED) {
            facts(serialInteractionOutput(state))
          } else if (state?.stage == AcceptanceCriterionRepairStage.REPAIR_COMPLETED_AWAITING_TARGETED_VERIFICATION) {
            facts(serialVerificationOutput(audit, state, repairs.get()))
          } else {
            facts(
              if (audit <= 4) {
                auditRemainingAcOutput(
                  serialGapDiscoveryProse("AC-001, AC-002", "There is no caller for the first requirement."),
                ).replace(
                  "\"status\": \"completed\",",
                  "\"status\": \"completed\", " + "\"verdict\": \"advance\",",
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
          assertContains(prompt, "AC-001.G1: Missing production caller @ src/Foo.kt:1")
          assertFalse(prompt.contains("live cycle:"))
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
            facts(completedRepairOutput(defaultPhaseOutput(request)))
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

  private fun completedRepairOutput(output: String): String =
    output.replace(
      "\"status\": \"completed\",",
      "\"status\": \"completed\", \"verdict\": \"completed\",",
    )
}

internal fun runOwnedSerial(
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
      absentDatabaseCheckpoint,
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

internal fun continuingAuditOutput(
  state: AcceptanceCriterionRepairState?,
  audits: Int,
  satisfied: Boolean = true,
): String =
  when {
    state == null -> serialDiscoveryOutput()
    state.stage == AcceptanceCriterionRepairStage.REPAIR_COMPLETED_AWAITING_TARGETED_VERIFICATION ->
      serialStructuredAuditOutput(audits, state, "The production repair was inspected.", satisfied = satisfied)
    state.stage == AcceptanceCriterionRepairStage.INTERACTION_TRACE_REQUIRED -> serialInteractionOutput(state)
    else ->
      auditRemainingAcOutput(serialGapDiscoveryProse("AC-001")).replace(
        "\"status\": \"completed\",",
        "\"status\": \"completed\", " + "\"verdict\": \"advance\",",
      )
  }

internal fun serialDiscoveryOutput(openRefs: String = "AC-001"): String {
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
      Keys.INTERACTIONS to emptyList<Any>(),
      Keys.RECHECKS to emptyList<Any>(),
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
          SharedPayloadKeys.VALUE to serialGapDiscoveryProse(openRefs),
          Keys.ACCEPTANCE_CRITERION_AUDIT_RESULT to audit,
        ),
    ),
  )
}

internal fun serialStructuredAuditOutput(
  attempt: Int,
  state: AcceptanceCriterionRepairState?,
  prose: String,
  satisfied: Boolean = true,
): String {
  val current = assertNotNull(state)
  val report =
    (
      listOf(prose) +
        current.criteria.firstOrNull { it.canonicalRef == current.currentCriterionRef }
          ?.gaps.orEmpty().filter { it.status == AcceptanceCriterionGapStatus.OPEN }
          .map { "${it.id}: ${if (satisfied) "closed" else "open"} @ src/Foo.kt:1" }
    ).joinToString("\n")
  val tree = current.admittedTreeDigest ?: sha256Hex("unchanged-source")
  val checkpoint = current.admittedCheckpointId ?: "unchanged-source"
  val ref = "$WORKFLOW_ID:audit:$attempt:${current.revision}"
  val digest = sha256Hex("$WORKFLOW_ID:audit:$attempt:${current.revision}:$report:$tree")
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
      Keys.CRITERIA to selected.map { criterionFact(it, targeted && satisfied, report) },
      Keys.DEPENDENCIES to current.asRepairWireMap()[Keys.DEPENDENCIES],
      Keys.REGRESSED_REFS to emptyList<String>(),
      Keys.EDGE_CHANGES to emptyList<Any>(),
      Keys.INTERACTIONS to emptyList<Any>(),
      Keys.RECHECKS to emptyList<Any>(),
    )
  return JsonCodec.mapToJsonString(
    mapOf(
      SharedPayloadKeys.CONTRACT_VERSION to FEATURE_TASK_RUNTIME_CONTRACT_VERSION,
      SharedPayloadKeys.PHASE_ID to "audit",
      SharedPayloadKeys.STATUS to "completed",
      SharedPayloadKeys.SUMMARY to "Structured acceptance audit.",
      SharedPayloadKeys.VERDICT to if (satisfied) "satisfied" else "advance",
      SharedPayloadKeys.PRODUCED_OUTPUTS to
        mapOf(
          SharedPayloadKeys.VALUE to report,
          Keys.ACCEPTANCE_CRITERION_AUDIT_RESULT to auditResult,
        ),
    ),
  )
}

private fun criterionFact(
  criterion: AcceptanceCriterionRepairCriterion,
  targeted: Boolean,
  prose: String,
): Map<String, Any?> {
  val disposition = if (targeted) AcceptanceCriterionSourceDisposition.SOURCE_CLOSED else criterion.sourceDisposition
  return mapOf(
    Keys.CANONICAL_REF to criterion.canonicalRef,
    Keys.SOURCE_DISPOSITION to disposition.wireValue,
    Keys.VALIDATION_OBLIGATION to criterion.validationObligation.wireValue,
    Keys.VALIDATION_OWNER_AVAILABLE to criterion.validationOwnerAvailable,
    Keys.EVIDENCE_REF to prose,
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
      PlanBundleFeasibilityStateCodec(validator),
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
      RUNNER_TEST_PROJECT_STORE,
      SqliteWorkflowArtifactWireEncoder(),
    ),
    AcceptanceCriterionRepairAuditAdmission(validator),
  )
}

internal fun serialGapDiscoveryProse(
  refs: String,
  text: String = "Production callers are missing.",
): String =
  (
    listOf(text, "Open criteria: $refs") +
      refs.split(",").map {
        "${it.trim()}.G1: Missing production caller @ src/Foo.kt:1"
      }
  ).joinToString("\n")

internal fun serialInteractionOutput(state: AcceptanceCriterionRepairState): String =
  auditRemainingAcOutput(
    (
      state.interactionTrace.pendingScenarioRefs.map { "Interaction $it: holds evidence src/Foo.kt:1" } +
        state.interactionTrace.pendingRechecks.map { "Recheck $it: holds evidence src/Foo.kt:1" }
    ).joinToString("\n"),
  )
    .replace("\"status\": \"completed\",", "\"status\": \"completed\", \"verdict\": \"satisfied\",")
