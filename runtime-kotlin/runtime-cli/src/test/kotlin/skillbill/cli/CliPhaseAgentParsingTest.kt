package skillbill.cli

import com.github.ajalt.clikt.core.UsageError
import skillbill.cli.core.CliRuntime
import skillbill.cli.kernel.agent.parsePhaseAgents
import skillbill.cli.model.CliRuntimeContext
import skillbill.config.model.WORKFLOW_AGENT_ASSIGNMENT_PHASE_IDS
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class CliPhaseAgentParsingTest {
  @Test
  fun `goal help exposes repeatable phase-agent`() {
    val result = CliRuntime.run(listOf("goal", "--help"), CliRuntimeContext())

    assertEquals(0, result.exitCode, result.stdout)
    assertContains(result.stdout, "--phase-agent")
    assertContains(result.stdout, "--agent-override")
  }

  @Test
  fun `goal catalog accepts repair and finalization phases`() {
    val parsed =
      parsePhaseAgents(
        listOf("goal_assessment=claude", "goal_assessment_fix=codex", "implement=cursor"),
        WORKFLOW_AGENT_ASSIGNMENT_PHASE_IDS,
      )

    assertEquals(
      mapOf(
        "goal_assessment" to "claude",
        "goal_assessment_fix" to "codex",
        "implement" to "cursor",
      ),
      parsed,
    )
  }

  @Test
  fun `last duplicate phase value wins`() {
    val parsed = parsePhaseAgents(listOf("plan=claude", "plan=codex"))

    assertEquals(mapOf("plan" to "codex"), parsed)
  }

  @Test
  fun `unknown phase is an explicit input error`() {
    val error = assertFailsWith<UsageError> { parsePhaseAgents(listOf("notaphase=claude")) }

    assertContains(error.message.orEmpty(), "not a runtime phase")
  }

  @Test
  fun `unknown provider is an explicit input error`() {
    val error = assertFailsWith<UsageError> { parsePhaseAgents(listOf("plan=notanagent")) }

    assertContains(error.message.orEmpty(), "not a supported provider id")
  }

  @Test
  fun `malformed assignment is an explicit input error`() {
    assertTrue {
      assertFailsWith<UsageError> { parsePhaseAgents(listOf("plan")) }.message
        .orEmpty().contains("phase=agent")
    }
  }
}
