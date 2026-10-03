package skillbill.engine.featuretask.phaserun

import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeRunReport
import skillbill.engine.featuretask.model.phase.FeatureTaskRuntimePhaseLaunchBriefing
import skillbill.engine.featuretask.model.phase.FeatureTaskRuntimePhaseStateRequest
import skillbill.engine.featuretask.runloop.core.FeatureTaskRuntimeRunLoop
import skillbill.engine.featuretask.runloop.core.FeatureTaskRuntimeRunLoopContext
import skillbill.engine.featuretask.runloop.core.FeatureTaskRuntimeRunLoopPlanningBranch
import skillbill.engine.featuretask.runloop.core.PhaseRun
import skillbill.engine.featuretask.runner.IMPLEMENT_OUTPUT
import skillbill.engine.featuretask.runner.PLAN_OUTPUT
import skillbill.engine.featuretask.runner.PREPLAN_OUTPUT
import skillbill.engine.featuretask.runner.RuntimeHarnessConfig
import skillbill.engine.featuretask.runner.SIMPLIFY_OUTPUT
import skillbill.engine.featuretask.runner.TestFeatureTaskRuntimeRunLoopEntry
import skillbill.engine.featuretask.runner.WORKFLOW_ID
import skillbill.engine.featuretask.runner.committedRepoBranchSetup
import skillbill.engine.featuretask.runner.satisfiedAuditLauncher
import skillbill.engine.featuretask.runner.telemetryRunnerHarness
import skillbill.engine.featuretask.runner.withRunState
import skillbill.engine.featuretask.slot.PhaseStrategy
import skillbill.engine.featuretask.slot.attempt.PhaseAttemptOnce
import skillbill.engine.featuretask.slot.attempt.PhaseAttemptRunHost
import skillbill.engine.featuretask.slot.attempt.PhaseAttemptScope
import skillbill.engine.featuretask.slot.attempt.stepCall
import skillbill.engine.featuretask.slot.qualitygate.packbuild.PackBuildStrategy
import skillbill.engine.featuretask.slot.qualitygate.packvalidation.PackValidationStrategy
import skillbill.engine.featuretask.slot.state.PhaseAcceptedStepExecution
import skillbill.engine.featuretask.slot.state.PhaseAgentExecution
import skillbill.engine.featuretask.slot.state.PhaseCommitStepBinding
import skillbill.engine.featuretask.slot.state.PhasePlanningBriefingBinding
import skillbill.engine.featuretask.slot.state.PhasePullRequestStepBinding
import skillbill.engine.featuretask.slot.state.PhaseQualityGateStepBinding
import skillbill.engine.featuretask.slot.state.PhaseReviewStepBinding
import skillbill.engine.featuretask.slot.state.PhaseRunRecords
import skillbill.engine.featuretask.slot.state.PhaseRunState
import skillbill.engine.featuretask.slot.state.RequiredPhaseWrite
import skillbill.engine.featuretask.slot.state.RequiredPhaseWriteKind
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.ports.validation.ValidationGateRunner
import skillbill.ports.validation.model.ValidationGateRunRequest
import skillbill.ports.validation.model.ValidationGateRunResult
import skillbill.workflow.model.WorkflowStepStatus
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimeResolvedBranch
import skillbill.workflow.taskruntime.model.handoff.assembly.FeatureTaskRuntimePhaseOutput
import skillbill.workflow.taskruntime.model.handoff.task.FeatureTaskRuntimeSharedEvidenceMeasurement
import java.nio.file.Files
import java.util.concurrent.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class RequiredPhasePersistenceTest {
  @Test
  fun rejectedStartPreventsOrdinaryAuditReviewGateAndCommitEffects() {
    listOf("preplan", "implement", "audit", "review", "validate", "build", "commit_push").forEach { phase ->
      withCapturedContext { context, launcherCount, gateCount, assertNoGitEffects ->
        val rejection = RequiredPhaseWrite.Rejected(RequiredPhaseWriteKind.START, WORKFLOW_ID, phase, 1)
        val records = rejectingRecords(context.recorder, rejection)
        val strategy =
          when (phase) {
            "build" -> PackBuildStrategy()
            "validate" -> PackValidationStrategy()
            else -> context.runState.strategyFor(phase)
          }
        val intercepted = context.withRecords(records, selectedStrategy = strategy)
        val run = phaseRun(intercepted, phase)

        val binding = intercepted.runState.step(run)
        if (phase in setOf("build", "validate", "commit_push")) {
          assertFalse(binding is PhaseAgentExecution, phase)
        }
        val outcome = strategy.runStep(run, binding)

        assertEquals(rejection.message, outcome.blockedReason, phase)
        assertEquals(0, launcherCount(), phase)
        assertEquals(0, gateCount(), phase)
        assertNoGitEffects()
        val terminal = assertNotNull(context.recorder.loadPhaseRecords(WORKFLOW_ID)?.get(phase))
        assertEquals(WorkflowStepStatus.BLOCKED, terminal.status)
        assertEquals(rejection.message, terminal.blockedReason)
        assertEquals(1, terminal.attemptCount)
      }
    }
  }

  @Test
  fun rejectedBriefingPreventsLaunchAndRecordsItsOwnReason() {
    withCapturedContext { context, launcherCount, gateCount, assertNoGitEffects ->
      val rejection = RequiredPhaseWrite.Rejected(RequiredPhaseWriteKind.BRIEFING, WORKFLOW_ID, "preplan", 1)
      val intercepted = context.withRecords(rejectingRecords(context.recorder, rejection))
      val run = phaseRun(intercepted, "preplan")

      val outcome = intercepted.runState.strategyFor("preplan").runStep(run, intercepted.runState.step(run))

      assertEquals(rejection.message, outcome.blockedReason)
      assertEquals(0, launcherCount())
      assertEquals(0, gateCount())
      assertNoGitEffects()
      assertEquals(
        rejection.message,
        context.recorder
          .loadPhaseRecords(WORKFLOW_ID)
          ?.get("preplan")
          ?.blockedReason,
      )
    }
  }

  @Test
  fun rejectedReviewBriefingBlocksBeforeReviewLaunch() {
    withCapturedContext { context, launcherCount, gateCount, assertNoGitEffects ->
      val branch = requireNotNull(context.recorder.loadResolvedBranch(WORKFLOW_ID))
      val rejection = RequiredPhaseWrite.Rejected(RequiredPhaseWriteKind.BRIEFING, WORKFLOW_ID, "review", 1)
      val records =
        object : PhaseRunRecords by rejectingRecords(context.recorder, rejection) {
          override fun loadResolvedBranch(workflowId: String) = branch.copy(reviewBaseSha = "0".repeat(40))
        }
      val intercepted = context.withRecords(records)
      val run = phaseRun(intercepted, "review")

      val outcome = intercepted.runState.strategyFor("review").runStep(run, intercepted.runState.step(run))

      assertEquals(rejection.message, outcome.blockedReason)
      val terminal = assertNotNull(context.recorder.loadPhaseRecords(WORKFLOW_ID)?.get("review"))
      assertEquals(WorkflowStepStatus.BLOCKED, terminal.status)
      assertEquals(rejection.message, terminal.blockedReason)
      assertEquals(0, launcherCount())
      assertEquals(0, gateCount())
      assertNoGitEffects()
    }
  }

  @Test
  fun secondaryTerminalAndDiagnosticFailuresDoNotReplaceTheAttributedRejection() {
    withCapturedContext { context, launcherCount, _, assertNoGitEffects ->
      val rejection = RequiredPhaseWrite.Rejected(RequiredPhaseWriteKind.START, WORKFLOW_ID, "preplan", 1)
      val secondary = IllegalStateException("terminal storage unavailable")
      val warnedCauses = mutableListOf<Throwable?>()
      val diagnostics =
        object : RuntimeDiagnostics {
          override fun warning(
            message: String,
            error: Throwable?,
          ) {
            warnedCauses += error
            error("diagnostics unavailable")
          }

          override fun error(
            message: String,
            error: Throwable?,
          ) = Unit
        }
      val records = rejectingRecords(context.recorder, rejection, secondary)
      val intercepted = context.withRecords(records, diagnostics)
      val run = phaseRun(intercepted, "preplan")

      val outcome = intercepted.runState.strategyFor("preplan").runStep(run, intercepted.runState.step(run))

      assertEquals(rejection.message, outcome.blockedReason)
      assertTrue(warnedCauses.any { it === secondary })
      assertEquals(0, launcherCount())
      assertNoGitEffects()
    }
  }

  @Test
  fun cancellationAndUnrelatedWriteExceptionsKeepTheirIdentity() {
    listOf(CancellationException("cancelled"), IllegalArgumentException("existing write error")).forEach { failure ->
      withCapturedContext { context, launcherCount, gateCount, assertNoGitEffects ->
        val records =
          object : PhaseRunRecords by context.recorder {
            override fun recordRequiredPhaseStart(request: FeatureTaskRuntimePhaseStateRequest): RequiredPhaseWrite =
              throw failure
          }
        val intercepted = context.withRecords(records)
        val run = phaseRun(intercepted, "preplan")

        val thrown =
          assertFailsWith<RuntimeException> {
            intercepted.runState.strategyFor("preplan").runStep(run, intercepted.runState.step(run))
          }

        assertSame(failure, thrown)
        assertEquals(0, launcherCount())
        assertEquals(0, gateCount())
        assertNoGitEffects()
      }
    }
  }

  @Test
  fun terminalPersistenceKeepsTheRejectedChildAttemptInsteadOfTheOuterIteration() {
    withCapturedContext { context, launcherCount, _, _ ->
      val run = phaseRun(context, "preplan")
      val rejection = RequiredPhaseWrite.Rejected(RequiredPhaseWriteKind.BRIEFING, WORKFLOW_ID, "preplan", 7)

      val scope =
        PhaseAttemptScope(
          PhaseAttemptRunHost(
            run,
            context.runState,
          ),
        )
      val outcome = PhaseAttemptOnce.blockRequiredWriteRejection(scope, run, rejection)

      val terminal = assertNotNull(context.recorder.loadPhaseRecords(WORKFLOW_ID)?.get("preplan"))
      assertEquals(7, terminal.attemptCount)
      assertEquals(rejection.message, terminal.blockedReason)
      assertEquals(rejection.message, outcome.blockedReason)
      assertEquals(0, launcherCount())
    }
  }

  @Test
  fun cancellationDuringTerminalPersistenceStillPropagatesAfterRejection() {
    withCapturedContext { context, launcherCount, _, assertNoGitEffects ->
      val rejection = RequiredPhaseWrite.Rejected(RequiredPhaseWriteKind.START, WORKFLOW_ID, "preplan", 1)
      val cancellation = CancellationException("cancelled while persisting rejection")
      val intercepted = context.withRecords(rejectingRecords(context.recorder, rejection, cancellation))
      val run = phaseRun(intercepted, "preplan")

      val thrown =
        assertFailsWith<CancellationException> {
          intercepted.runState.strategyFor("preplan").runStep(run, intercepted.runState.step(run))
        }

      assertSame(cancellation, thrown)
      assertEquals(0, launcherCount())
      assertNoGitEffects()
    }
  }

  @Test
  fun auditLaunchDoesNotRequireDurableBriefingStorage() {
    withCapturedContext { context, launcherCount, _, _ ->
      mapOf(
        "preplan" to PREPLAN_OUTPUT,
        "plan" to PLAN_OUTPUT,
        "implement" to IMPLEMENT_OUTPUT,
        "simplify" to SIMPLIFY_OUTPUT,
      ).forEach { (phase, payload) ->
        context.state.recordCompleted(FeatureTaskRuntimePhaseOutput(phase, 1, payload))
      }
      var starts = 0
      val records =
        object : PhaseRunRecords by context.recorder {
          override fun recordRequiredPhaseStart(request: FeatureTaskRuntimePhaseStateRequest): RequiredPhaseWrite {
            starts++
            return context.recorder.recordRequiredPhaseStart(request)
          }

          override fun recordPhaseBriefing(
            workflowId: String,
            briefing: FeatureTaskRuntimePhaseLaunchBriefing,
            sharedEvidenceMeasurement: FeatureTaskRuntimeSharedEvidenceMeasurement?,
            attempt: Int,
          ): RequiredPhaseWrite = error("Audit must not persist a durable briefing")
        }
      val intercepted = context.withRecords(records)
      val run = phaseRun(intercepted, "audit")

      val outcome = intercepted.runState.strategyFor("audit").runStep(run, intercepted.runState.step(run))

      assertNotNull(outcome.completedOutput, outcome.toString())
      assertEquals(1, launcherCount())
      assertTrue(starts > 0)
    }
  }

  @Test
  fun `audit binding cannot acquire review gate or finalization authority or launch another step`() {
    withCapturedContext { context, launcherCount, gateCount, assertNoGitEffects ->
      val run = phaseRun(context, "audit")
      context.runState.stepBinding.authorizeCoordinatorDispatch(run)
      val binding = context.runState.step(run)
      try {
        assertFalse(binding is PhasePlanningBriefingBinding)
        assertFalse(binding is PhaseReviewStepBinding)
        assertFalse(binding is PhaseQualityGateStepBinding)
        assertFalse(binding is PhaseCommitStepBinding)
        assertFalse(binding is PhasePullRequestStepBinding)
        val foreignRun = run.copy(phaseId = "review")
        val call = context.runState.strategyFor("audit").stepCall(run, binding)
        assertFailsWith<IllegalArgumentException> {
          (binding as PhaseAgentExecution).runAcceptedAgentStep(
            foreignRun,
            call,
          )
        }
        assertFailsWith<IllegalStateException> { binding.launchState.recordTokenUsage("review", 1, 1) }
        assertEquals(0, launcherCount())
        assertEquals(0, gateCount())
        assertNoGitEffects()
      } finally {
        binding.finishStepExecution()
        context.runState.stepBinding.releaseCoordinatorDispatch()
      }
    }
  }

  private fun rejectingRecords(
    delegate: PhaseRunRecords,
    rejection: RequiredPhaseWrite.Rejected,
    terminalFailure: Throwable? = null,
  ): PhaseRunRecords =
    object : PhaseRunRecords by delegate {
      override fun recordRequiredPhaseStart(request: FeatureTaskRuntimePhaseStateRequest): RequiredPhaseWrite {
        if (rejection.writeKind == RequiredPhaseWriteKind.START) return rejection
        return delegate.recordRequiredPhaseStart(request)
      }

      override fun recordPhaseBriefing(
        workflowId: String,
        briefing: FeatureTaskRuntimePhaseLaunchBriefing,
        sharedEvidenceMeasurement: FeatureTaskRuntimeSharedEvidenceMeasurement?,
        attempt: Int,
      ): RequiredPhaseWrite {
        assertEquals(rejection.phaseId, briefing.phaseId)
        assertEquals(rejection.attempt, attempt)
        return rejection
      }

      override fun recordPhaseState(request: FeatureTaskRuntimePhaseStateRequest): Boolean {
        terminalFailure?.let { throw it }
        return delegate.recordPhaseState(request)
      }
    }

  private fun phaseRun(
    context: FeatureTaskRuntimeRunLoopContext,
    phase: String,
  ): PhaseRun =
    FeatureTaskRuntimeRunLoopPlanningBranch.buildPhaseRun(
      context,
      phase,
      context.request,
      context.specSource,
      null,
    )

  private fun FeatureTaskRuntimeRunLoopContext.withRecords(
    interceptedRecords: PhaseRunRecords,
    diagnostics: RuntimeDiagnostics = this.diagnostics,
    selectedStrategy: PhaseStrategy? = null,
  ): FeatureTaskRuntimeRunLoopContext {
    val delegate = runState
    val wrapped =
      object : PhaseRunState by delegate {
        override val records = interceptedRecords
        override val diagnostics = diagnostics

        override fun strategyFor(stepId: String): PhaseStrategy =
          selectedStrategy?.takeIf { stepId in it.steps } ?: delegate.strategyFor(stepId)

        override fun selectedOwnerOf(stepId: String): PhaseStrategy? =
          selectedStrategy?.takeIf { stepId in it.steps } ?: delegate.selectedOwnerOf(stepId)

        override fun step(run: PhaseRun): PhaseAcceptedStepExecution {
          stepBinding.authorizeCoordinatorDispatch(run)
          return this@withRecords.withRunState(this).acceptedStep(run)
        }
      }
    return withRunState(wrapped)
  }

  private fun withCapturedContext(
    inspect: (FeatureTaskRuntimeRunLoopContext, () -> Int, () -> Int, () -> Unit) -> Unit,
  ) {
    val repo = Files.createTempDirectory("required-phase-write")
    try {
      val branch = committedRepoBranchSetup()
      val head = branch.gitOperations.headCommitShaValue
      val launcher = satisfiedAuditLauncher()
      var gates = 0
      val harness =
        telemetryRunnerHarness(
          RuntimeHarnessConfig(
            branchSetup = branch,
            repoRoot = repo,
            launcher = launcher,
            validationGateRunner =
              object : ValidationGateRunner {
                override fun run(request: ValidationGateRunRequest): ValidationGateRunResult {
                  gates++
                  error("Rejected start must not launch a gate")
                }
              },
          ),
        )
      val entry =
        object : TestFeatureTaskRuntimeRunLoopEntry() {
          override fun run(
            context: FeatureTaskRuntimeRunLoopContext,
            beforeDrive: (FeatureTaskRuntimeRunLoop) -> Unit,
          ): FeatureTaskRuntimeRunReport {
            assertTrue(
              harness.recorder.recordResolvedBranch(
                WORKFLOW_ID,
                FeatureTaskRuntimeResolvedBranch(branch.gitOperations.currentBranchValue, baseBranch = "main"),
              ),
            )
            inspect(context, { launcher.requests.size }, { gates }) {
              branch.gitOperations.assertNoCommitOrCheckpointRef(head)
              assertEquals(emptyList(), branch.gitOperations.pushedBranches)
            }
            throw InspectionFinished()
          }
        }
      assertFailsWith<InspectionFinished> { harness.withEntry(entry).run(harness.request) }
    } finally {
      repo.toFile().deleteRecursively()
    }
  }
}

private class InspectionFinished : RuntimeException()
