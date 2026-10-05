package skillbill.ports.agentrun

import skillbill.install.model.SupportedAgent
import skillbill.ports.agentrun.model.AgentRunLaunchFacts
import skillbill.ports.agentrun.model.AgentRunLaunchModelRequest
import skillbill.ports.agentrun.model.AgentRunLivenessSnapshot
import skillbill.ports.agentrun.model.AgentRunTermination
import skillbill.workflow.taskruntime.model.skeleton.EffectiveLaunchModel
import skillbill.workflow.taskruntime.model.skeleton.passThroughLaunchModel
import java.security.MessageDigest

fun agentRunLaunchFacts(
  agent: SupportedAgent,
  termination: AgentRunTermination = AgentRunTermination.Exited(0),
  stdout: String = "",
  stderr: String = "",
  stdoutByteSize: Long = stdout.encodeToByteArray().size.toLong(),
  stdoutSha256: String = sha256Hex(stdout.encodeToByteArray()),
  liveness: AgentRunLivenessSnapshot? = null,
  processStarted: Boolean = termination != AgentRunTermination.SpawnFailed,
  mcpStartupObserved: Boolean = false,
  childSessionPath: String? = null,
  childSessionId: String? = null,
  assistantEventCount: Int? = null,
  rawOutputPreview: String? = null,
  stdoutTruncated: Boolean = false,
): AgentRunLaunchFacts =
  AgentRunLaunchFacts(
    agent = agent,
    termination = termination,
    stdout = stdout,
    stderr = stderr,
    stdoutByteSize = stdoutByteSize,
    stdoutSha256 = stdoutSha256,
    liveness = liveness,
    processStarted = processStarted,
    mcpStartupObserved = mcpStartupObserved,
    childSessionPath = childSessionPath,
    childSessionId = childSessionId,
    assistantEventCount = assistantEventCount,
    rawOutputPreview = rawOutputPreview,
    stdoutTruncated = stdoutTruncated,
  )

private fun sha256Hex(bytes: ByteArray): String =
  MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { byte -> "%02x".format(byte) }

fun passThroughResolvedLaunchModel(request: AgentRunLaunchModelRequest): EffectiveLaunchModel =
  passThroughLaunchModel(request.requestedModel, request.requestedEffort)
