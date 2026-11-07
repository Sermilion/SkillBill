package skillbill.infrastructure.host.scratch

import skillbill.model.EnvironmentContext
import java.nio.file.Files
import java.nio.file.Path

fun testAgentRunScratchRoot(stateDir: Path = Files.createTempDirectory("agent-run-state")): AgentRunScratchRoot =
  AgentRunScratchRoot(
    EnvironmentContext(environment = mapOf("SKILL_BILL_STATE_DIR" to stateDir.toString()), userHome = stateDir),
  )
