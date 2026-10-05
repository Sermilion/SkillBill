package skillbill.infrastructure.skills.nativeagent

import skillbill.error.core.SkillBillRuntimeException
import skillbill.error.shellcontent.InstallFailureCode
import skillbill.infrastructure.skills.nativeagent.composition.NativeAgentCompositionDirective
import skillbill.infrastructure.skills.nativeagent.composition.NativeAgentCompositionKind
import skillbill.infrastructure.skills.nativeagent.composition.NativeAgentSource
import skillbill.infrastructure.skills.nativeagent.composition.parseNativeAgentBundle
import skillbill.infrastructure.skills.nativeagent.composition.parseNativeAgentSourceText
import skillbill.infrastructure.skills.nativeagent.composition.renderNativeAgentBundle
import skillbill.infrastructure.skills.nativeagent.composition.renderNativeAgentSource
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class NativeAgentSourceParserTest {
  @Test
  fun `compose directive parses governed content target`() {
    val source =
      parseNativeAgentSourceText(
        "---\n" +
          "name: bill-composed\n" +
          "description: Composed worker.\n" +
          "compose: governed-content\n" +
          "---\n\n",
      )

    assertEquals(
      NativeAgentCompositionDirective(NativeAgentCompositionKind.GovernedContent),
      source.composition,
    )
    assertEquals("", source.body)
  }

  @Test
  fun `agents yaml parses multiple bundled native agents`() {
    val dir = Files.createTempDirectory("skillbill-native-agent-bundle")
    val bundlePath = dir.resolve("agents.yaml")
    Files.writeString(
      bundlePath,
      """
      agents:
        - name: bill-one
          description: First worker.
          compose: governed-content
        - name: bill-two
          description: Second worker.
          body: |-
            # Worker

            Do the work.
      """.trimIndent() + "\n",
    )

    val sources = parseNativeAgentBundle(bundlePath)

    assertEquals(listOf("bill-one", "bill-two"), sources.map { it.name })
    assertEquals(NativeAgentCompositionDirective(NativeAgentCompositionKind.GovernedContent), sources[0].composition)
    assertEquals("", sources[0].body)
    assertEquals("# Worker\n\nDo the work.", sources[1].body)
    assertEquals(bundlePath, sources[0].path)
    assertEquals("bill-one", sources[0].bundleEntryName)
  }

  @Test
  fun `renderNativeAgentBundle quotes implicit scalar descriptions`() {
    val dir = Files.createTempDirectory("skillbill-native-agent-bundle-implicit-scalars")
    val bundlePath = dir.resolve("agents.yaml")
    Files.writeString(
      bundlePath,
      renderNativeAgentBundle(
        listOf(
          NativeAgentSource(
            name = "bill-number",
            description = "123",
            body = "# Number",
          ),
          NativeAgentSource(
            name = "bill-boolean",
            description = "true",
            body = "# Boolean",
          ),
        ),
      ),
    )

    val rendered = Files.readString(bundlePath)
    val reparsed = parseNativeAgentBundle(bundlePath)

    assertContains(rendered, "description: \"123\"")
    assertContains(rendered, "description: \"true\"")
    assertEquals(listOf("123", "true"), reparsed.map { it.description })
  }

  @Test
  fun `agents yaml rejects unsupported entry keys`() {
    val dir = Files.createTempDirectory("skillbill-native-agent-bundle-invalid")
    val bundlePath = dir.resolve("agents.yaml")
    Files.writeString(
      bundlePath,
      """
      agents:
        - name: bill-one
          description: First worker.
          mode: subagent
          body: Do the work.
      """.trimIndent() + "\n",
    )

    val error =
      assertFailsWith<SkillBillRuntimeException> {
        parseNativeAgentBundle(bundlePath)
      }.also { assertEquals(InstallFailureCode.INVALID_NATIVE_AGENT_COMPOSITION_SCHEMA, it.code) }

    assertContains(error.message.orEmpty(), "property 'mode' is not defined")
  }

  @Test
  fun `agents yaml rejects body omission without compose`() {
    val dir = Files.createTempDirectory("skillbill-native-agent-bundle-body")
    val bundlePath = dir.resolve("agents.yaml")
    Files.writeString(
      bundlePath,
      """
      agents:
        - name: bill-one
          description: First worker.
      """.trimIndent() + "\n",
    )

    val error =
      assertFailsWith<SkillBillRuntimeException> {
        parseNativeAgentBundle(bundlePath)
      }.also { assertEquals(InstallFailureCode.INVALID_NATIVE_AGENT_COMPOSITION_SCHEMA, it.code) }

    assertContains(error.message.orEmpty(), "required property 'compose' not found")
  }

  @Test
  fun `agents yaml reports blank body after schema validation as typed schema error`() {
    val dir = Files.createTempDirectory("skillbill-native-agent-bundle-blank-body")
    val bundlePath = dir.resolve("agents.yaml")
    Files.writeString(
      bundlePath,
      """
      agents:
        - name: bill-one
          description: First worker.
          body: ""
      """.trimIndent() + "\n",
    )

    val error =
      assertFailsWith<SkillBillRuntimeException> {
        parseNativeAgentBundle(bundlePath)
      }.also { assertEquals(InstallFailureCode.INVALID_NATIVE_AGENT_COMPOSITION_SCHEMA, it.code) }

    assertContains(error.message.orEmpty(), bundlePath.toString())
    assertContains(error.message.orEmpty(), "native agent body is required")
  }

  @Test
  fun `agents yaml reports malformed yaml as typed schema error`() {
    val dir = Files.createTempDirectory("skillbill-native-agent-bundle-malformed-yaml")
    val bundlePath = dir.resolve("agents.yaml")
    Files.writeString(bundlePath, "agents:\n  - name: [unterminated\n")

    val error =
      assertFailsWith<SkillBillRuntimeException> {
        parseNativeAgentBundle(bundlePath)
      }.also { assertEquals(InstallFailureCode.INVALID_NATIVE_AGENT_COMPOSITION_SCHEMA, it.code) }

    assertContains(error.message.orEmpty(), bundlePath.toString())
    assertContains(error.message.orEmpty(), "could not parse YAML")
  }

  @Test
  fun `renderNativeAgentSource preserves compose directive`() {
    val source =
      NativeAgentSource(
        name = "bill-composed",
        description = "Composed worker.",
        body = "",
        composition = NativeAgentCompositionDirective(NativeAgentCompositionKind.GovernedContent),
      )

    val expected =
      "---\n" +
        "contract_version: \"0.1\"\n" +
        "name: bill-composed\n" +
        "description: Composed worker.\n" +
        "compose: governed-content\n" +
        "---\n\n"
    assertEquals(expected, renderNativeAgentSource(source))
  }

  @Test
  fun `blank body remains rejected without compose directive`() {
    val error =
      assertFailsWith<IllegalArgumentException> {
        parseNativeAgentSourceText(
          "---\n" +
            "name: bill-blank\n" +
            "description: Blank worker.\n" +
            "---\n\n",
          label = "test source",
        )
      }

    assertContains(error.message.orEmpty(), "test source")
    assertContains(error.message.orEmpty(), "native agent body is required")
  }

  @Test
  fun `unsupported compose directive fails strictly`() {
    val error =
      assertFailsWith<SkillBillRuntimeException> {
        parseNativeAgentSourceText(
          "---\n" +
            "name: bill-composed\n" +
            "description: Composed worker.\n" +
            "compose: local-file\n" +
            "---\n\n",
          label = "test source",
        )
      }

    assertEquals(InstallFailureCode.INVALID_NATIVE_AGENT_COMPOSITION_SCHEMA, error.code)
    assertContains(error.message.orEmpty(), "test source")
    assertContains(error.message.orEmpty(), "unsupported native agent compose directive 'local-file'")
  }

  @Test
  fun `double-quoted description decodes backslash quote and newline escapes`() {
    val source =
      parseNativeAgentSourceText(
        """
        ---
        name: bill-quoted-double
        description: "She said \"hi\"\nthen left."
        ---

        # Body
        """.trimIndent(),
      )

    assertEquals("bill-quoted-double", source.name)
    assertEquals("She said \"hi\"\nthen left.", source.description)
  }

  @Test
  fun `single-quoted description decodes doubled apostrophe escape`() {
    val source =
      parseNativeAgentSourceText(
        """
        ---
        name: bill-quoted-single
        description: 'It''s here'
        ---

        # Body
        """.trimIndent(),
      )

    assertEquals("It's here", source.description)
  }

  @Test
  fun `unquoted description passes through trimmed unchanged`() {
    val source =
      parseNativeAgentSourceText(
        """
        ---
        name: bill-plain
        description: Plain description with no quoting.
        ---

        # Body
        """.trimIndent(),
      )

    assertEquals("Plain description with no quoting.", source.description)
  }

  @Test
  fun `double-quoted description decodes literal backslash`() {
    val source =
      parseNativeAgentSourceText(
        """
        ---
        name: bill-backslash
        description: "Path is C:\\Users\\Name"
        ---

        # Body
        """.trimIndent(),
      )

    assertEquals("Path is C:\\Users\\Name", source.description)
  }

  @Test
  fun `unterminated double-quoted description fails`() {
    val text =
      "---\n" +
        "name: bill-unterminated\n" +
        "description: \"abc\n" +
        "---\n" +
        "\n" +
        "# Body\n"

    val error =
      assertFailsWith<IllegalArgumentException> {
        parseNativeAgentSourceText(text, label = "test source")
      }

    assertContains(error.message.orEmpty(), "test source")
    assertContains(error.message.orEmpty(), "unterminated double-quoted scalar")
  }

  @Test
  fun `embedded unescaped double quote inside double-quoted description fails`() {
    val text =
      "---\n" +
        "name: bill-embedded-quote\n" +
        "description: \"foo\"bar\"\n" +
        "---\n" +
        "\n" +
        "# Body\n"

    val error =
      assertFailsWith<IllegalArgumentException> {
        parseNativeAgentSourceText(text, label = "test source")
      }

    assertContains(error.message.orEmpty(), "test source")
    assertContains(
      error.message.orEmpty(),
      "unescaped double quote inside double-quoted scalar",
    )
  }

  @Test
  fun `unknown backslash escape inside double-quoted description fails`() {
    val text =
      "---\n" +
        "name: bill-bad-escape\n" +
        "description: \"\\q\"\n" +
        "---\n" +
        "\n" +
        "# Body\n"

    val error =
      assertFailsWith<IllegalArgumentException> {
        parseNativeAgentSourceText(text, label = "test source")
      }

    assertContains(error.message.orEmpty(), "test source")
    assertContains(error.message.orEmpty(), "unknown escape sequence \\q")
  }

  @Test
  fun `unterminated single-quoted description fails`() {
    val text =
      "---\n" +
        "name: bill-single-unterminated\n" +
        "description: 'abc\n" +
        "---\n" +
        "\n" +
        "# Body\n"

    val error =
      assertFailsWith<IllegalArgumentException> {
        parseNativeAgentSourceText(text, label = "test source")
      }

    assertContains(error.message.orEmpty(), "test source")
    assertContains(error.message.orEmpty(), "unterminated single-quoted scalar")
  }
}
