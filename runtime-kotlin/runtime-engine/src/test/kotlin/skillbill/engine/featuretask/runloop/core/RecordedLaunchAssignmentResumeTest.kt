package skillbill.engine.featuretask.runloop.core

import skillbill.config.model.ExecutionMatrix
import skillbill.config.model.ExecutionTier
import skillbill.config.model.PhaseModelDirective
import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeModelAssignment
import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeRunReport
import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeRunRequest
import skillbill.engine.featuretask.runner.RuntimeHarnessConfig
import skillbill.engine.featuretask.runner.TestFeatureTaskRuntimeRunLoopEntry
import skillbill.engine.featuretask.runner.committedRepoBranchSetup
import skillbill.engine.featuretask.runner.telemetryRunnerHarness
import skillbill.engine.featuretask.runner.withRunState
import skillbill.engine.featuretask.slot.state.PhaseRunState
import skillbill.install.model.SupportedAgent
import skillbill.workflow.taskruntime.model.skeleton.EffectiveLaunchModel
import skillbill.workflow.taskruntime.model.skeleton.LaunchModelProvenance
import skillbill.workflow.taskruntime.model.skeleton.LaunchProviderNamespace
import skillbill.workflow.taskruntime.model.skeleton.PhaseModelProfile
import skillbill.workflow.taskruntime.model.skeleton.PhaseModelProfileClassifier
import skillbill.workflow.taskruntime.model.skeleton.StepLaunchAssignment
import skillbill.workflow.taskruntime.phase.task.FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_VALIDATE
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
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

private fun phaseRun(context: FeatureTaskRuntimeRunLoopContext): PhaseRun =
  FeatureTaskRuntimeRunLoopPlanningBranch.buildPhaseRun(
    context,
    PHASE_VALIDATE,
    context.request,
    context.specSource,
    null,
  )

private fun FeatureTaskRuntimeRunLoopContext.resumedAs(
  agentId: String,
  recorded: StepLaunchAssignment,
  modelAssignment: FeatureTaskRuntimeModelAssignment = FeatureTaskRuntimeModelAssignment(),
): FeatureTaskRuntimeRunLoopContext {
  val resumed =
    FeatureTaskRuntimeRunLoopContext(
      (request as FeatureTaskRuntimeRunRequest).copy(
        invokedAgentId = agentId,
        modelAssignment = modelAssignment,
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
      projectStoreRoot,
    )
  val delegate = resumed.runState
  val wrapped =
    object : PhaseRunState by delegate {
      override fun stepLaunchAssignment(stepId: String): StepLaunchAssignment? = recorded.takeIf { it.stepId == stepId }
    }
  return resumed.withRunState(wrapped)
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
