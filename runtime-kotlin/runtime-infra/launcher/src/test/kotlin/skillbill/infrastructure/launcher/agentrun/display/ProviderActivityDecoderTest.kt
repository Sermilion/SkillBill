package skillbill.infrastructure.launcher.agentrun.display

import com.fasterxml.jackson.databind.ObjectMapper
import skillbill.ports.agentrun.activity.model.AgentRunActivityKind
import skillbill.ports.agentrun.activity.model.AgentRunActivityState
import skillbill.ports.diagnostics.NoopRuntimeDiagnostics
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ProviderActivityDecoderTest {
  private val mapper = ObjectMapper()

  @Test
  fun `Claude tool results update their original call and never turn prose into a read`() {
    val decoder = ProviderActivityDecoder("claude", NoopRuntimeDiagnostics)
    val started =
      decoder.decode(
        mapper.readTree(
          """
          {
            "type": "assistant",
            "message": {
              "content": [
                {
                  "type": "tool_use",
                  "id": "read-1",
                  "name": "Read",
                  "input": {
                    "file_path": "/repo/a.kt"
                  }
                },
                {
                  "type": "text",
                  "text": "Read the remaining files before editing."
                }
              ]
            }
          }
          """.trimIndent(),
        ),
      )
    assertEquals(
      listOf(
        AgentRunActivityKind.STREAM,
        AgentRunActivityKind.READ,
        AgentRunActivityKind.NOTE,
      ),
      started.map {
        it.kind
      },
    )
    assertEquals("/repo/a.kt", started[1].path)
    val result =
      mapper.readTree(
        """
        {"type":"user","message":{"content":[
          {"type":"tool_result","tool_use_id":"read-1","is_error":true}
        ]}}
        """.trimIndent(),
      )
    val ended = decoder.decode(result).single()
    assertEquals(started[1].id, ended.id)
    assertEquals(AgentRunActivityState.FAILED, ended.state)
    assertTrue(decoder.decode(result).isEmpty())
  }

  @Test
  fun `Codex commands keep start identity exit code and recorded duration on completion`() {
    val decoder = ProviderActivityDecoder("codex", NoopRuntimeDiagnostics)
    val started =
      decoder.decode(
        mapper.readTree(
          """
          {
            "type": "item.started",
            "item": {
              "id": "cmd-1",
              "type": "command_execution",
              "command": "./gradlew check"
            }
          }
          """.trimIndent(),
        ),
      ).last()
    val ended =
      decoder.decode(
        mapper.readTree(
          """
          {
            "type": "item.completed",
            "item": {
              "id": "cmd-1",
              "type": "command_execution",
              "command": "./gradlew check",
              "exit_code": 1,
              "duration_ms": 4100
            }
          }
          """.trimIndent(),
        ),
      ).single()
    assertEquals(started.id, ended.id)
    assertEquals(AgentRunActivityKind.COMMAND, ended.kind)
    assertEquals(AgentRunActivityState.FAILED, ended.state)
    assertEquals(1, ended.exitCode)
    assertEquals(4100L, ended.durationMs)
  }

  @Test
  fun `a Codex patch records every changed spec rather than only its first file`() {
    val event =
      mapper.readTree(
        """
        {"type":"item.completed","item":{"id":"patch-1","type":"file_change","status":"completed","changes":[
          {"path":"specs/spec.md","kind":"update"},
          {"path":"specs/spec_subtask_1.md","kind":"add"}
        ]}}
        """.trimIndent(),
      )
    val completed =
      ProviderActivityDecoder("codex", NoopRuntimeDiagnostics).decode(event)
        .filter { it.kind == AgentRunActivityKind.WRITE && it.state == AgentRunActivityState.COMPLETED }
    assertEquals(listOf("specs/spec.md", "specs/spec_subtask_1.md"), completed.map { it.path })
    assertEquals(2, completed.map { it.id }.distinct().size)
  }

  @Test
  fun `independent launches cannot collide on provider local item ids`() {
    val event =
      mapper.readTree(
        """
        {
          "type": "item.started",
          "item": {
            "id": "item_0",
            "type": "command_execution",
            "command": "git status"
          }
        }
        """.trimIndent(),
      )
    val first = ProviderActivityDecoder("codex", NoopRuntimeDiagnostics).decode(event).last()
    val second = ProviderActivityDecoder("codex", NoopRuntimeDiagnostics).decode(event).last()
    assertTrue(first.id != second.id)
  }
}
