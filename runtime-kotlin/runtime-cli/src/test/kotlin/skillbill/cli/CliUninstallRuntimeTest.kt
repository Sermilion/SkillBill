package skillbill.cli

import skillbill.cli.core.CliRuntime
import skillbill.cli.model.CliRuntimeContext
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CliUninstallRuntimeTest {
  @Test
  fun `dry run reports uninstall plan without removing files`() {
    val fixture = uninstallFixture()

    val result = runUninstall(fixture.home, "--dry-run")

    assertEquals(0, result.exitCode, result.stdout)
    assertEquals(
      "uninstall_status: dry_run\n" +
        "state_root: ${fixture.stateRoot}\n" +
        "agent_targets: 7\n" +
        "skill_names: 1\n",
      result.stdout,
    )
    assertTrue(Files.exists(fixture.stateRoot))
    assertTrue(Files.exists(fixture.managedSkillDir))
    assertTrue(Files.isSymbolicLink(fixture.skillBillLauncher))
  }

  @Test
  fun `uninstall aborts without confirmation`() {
    val fixture = uninstallFixture()

    val liveStdout = StringBuilder()

    val result = runUninstall(fixture.home, stdinText = "no\n", liveStdout = { liveStdout.append(it) })

    assertEquals(1, result.exitCode, result.stdout)
    assertContains(liveStdout.toString(), fixture.stateRoot.toString())
    assertContains(liveStdout.toString(), "Continue? [y/N]")
    assertEquals("uninstall_status: aborted\n", result.stdout)
    assertTrue(Files.exists(fixture.stateRoot))
    assertTrue(Files.exists(fixture.managedSkillDir))
  }

  @Test
  fun `uninstall refuses during goal continuation`() {
    val home = Files.createTempDirectory("skillbill-cli-uninstall-goal")

    val result =
      CliRuntime.run(
        listOf("--home", home.toString(), "uninstall", "--yes"),
        CliRuntimeContext(
          userHome = home,
          environment = mapOf("SKILL_BILL_GOAL_CONTINUATION" to "1"),
        ),
      )

    assertEquals(64, result.exitCode, result.stdout)
    assertContains(result.stdout, "Refusing to run skill-bill uninstall during skill-bill goal-continuation.")
  }

  @Test
  fun `uninstall preserves a launcher symlink with an unexpected target`() {
    val fixture = uninstallFixture()
    Files.delete(fixture.skillBillLauncher)
    Files.createSymbolicLink(fixture.skillBillLauncher, fixture.userLauncher)

    val result = runUninstall(fixture.home, "--yes")

    assertEquals(0, result.exitCode, result.stdout)
    assertTrue(Files.isSymbolicLink(fixture.skillBillLauncher))
    assertEquals(fixture.userLauncher, Files.readSymbolicLink(fixture.skillBillLauncher))
  }

  @Test
  fun `uninstall uses the selected home instead of the embedding context home`() {
    val selected = uninstallFixture()
    val contextHome = Files.createTempDirectory("skillbill-cli-uninstall-context-home")

    val result =
      CliRuntime.run(
        listOf("--home", selected.home.toString(), "uninstall", "--yes"),
        CliRuntimeContext(
          userHome = contextHome,
          environment = emptyMap(),
        ),
      )

    assertEquals(0, result.exitCode, result.stdout)
    assertFalse(Files.exists(selected.stateRoot))
    assertFalse(Files.exists(contextHome.resolve(".skill-bill")))
  }

  @Test
  fun `uninstall removes managed install artifacts and preserves user files`() {
    val fixture = uninstallFixture()

    val result = runUninstall(fixture.home, "--yes")

    assertEquals(0, result.exitCode, result.stdout)
    assertContains(result.stdout, "uninstall_status: completed")
    assertFalse(Files.exists(fixture.stateRoot))
    assertFalse(Files.exists(fixture.managedSkillDir))
    assertFalse(Files.exists(fixture.legacySkillDir))
    assertFalse(Files.exists(fixture.ownedSymlink))
    assertFalse(Files.exists(fixture.skillBillLauncher))
    assertFalse(Files.exists(fixture.skillBillMcpLauncher))
    assertTrue(Files.exists(fixture.userSkillDir))
    assertTrue(Files.exists(fixture.userLauncher))
  }

  @Test
  fun `uninstall removes the skill-bill and bill-feature links and keeps an unrelated user skill`() {
    val fixture = uninstallFixture()
    val codexSkills = fixture.home.resolve(".codex/skills")
    val installedSkills = fixture.stateRoot.resolve("installed-skills")
    val dispatcherLink = codexSkills.resolve("skill-bill")
    val featureLink = codexSkills.resolve("bill-feature")
    val mySkill = codexSkills.resolve("my-skill")
    listOf("skill-bill", "bill-feature").forEach { name ->
      val staged = Files.createDirectories(installedSkills.resolve("$name-2222222222222222"))
      Files.writeString(staged.resolve("SKILL.md"), "# $name\n")
      Files.createSymbolicLink(codexSkills.resolve(name), staged)
    }
    Files.createDirectories(mySkill)
    Files.writeString(mySkill.resolve("SKILL.md"), "# my-skill\n")

    val result = runUninstall(fixture.home, "--yes")

    assertEquals(0, result.exitCode, result.stdout)
    assertFalse(Files.exists(dispatcherLink, LinkOption.NOFOLLOW_LINKS))
    assertFalse(Files.exists(featureLink, LinkOption.NOFOLLOW_LINKS))
    assertTrue(Files.isRegularFile(mySkill.resolve("SKILL.md")))
  }

  private fun runUninstall(
    home: Path,
    vararg args: String,
    stdinText: String? = null,
    liveStdout: (String) -> Unit = {},
  ) = CliRuntime.run(
    listOf("--home", home.toString(), "uninstall") + args,
    CliRuntimeContext(
      userHome = home,
      stdinText = stdinText,
      environment = mapOf("HOME" to home.toString()),
      liveStdout = liveStdout,
    ),
  )

  private fun uninstallFixture(): UninstallFixture {
    val home = Files.createTempDirectory("skillbill-cli-uninstall")
    val stateRoot = home.resolve(".skill-bill")
    val installedSkill = stateRoot.resolve("installed-skills/bill-code-review-1111111111111111")
    val runtimeCli = stateRoot.resolve("runtime/runtime-cli/bin/runtime-cli")
    val runtimeMcp = stateRoot.resolve("runtime/runtime-mcp/bin/runtime-mcp")
    Files.createDirectories(installedSkill)
    Files.writeString(installedSkill.resolve("SKILL.md"), "# bill-code-review\n")
    Files.createDirectories(runtimeCli.parent)
    Files.writeString(runtimeCli, "runtime cli\n")
    Files.createDirectories(runtimeMcp.parent)
    Files.writeString(runtimeMcp, "runtime mcp\n")

    val codexSkills = home.resolve(".codex/skills")
    val managedSkillDir = codexSkills.resolve("bill-code-review")
    val legacySkillDir = codexSkills.resolve("mdp-code-review")
    val userSkillDir = codexSkills.resolve("user-skill")
    val ownedSymlink = codexSkills.resolve("bill-old-orphan")
    Files.createDirectories(managedSkillDir)
    Files.writeString(managedSkillDir.resolve("Managed by skill-bill install.sh"), "")
    Files.createDirectories(legacySkillDir)
    Files.writeString(legacySkillDir.resolve("Managed by skill-bill install.sh"), "")
    Files.createDirectories(userSkillDir)
    Files.writeString(userSkillDir.resolve("README.md"), "user owned\n")
    Files.createSymbolicLink(ownedSymlink, installedSkill)

    val binDir = home.resolve(".local/bin")
    Files.createDirectories(binDir)
    val skillBillLauncher = binDir.resolve("skill-bill")
    val skillBillMcpLauncher = binDir.resolve("skill-bill-mcp")
    val userLauncher = binDir.resolve("skill-bill-user")
    Files.createSymbolicLink(skillBillLauncher, runtimeCli)
    Files.createSymbolicLink(skillBillMcpLauncher, runtimeMcp)
    Files.writeString(userLauncher, "user launcher\n")

    return UninstallFixture(
      home = home,
      stateRoot = stateRoot,
      managedSkillDir = managedSkillDir,
      legacySkillDir = legacySkillDir,
      userSkillDir = userSkillDir,
      ownedSymlink = ownedSymlink,
      skillBillLauncher = skillBillLauncher,
      skillBillMcpLauncher = skillBillMcpLauncher,
      userLauncher = userLauncher,
    )
  }
}

private data class UninstallFixture(
  val home: Path,
  val stateRoot: Path,
  val managedSkillDir: Path,
  val legacySkillDir: Path,
  val userSkillDir: Path,
  val ownedSymlink: Path,
  val skillBillLauncher: Path,
  val skillBillMcpLauncher: Path,
  val userLauncher: Path,
)
