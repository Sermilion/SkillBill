package skillbill.application

import skillbill.application.agentrun.AgentRunService
import skillbill.application.agentrun.model.AgentRunStartRequest
import skillbill.install.model.SupportedAgent
import skillbill.ports.agentrun.AgentRunLauncher
import skillbill.ports.agentrun.agentRunLaunchFacts
import skillbill.ports.agentrun.model.AgentRunLaunchModelRequest
import skillbill.ports.agentrun.model.AgentRunLaunchOutcome
import skillbill.ports.agentrun.model.AgentRunLaunchRequest
import skillbill.ports.agentrun.model.SkillRunRequest
import skillbill.ports.agentrun.passThroughResolvedLaunchModel
import skillbill.workflow.taskruntime.model.skeleton.EffectiveLaunchModel
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class AgentRunServiceTest {
  @Test
  fun `configured override wins over invoked goal runtime agent`() {
    val launcher = RecordingAgentRunLauncher()
    val service = AgentRunService(launcher)

    val result =
      service.launch(
        AgentRunStartRequest(
          invokedAgentId = "claude",
          configuredAgentOverrideId = "codex",
          skillRunRequest = skillRunRequest(),
        ),
      )

    assertEquals(SupportedAgent.CLAUDE, result.resolution.invokedAgent)
    assertEquals(SupportedAgent.CODEX, result.resolution.configuredOverrideAgent)
    assertEquals(SupportedAgent.CODEX, result.resolution.effectiveAgent)
    assertEquals("codex", launcher.requests.single().agentId)
  }

  @Test
  fun `invoked goal runtime agent is the default without hardcoded fallback`() {
    val launcher = RecordingAgentRunLauncher()
    val service = AgentRunService(launcher)

    val result =
      service.launch(
        AgentRunStartRequest(
          invokedAgentId = "junie",
          configuredAgentOverrideId = null,
          skillRunRequest = skillRunRequest(),
        ),
      )

    assertEquals(SupportedAgent.JUNIE, result.resolution.effectiveAgent)
    assertEquals("junie", launcher.requests.single().agentId)
  }

  @Test
  fun `unknown configured agent fails loudly before launch`() {
    val launcher = RecordingAgentRunLauncher()
    val service = AgentRunService(launcher)

    assertFailsWith<IllegalArgumentException> {
      service.launch(
        AgentRunStartRequest(
          invokedAgentId = "codex",
          configuredAgentOverrideId = "unknown",
          skillRunRequest = skillRunRequest(),
        ),
      )
    }

    assertEquals(emptyList(), launcher.requests)
  }

  private fun skillRunRequest(): SkillRunRequest =
    SkillRunRequest(
      issueKey = "SKILL-56",
      repoRoot = Path.of("/tmp/skillbill-agent-run-service"),
      subtaskId = 2,
    )
}

private class RecordingAgentRunLauncher : AgentRunLauncher {
  val requests: MutableList<AgentRunLaunchRequest> = mutableListOf()

  override fun resolveLaunchModel(request: AgentRunLaunchModelRequest): EffectiveLaunchModel =
    passThroughResolvedLaunchModel(request)

  override fun launch(request: AgentRunLaunchRequest): AgentRunLaunchOutcome {
    requests += request
    return agentRunLaunchFacts(
      agent = SupportedAgent.fromId(request.agentId),
      stdout = "diagnostic",
      stderr = "",
    )
  }
}
