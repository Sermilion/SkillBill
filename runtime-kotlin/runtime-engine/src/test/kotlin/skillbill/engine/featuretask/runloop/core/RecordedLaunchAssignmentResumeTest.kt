package skillbill.engine.featuretask.runloop.core

import skillbill.config.model.ExecutionMatrix
import skillbill.config.model.ExecutionTier
import skillbill.config.model.PhaseModelDirective
import skillbill.engine.featuretask.model.audit.AcceptanceCriterionRepairHydration
import skillbill.engine.featuretask.model.audit.AcceptanceCriterionRepairRunState
import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeModelAssignment
import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeRunReport
import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeRunRequest
import skillbill.engine.featuretask.runner.RuntimeHarnessConfig
import skillbill.engine.featuretask.runner.TestFeatureTaskRuntimeRunLoopEntry
import skillbill.engine.featuretask.runner.committedRepoBranchSetup
import skillbill.engine.featuretask.runner.telemetryRunnerHarness
import skillbill.engine.featuretask.runner.withRunState
import skillbill.engine.featuretask.slot.audit.AcceptanceAuditStrategy
import skillbill.engine.featuretask.slot.state.PhaseRunState
import skillbill.install.model.SupportedAgent
import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionProseAudit
import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionRepairAdmission
import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionRepairCriterion
import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionRepairOutcome
import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionRepairOutcomeKind
import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionRepairState
import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionSourceDisposition
import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionValidationObligation
import skillbill.workflow.taskruntime.model.audit.recordProseAudit
import skillbill.workflow.taskruntime.model.skeleton.EffectiveLaunchModel
import skillbill.workflow.taskruntime.model.skeleton.LaunchModelProvenance
import skillbill.workflow.taskruntime.model.skeleton.LaunchProviderNamespace
import skillbill.workflow.taskruntime.model.skeleton.PhaseModelProfile
import skillbill.workflow.taskruntime.model.skeleton.PhaseModelProfileClassifier
import skillbill.workflow.taskruntime.model.skeleton.StepLaunchAssignment
import skillbill.workflow.taskruntime.phase.task.FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_AUDIT_IMPLEMENT_FIX
import skillbill.workflow.taskruntime.phase.task.FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_VALIDATE
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class RecordedLaunchAssignmentResumeTest {
  @Test
  fun `resuming a claude recorded validate step under cursor does not launch the claude model`() {
    withCapturedContext { context ->
      val recorded = claudeOpusAssignment()
      val claudeResume =
        context.resumedAs(
          SupportedAgent.CLAUDE.id,
          recorded,
          matrixDirective(SupportedAgent.CLAUDE, PhaseModelDirective(model = "claude-sonnet-5-5", effort = "low")),
        )
      val claudeRun = phaseRun(claudeResume)
      val claudeLaunched = FeatureTaskRuntimeRunLoopLaunch.launchedModelDirective(claudeRun)
      assertEquals(SupportedAgent.CLAUDE.id, claudeRun.resolvedAgent.resolvedAgentId)
      assertEquals(recorded.agentId, claudeRun.launchAssignment?.agentId)
      assertEquals(PhaseModelProfileClassifier.ANTHROPIC_OPUS_55, claudeLaunched.modelOverride)
      assertEquals("high", claudeLaunched.effortOverride)

      val cursorResume =
        context.resumedAs(
          SupportedAgent.CURSOR.id,
          recorded,
          matrixDirective(SupportedAgent.CURSOR, PhaseModelDirective(model = "claude-opus-5-5-high")),
        )
      val cursorRun = phaseRun(cursorResume)
      val cursorLaunched = FeatureTaskRuntimeRunLoopLaunch.launchedModelDirective(cursorRun)
      assertEquals(SupportedAgent.CURSOR.id, cursorRun.resolvedAgent.resolvedAgentId)
      assertNull(cursorRun.launchAssignment)
      assertEquals("claude-opus-5-5-high", cursorLaunched.modelOverride)
      assertNull(cursorLaunched.effortOverride)

      val cursorDefault = phaseRun(context.resumedAs(SupportedAgent.CURSOR.id, recorded))
      val defaultLaunched = FeatureTaskRuntimeRunLoopLaunch.launchedModelDirective(cursorDefault)
      assertNull(cursorDefault.launchAssignment)
      assertNull(defaultLaunched.modelOverride)
      assertNull(defaultLaunched.effortOverride)
    }
  }

  @Test
  fun `an escalated repair uses the reasoning tier while preserving its recorded assignment`() {
    withCapturedContext { context ->
      val recorded = claudeOpusAssignment().copy(stepId = PHASE_AUDIT_IMPLEMENT_FIX)
      val reasoning = PhaseModelDirective("claude-opus-5-5", "max")
      val state = repairLaunchState()
      val resumed =
        context.resumedAs(
          SupportedAgent.CLAUDE.id,
          recorded,
          matrixDirective(SupportedAgent.CLAUDE, reasoning),
          state,
        )
      val launched = FeatureTaskRuntimeRunLoopLaunch.launchedModelDirective(phaseRun(resumed, recorded.stepId))
      assertEquals(reasoning.model, launched.modelOverride)
      assertEquals(reasoning.effort, launched.effortOverride)
      assertEquals(recorded, resumed.runState.stepLaunchAssignment(recorded.stepId))

      val unavailable = context.resumedAs(SupportedAgent.CLAUDE.id, recorded, repairState = state)
      val fallback = phaseRun(unavailable, recorded.stepId)
      assertEquals(recorded, fallback.launchAssignment)
      assertEquals("high", FeatureTaskRuntimeRunLoopLaunch.launchedModelDirective(fallback).effortOverride)

      val ordinary =
        context.resumedAs(
          SupportedAgent.CLAUDE.id,
          recorded,
          matrixDirective(SupportedAgent.CLAUDE, reasoning),
          repairLaunchState(escalated = false),
        )
      assertEquals(recorded, phaseRun(ordinary, recorded.stepId).launchAssignment)
    }
  }
}

