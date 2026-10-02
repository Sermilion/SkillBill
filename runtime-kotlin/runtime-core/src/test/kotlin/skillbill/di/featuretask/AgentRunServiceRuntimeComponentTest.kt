package skillbill.di.featuretask

import skillbill.application.agentrun.model.AgentRunStartRequest
import skillbill.di.core.OptionalCallbacks
import skillbill.di.core.RuntimeComponent
import skillbill.di.core.RuntimeContext
import skillbill.di.core.TransportContext
import skillbill.di.core.WorkflowOpsContext
import skillbill.di.core.create
import skillbill.infrastructure.launcher.agentrun.PathExecutableLookup
import skillbill.install.model.SupportedAgent
import skillbill.model.EnvironmentContext
import skillbill.ports.agentrun.ExecutableLookup
import skillbill.ports.agentrun.model.AgentRunLaunchFacts
import skillbill.ports.agentrun.model.AgentRunSpawnAuthorization
import skillbill.ports.agentrun.model.AgentRunSpawnAuthorizationResult
import skillbill.ports.agentrun.model.AgentRunTermination
import skillbill.ports.agentrun.model.SkillRunRequest
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class AgentRunServiceRuntimeComponentTest {
  @Test
  fun `runtime component exposes agent run service with filesystem launcher binding`() {
    val tempDir = Files.createTempDirectory("skillbill-agent-run-component")
    val service =
      RuntimeComponent::class.create(
        RuntimeContext(
          environment = EnvironmentContext(environment = emptyMap(), userHome = tempDir),
          transport = TransportContext(),
          workflowOps = WorkflowOpsContext(),
          callbacks = OptionalCallbacks(executableLookup = ExecutableLookup { false }),
        ),
      ).agentRunService

    val result =
      service.launch(
        AgentRunStartRequest(
          invokedAgentId = "junie",
          skillRunRequest =
            SkillRunRequest(
              issueKey = "SKILL-56",
              repoRoot = tempDir,
              subtaskId = 2,
              promptOverride = "Phase: validate",
            ),
        ),
      )

    assertEquals(SupportedAgent.JUNIE, result.resolution.effectiveAgent)
    val facts = assertIs<AgentRunLaunchFacts>(result.launchOutcome)
    assertEquals(AgentRunTermination.SpawnFailed, facts.termination)
    assertContains(facts.stderr, "'junie' is not on PATH")
  }

  @Test
  fun `injected executable lookup refusal keeps a controlled fixture off the launch path`() {
    val tempDir = Files.createTempDirectory("skillbill-agent-run-lookup-refusal")
    val binDir = Files.createTempDirectory("skillbill-agent-run-bin")
    val marker = binDir.resolve("ran.marker")
    val junie = binDir.resolve("junie")
    Files.writeString(
      junie,
      """
      #!/bin/sh
      touch "$marker"
      echo SKILL350_CONTROLLED_EXECUTABLE
      """.trimIndent() + "\n",
    )
    junie.toFile().setExecutable(true)
    val fixturePathLookup = PathExecutableLookup { binDir.toAbsolutePath().normalize().toString() }
    assertTrue(fixturePathLookup.onPath("junie"))
    val lookupRequests = mutableListOf<String>()
    val service =
      RuntimeComponent::class.create(
        RuntimeContext(
          environment = EnvironmentContext(environment = emptyMap(), userHome = tempDir),
          transport = TransportContext(),
          workflowOps = WorkflowOpsContext(),
          callbacks =
            OptionalCallbacks(
              executableLookup =
                ExecutableLookup { executable ->
                  lookupRequests += executable
                  false
                },
            ),
        ),
      ).agentRunService

    val result =
      service.launch(
        AgentRunStartRequest(
          invokedAgentId = "junie",
          skillRunRequest =
            SkillRunRequest(
              issueKey = "SKILL-350",
              repoRoot = tempDir,
              subtaskId = 1,
              promptOverride = "Phase: implement",
              spawnAuthorization =
                object : AgentRunSpawnAuthorization {
                  override fun <T> withAuthorization(spawn: () -> T): AgentRunSpawnAuthorizationResult<T> =
                    error("The executable lookup refusal must prevent process authorization.")
                },
            ),
        ),
      )

    val facts = assertIs<AgentRunLaunchFacts>(result.launchOutcome)
    assertEquals(AgentRunTermination.SpawnFailed, facts.termination)
    assertContains(facts.stderr, "'junie' is not on PATH")
    assertFalse(Files.exists(marker))
    assertEquals(listOf("junie"), lookupRequests)
  }
}
