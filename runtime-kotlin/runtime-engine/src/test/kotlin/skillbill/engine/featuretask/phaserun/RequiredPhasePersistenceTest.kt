package skillbill.engine.featuretask.phaserun

import skillbill.engine.IMPLEMENT_OUTPUT
import skillbill.engine.PLAN_OUTPUT
import skillbill.engine.PREPLAN_OUTPUT
import skillbill.engine.RuntimeHarnessConfig
import skillbill.engine.SIMPLIFY_OUTPUT
import skillbill.engine.WORKFLOW_ID
import skillbill.engine.committedRepoBranchSetup
import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeRunReport
import skillbill.engine.featuretask.model.phase.FeatureTaskRuntimePhaseLaunchBriefing
import skillbill.engine.featuretask.model.phase.FeatureTaskRuntimePhaseStateRequest
import skillbill.engine.featuretask.runloop.core.FeatureTaskRuntimeRunLoop
import skillbill.engine.featuretask.runloop.core.FeatureTaskRuntimeRunLoopContext
import skillbill.engine.featuretask.runloop.core.FeatureTaskRuntimeRunLoopEntry
import skillbill.engine.featuretask.runloop.core.FeatureTaskRuntimeRunLoopPlanningBranch
import skillbill.engine.featuretask.runloop.core.PhaseRun
import skillbill.engine.featuretask.runloop.state.FeatureTaskRuntimeRunLoopStepState
import skillbill.engine.featuretask.runner.FeatureTaskRuntimeRunner
import skillbill.engine.featuretask.slot.attempt.PhaseAttemptOnce
import skillbill.engine.featuretask.slot.attempt.PhaseAttemptScope
import skillbill.engine.featuretask.slot.qualitygate.packbuild.PackBuildStrategy
import skillbill.engine.featuretask.slot.qualitygate.packvalidation.PackValidationStrategy
import skillbill.engine.featuretask.slot.state.PhaseRunRecords
import skillbill.engine.featuretask.slot.state.PhaseRunState
import skillbill.engine.featuretask.slot.state.PhaseStepState
import skillbill.engine.featuretask.slot.state.RequiredPhaseWriteKind
import skillbill.engine.featuretask.slot.state.RequiredPhaseWriteRejected
import skillbill.engine.satisfiedAuditLauncher
import skillbill.engine.telemetryRunnerHarness
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.ports.validation.ValidationGateRunner
import skillbill.ports.validation.model.ValidationGateRunRequest
import skillbill.ports.validation.model.ValidationGateRunResult
import skillbill.workflow.model.WorkflowStepStatus
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimeResolvedBranch
import skillbill.workflow.taskruntime.model.handoff.task.FeatureTaskRuntimePhaseOutput
import skillbill.workflow.taskruntime.model.handoff.task.FeatureTaskRuntimeSharedEvidenceMeasurement
import java.nio.file.Files
import java.util.concurrent.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class RequiredPhasePersistenceTest {
  @Test
  fun rejectedStartPreventsOrdinaryAuditReviewGateAndCommitEffects() {
    listOf("preplan", "implement", "audit", "review", "validate", "build", "commit_push").forEach { phase ->
      withCapturedContext { context, launcherCount, gateCount, assertNoGitEffects ->
        val rejection = RequiredPhaseWriteRejected(RequiredPhaseWriteKind.START, WORKFLOW_ID, phase, 1)
        val records = rejectingRecords(context.recorder, rejection)
        val intercepted = context.withRecords(records)
        val run = phaseRun(intercepted, phase)
        val strategy = when (phase) {
          "build" -> PackBuildStrategy(context.strategyFor("validate").runner)
          "validate" -> PackValidationStrategy(context.strategyFor("validate").runner)
          else -> intercepted.strategyFor(phase)
        }

        val outcome = strategy.runStep(run, intercepted.runState.step(run))

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
      val rejection = RequiredPhaseWriteRejected(RequiredPhaseWriteKind.BRIEFING, WORKFLOW_ID, "preplan", 1)
      val intercepted = context.withRecords(rejectingRecords(context.recorder, rejection))
      val run = phaseRun(intercepted, "preplan")

      val outcome = intercepted.strategyFor("preplan").runStep(run, intercepted.runState.step(run))

      assertEquals(rejection.message, outcome.blockedReason)
      assertEquals(0, launcherCount())
      assertEquals(0, gateCount())
      assertNoGitEffects()
      assertEquals(rejection.message, context.recorder.loadPhaseRecords(WORKFLOW_ID)?.get("preplan")?.blockedReason)
    }
  }

  @Test
  fun secondaryTerminalAndDiagnosticFailuresDoNotReplaceTheAttributedRejection() {
    withCapturedContext { context, launcherCount, _, assertNoGitEffects ->
      val rejection = RequiredPhaseWriteRejected(RequiredPhaseWriteKind.START, WORKFLOW_ID, "preplan", 1)
      val secondary = IllegalStateException("terminal storage unavailable")
      val diagnostics = object : RuntimeDiagnostics {
        override fun warning(message: String, error: Throwable?) = throw IllegalStateException("diagnostics unavailable")
        override fun error(message: String, error: Throwable?) = Unit
      }
      val records = rejectingRecords(context.recorder, rejection, secondary)
      val intercepted = context.withRecords(records, diagnostics)
      val run = phaseRun(intercepted, "preplan")

      val outcome = intercepted.strategyFor("preplan").runStep(run, intercepted.runState.step(run))

      assertEquals(rejection.message, outcome.blockedReason)
      assertTrue(rejection.suppressed.any { it === secondary })
      assertEquals(0, launcherCount())
      assertNoGitEffects()
    }
  }

  @Test
  fun cancellationAndUnrelatedWriteExceptionsKeepTheirIdentity() {
    listOf(CancellationException("cancelled"), IllegalArgumentException("existing write error")).forEach { failure ->
      withCapturedContext { context, launcherCount, gateCount, assertNoGitEffects ->
        val records = object : PhaseRunRecords by context.recorder {
          override fun recordRequiredPhaseStart(request: FeatureTaskRuntimePhaseStateRequest) = throw failure
        }
        val intercepted = context.withRecords(records)
        val run = phaseRun(intercepted, "preplan")

        val thrown = assertFailsWith<RuntimeException> {
          intercepted.strategyFor("preplan").runStep(run, intercepted.runState.step(run))
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
      val rejection = RequiredPhaseWriteRejected(RequiredPhaseWriteKind.BRIEFING, WORKFLOW_ID, "preplan", 7)

      val outcome = PhaseAttemptOnce.blockRequiredWriteRejection(context, run, rejection)

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
      val rejection = RequiredPhaseWriteRejected(RequiredPhaseWriteKind.START, WORKFLOW_ID, "preplan", 1)
      val cancellation = CancellationException("cancelled while persisting rejection")
      val intercepted = context.withRecords(rejectingRecords(context.recorder, rejection, cancellation))
      val run = phaseRun(intercepted, "preplan")

      val thrown = assertFailsWith<CancellationException> {
        intercepted.strategyFor("preplan").runStep(run, intercepted.runState.step(run))
      }

      assertSame(cancellation, thrown)
      assertTrue(rejection.suppressed.any { it === cancellation })
      assertEquals(0, launcherCount())
      assertNoGitEffects()
    }
  }

  @Test
  fun auditLaunchDoesNotRequireDurableBriefingStorage() {
    withCapturedContext { context, launcherCount, _, _ ->
      mapOf("preplan" to PREPLAN_OUTPUT, "plan" to PLAN_OUTPUT, "implement" to IMPLEMENT_OUTPUT,
        "simplify" to SIMPLIFY_OUTPUT).forEach { (phase, payload) ->
        context.state.recordCompleted(FeatureTaskRuntimePhaseOutput(phase, 1, payload))
      }
      var starts = 0
      val records = object : PhaseRunRecords by context.recorder {
        override fun recordRequiredPhaseStart(request: FeatureTaskRuntimePhaseStateRequest) {
          starts++
          context.recorder.recordRequiredPhaseStart(request)
        }
        override fun recordPhaseBriefing(
          workflowId: String,
          briefing: FeatureTaskRuntimePhaseLaunchBriefing,
          sharedEvidenceMeasurement: FeatureTaskRuntimeSharedEvidenceMeasurement?,
          attempt: Int,
        ): Unit = error("Audit must not persist a durable briefing")
      }
      val intercepted = context.withRecords(records)
      val run = phaseRun(intercepted, "audit")

      val outcome = intercepted.strategyFor("audit").runStep(run, intercepted.runState.step(run))

      assertNotNull(outcome.completedOutput, outcome.toString())
      assertEquals(1, launcherCount())
      assertTrue(starts > 0)
    }
  }

  private fun rejectingRecords(
    delegate: PhaseRunRecords,
    rejection: RequiredPhaseWriteRejected,
    terminalFailure: Throwable? = null,
  ): PhaseRunRecords = object : PhaseRunRecords by delegate {
    override fun recordRequiredPhaseStart(request: FeatureTaskRuntimePhaseStateRequest) {
      if (rejection.writeKind == RequiredPhaseWriteKind.START) throw rejection
      delegate.recordRequiredPhaseStart(request)
    }
    override fun recordPhaseBriefing(
      workflowId: String,
      briefing: FeatureTaskRuntimePhaseLaunchBriefing,
      sharedEvidenceMeasurement: FeatureTaskRuntimeSharedEvidenceMeasurement?,
      attempt: Int,
    ) {
      assertEquals(rejection.phaseId, briefing.phaseId)
      assertEquals(rejection.attempt, attempt)
      throw rejection
    }
    override fun recordPhaseState(request: FeatureTaskRuntimePhaseStateRequest): Boolean {
      terminalFailure?.let { throw it }
      return delegate.recordPhaseState(request)
    }
  }

  private fun phaseRun(context: FeatureTaskRuntimeRunLoopContext, phase: String): PhaseRun {
    val source = if (phase == "build") "validate" else phase
    return FeatureTaskRuntimeRunLoopPlanningBranch.buildPhaseRun(
      context, source, context.request, context.specSource, null,
    ).copy(phaseId = phase)
  }

  private fun FeatureTaskRuntimeRunLoopContext.withRecords(
    interceptedRecords: PhaseRunRecords,
    diagnostics: RuntimeDiagnostics = this.diagnostics,
  ): FeatureTaskRuntimeRunLoopContext {
    val delegate = runState
    val wrapped = object : PhaseRunState by delegate {
      override val records = interceptedRecords
      override val collaborators = delegate.collaborators.copy(diagnostics = diagnostics)
      override fun step(run: PhaseRun): PhaseStepState =
        FeatureTaskRuntimeRunLoopStepState(PhaseAttemptScope(run.request, this), run)
    }
    return copy(runState = wrapped)
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
      val harness = telemetryRunnerHarness(RuntimeHarnessConfig(
        branchSetup = branch,
        repoRoot = repo,
        launcher = launcher,
        validationGateRunner = object : ValidationGateRunner {
          override fun run(request: ValidationGateRunRequest): ValidationGateRunResult {
            gates++
            error("Rejected start must not launch a gate")
          }
        },
      ))
      val entry = object : FeatureTaskRuntimeRunLoopEntry() {
        override fun run(
          context: FeatureTaskRuntimeRunLoopContext,
          beforeDrive: (FeatureTaskRuntimeRunLoop) -> Unit,
        ): FeatureTaskRuntimeRunReport {
          assertTrue(harness.recorder.recordResolvedBranch(
            WORKFLOW_ID,
            FeatureTaskRuntimeResolvedBranch(branch.gitOperations.currentBranchValue, baseBranch = "main"),
          ))
          inspect(context, { launcher.requests.size }, { gates }) {
            branch.gitOperations.assertNoCommitOrCheckpointRef(head)
            assertEquals(emptyList(), branch.gitOperations.pushedBranches)
          }
          throw InspectionFinished()
        }
      }
      assertFailsWith<InspectionFinished> { harness.runner.withEntry(entry).run(harness.request) }
    } finally {
      repo.toFile().deleteRecursively()
    }
  }
}

private class InspectionFinished : RuntimeException()

internal fun FeatureTaskRuntimeRunner.withEntry(entry: FeatureTaskRuntimeRunLoopEntry) = FeatureTaskRuntimeRunner(
  strategies, recorder, goalContinuationRecorder, outputValidator, phaseGates, startup, phaseSettlementService,
  diagnostics, clock, probeWriters, entry,
)