private fun matrixDirective(
  agent: SupportedAgent,
  directive: PhaseModelDirective,
): FeatureTaskRuntimeModelAssignment =
  FeatureTaskRuntimeModelAssignment(
    matrix =
      ExecutionMatrix(
        agents = mapOf(agent to mapOf(ExecutionTier.REASONING to directive)),
      ),
  )

private fun claudeOpusAssignment(): StepLaunchAssignment =
  StepLaunchAssignment(
    PHASE_VALIDATE,
    SupportedAgent.CLAUDE.id,
    EffectiveLaunchModel(
      requestedModel = PhaseModelProfileClassifier.ANTHROPIC_OPUS_55,
      requestedEffort = "high",
      effectiveModel = PhaseModelProfileClassifier.ANTHROPIC_OPUS_55,
      namespace = LaunchProviderNamespace.ANTHROPIC_API,
      provenance = LaunchModelProvenance.REQUESTED_EXACT,
      unknownReason = null,
      profile = PhaseModelProfile.OPUS_5_5,
    ),
  )

private fun phaseRun(
  context: FeatureTaskRuntimeRunLoopContext,
  phaseId: String = PHASE_VALIDATE,
): PhaseRun =
  context.phaseRunFactory.build(
    context,
    phaseId,
    context.request,
    context.specSource,
    null,
  )

