package skillbill.infrastructure.workflow.qa

import skillbill.infrastructure.workflow.monitor.FakeMonitorProcessInspector
import skillbill.model.EnvironmentContext
import skillbill.ports.taskruntime.model.FeatureTaskRuntimeProcessInspection
import skillbill.telemetry.STATE_DIR_ENVIRONMENT_KEY
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FileSystemQaWorkspaceTest {
  private val scratch: Path = Files.createTempDirectory("skillbill-qa-workspace")
  private val stateDir: Path = scratch.resolve("state")
  private val repoRoot: Path = scratch.resolve("app")
  private val workspaces =
    FileSystemQaWorkspace(
      FakeMonitorProcessInspector(FeatureTaskRuntimeProcessInspection.ExactLive),
      Clock.systemUTC(),
      EnvironmentContext(
        environment = mapOf(STATE_DIR_ENVIRONMENT_KEY to stateDir.toString()),
        userHome = scratch.resolve("home"),
      ),
    )

  @AfterTest
  fun cleanUp() {
    scratch.toFile().deleteRecursively()
  }

  @Test
  fun `the QA workspace lives under the state dir outside git and is shared by every worktree`() {
    git(scratch, "init", "-q", repoRoot.toString())
    git(repoRoot, "-c", "user.name=qa", "-c", "user.email=qa@example.com", "commit", "-q", "--allow-empty", "-m", "i")
    val linkedWorktree = scratch.resolve("app-linked")
    git(repoRoot, "worktree", "add", "-q", "--detach", linkedWorktree.toString())

    val main = workspaces.open(repoRoot, "phr-main")
    val linked = workspaces.open(linkedWorktree, "phr-linked")

    val qaRoot = main.directory.parent
    assertEquals(stateDir.resolve("qa"), qaRoot.parent, "the QA root must sit under the injected state dir")
    assertTrue(Regex("^app-[0-9a-f]{16}$").matches(qaRoot.fileName.toString()), "unexpected repo key $qaRoot")
    assertEquals(qaRoot, linked.directory.parent, "worktrees of one repository must share a repo key")
    assertTrue(main.directory.none { it.toString() == ".git" }, "QA state must stay out of .git: ${main.directory}")
    assertEquals("${main.directory}/", main.evidencePrefix)
    assertTrue(Files.isRegularFile(main.directory.resolve("owner.json")))
  }

  private fun git(
    directory: Path,
    vararg arguments: String,
  ) {
    val builder = ProcessBuilder(listOf("git", "-C", directory.toString()) + arguments).redirectErrorStream(true)
    builder.environment() += mapOf("GIT_CONFIG_GLOBAL" to "/dev/null", "GIT_CONFIG_NOSYSTEM" to "1")
    val process = builder.start()
    val output = process.inputStream.bufferedReader().readText()
    check(process.waitFor(30, TimeUnit.SECONDS) && process.exitValue() == 0) { "git ${arguments.toList()}: $output" }
  }
}
