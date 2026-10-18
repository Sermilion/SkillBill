package skillbill.installer

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class InstallerShellReconcileTest {
  private val runtimeRoot: Path =
    Path.of("").toAbsolutePath().normalize().let { workingDir ->
      if (workingDir.fileName.toString().startsWith("runtime-")) {
        workingDir.parent
      } else {
        workingDir
      }
    }

  @Test
  fun `reinstall with no upstream-local change is idempotent and commits the apply`() {
    val run = runInstallerShellRaw(input = "1\nclaude\noff\nskip\n")

    assertEquals(0, run.exitCode, run.output)
    assertLiveSourcePopulated(run.home)
    assertFalse(
      Files.exists(run.home.resolve(".skill-bill/.candidate-source")),
      "the staged candidate source must be reaped after apply",
    )
  }

  @Test
  fun `reinstall replaces a user-edited live skill with the current upstream bytes`() {
    val first = runInstallerShellRaw(input = "1\nclaude\noff\nskip\n")
    assertEquals(0, first.exitCode, first.output)
    val liveSkill = first.home.resolve(".skill-bill/skills/bill-sample/content.md")
    assertTrue(Files.isRegularFile(liveSkill), "first install must materialize the live skill")
    Files.writeString(liveSkill, "USER EDIT SENTINEL\n")

    val newUpstreamBody = "---\nname: bill-sample\ndescription: Sample skill.\n---\n\nUPSTREAM ADOPT BODY.\n"
    val second =
      runInstallerShellRaw(
        input = "1\nclaude\noff\nskip\n",
        reuse = first,
        scenario =
          ReconcileScenario(
            mutateUpstream = { repoRoot ->
              Files.writeString(repoRoot.resolve("skills/bill-sample/content.md"), newUpstreamBody)
            },
          ),
      )

    assertEquals(0, second.exitCode, second.output)
    assertEquals(
      newUpstreamBody,
      Files.readString(liveSkill),
      "upstream always wins: a user-edited live skill must be replaced by the upstream bytes",
    )
    assertTrue(Files.isRegularFile(first.home.resolve(".skill-bill/baseline-manifest.json")))
  }

  @Test
  fun `reinstall replaces a user-edited agent addon`() {
    val first = runInstallerShellRaw(input = "1\nclaude\noff\nskip\n")
    assertEquals(0, first.exitCode, first.output)
    val liveAddon = first.home.resolve(".skill-bill/agent-addons/review-helper/content.md")
    val upstreamBytes = Files.readString(liveAddon)
    Files.writeString(liveAddon, "USER AGENT ADDON EDIT\n")

    val second = runInstallerShellRaw(input = "1\nclaude\noff\nskip\n", reuse = first)

    assertEquals(0, second.exitCode, second.output)
    assertEquals(upstreamBytes, Files.readString(liveAddon))
  }

  @Test
  fun `shell reinstall is idempotent with a byte-identical baseline`() {
    val first = runInstallerShellRaw(input = "1\nclaude\noff\nskip\n")
    assertEquals(0, first.exitCode, first.output)
    val baseline = first.home.resolve(".skill-bill/baseline-manifest.json")
    val baselineAfterFirst = Files.readAllBytes(baseline)

    val second = runInstallerShellRaw(input = "1\nclaude\noff\nskip\n", reuse = first)

    assertEquals(0, second.exitCode, second.output)
    assertTrue(
      baselineAfterFirst.contentEquals(Files.readAllBytes(baseline)),
      "a no-edit reinstall must leave the baseline-manifest.json bytes identical",
    )
  }

  @Test
  fun `reinstall without the clone present still resolves the copied source`() {
    val run = runInstallerShellRaw(input = "1\nclaude\noff\nskip\n")
    assertEquals(0, run.exitCode, run.output)

    Files.walk(run.repoRoot).use { stream ->
      stream.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
    }
    assertFalse(Files.exists(run.repoRoot), "clone should be deleted for the reinstall check")
    assertTrue(
      Files.isRegularFile(run.home.resolve(".skill-bill/skills/bill-sample/content.md")),
      "copied skill content.md must survive clone deletion",
    )
  }

  @Test
  fun `reconcile failure retries once with clean copied source reset`() {
    val run =
      runInstallerShellRaw(
        input = "1\nclaude\noff\nskip\n",
        scenario = ReconcileScenario(reconcileFailOnce = true),
      )

    assertEquals(0, run.exitCode, run.output)
    assertContains(run.output, "retrying once with a clean copied-source reset")
    assertLiveSourcePopulated(run.home)
    assertFalse(
      Files.exists(run.home.resolve(".skill-bill/.candidate-source")),
      "the retry must still reap the staged candidate source after apply",
    )
  }

  private fun assertLiveSourcePopulated(home: Path) {
    val stateDir = home.resolve(".skill-bill")
    listOf(stateDir.resolve("skills"), stateDir.resolve("platform-packs"), stateDir.resolve("orchestration"))
      .forEach { dir ->
        assertTrue(Files.isDirectory(dir), "copy-in must create real directory $dir")
        assertFalse(Files.isSymbolicLink(dir), "copy-in must create REAL files, not a symlink: $dir")
      }
    assertTrue(
      Files.isRegularFile(stateDir.resolve("skills/bill-sample/content.md")),
      "copy-in must materialize skill content.md under the copy",
    )
    assertTrue(
      Files.isRegularFile(stateDir.resolve("orchestration/review-orchestrator/PLAYBOOK.md")),
      "copy-in must materialize the WHOLE orchestration tree under the copy",
    )
  }

  private data class InstallerShellRawRun(
    val repoRoot: Path,
    val home: Path,
    val binDir: Path,
    val exitCode: Int,
    val output: String,
  )

  private data class ReconcileScenario(
    val mutateUpstream: ((Path) -> Unit)? = null,
    val reconcileFailOnce: Boolean = false,
  )

  private fun runInstallerShellRaw(
    input: String,
    reuse: InstallerShellRawRun? = null,
    scenario: ReconcileScenario = ReconcileScenario(),
  ): InstallerShellRawRun {
    val repoRoot = Files.createTempDirectory("skillbill-reconcile-shell-repo")
    val home = reuse?.home ?: Files.createTempDirectory("skillbill-reconcile-shell-home")
    val binDir = reuse?.binDir ?: Files.createTempDirectory("skillbill-reconcile-shell-bin")
    val releaseDir = Files.createTempDirectory("skillbill-reconcile-shell-release")
    val logPath = Files.createTempFile("skillbill-reconcile-shell-runtime", ".log")
    Files.writeString(repoRoot.resolve("install.sh"), Files.readString(runtimeRoot.parent.resolve("install.sh")))
    repoRoot.resolve("install.sh").toFile().setExecutable(true)
    InstallerShellFixtures.seedAuthoredSource(repoRoot)
    InstallerShellFixtures.seedAgentAddon(repoRoot)
    InstallerShellFixtures.seedInstallerPlatformPack(repoRoot, "kotlin")
    InstallerShellFixtures.seedInstallerRuntime(repoRoot)
    stageRelease(releaseDir, releaseValid = true, omitRuntimeAssets = false)
    scenario.mutateUpstream?.invoke(repoRoot)

    val process =
      ProcessBuilder("bash", repoRoot.resolve("install.sh").toString())
        .directory(repoRoot.toFile())
        .redirectErrorStream(true)
        .apply {
          environment()["HOME"] = home.toString()
          environment()["SKILL_BILL_BIN_DIR"] = binDir.toString()
          environment()["SKILL_BILL_DESKTOP_APP_DIR"] = home.resolve("desktop").toString()
          environment()["SKILL_BILL_SKIP_RUNTIME_DISTRIBUTION_BUILD"] = "1"
          environment()["SKILL_BILL_SKIP_PREINSTALL_UNINSTALL"] = "1"
          environment()["SKILL_BILL_TEST_RUNTIME_LOG"] = logPath.toString()
          environment()["SKILL_BILL_RELEASE_DIR"] = releaseDir.toString()
          environment().remove("SKILL_BILL_GOAL_CONTINUATION")
          environment().remove("DISPLAY")
          environment().remove("WAYLAND_DISPLAY")
          environment().useOfflineDesktopRelease(home)
          if (scenario.reconcileFailOnce) {
            environment()["SKILL_BILL_FAKE_RECONCILE_FAIL_ONCE"] = "1"
          }
        }
        .start()
    process.outputStream.bufferedWriter().use { writer -> writer.write(input) }
    val output = process.inputStream.bufferedReader().readText()
    val exitCode = process.waitFor()
    return InstallerShellRawRun(repoRoot, home, binDir, exitCode, output)
  }
}
