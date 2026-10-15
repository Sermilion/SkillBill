package skillbill.installer

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class InstallerShellDelegationTest {
  @Test
  fun `uninstaller removes desktop app install and launcher`() {
    val run = runUninstallerShellWithDesktopInstall()

    assertEquals(0, run.exitCode, run.output)
    assertFalse(Files.exists(run.appTarget), "desktop app should be removed")
    assertFalse(Files.exists(run.launcherPath), "desktop launcher should be removed")
  }

  @Test
  fun `uninstaller removes desktop app before runtime dependent cleanup`() {
    val run = runUninstallerShellWithDesktopInstall(seedRuntime = false)

    assertFalse(run.exitCode == 0, "runtime-dependent cleanup should still fail without a runtime")
    assertFalse(Files.exists(run.appTarget), "desktop app should be removed")
    assertFalse(Files.exists(run.launcherPath), "desktop launcher should be removed")
  }

  @Test
  fun `installer shell builds all-pack all-agent runtime apply argv`() {
    val run = runInstallerShell(input = "1\nall\noff\nskip\n")

    assertEquals(
      expectedApplyArgs(
        ExpectedApply(run, agentMode = "manual", platformMode = "all", telemetry = "off", mcp = "register"),
      ) +
        listOf(
          "--agent",
          "claude",
          "--agent",
          "codex",
          "--agent-target",
          "codex=${run.home.resolve("agent-targets/codex")}",
          "--agent",
          "junie",
          "--agent-target",
          "junie=${run.home.resolve("agent-targets/junie")}",
          "--agent",
          "cursor",
          "--agent-target",
          "cursor=${run.home.resolve("agent-targets/cursor")}",
        ),
      run.applyArgs,
    )
    assertCopyInPopulatedRealFiles(run)
  }

  @Test
  fun `installer copy-in materializes self-contained source so deleting the clone keeps skills resolving`() {
    val run = runInstallerShell(input = "1\nclaude\noff\nskip\n")

    assertCopyInPopulatedRealFiles(run)

    Files.walk(run.repoRoot).use { stream ->
      stream.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
    }
    assertFalse(Files.exists(run.repoRoot), "clone should be deleted for the AC-3 check")
    val copiedSkill = run.home.resolve(".skill-bill/skills/bill-sample/content.md")
    assertTrue(
      Files.isRegularFile(copiedSkill),
      "AC-3: copied skill content.md must survive clone deletion at $copiedSkill",
    )
    assertTrue(
      Files.isRegularFile(run.home.resolve(".skill-bill/orchestration/review-orchestrator/PLAYBOOK.md")),
      "AC-3: copied orchestration playbook must survive clone deletion",
    )
    assertTrue(
      Files.isRegularFile(run.home.resolve(".skill-bill/agent-addons/review-helper/content.md")),
      "AC-3: copied agent add-on source must survive clone deletion",
    )
  }

  @Test
  fun `pre-install wipe preserves copied source reserved baseline and state dbs`() {
    val fixtures = seedStateDirForWipe()
    val journalsAndBackup =
      listOf(
        fixtures.stateDb.resolveSibling("review-metrics.db-wal"),
        fixtures.stateDb.resolveSibling("review-metrics.db-shm"),
        fixtures.stateDb.resolveSibling("review-metrics.db-journal"),
        fixtures.stateDir.resolve("backups/baseline/review-metrics.db"),
        fixtures.stateDir.resolve("backups/baseline/reset-report.json"),
      )
    journalsAndBackup.forEach { path ->
      Files.createDirectories(path.parent)
      Files.writeString(path, "preserved ${path.fileName}")
    }
    val run = runUninstaller(fixtures, preserveSource = true, goalContinuation = false)

    assertEquals(0, run.exitCode, run.output)

    assertTrue(Files.isRegularFile(fixtures.skillContent), "skills/ must be preserved under preserve-wipe")
    assertTrue(Files.isRegularFile(fixtures.packYaml), "platform-packs/ must be preserved under preserve-wipe")
    assertTrue(Files.isRegularFile(fixtures.orchestrationPlaybook), "orchestration/ must be preserved")
    assertTrue(Files.isRegularFile(fixtures.baselineManifest), "reserved baseline-manifest path must be preserved")

    assertFalse(Files.exists(fixtures.runtimeBin), "runtime/ must be cleared under preserve-wipe")
    assertFalse(Files.exists(fixtures.installedSkill), "installed-skills/ must be cleared under preserve-wipe")
    assertTrue(Files.isRegularFile(fixtures.stateDb), "*.db state DBs must be preserved under preserve-wipe")
    journalsAndBackup.forEach { path ->
      assertEquals("preserved ${path.fileName}", Files.readString(path), "Database recovery data must survive cleanup")
    }
  }

  @Test
  fun `pre-install wipe continues when native subagent unlink cleanup fails`() {
    val fixtures = seedStateDirForWipe(failingNativeUnlinkCommand = "install unlink-claude-agents")
    val run = runUninstaller(fixtures, preserveSource = true, goalContinuation = false)

    assertEquals(0, run.exitCode, run.output)
    assertContains(run.output, "Claude subagent cleanup failed; continuing uninstall so reinstall can recover.")
    assertFalse(Files.exists(fixtures.runtimeBin), "runtime/ must still be cleared after native cleanup failure")
    assertFalse(
      Files.exists(fixtures.installedSkill),
      "installed-skills/ must still be cleared after native cleanup failure",
    )
    assertTrue(Files.isRegularFile(fixtures.skillContent), "copied source must still be preserved")
  }

  @Test
  fun `goal continuation exit-64 guard preserves the entire state dir including the active goal db`() {
    val fixtures = seedStateDirForWipe()
    val run = runUninstaller(fixtures, preserveSource = true, goalContinuation = true)

    assertEquals(64, run.exitCode, run.output)
    assertContains(run.output, "Refusing to run uninstall.sh during skill-bill goal-continuation")
    assertTrue(
      Files.isRegularFile(fixtures.stateDb),
      "active goal *.db must survive untouched when the exit-64 guard fires",
    )
    assertTrue(Files.isRegularFile(fixtures.runtimeBin), "exit-64 guard must abort before any removal")
  }

  @Test
  fun `explicit uninstall fully removes the state dir even with copied source present`() {
    val fixtures = seedStateDirForWipe()
    val run = runUninstaller(fixtures, preserveSource = false, goalContinuation = false)

    assertEquals(0, run.exitCode, run.output)
    assertFalse(Files.exists(fixtures.stateDir), "explicit uninstall must fully remove ~/.skill-bill")
  }

  @Test
  fun `installer shell installs all packs without consuming a platform prompt`() {
    val run = runInstallerShell(input = "1\ncodex\nfull\nskip\n")

    assertEquals(
      expectedApplyArgs(
        ExpectedApply(run, agentMode = "manual", platformMode = "all", telemetry = "full", mcp = "register"),
      ) +
        listOf(
          "--agent",
          "codex",
          "--agent-target",
          "codex=${run.home.resolve("agent-targets/codex")}",
        ),
      run.applyArgs,
    )
    assertFalse(run.output.contains("Enter platforms"))
    assertFalse(run.output.contains("Available optional platforms"))
  }

  @Test
  fun `installer shell builds detected all-platform runtime apply argv`() {
    val run = runInstallerShell(input = "detected\nanonymous\nskip\n")

    assertEquals(
      expectedApplyArgs(
        ExpectedApply(run, agentMode = "detected", platformMode = "all", telemetry = "anonymous", mcp = "register"),
      ),
      run.applyArgs,
    )
  }

  @Test
  fun `prebuilt default installs runtime from staged release without gradle`() {
    val run = runPrebuiltInstaller(releaseValid = true)

    assertEquals(0, run.exitCode, run.output)

    assertFalse(run.output.contains("gradlew"), "prebuilt install must not call Gradle. Output:\n${run.output}")
    assertFalse(
      run.runtimeLog.contains("installDist"),
      "prebuilt install must not run a Gradle installDist build",
    )
    assertContains(run.output, "verified checksum:")
    assertContains(run.output, "Kotlin runtime installed from prebuilt release")

    assertTrue(
      Files.isExecutable(run.home.resolve(".skill-bill/runtime/runtime-cli/bin/runtime-cli")),
      "runtime-cli should be installed from the staged release",
    )
    assertTrue(
      Files.isExecutable(run.home.resolve(".skill-bill/runtime/runtime-mcp/bin/runtime-mcp")),
      "runtime-mcp should be installed from the staged release",
    )
  }

  @Test
  fun `prebuilt install fails loudly on checksum mismatch with no partial install`() {
    val run = runPrebuiltInstaller(releaseValid = false)

    assertFalse(run.exitCode == 0, "checksum mismatch must fail non-zero. Output:\n${run.output}")
    assertContains(run.output, "Checksum mismatch")

    assertFalse(
      Files.exists(run.home.resolve(".skill-bill/runtime/runtime-cli")),
      "no partial runtime-cli should be installed on checksum failure",
    )
  }

  @Test
  fun `from-source keeps gradle skip-build install behavior`() {
    val run = runInstallerShell(input = "1\nclaude\noff\nskip\n", fromSource = true)

    assertContains(run.output, "Installing runtime from source (--from-source)")
    assertFalse(run.output.contains("verified checksum:"), "from-source must not verify release checksums")
  }

  @Test
  fun `source desktop build installs the host bundle and executable layout`() {
    val launchers =
      listOf(
        "Darwin" to "Skill Bill.app/Contents/MacOS/Skill Bill",
        "Linux" to "Skill Bill/bin/Skill Bill",
        "MINGW64_NT-10.0" to "Skill Bill/bin/Skill Bill.bat",
      )
    launchers.forEach { (host, launcher) ->
      val run = runInstallerShell(input = "1\nclaude\noff\nskip\n", fromSource = true, hostUname = host)

      val installed = run.home.resolve("desktop-apps").resolve(launcher)
      assertTrue(Files.isRegularFile(installed), "Missing desktop launcher for $host: $installed\n${run.output}")
      if (host != "MINGW64_NT-10.0") assertTrue(Files.isExecutable(installed), installed.toString())
    }
  }

  @Test
  fun `prebuilt auto-falls back to source when host token has no matching asset`() {
    val run = runPrebuiltInstaller(releaseValid = true, options = PrebuiltOptions(omitRuntimeAssets = true))

    assertEquals(0, run.exitCode, run.output)
    assertContains(run.output, "falling back to a from-source Gradle build")
  }

  @Test
  fun `install plan summary is printed before any mutation`() {
    val run =
      runPrebuiltInstaller(
        releaseValid = true,
        options = PrebuiltOptions(skipPreinstallUninstall = false, seedPriorInstall = true),
      )

    assertEquals(0, run.exitCode, run.output)
    val planIndex = run.output.indexOf("What this installer will change")
    val cleanupIndex = run.output.indexOf("Pre-install cleanup")
    assertTrue(planIndex >= 0, "install plan must be printed. Output:\n${run.output}")
    assertTrue(cleanupIndex >= 0, "pre-install cleanup must run. Output:\n${run.output}")
    assertTrue(planIndex < cleanupIndex, "the plan must print before the first mutation")
    assertContains(run.output, "Reverse everything with:")
  }
}
