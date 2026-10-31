package dev.skillbill.desktop.core.data.chat

import dev.skillbill.desktop.core.data.repository.workItem
import dev.skillbill.desktop.core.domain.model.ChatAgent
import dev.skillbill.desktop.core.domain.model.CompactionConfig
import dev.skillbill.desktop.core.domain.model.ConfigDocument
import dev.skillbill.desktop.core.domain.model.ConfigFingerprint
import dev.skillbill.desktop.core.domain.model.ExecutionMatrixConfig
import dev.skillbill.desktop.core.domain.model.MachineConfig
import dev.skillbill.desktop.core.domain.model.ModelDirective
import dev.skillbill.desktop.core.domain.model.ReadProblem
import dev.skillbill.desktop.core.domain.model.RunOptions
import dev.skillbill.desktop.core.domain.model.Tier
import dev.skillbill.desktop.core.domain.repository.ConfigRead
import dev.skillbill.desktop.core.testing.RecordingDiagnostics
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class ChatAgentResolverTest {
  private val claudeItem = workItem("wf").copy(agentId = "claude")

  // A wrong lookup sends the chat to another model than the one the operator configured for reasoning.
  @Test
  fun `the reasoning tier of the matrix sets the model`() = runTest {
    val matrix = ExecutionMatrixConfig(
      phaseTiers = emptyMap(),
      agents = mapOf(
        "claude" to mapOf(Tier.REASONING to ModelDirective("opus", "high"), Tier.IMPLEMENTATION to ModelDirective("sonnet", null)),
      ),
      agentPhaseOverrides = emptyMap(),
    )
    val config = MachineConfig(matrix, CompactionConfig(false, 0, 0, emptyMap()), null, null)
    val diagnostics = RecordingDiagnostics()
    val present = ConfigDocument.Present("p", ConfigFingerprint("f"), config, false)
    val resolver = ChatAgentResolver({ ConfigRead.Document(present) }, diagnostics)

    assertEquals(ChatAgent("claude", "opus", "high"), resolver.resolve(claudeItem, null, null))
    assertEquals(emptyList(), diagnostics.categories)
  }

  // A failed config read must leave the CLI default rather than fail the chat.
  @Test
  fun `a failed config read leaves the model to the CLI`() = runTest {
    val diagnostics = RecordingDiagnostics()
    val resolver = ChatAgentResolver({ ConfigRead.Failed(ReadProblem.Runtime("down")) }, diagnostics)

    assertEquals(ChatAgent("claude", null, null), resolver.resolve(claudeItem, null, null))
    assertEquals(listOf("chat.matrix_fallback"), diagnostics.categories)
  }

  // An execution with no recorded agent would otherwise get no chat at all.
  @Test
  fun `an execution without an agent falls back to the launch default`() = runTest {
    val diagnostics = RecordingDiagnostics()
    val resolver = ChatAgentResolver({ ConfigRead.Failed(ReadProblem.Runtime("down")) }, diagnostics)
    val options = RunOptions(false, true, "Run", "codex", listOf("claude"), null)

    assertEquals("codex", resolver.resolve(workItem("wf"), null, options)?.agentId)
    assertEquals(listOf("chat.agent_fallback", "chat.matrix_fallback"), diagnostics.categories)
  }
}
