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
  fun `resolved scalar permissions remain valid config actions and patterned denials survive`() {
    val baseline = """{"permission":[
      {"permission":"*","pattern":"*","action":"allow"},
      {"permission":"question","pattern":"*","action":"allow"},
      {"permission":"doom_loop","pattern":"*","action":"ask"},
      {"permission":"edit","pattern":"*","action":"allow"},
      {"permission":"edit","pattern":"secret.txt","action":"deny"}
    ]}"""

    listOf(false, true).forEach { readOnly ->
      val overlay =
        assertNotNull(
          OpenCodeChatPermissionPolicy.prepare("{}", null, readOnly, mapOf("build" to baseline)),
        )
      val policy = mapper.readTree(overlay.inlineConfiguration).path("agent").path("build").path("permission")

      assertEquals(if (readOnly) "allow" else "ask", policy.path("question").textValue())
      assertEquals(if (readOnly) "deny" else "ask", policy.path("doom_loop").textValue())
      assertEquals("deny", effectiveAction(policy.toString(), "edit", "secret.txt"))
      assertEquals(if (readOnly) "deny" else "ask", effectiveAction(policy.toString(), "edit", "file.kt"))
      assertTrue(OpenCodeChatPermissionPolicy.admits(agentConfiguration(policy.toString()), readOnly))
    }
  }

  @Test
  fun `provider tool output access does not bypass tool mutation checks`() {
    listOf(false, true).forEach { readOnly ->
      val rules = mapper.createArrayNode()
      rules.addObject().put("permission", "*").put("pattern", "*").put("action", if (readOnly) "deny" else "ask")
      rules.addObject().put("permission", "external_directory")
        .put("pattern", "/data/opencode/tool-output/*").put("action", "allow")
      val agent = mapper.createObjectNode()
      agent.set<JsonNode>("permission", rules)

      assertTrue(OpenCodeChatPermissionPolicy.admits(agent.toString(), readOnly))

      listOf("edit", "bash", "unknown_tool").forEach { tool ->
        val unsafe = agent.deepCopy()
        unsafe.withArray("permission").addObject().put("permission", tool).put("pattern", "*").put("action", "allow")
        assertFalse(OpenCodeChatPermissionPolicy.admits(unsafe.toString(), readOnly))
      }
    }
  }

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