private fun FeatureTaskRuntimeRunLoopContext.resumedAs(
  agentId: String,
  recorded: StepLaunchAssignment,
  modelAssignment: FeatureTaskRuntimeModelAssignment = FeatureTaskRuntimeModelAssignment(),
  repairState: AcceptanceCriterionRepairState? = null,
): FeatureTaskRuntimeRunLoopContext {
  val resumed =
    FeatureTaskRuntimeRunLoopContext(
      (request as FeatureTaskRuntimeRunRequest).copy(
        invokedAgentId = agentId,
        modelAssignment = modelAssignment,
        acceptanceCriterionRepair =
          repairState?.let {
            AcceptanceCriterionRepairRunState(AcceptanceCriterionRepairHydration.Present(it)).apply {
              bindSteps(AcceptanceAuditStrategy().acceptanceRepairSteps)
            }
          },
      ),
      runState,
      gitOperations,
      decompositionPlanner,
      findingVerificationBoundaryMemory,
      specIntentProjectionResolver,
      lifecycleTelemetry,
      qualityGateCycles,
      sharedEvidenceResolver,
      diffResolver,
      phaseRunFactory,
    )
  val delegate = resumed.runState
  val wrapped =
    object : PhaseRunState by delegate {
      override fun stepLaunchAssignment(stepId: String): StepLaunchAssignment? = recorded.takeIf { it.stepId == stepId }
    }
  return resumed.withRunState(wrapped)
}

private fun repairLaunchState(escalated: Boolean = true): AcceptanceCriterionRepairState {
  var state = discoveredRepairLaunchState()
  repeat(if (escalated) 2 else 0) { index ->
    val reservation = "repair-$index"
    val reserved = assertNotNull(state.reserve(reservation))
    val verifying =
      assertNotNull(
        reserved.completeRepairAwaitingTargetedVerification(reservation, "Repair report", "d".repeat(64), "checkpoint"),
      )
    state =
      assertNotNull(
        verifying.record(
          AcceptanceCriterionRepairOutcome(
            "outcome-$index",
            reservation,
            "AC-001",
            "e".repeat(64),
            AcceptanceCriterionRepairOutcomeKind.TARGETED_FAILURE,
            "AC-001.G1: open @ src/Foo.kt:1",
          ),
        ),
      )
  }
  return assertNotNull(AcceptanceCriterionRepairState.restore(state))
}

private fun discoveredRepairLaunchState(): AcceptanceCriterionRepairState {
  val state =
    assertNotNull(
      AcceptanceCriterionRepairState.admit(
        AcceptanceCriterionRepairAdmission(
          "catalog",
          "execution",
          "a".repeat(64),
          listOf("Repair source."),
          listOf(
            AcceptanceCriterionRepairCriterion(
              "AC-001",
              null,
              "Repair source.",
              1,
              AcceptanceCriterionSourceDisposition.SOURCE_UNRESOLVED,
              AcceptanceCriterionValidationObligation.DEFERRED,
              true,
              "a".repeat(64),
            ),
          ),
          emptyList(),
        ),
      ),
    )
  return assertNotNull(
    state.recordProseAudit(
      AcceptanceCriterionProseAudit(
        "discovery",
        1,
        null,
        false,
        "b".repeat(64),
        "Open criteria: AC-001\nAC-001.G1: Missing caller @ src/Foo.kt:1",
        "c".repeat(64),
        "checkpoint",
      ),
    ),
  )
}

private fun withCapturedContext(inspect: (FeatureTaskRuntimeRunLoopContext) -> Unit) {
  val repo = Files.createTempDirectory("recorded-launch-assignment")
  try {
    val branch = committedRepoBranchSetup()
    val harness =
      telemetryRunnerHarness(
        RuntimeHarnessConfig(
          branchSetup = branch,
          repoRoot = repo,
        ),
      )
    val entry =
      object : TestFeatureTaskRuntimeRunLoopEntry() {
        override fun run(
          context: FeatureTaskRuntimeRunLoopContext,
          beforeDrive: (FeatureTaskRuntimeRunLoop) -> Unit,
        ): FeatureTaskRuntimeRunReport {
          inspect(context)
          throw InspectionFinished()
        }
      }
    assertFailsWith<InspectionFinished> { harness.withEntry(entry).run(harness.request) }
  } finally {
    repo.toFile().deleteRecursively()
  }
}

private class InspectionFinished : RuntimeException()
