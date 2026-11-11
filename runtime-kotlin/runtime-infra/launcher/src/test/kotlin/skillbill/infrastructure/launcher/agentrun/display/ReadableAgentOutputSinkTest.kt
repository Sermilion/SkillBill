package skillbill.infrastructure.launcher.agentrun.display

import skillbill.ports.agentrun.model.AgentRunModelObserver
import skillbill.ports.agentrun.model.AgentRunOutputSink
import skillbill.ports.agentrun.model.AgentRunOutputStream
import skillbill.ports.diagnostics.NoopRuntimeDiagnostics
import kotlin.test.Test
import kotlin.test.assertEquals

class ReadableAgentOutputSinkTest {
  @Test
  fun `provider model reports arrive before prose and tool payloads cannot replace them`() {
    val models = mutableListOf<String?>()
    val sink =
      ReadableAgentOutputSink(
        "claude",
        AgentRunOutputSink.NONE,
        NoopRuntimeDiagnostics,
        AgentRunModelObserver(models::add),
      )
    sink.write(AgentRunOutputStream.STDOUT, """{"type":"system","subtype":"init","model":"actual-model"}""" + "\n")
    assertEquals<List<String?>>(listOf("actual-model"), models)
    sink.write(AgentRunOutputStream.STDOUT, """{"type":"tool_result","model":"untrusted-tool-model"}""" + "\n")
    sink.write(AgentRunOutputStream.STDOUT, """{"type":"assistant","message":{"model":"bad model"}}""" + "\n")
    assertEquals<List<String?>>(listOf("actual-model"), models)
    sink.write(AgentRunOutputStream.STDOUT, """{"type":"assistant","message":{"model":"fallback-model"}}""" + "\n")
    assertEquals<List<String?>>(listOf("actual-model", "fallback-model"), models)
  }

  @Test
  fun `cursor partial text without a newline reaches the live sink and completed event does not repeat it`() {
    val displayed = StringBuilder()
    val sink =
      ReadableAgentOutputSink(
        "cursor",
        AgentRunOutputSink { _, text -> displayed.append(text) },
        NoopRuntimeDiagnostics,
      )
    val paragraph = "A long assistant paragraph remains visible while Cursor is still generating it."

    sink.write(AgentRunOutputStream.STDOUT, """{"type":"partial","delta":"$paragraph"}""" + "\n")

    assertEquals(paragraph, displayed.toString())

    sink.write(
      AgentRunOutputStream.STDOUT,
      """{"type":"assistant","message":{"content":[{"type":"text","text":"$paragraph"}]}}""" + "\n",
    )

    assertEquals("$paragraph\n", displayed.toString())
  }
}
