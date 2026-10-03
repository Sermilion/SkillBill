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
    val modes = tokenValues(tokenForwarding, "mode")

    assertEquals(setOf("inline", "delegated"), modes.toSet())
    modes.forEach { value ->
      assertEquals(value, PhaseInvocationParser.parse("review", listOf("mode:$value")).mode)
    }
  }

  @Test
  fun `phase review forwards the pr staged and unstaged targets beside HEAD uncommitted and a sha`() {
    val targets =
      Regex("""`target:([^`]+)`""").findAll(tokenForwarding).single().groupValues[1].split('|').toSet()

    assertEquals(setOf("pr", "staged", "unstaged", "HEAD", "last", "uncommitted", "<sha>"), targets)
    assertContains(
      section(dispatcher, "Phase Review"),
      "The accepted targets are `pr`, `staged`, `unstaged`, `HEAD` or `last`, `uncommitted`, and a commit `<sha>`.",
    )
  }

  @Test
  fun `full run forwards code-review tokens verbatim as the goal code review mode flag`() {
    assertEquals(setOf("inline", "auto"), tokenValues(dispatcher, "code-review").toSet())
    assertContains(dispatcher, "`${FeatureTaskRuntimeGoalContinuationLaunchTokens.CODE_REVIEW_MODE_FLAG} <value>`")
  }

  @Test
  fun `phase with operation stays a usage error that never reaches the cli`() {
    assertContains(dispatcher, "the caller passes `phase:` together with `operation:`: report a usage error.")
    assertTrue(dispatcher.contains("Stop without running any CLI command when:"))
    assertFalse(Regex("""skill-bill phase [^\n`]*operation:""").containsMatchIn(dispatcher))
  }

  @Test
  fun `full run launches intake through the goal runtime without standalone preparation`() {
    assertContains(
      section(dispatcher, "Launch"),
      "skill-bill <intake> --agent <currently-executing-agent> --no-live-output",
    )
    assertContains(section(dispatcher, "Intake"), "ask for the tracker issue key")
    assertContains(section(dispatcher, "Intake"), "ask for the requirements")
    assertContains(section(dispatcher, "Issue resolution"), "Linear, Jira, and any other connected tracker")
    assertContains(section(dispatcher, "Relay"), "Relay its output verbatim, adding nothing.")
    assertContains(
      section(dispatcher, "Operator-only commands"),
      "only when the operator explicitly requests that standalone phase or operation",
    )
    assertContains(section(dispatcher, "Phase Forms"), "only at the operator's explicit request")
    assertContains(section(dispatcher, "Operation Forms"), "only at the operator's explicit request")
    assertFalse(dispatcher.contains("bill-feature"), "the dispatcher must not name the retired feature skill")
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

  private val tokenForwarding: String get() = section(dispatcher, "Token Forwarding")

  private fun tokenValues(
    text: String,
    key: String,
  ): List<String> = Regex("""`$key:([a-z|]+)`""").findAll(text).single().groupValues[1].split('|')

  private fun repositoryRoot(): Path =
    generateSequence(Path.of("").toAbsolutePath().normalize()) { it.parent }
      .first { Files.isRegularFile(it.resolve("LICENSE")) }
}
