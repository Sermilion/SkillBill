package skillbill.infrastructure.skills.nativeagent

import skillbill.error.core.SkillBillRuntimeException
import skillbill.error.shellcontent.InstallFailureCode
import skillbill.infrastructure.skills.nativeagent.composition.NativeAgentSource
import skillbill.infrastructure.skills.nativeagent.composition.parseNativeAgentSource
import skillbill.infrastructure.skills.nativeagent.composition.parseNativeAgentSourceText
import skillbill.infrastructure.skills.nativeagent.rendering.NativeAgentProvider
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
  fun `renderers emit claude codex junie and cursor shapes from one source`() {
    val source =
      NativeAgentSource(
        name = "bill-test-worker",
        description = "Test worker.",
        body = "# Worker\n\nDo the work.",
        tools = listOf("Read", "Grep", "Glob", "Bash"),
      )

    val claude = NativeAgentProvider.Claude.render(source)
    val codex = NativeAgentProvider.Codex.render(source)
    val junie = NativeAgentProvider.Junie.render(source)
    val cursor = NativeAgentProvider.Cursor.render(source)

    assertContains(claude, "name: bill-test-worker")
    assertContains(claude, "description: Test worker.")
    assertContains(claude, "tools: Read, Grep, Glob, Bash")
    assertContains(claude, "# Worker\n\nDo the work.")
    assertFalse("mode: subagent" in claude)
    assertContains(codex, "developer_instructions = \"\"\"")
    assertContains(codex, "# Worker\n\nDo the work.")
    assertContains(junie, "name: bill-test-worker")
    assertContains(junie, "description: Test worker.")
    assertContains(junie, "tools: Read, Grep, Glob, Bash")
    assertContains(junie, "# Worker\n\nDo the work.")
    assertFalse("mode: subagent" in junie)
    assertContains(cursor, "# Worker\n\nDo the work.")
    val cursorFrontmatter = cursor.substringAfter("---\n").substringBefore("\n---")
    assertEquals(
      listOf("name: bill-test-worker", "description: Test worker.", "readonly: true"),
      cursorFrontmatter.lines(),
      "Cursor frontmatter is name, description, then the readonly projection of the declared toolset",
    )
    assertEquals(claude, junie, "Claude and Junie share the same markdown shape; drift must be intentional")
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
    val junie = NativeAgentProvider.Junie.render(source)

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

    listOf(junie).forEach { rendered ->
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
