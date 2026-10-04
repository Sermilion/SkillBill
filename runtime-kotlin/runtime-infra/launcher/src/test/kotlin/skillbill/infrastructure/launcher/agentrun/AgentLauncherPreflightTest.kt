package skillbill.infrastructure.launcher.agentrun

import skillbill.install.model.SupportedAgent
import skillbill.ports.agentrun.ExecutableLookup
import skillbill.ports.agentrun.model.AgentRunTermination
import skillbill.ports.agentrun.model.SkillRunRequest
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class AgentLauncherPreflightTest {
  private fun request(): SkillRunRequest =
    SkillRunRequest(
      issueKey = "SKILL-162",
      repoRoot = Path.of("/tmp/skillbill-launcher-preflight"),
      subtaskId = 1,
      timeout = 10.seconds,
      goalContinuation = null,
      promptOverride = "Test prompt",
    )

  private fun cursorAdapter(lookup: ExecutableLookup) =
    ProcessAgentRunAdapter(
      agent = SupportedAgent.CURSOR,
      commandBuilder = CursorAgentRunCommandBuilder(),
      processRunner = RecordingAgentRunProcessRunner(),
      executableLookup = lookup,
    )

  @Test
  fun `an agent whose headless CLI is absent never reaches the process runner`() {
    val runner = RecordingAgentRunProcessRunner()
    val adapter =
      ProcessAgentRunAdapter(
        agent = SupportedAgent.CURSOR,
        commandBuilder = CursorAgentRunCommandBuilder(),
        processRunner = runner,
        executableLookup = executablesAvailable(),
      )

    val facts = adapter.launchFacts(request())

    assertEquals(AgentRunTermination.SpawnFailed, facts.termination)
    assertFalse(facts.processStarted)
    assertEquals(emptyList(), runner.requests.toList())
  }

  @Test
  fun `a missing headless CLI is reported by name with an install hint`() {
    val facts = cursorAdapter(executablesAvailable()).launchFacts(request())

    assertContains(facts.stderr, "'agent' is not on PATH")
    assertContains(facts.stderr, "curl https://cursor.com/install")

    assertContains(facts.stderr, "separate install")
  }

  @Test
  fun `a declared legacy executable is substituted when the preferred name is absent`() {
    val runner = RecordingAgentRunProcessRunner()
    val adapter =
      ProcessAgentRunAdapter(
        agent = SupportedAgent.CURSOR,
        commandBuilder = CursorAgentRunCommandBuilder(),
        processRunner = runner,
        executableLookup = executablesAvailable("cursor-agent"),
      )

    val facts = adapter.launchFacts(request())

    assertNotEquals<AgentRunTermination>(AgentRunTermination.SpawnFailed, facts.termination)
    assertEquals("cursor-agent", runner.requests.single().launch.command.first())
    assertContains(runner.requests.single().launch.command, "--print")
  }

  @Test
  fun `the preferred executable wins when both names resolve`() {
    val runner = RecordingAgentRunProcessRunner()
    val adapter =
      ProcessAgentRunAdapter(
        agent = SupportedAgent.CURSOR,
        commandBuilder = CursorAgentRunCommandBuilder(),
        processRunner = runner,
        executableLookup = executablesAvailable("agent", "cursor-agent"),
      )

    adapter.launch(request())

    assertEquals("agent", runner.requests.single().launch.command.first())
  }
}

class PathExecutableLookupTest {
  @Test
  fun `an executable regular file on PATH resolves`() {
    val directory = Files.createTempDirectory("skillbill-path-lookup")
    val binary = Files.createFile(directory.resolve("agent"))
    binary.toFile().setExecutable(true)

    val lookup = PathExecutableLookup { directory.toString() }

    assertTrue(lookup.onPath("agent"))
  }

  @Test
  fun `a non-executable file with the right name does not resolve`() {
    val directory = Files.createTempDirectory("skillbill-path-lookup-nonexec")
    val binary = Files.createFile(directory.resolve("agent"))
    binary.toFile().setExecutable(false)

    val lookup = PathExecutableLookup { directory.toString() }

    assertFalse(lookup.onPath("agent"))
  }

  @Test
  fun `an unset PATH resolves nothing rather than throwing`() {
    val lookup = PathExecutableLookup { null }

    assertFalse(lookup.onPath("agent"))
  }

  @Test
  fun `later PATH entries are searched`() {
    val empty = Files.createTempDirectory("skillbill-path-lookup-empty")
    val directory = Files.createTempDirectory("skillbill-path-lookup-second")
    Files.createFile(directory.resolve("codex")).toFile().setExecutable(true)

    val lookup = PathExecutableLookup { "$empty:$directory" }

    assertTrue(lookup.onPath("codex"))
    assertFalse(lookup.onPath("claude"))
  }
}
