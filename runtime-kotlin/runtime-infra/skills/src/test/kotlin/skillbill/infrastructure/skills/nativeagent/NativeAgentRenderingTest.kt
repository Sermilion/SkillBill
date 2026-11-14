package skillbill.infrastructure.skills.nativeagent

import skillbill.error.core.SkillBillRuntimeException
import skillbill.error.shellcontent.InstallFailureCode
import skillbill.infrastructure.skills.nativeagent.composition.NativeAgentSource
import skillbill.infrastructure.skills.nativeagent.composition.parseNativeAgentSource
import skillbill.infrastructure.skills.nativeagent.composition.parseNativeAgentSourceText
import skillbill.infrastructure.skills.nativeagent.rendering.NativeAgentProvider
import skillbill.install.model.SupportedAgent
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class NativeAgentRenderingTest {
  @Test
  fun `source parser reads provider-neutral frontmatter and body`() {
    val source =
      parseNativeAgentSourceText(
        """
        ---
        name: bill-test-worker
        description: Test worker.
        ---

        # Worker

        Do the work.
        """.trimIndent(),
      )

    assertEquals("bill-test-worker", source.name)
    assertEquals("Test worker.", source.description)
    assertEquals("# Worker\n\nDo the work.", source.body)
  }

  @Test
  fun `source parser rejects provider-specific mode frontmatter`() {
    val error =
      assertFailsWith<SkillBillRuntimeException> {
        parseNativeAgentSourceText(
          """
          ---
          name: bill-test-worker
          description: Test worker.
          mode: subagent
          ---

          # Worker
          """.trimIndent(),
        )
      }

    assertEquals(InstallFailureCode.INVALID_NATIVE_AGENT_COMPOSITION_SCHEMA, error.code)
    assertContains(error.message.orEmpty(), "unsupported native agent frontmatter key 'mode'")
  }

  @Test
  fun `renderers emit claude codex and cursor shapes from one source`() {
    val source =
      NativeAgentSource(
        name = "bill-test-worker",
        description = "Test worker.",
        body = "# Worker\n\nDo the work.",
        tools = listOf("Read", "Grep", "Glob", "Bash"),
      )

    val claude = NativeAgentProvider.Claude.render(source)
    val codex = NativeAgentProvider.Codex.render(source)
    val cursor = NativeAgentProvider.Cursor.render(source)

    assertContains(claude, "name: bill-test-worker")
    assertContains(claude, "description: Test worker.")
    assertContains(claude, "tools: Read, Grep, Glob, Bash")
    assertContains(claude, "# Worker\n\nDo the work.")
    assertFalse("mode: subagent" in claude)
    assertContains(codex, "developer_instructions = \"\"\"")
    assertContains(codex, "# Worker\n\nDo the work.")
    assertContains(cursor, "# Worker\n\nDo the work.")
    val cursorFrontmatter = cursor.substringAfter("---\n").substringBefore("\n---")
    assertEquals(
      listOf("name: bill-test-worker", "description: Test worker.", "readonly: true"),
      cursorFrontmatter.lines(),
      "Cursor frontmatter is name, description, then the readonly projection of the declared toolset",
    )
    assertNotEquals(claude, codex)
  }

  @Test
  fun `renderers escape codex triple quote and yaml special characters`() {
    val source =
      NativeAgentSource(
        name = "bill-test-edge",
        description = "Edge: case - quoted \"value\" and 'apostrophes'.",
        body = "# Edge\n\nBody with \"\"\" triple quotes and a back\\slash.",
      )

    val claude = NativeAgentProvider.Claude.render(source)
    val codex = NativeAgentProvider.Codex.render(source)

    assertFalse(
      claude.lines().any { line -> line.startsWith("description: Edge: case") },
      "YAML scalar with ': ' or leading hyphen must be quoted",
    )
    assertContains(claude, "description: ")
    val descriptionLine = claude.lines().first { it.startsWith("description: ") }
    assertTrue(descriptionLine.endsWith("\"") && descriptionLine.removePrefix("description: ").startsWith("\""))

    val frontmatterClose = codex.indexOf("\\\"\\\"\\\"")
    assertTrue(frontmatterClose >= 0, "Codex multiline body must escape literal triple quotes")
    val tripleQuoteOpens = codex.split("\"\"\"").size - 1
    assertEquals(2, tripleQuoteOpens, "Codex output must have exactly two unescaped triple quotes (open + close)")
    assertContains(codex, "back\\\\slash")
    assertContains(codex, "Edge: case - quoted \\\"value\\\"")

    listOf(claude).forEach { rendered ->
      val renderedDescriptionLine = rendered.lines().first { it.startsWith("description: ") }
      assertTrue(
        renderedDescriptionLine.removePrefix("description: ").startsWith("\""),
        "Frontmatter description with reserved YAML chars must be quoted: $renderedDescriptionLine",
      )
    }

    val parsedFromClaude = parseFrontmatterValue(claude, "description")
    assertEquals(source.description, parsedFromClaude)
  }

  @Test
  fun `opencode renders primary specialists with exact permission mappings`() {
    val source =
      NativeAgentSource(
        name = "bill-test-worker",
        description = "Review: the selected evidence.",
        body = "# Worker\n\nUse the composed governed body.",
        tools =
          listOf(
            "mcp__skill-bill-review-evidence__read_evidence",
            "mcp__skill-bill-review-evidence__request_expansion",
          ),
      )

    val rendered = NativeAgentProvider.forSupportedAgent(SupportedAgent.OPENCODE).render(source)

    assertContains(rendered, "name: bill-test-worker")
    assertContains(rendered, "description: \"Review: the selected evidence.\"")
    assertContains(rendered, "mode: primary")
    assertContains(
      rendered,
      "permission:\n  \"*\": deny\n  skill-bill-review-evidence_read_evidence: allow\n" +
        "  skill-bill-review-evidence_request_expansion: allow",
    )
    assertContains(rendered, "# Worker\n\nUse the composed governed body.")
    assertFalse("tools:" in rendered)
    assertFalse("model:" in rendered)
    assertFalse("variant:" in rendered)

    val hostDefaults =
      NativeAgentProvider.OpenCode.render(
        NativeAgentSource("bill-default-tools", "Default tools.", "# Worker"),
      )
    assertFalse("permission:" in hostDefaults)
  }

  @Test
  fun `opencode rejects an unmapped declared capability`() {
    val source = NativeAgentSource("bill-test-worker", "Worker.", "# Worker", tools = listOf("Unknown"))

    val error = assertFailsWith<SkillBillRuntimeException> { NativeAgentProvider.OpenCode.render(source) }

    assertEquals(InstallFailureCode.INVALID_NATIVE_AGENT_COMPOSITION_SCHEMA, error.code)
  }

  @Test
  fun `opencode agent directories use the configured root before creation`() {
    val home = Files.createTempDirectory("skillbill-opencode-agent-root")
    val configured = home.resolve("custom-opencode")
    val environment = mapOf("OPENCODE_CONFIG_DIR" to configured.toString())
    val provider = NativeAgentProvider.OpenCode

    assertEquals(listOf(configured.resolve("agents")), provider.homeAgentDirs(home, environment))
    assertEquals(
      listOf(configured.resolve("agents")),
      provider.inventoryHomeAgentDirs(home, environment).map { it.path },
    )
    assertFalse(Files.exists(configured.resolve("agents")))

    Files.createDirectories(configured.resolve("agents"))
    assertEquals(listOf(configured.resolve("agents")), provider.activeHomeAgentDirs(home, environment))
  }

  @Test
  fun `renderers are deterministic across repeated calls`() {
    val source =
      NativeAgentSource(
        name = "bill-test-worker",
        description = "Test worker.",
        body = "# Worker\n\nDo the work.",
      )

    NativeAgentProvider.entries.forEach { provider ->
      val first = provider.render(source)
      val second = provider.render(source)
      assertEquals(first.toByteArray(Charsets.UTF_8).toList(), second.toByteArray(Charsets.UTF_8).toList())
    }
  }

  @Test
  fun `source filename must match frontmatter name`() {
    val dir = Files.createTempDirectory("skillbill-native-agent-source")
    val sourcePath = dir.resolve("wrong-name.md")
    Files.writeString(
      sourcePath,
      """
      ---
      name: bill-test-worker
      description: Test worker.
      ---

      # Worker
      """.trimIndent(),
    )

    val error =
      assertFailsWith<SkillBillRuntimeException> {
        parseNativeAgentSource(sourcePath)
      }

    assertEquals(InstallFailureCode.INVALID_NATIVE_AGENT_COMPOSITION_SCHEMA, error.code)
    assertContains(error.message.orEmpty(), "filename must match frontmatter name")
  }

  @Test
  fun `parseNativeAgentSource returns parsed fields when filename matches name`() {
    val dir = Files.createTempDirectory("skillbill-native-agent-source-positive")
    val sourcePath = dir.resolve("bill-test-worker.md")
    Files.writeString(
      sourcePath,
      """
      ---
      name: bill-test-worker
      description: Worker that does the work.
      ---

      # Worker

      Body line.
      """.trimIndent(),
    )

    val source = parseNativeAgentSource(sourcePath)

    assertEquals("bill-test-worker", source.name)
    assertEquals("Worker that does the work.", source.description)
    assertEquals("# Worker\n\nBody line.", source.body)
    assertEquals(sourcePath, source.path)
  }

  private fun parseFrontmatterValue(
    rendered: String,
    key: String,
  ): String {
    val line = rendered.lines().first { it.startsWith("$key: ") }
    val raw = line.removePrefix("$key: ")
    if (raw.startsWith("\"") && raw.endsWith("\"")) {
      val inner = raw.substring(1, raw.length - 1)
      return buildString {
        var index = 0
        while (index < inner.length) {
          val char = inner[index]
          if (char == '\\' && index + 1 < inner.length) {
            when (val next = inner[index + 1]) {
              'n' -> append('\n')
              'r' -> append('\r')
              't' -> append('\t')
              '\\' -> append('\\')
              '"' -> append('"')
              else -> {
                append('\\')
                append(next)
              }
            }
            index += 2
          } else {
            append(char)
            index++
          }
        }
      }
    }
    return raw
  }
}
