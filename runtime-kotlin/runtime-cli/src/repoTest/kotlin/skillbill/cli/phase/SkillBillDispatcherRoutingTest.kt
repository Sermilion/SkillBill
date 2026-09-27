package skillbill.cli.phase

import skillbill.cli.core.CliRuntime
import skillbill.cli.model.CliRuntimeContext
import skillbill.cli.operation.OperationInvocationParser
import skillbill.contracts.workflow.identity.task.FeatureTaskRuntimeGoalContinuationLaunchTokens
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SkillBillDispatcherRoutingTest {
  private val dispatcher: String = Files.readString(repositoryRoot().resolve("skills/skill-bill/content.md"))

  @Test
  fun `dispatcher routes exactly the in-memory phase definitions and each route parses`() {
    val routed = Regex("""skill-bill phase ([a-z_-]+)""").findAll(dispatcher).map { it.groupValues[1] }.toSet()

    assertEquals(PhaseInvocationParser.phaseNames().toSet(), routed)
    routed.forEach { name -> assertEquals(name, PhaseInvocationParser.parse(name, listOf("SKILL-1")).definitionId) }
  }

  @Test
  fun `dispatcher routes exactly the registered operations and each route parses`() {
    val routed = Regex("""skill-bill operation ([a-z-]+)""").findAll(dispatcher).map { it.groupValues[1] }.toSet()
    val home = Files.createTempDirectory("dispatcher-operations")
    val unknown =
      try {
        CliRuntime.run(
          listOf("--db", home.resolve("metrics.db").toString(), "operation", "not-an-operation"),
          CliRuntimeContext(userHome = home, repositoryRoot = home),
        )
      } finally {
        home.toFile().deleteRecursively()
      }
    val registered =
      Regex("""expected one of ([a-z, -]+)\.""").find(unknown.stdout + unknown.stderr)?.groupValues?.get(1)
        ?.split(", ")?.toSet()

    assertEquals(registered, routed)
    routed.forEach { name -> assertEquals(name, OperationInvocationParser.parse(name, listOf("intake")).operationId) }
  }

  @Test
  fun `review mode tokens reach the phase runtime unchanged`() {
    val modes = tokenValues("mode")

    assertEquals(setOf("inline", "delegated"), modes.toSet())
    modes.forEach { value ->
      assertEquals(value, PhaseInvocationParser.parse("review", listOf("mode:$value")).mode)
    }
  }

  @Test
  fun `full run forwards code-review tokens verbatim as the goal code review mode flag`() {
    assertEquals(setOf("inline", "auto"), tokenValues("code-review").toSet())
    assertContains(dispatcher, "`${FeatureTaskRuntimeGoalContinuationLaunchTokens.CODE_REVIEW_MODE_FLAG} <value>`")
  }

  @Test
  fun `phase with operation stays a usage error that never reaches the cli`() {
    assertContains(dispatcher, "the caller passes `phase:` together with `operation:`: report a usage error.")
    assertTrue(dispatcher.contains("Stop without running preflight or any CLI command when:"))
    assertFalse(Regex("""skill-bill phase [^\n`]*operation:""").containsMatchIn(dispatcher))
  }

  @Test
  fun `full run keeps the bill-feature gate and routes a missing spec to phase plan`() {
    val billFeature = Files.readString(repositoryRoot().resolve("skills/bill-feature/content.md"))
    val preflight = section(dispatcher, "Preflight")

    listOf("Gate", "Rehydrate", "Launch", "Relay").forEach { heading ->
      assertEquals(section(billFeature, heading), section(dispatcher, heading), heading)
    }
    assertContains(
      preflight,
      "reports new work, the spec is missing: run `skill-bill phase plan <intake> --agent <currently-executing-agent>`",
    )
    assertFalse(dispatcher.contains("bill-feature-spec"))
  }

  private fun section(
    text: String,
    heading: String,
  ): String =
    text
      .substringAfter("\n## $heading\n")
      .substringBefore("\n## ")
      .split(Regex("""\s+"""))
      .joinToString(" ")
      .trim()

  private fun tokenValues(key: String): List<String> =
    Regex("""`$key:([a-z|]+)`""").findAll(dispatcher).single().groupValues[1].split('|')

  private fun repositoryRoot(): Path =
    generateSequence(Path.of("").toAbsolutePath().normalize()) { it.parent }
      .first { Files.isRegularFile(it.resolve("LICENSE")) }
}
