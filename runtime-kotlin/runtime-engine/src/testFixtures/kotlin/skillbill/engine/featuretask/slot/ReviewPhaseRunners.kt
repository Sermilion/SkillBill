package skillbill.engine.featuretask.slot

import skillbill.ports.agentrun.model.AgentRunTermination
import skillbill.workflow.taskruntime.phase.task.FeatureTaskRuntimePhaseWorkflowDefinition
import java.security.MessageDigest

object ApprovingReviewPhaseRunner : PhaseRunner {
  override fun run(
    input: PhaseStepInput,
    state: PhaseLaunchState,
  ): PhaseStepOutput = reviewStepOutput("verdict: approved")
}

fun scriptedReviewPhaseRunner(stdout: () -> String): PhaseRunner =
  object : PhaseRunner {
    override fun run(
      input: PhaseStepInput,
      state: PhaseLaunchState,
    ): PhaseStepOutput = reviewStepOutput(stdout())
  }

fun reviewStepOutput(stdout: String): PhaseStepOutput {
  val bytes = stdout.toByteArray()
  return PhaseStepOutput(
    status = "",
    value = stdout,
    summary = null,
    verdict = null,
    failureDisposition = null,
    stdout =
      PhaseStepStdout(
        text = stdout,
        bytes = bytes,
        truncated = false,
        byteSize = bytes.size.toLong(),
        sha256 = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) },
      ),
    stderr = "",
    processStarted = true,
    termination = AgentRunTermination.Exited(0),
    fileManifest = null,
    settledEnvelope = PhaseSettledEnvelopeRead.None,
    launchFailure = null,
  )
}

fun reviewRoutingPhaseRunner(
  review: PhaseRunner,
  others: PhaseRunner,
): PhaseRunner =
  object : PhaseRunner {
    override fun run(
      input: PhaseStepInput,
      state: PhaseLaunchState,
    ): PhaseStepOutput =
      if (input.stepName == FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_REVIEW) {
        review.run(input, state)
      } else {
        others.run(input, state)
      }
  }
