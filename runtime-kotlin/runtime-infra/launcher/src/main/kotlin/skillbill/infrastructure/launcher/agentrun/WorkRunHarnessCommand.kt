package skillbill.infrastructure.launcher.agentrun

import skillbill.install.model.SupportedAgent
import skillbill.ports.agentrun.model.SkillRunRequest
import skillbill.ports.work.model.WorkRunLaunchKind
import skillbill.ports.work.model.WorkRunLaunchRequest
import java.nio.file.Path

internal fun workRunHarnessCommand(
  request: WorkRunLaunchRequest,
  harness: WorkRunLaunchKind.AgentHarness,
  agent: SupportedAgent,
  environment: Map<String, String>,
  projectStoreRoot: Path,
): AgentRunCommand {
  val builder =
    when (agent) {
      SupportedAgent.CLAUDE -> ClaudeAgentRunCommandBuilder(providerEnvironment = environment)
      SupportedAgent.CODEX -> CodexAgentRunCommandBuilder()
      SupportedAgent.JUNIE -> JunieAgentRunCommandBuilder()
      SupportedAgent.CURSOR -> CursorAgentRunCommandBuilder()
    }
  return builder.build(
    SkillRunRequest(
      issueKey = request.ownershipKey,
      repoRoot = request.repositoryRoot,
      promptOverride = harness.prompt,
      streamProviderOutput = true,
      additionalWritableDirectories = listOf(projectStoreRoot),
    ),
  )
}
