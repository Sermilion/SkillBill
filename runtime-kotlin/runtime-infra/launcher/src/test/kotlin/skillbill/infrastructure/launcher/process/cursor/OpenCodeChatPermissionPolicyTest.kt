package skillbill.infrastructure.launcher.process.cursor

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class OpenCodeChatPermissionPolicyTest {
  private val mapper = ObjectMapper()

  @Test
  fun `Act accepts an agent that retains a blanket denial without permitting mutations`() {
    val overlay =
      assertNotNull(
        OpenCodeChatPermissionPolicy.prepare(
          "{}",
          null,
          false,
          mapOf("title" to """{"permission":[{"permission":"*","pattern":"*","action":"deny"}]}"""),
        ),
      )
    val policy = mapper.readTree(overlay.inlineConfiguration).path("agent").path("title").path("permission").toString()

    assertEquals("deny", effectiveAction(policy, "edit", "file.kt"))
    assertTrue(OpenCodeChatPermissionPolicy.admits(agentConfiguration(policy), false))
    assertFalse(
      OpenCodeChatPermissionPolicy.admits(
        """{"permission":[{"permission":"*","pattern":"*","action":"allow"}]}""",
        false,
      ),
    )
  }

  @Test
  fun `the fallback precedes configured denials and ReadOnly does not allow an ask rule`() {
    val configuration = """{"permission":{"edit":"deny","read":{"*":"ask"}}}"""
    val act = assertNotNull(OpenCodeChatPermissionPolicy.prepare(configuration, null, false, emptyMap()))
    val readOnly = assertNotNull(OpenCodeChatPermissionPolicy.prepare(configuration, null, true, emptyMap()))

    assertEquals("deny", effectiveAction(act.permissionEnvironment, "edit", "file.kt"))
    assertEquals("ask", effectiveAction(act.permissionEnvironment, "bash", "git status"))
    assertEquals("deny", effectiveAction(readOnly.permissionEnvironment, "read", "secret.txt"))
    assertEquals("deny", effectiveAction(readOnly.permissionEnvironment, "edit", "file.kt"))
    assertTrue(OpenCodeChatPermissionPolicy.admits(agentConfiguration(act.permissionEnvironment), false))
    assertTrue(OpenCodeChatPermissionPolicy.admits(agentConfiguration(readOnly.permissionEnvironment), true))
  }

  private fun agentConfiguration(policy: String): String {
    val rules = mapper.createArrayNode()
    mapper.readTree(policy).fields().forEachRemaining { (permission, value) ->
      if (value.isTextual) {
        rules.addObject().put("permission", permission).put("pattern", "*").put("action", value.asText())
      } else {
        value.fields().forEachRemaining { (pattern, action) ->
          rules.addObject().put("permission", permission).put("pattern", pattern).put("action", action.asText())
        }
      }
    }
    return mapper.createObjectNode().set<JsonNode>("permission", rules).toString()
  }

  private fun effectiveAction(
    policy: String,
    permission: String,
    pattern: String,
  ): String? =
    mapper.readTree(agentConfiguration(policy)).path("permission").lastOrNull {
      it.path("permission").asText() in setOf("*", permission) &&
        it.path("pattern").asText() in setOf("*", pattern)
    }?.path("action")?.asText()
}
