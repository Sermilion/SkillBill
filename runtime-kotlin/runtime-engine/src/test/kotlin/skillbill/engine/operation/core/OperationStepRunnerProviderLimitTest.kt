package skillbill.engine.operation.core

import skillbill.engine.featuretask.runloop.observability.AgentBuildObserverFactory
import skillbill.engine.featuretask.slot.PhaseLaunchFailure
import skillbill.engine.featuretask.slot.PhaseLaunchFailureKind
import skillbill.engine.featuretask.slot.PhaseRunner
import skillbill.engine.featuretask.slot.PhaseStepInput
import skillbill.engine.featuretask.slot.PhaseStepOutput
import skillbill.engine.featuretask.slot.PhaseStepStdout
import skillbill.engine.featuretask.slot.state.PhaseLaunchState
import skillbill.engine.featuretask.slot.state.PhaseSettledEnvelopeRead
import skillbill.ports.workflow.gitops.NoopWorkflowGitOperations
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimeProviderLimitSignal
import skillbill.workflow.taskruntime.validation.FeatureTaskRuntimeProviderLimitPause
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertTrue

class OperationStepRunnerProviderLimitTest {
  @Test
  fun `provider limit launch is refused blocked not failed`() {
    val pauseReason =
      FeatureTaskRuntimeProviderLimitPause.reason(
        phaseLabel = "Operation step 'qa'",
        agentId = "codex",
        resumePhaseId = "qa",
        signal = FeatureTaskRuntimeProviderLimitSignal(evidence = "usage_limit_exceeded", resetHint = null),
      )
    val steps =
      OperationStepRunner(
        object : PhaseRunner {
          override fun run(
            input: PhaseStepInput,
            state: PhaseLaunchState,
          ): PhaseStepOutput {
            val bytes = byteArrayOf()
            return PhaseStepOutput(
              stdout = PhaseStepStdout("", bytes, false, 0, ""),
              stderr = "",
              processStarted = false,
              termination = null,
              fileManifest = null,
              settledEnvelope = PhaseSettledEnvelopeRead.None,
              launchFailure = PhaseLaunchFailure(PhaseLaunchFailureKind.PROVIDER_LIMIT, pauseReason),
            )
          }
        },
        NoopWorkflowGitOperations,
        AgentBuildObserverFactory.NONE,
      )
    val repo = Files.createTempDirectory("op-step-limit")
    try {
      val context =
        OperationContext(
          invocationId = "opr-1",
          repoRoot = repo,
          projectStoreRoot = repo.resolve("store"),
          invokedAgentId = "codex",
          arguments = OperationArguments(),
          instructions = null,
          steps = steps,
        )
      val result = steps.runReportOnly(context, "qa", "directive", emptyMap())
      val refused = assertIs<OperationStepResult.Refused>(result)
      val blocked = assertIs<OperationOutcome.Blocked>(refused.refusal)
      assertTrue(FeatureTaskRuntimeProviderLimitPause.isProviderLimitPauseReason(blocked.reason))
    } finally {
      repo.toFile().deleteRecursively()
    }
  }
}
