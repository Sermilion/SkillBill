package skillbill.infrastructure.skills.nativeagent

import skillbill.error.core.SkillBillRuntimeException
import skillbill.error.shellcontent.InstallFailureCode
import skillbill.infrastructure.skills.nativeagent.composition.NativeAgentSource
import skillbill.infrastructure.skills.nativeagent.composition.parseNativeAgentSourceFile
import skillbill.infrastructure.skills.nativeagent.composition.parseNativeAgentSourceText
import skillbill.infrastructure.skills.nativeagent.composition.parseNativeAgentTools
import skillbill.infrastructure.skills.nativeagent.composition.renderNativeAgentBundle
import skillbill.infrastructure.skills.nativeagent.composition.renderNativeAgentSource
import skillbill.infrastructure.skills.nativeagent.rendering.NativeAgentProvider
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NativeAgentToolsetContractTest {
  @Test
  fun `a declared toolset round-trips through the bundle and reaches rendered frontmatter`() {
    val agent =
      NativeAgentSource(
        name = "fixture-reviewer",
        description = "Fixture reviewer.",
        body = "# Worker\n\nReview it.",
        tools = listOf("Read", "Grep", "Glob", "Bash"),
      )

    val bundle = renderNativeAgentBundle(listOf(agent))
    assertTrue(bundle.contains("tools: [Read, Grep, Glob, Bash]"), "Bundle render must emit the toolset: $bundle")

    val roundTripped = parseNativeAgentSourceText(renderNativeAgentSource(agent))
    assertEquals(listOf("Read", "Grep", "Glob", "Bash"), roundTripped.tools)

    val rendered = NativeAgentProvider.Claude.render(agent)
    assertTrue(rendered.contains("tools: Read, Grep, Glob, Bash"), "Claude render must emit the toolset: $rendered")

    val cursor = NativeAgentProvider.Cursor.render(agent)
    assertTrue(cursor.contains("readonly: true"), "Cursor render must project the read-only toolset: $cursor")
  }

  @Test
  fun `a toolset granting mutation is not projected as read-only`() {
    val agent =
      NativeAgentSource(
        name = "fixture-writer",
        description = "Fixture writer.",
        body = "# Worker\n\nWrite it.",
        tools = listOf("Read", "Write"),
      )

    assertFalse(NativeAgentProvider.Cursor.render(agent).contains("readonly:"))
  }

  @Test
  fun `an omitted toolset stays absent so the host default is preserved`() {
    val agent = NativeAgentSource(name = "fixture-open", description = "Fixture.", body = "# Worker\n\nWork.")

    assertTrue(agent.tools.isEmpty())
    assertFalse(NativeAgentProvider.Claude.render(agent).contains("tools:"))
    assertFalse(renderNativeAgentBundle(listOf(agent)).contains("tools:"))
    assertFalse(NativeAgentProvider.Cursor.render(agent).contains("readonly:"))
  }

  @Test
  fun `an empty or repeated toolset fails loudly rather than silently widening access`() {
    val errors =
      listOf(
        assertFailsWith<SkillBillRuntimeException> { parseNativeAgentTools(emptyList<String>(), "fixture") },
        assertFailsWith<SkillBillRuntimeException> { parseNativeAgentTools(listOf("Read", "Read"), "fixture") },
        assertFailsWith<SkillBillRuntimeException> { parseNativeAgentTools(listOf("Read", ""), "fixture") },
        assertFailsWith<SkillBillRuntimeException> { parseNativeAgentTools("Read", "fixture") },
      )
    assertTrue(errors.all { error -> error.code == InstallFailureCode.INVALID_NATIVE_AGENT_COMPOSITION_SCHEMA })
  }

  @Test
  fun `every governed review worker declares a toolset without mutation or delegation`() {
    val root = repoRoot()
    val bundles =
      listOf(root.resolve("platform-packs"), root.resolve("skills")).flatMap { tree ->
        Files.walk(tree).use { paths -> paths.filter { it.fileName?.toString() == "agents.yaml" }.toList() }
      }
    assertTrue(bundles.isNotEmpty(), "Expected to discover native-agent bundles under $root")

    val reviewWorkers =
      bundles.flatMap { bundle -> parseNativeAgentSourceFile(bundle).map { bundle to it } }
        .filter { (_, agent) -> "code-review" in agent.name }
    assertTrue(reviewWorkers.isNotEmpty(), "Expected to discover governed review workers")

    reviewWorkers.forEach { (bundle, agent) ->
      assertTrue(
        agent.tools.isNotEmpty(),
        "${agent.name} in $bundle must declare a toolset; an undeclared worker inherits every host tool.",
      )
      assertEquals(
        GOVERNED_EVIDENCE_TOOLS,
        agent.tools,
        "${agent.name} in $bundle must reach repository content only through the governed evidence " +
          "operations; any other tool restores ungoverned filesystem or shell access on the review path.",
      )
      assertTrue(
        NativeAgentProvider.Cursor.render(agent).contains("readonly: true"),
        "${agent.name} must render read-only on Cursor; Cursor has no tools key, so a declaration that " +
          "reaches no capability field hands the worker every tool the parent can reach.",
      )
    }
  }

  private companion object {
    val GOVERNED_EVIDENCE_TOOLS =
      listOf(
        "mcp__skill-bill-review-evidence__read_evidence",
        "mcp__skill-bill-review-evidence__request_expansion",
      )
  }

  private fun repoRoot(): Path {
    var candidate = Path.of("").toAbsolutePath()
    while (!Files.isDirectory(candidate.resolve("platform-packs"))) {
      candidate = candidate.parent ?: error("Could not locate the repository root from ${Path.of("").toAbsolutePath()}")
    }
    return candidate
  }
}
