package skillbill.installer

import org.junit.jupiter.api.Assumptions
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

internal val installerShellRuntimeRoot: Path =
  Path.of("").toAbsolutePath().normalize().let { workingDir ->
    if (workingDir.fileName.toString().startsWith("runtime-")) {
      workingDir.parent
    } else {
      workingDir
    }
  }

internal fun assertCopyInPopulatedRealFiles(run: InstallerShellRun) {
  val stateDir = run.home.resolve(".skill-bill")
  val skills = stateDir.resolve("skills")
  val packs = stateDir.resolve("platform-packs")
  val orchestration = stateDir.resolve("orchestration")
  val agentAddons = stateDir.resolve("agent-addons")
  listOf(skills, packs, orchestration, agentAddons).forEach { dir ->
    assertTrue(Files.isDirectory(dir), "copy-in must create real directory $dir")
    assertFalse(Files.isSymbolicLink(dir), "copy-in must create REAL files, not a symlink: $dir")
  }
  assertTrue(
    Files.isRegularFile(skills.resolve("bill-sample/content.md")),
    "copy-in must materialize skill content.md under the copy",
  )
  assertTrue(
    Files.isRegularFile(orchestration.resolve("review-orchestrator/PLAYBOOK.md")),
    "copy-in must materialize the WHOLE orchestration tree under the copy",
  )
  assertTrue(
    Files.isRegularFile(agentAddons.resolve("review-helper/content.md")),
    "copy-in must materialize agent add-on source under the copy",
  )
}

internal fun runInstallerShell(input: String): InstallerShellRun = runInstallerShell(input, fromSource = false)

internal fun runInstallerShell(
  input: String,
  fromSource: Boolean,
): InstallerShellRun {
  val repoRoot = Files.createTempDirectory("skillbill-installer-shell-repo")
  val home = Files.createTempDirectory("skillbill-installer-shell-home")
  val binDir = Files.createTempDirectory("skillbill-installer-shell-bin")
  val logPath = Files.createTempFile("skillbill-installer-shell-runtime", ".log")
  val installScript = installerShellRuntimeRoot.parent.resolve("install.sh")
  Files.writeString(repoRoot.resolve("install.sh"), Files.readString(installScript))
  repoRoot.resolve("install.sh").toFile().setExecutable(true)
  InstallerShellFixtures.seedAuthoredSource(repoRoot)
  InstallerShellFixtures.seedAgentAddon(repoRoot)
  InstallerShellFixtures.seedInstallerPlatformPack(repoRoot, "kmp")
  InstallerShellFixtures.seedInstallerPlatformPack(repoRoot, "kotlin")
  InstallerShellFixtures.seedInstallerPlatformPack(repoRoot, "python")
  InstallerShellFixtures.seedInstallerRuntime(repoRoot)

  val command = mutableListOf("bash", repoRoot.resolve("install.sh").toString())
  if (fromSource) {
    command.add("--from-source")
  }
  val process =
    ProcessBuilder(command)
      .directory(repoRoot.toFile())
      .redirectErrorStream(true)
      .apply {
        environment()["HOME"] = home.toString()
        environment()["SKILL_BILL_BIN_DIR"] = binDir.toString()
        environment()["SKILL_BILL_SKIP_RUNTIME_DISTRIBUTION_BUILD"] = "1"
        environment()["SKILL_BILL_SKIP_PREINSTALL_UNINSTALL"] = "1"
        environment()["SKILL_BILL_TEST_RUNTIME_LOG"] = logPath.toString()
        environment().remove("SKILL_BILL_GOAL_CONTINUATION")
      }
      .start()
  process.outputStream.bufferedWriter().use { writer -> writer.write(input) }
  val output = process.inputStream.bufferedReader().readText()
  val exitCode = process.waitFor()
  assertEquals(0, exitCode, output)
  val applyCalls =
    parseRuntimeCalls(logPath).filter { args ->
      args.drop(2).take(2) == listOf("install", "apply")
    }
  assertEquals(1, applyCalls.size, "installer must invoke runtime apply exactly once. Output:\n$output")
  return InstallerShellRun(
    repoRoot = repoRoot,
    home = home,
    binDir = binDir,
    applyArgs = applyCalls.single(),
    output = output,
  )
}

internal fun expectedApplyArgs(expected: ExpectedApply): List<String> =
  listOf(
    "--home",
    expected.run.home.toString(),
    "install",
    "apply",
    "--repo-root",
    expected.run.home.resolve(".skill-bill").toString(),
    "--skills",
    expected.run.home.resolve(".skill-bill/skills").toString(),
    "--platform-packs",
    expected.run.home.resolve(".skill-bill/platform-packs").toString(),
    "--agent-mode",
    expected.agentMode,
    "--platform-mode",
    expected.platformMode,
    "--telemetry",
    expected.telemetry,
    "--mcp",
    expected.mcp,
    "--replace-existing-skill-bill-links",
    "--runtime-install-root",
    expected.run.home.resolve(".skill-bill/runtime").toString(),
    "--runtime-cli-build-dir",
    expected.run.repoRoot.resolve("runtime-kotlin/runtime-cli/build/install/runtime-cli").toString(),
    "--runtime-mcp-build-dir",
    expected.run.repoRoot.resolve("runtime-kotlin/runtime-mcp/build/install/runtime-mcp").toString(),
    "--runtime-cli-install-dir",
    expected.run.home.resolve(".skill-bill/runtime/runtime-cli").toString(),
    "--runtime-mcp-install-dir",
    expected.run.home.resolve(".skill-bill/runtime/runtime-mcp").toString(),
    "--runtime-launcher-bin-dir",
    expected.run.binDir.toString(),
    "--runtime-mcp-bin",
    expected.run.home.resolve(".skill-bill/runtime/runtime-mcp/bin/runtime-mcp").toString(),
  )

internal fun runUninstallerShellWithDesktopInstall(seedRuntime: Boolean = true): UninstallerShellRun {
  val repoRoot = Files.createTempDirectory("skillbill-uninstaller-shell-repo")
  val home = Files.createTempDirectory("skillbill-uninstaller-shell-home")
  val binDir = Files.createTempDirectory("skillbill-uninstaller-shell-bin")
  val desktopRoot = Files.createTempDirectory("skillbill-uninstaller-shell-desktop")
  val logPath = Files.createTempFile("skillbill-uninstaller-shell-runtime", ".log")
  val uninstallScript = installerShellRuntimeRoot.parent.resolve("uninstall.sh")
  Files.writeString(repoRoot.resolve("uninstall.sh"), Files.readString(uninstallScript))
  repoRoot.resolve("uninstall.sh").toFile().setExecutable(true)
  Files.createDirectories(repoRoot.resolve("skills/bill-test"))
  Files.writeString(repoRoot.resolve("skills/bill-test/content.md"), "# Test\n")
  InstallerShellFixtures.seedInstallerPlatformPack(repoRoot, "kotlin")
  if (seedRuntime) {
    InstallerShellFixtures.seedUninstallerRuntime(repoRoot)
  }

  val desktopInstall = InstallerShellFixtures.seedDesktopInstall(desktopRoot, binDir)

  val process =
    ProcessBuilder(
      "bash",
      repoRoot.resolve("uninstall.sh").toString(),
      "--desktop-app-dir",
      desktopRoot.toString(),
    )
      .directory(repoRoot.toFile())
      .redirectErrorStream(true)
      .apply {
        environment()["HOME"] = home.toString()
        environment()["SKILL_BILL_BIN_DIR"] = binDir.toString()
        environment()["SKILL_BILL_SKIP_RUNTIME_DISTRIBUTION_BUILD"] = "1"
        environment()["SKILL_BILL_SKIP_PREINSTALL_UNINSTALL"] = "1"
        environment()["SKILL_BILL_TEST_RUNTIME_LOG"] = logPath.toString()
        environment().remove("SKILL_BILL_GOAL_CONTINUATION")
      }
      .start()
  val output = process.inputStream.bufferedReader().readText()
  val exitCode = process.waitFor()

  return UninstallerShellRun(
    appTarget = desktopInstall.appTarget,
    launcherPath = desktopInstall.launcherPath,
    exitCode = exitCode,
    output = output,
  )
}

internal fun seedStateDirForWipe(failingNativeUnlinkCommand: String? = null): WipeFixtures {
  val home = Files.createTempDirectory("skillbill-wipe-home")
  val binDir = Files.createTempDirectory("skillbill-wipe-bin")
  val logPath = Files.createTempFile("skillbill-wipe-runtime", ".log")
  val repoRoot = Files.createTempDirectory("skillbill-wipe-repo")
  val uninstallScript = installerShellRuntimeRoot.parent.resolve("uninstall.sh")
  Files.writeString(repoRoot.resolve("uninstall.sh"), Files.readString(uninstallScript))
  repoRoot.resolve("uninstall.sh").toFile().setExecutable(true)
  InstallerShellFixtures.seedUninstallerRuntime(repoRoot, failingNativeUnlinkCommand)

  val stateDir = home.resolve(".skill-bill")
  val skillContent = stateDir.resolve("skills/bill-sample/content.md")
  val packYaml = stateDir.resolve("platform-packs/kotlin/platform.yaml")
  val orchestrationPlaybook = stateDir.resolve("orchestration/review-orchestrator/PLAYBOOK.md")
  val baselineManifest = stateDir.resolve("baseline-manifest.json")
  val runtimeBin = stateDir.resolve("runtime/runtime-cli/bin/runtime-cli")
  val installedSkill = stateDir.resolve("installed-skills/bill-sample-deadbeef/SKILL.md")
  val stateDb = stateDir.resolve("review-metrics.db")
  listOf(skillContent, packYaml, orchestrationPlaybook, baselineManifest, runtimeBin, installedSkill, stateDb)
    .forEach { path ->
      Files.createDirectories(path.parent)
      Files.writeString(path, "seed\n")
    }
  return WipeFixtures(
    repoRoot = repoRoot,
    home = home,
    binDir = binDir,
    logPath = logPath,
    stateDir = stateDir,
    skillContent = skillContent,
    packYaml = packYaml,
    orchestrationPlaybook = orchestrationPlaybook,
    baselineManifest = baselineManifest,
    runtimeBin = runtimeBin,
    installedSkill = installedSkill,
    stateDb = stateDb,
  )
}

internal fun runUninstaller(
  fixtures: WipeFixtures,
  preserveSource: Boolean,
  goalContinuation: Boolean,
): UninstallRun {
  val process =
    ProcessBuilder("bash", fixtures.repoRoot.resolve("uninstall.sh").toString())
      .directory(fixtures.repoRoot.toFile())
      .redirectErrorStream(true)
      .apply {
        environment()["HOME"] = fixtures.home.toString()
        environment()["SKILL_BILL_BIN_DIR"] = fixtures.binDir.toString()
        environment()["SKILL_BILL_SKIP_RUNTIME_DISTRIBUTION_BUILD"] = "1"
        environment()["SKILL_BILL_TEST_RUNTIME_LOG"] = fixtures.logPath.toString()
        if (preserveSource) {
          environment()["SKILL_BILL_PRESERVE_SOURCE_ON_WIPE"] = "1"
        } else {
          environment().remove("SKILL_BILL_PRESERVE_SOURCE_ON_WIPE")
        }
        if (goalContinuation) {
          environment()["SKILL_BILL_GOAL_CONTINUATION"] = "1"
        } else {
          environment().remove("SKILL_BILL_GOAL_CONTINUATION")
        }
      }
      .start()
  val output = process.inputStream.bufferedReader().readText()
  val exitCode = process.waitFor()
  return UninstallRun(exitCode = exitCode, output = output)
}

internal fun runPrebuiltInstaller(
  releaseValid: Boolean,
  extraArgs: List<String> = emptyList(),
  reuse: PrebuiltReuse? = null,
  options: PrebuiltOptions = PrebuiltOptions(),
): PrebuiltInstallerRun {
  PrebuiltReleaseStager.assumeReleaseStagingTools()
  if (options.interactiveTty) {
    Assumptions.assumeTrue(
      PrebuiltReleaseStager.toolOnPath("script"),
      "interactive-TTY install test requires `script` (util-linux) on PATH",
    )
  }
  val repoRoot = Files.createTempDirectory("skillbill-prebuilt-repo")
  val home = reuse?.home ?: Files.createTempDirectory("skillbill-prebuilt-home")
  val binDir = reuse?.binDir ?: Files.createTempDirectory("skillbill-prebuilt-bin")
  val releaseDir = Files.createTempDirectory("skillbill-prebuilt-release")
  val logPath = Files.createTempFile("skillbill-prebuilt-runtime", ".log")
  seedPrebuiltRepo(repoRoot)
  stageRelease(releaseDir, releaseValid, options.omitRuntimeAssets)

  val command = PrebuiltReleaseStager.buildPrebuiltCommand(repoRoot, extraArgs, options.interactiveTty)
  val builder =
    ProcessBuilder(command)
      .directory(repoRoot.toFile())
      .redirectErrorStream(true)
  builder.environment()["HOME"] = home.toString()
  builder.environment()["SKILL_BILL_BIN_DIR"] = binDir.toString()
  builder.environment()["SKILL_BILL_RELEASE_DIR"] = releaseDir.toString()
  builder.environment()["SKILL_BILL_TEST_RUNTIME_LOG"] = logPath.toString()
  builder.environment().remove("SKILL_BILL_GOAL_CONTINUATION")
  if (options.skipPreinstallUninstall) {
    builder.environment()["SKILL_BILL_SKIP_PREINSTALL_UNINSTALL"] = "1"
  }
  if (options.seedPriorInstall) {
    Files.createDirectories(home.resolve(".skill-bill"))
  }
  val process = builder.start()
  val input = "1\nclaude\nbase only\noff\n"
  process.outputStream.bufferedWriter().use { writer -> writer.write(input) }
  val output = process.inputStream.bufferedReader().readText()
  val exitCode = process.waitFor()
  val runtimeLog = if (Files.exists(logPath)) Files.readString(logPath) else ""

  return PrebuiltInstallerRun(
    home = home,
    binDir = binDir,
    exitCode = exitCode,
    output = output,
    runtimeLog = runtimeLog,
  )
}

internal fun seedPrebuiltRepo(repoRoot: Path) {
  val installScript = installerShellRuntimeRoot.parent.resolve("install.sh")
  Files.writeString(repoRoot.resolve("install.sh"), Files.readString(installScript))
  repoRoot.resolve("install.sh").toFile().setExecutable(true)
  val uninstallScript = installerShellRuntimeRoot.parent.resolve("uninstall.sh")
  Files.writeString(repoRoot.resolve("uninstall.sh"), Files.readString(uninstallScript))
  repoRoot.resolve("uninstall.sh").toFile().setExecutable(true)
  InstallerShellFixtures.seedAuthoredSource(repoRoot)
  InstallerShellFixtures.seedInstallerRuntime(repoRoot)
  InstallerShellFixtures.seedFakeGradlew(repoRoot)
}

internal fun stageRelease(
  releaseDir: Path,
  releaseValid: Boolean,
  omitRuntimeAssets: Boolean,
) {
  PrebuiltReleaseStager.stage(releaseDir, releaseValid, omitRuntimeAssets)
}

internal fun parseRuntimeCalls(logPath: Path): List<List<String>> {
  val calls = mutableListOf<MutableList<String>>()
  Files.readAllLines(logPath).forEach { line ->
    if (line == "CALL") {
      calls.add(mutableListOf())
    } else if (line.startsWith("ARG\t")) {
      calls.last().add(line.removePrefix("ARG\t"))
    }
  }
  return calls
}

internal object InstallerShellFixtures {
  fun seedAgentAddon(repoRoot: Path) {
    val agentAddon = repoRoot.resolve("agent-addons/review-helper")
    Files.createDirectories(agentAddon)
    Files.writeString(
      agentAddon.resolve("agent-addon.yaml"),
      """
      contract_version: "1.0"
      slug: review-helper
      description: Review helper
      agent_ids:
        - codex
      consumers:
        - skill-bill
      """.trimIndent() + "\n",
    )
    Files.writeString(agentAddon.resolve("content.md"), "Review helper.\n")
  }

  private val fakeRuntimeCliLoggingBlock: String =
    """
    |{
    |  echo CALL
    |  for arg in "${'$'}@"; do
    |    printf 'ARG\t%s\n' "${'$'}arg"
    |  done
    |} >> "${'$'}{SKILL_BILL_TEST_RUNTIME_LOG:?}"
    """.trimMargin()

  private val fakeRuntimeCliHomeShiftBlock: String =
    """
    |if [[ "${'$'}{1:-}" == "--home" ]]; then
    |  home="${'$'}2"
    |  shift 2
    |fi
    """.trimMargin()

  private val fakeRuntimeCliAgentPathBlock: String =
    """
    |if [[ "${'$'}{1:-}" == "install" && "${'$'}{2:-}" == "agent-path" ]]; then
    |  printf '%s\n' "${'$'}home/agent-targets/${'$'}3"
    |  exit 0
    |fi
    """.trimMargin()

  private val fakeRuntimeCliApplyBlocks: String =
    """
    |if [[ "${'$'}{1:-}" == "install" && "${'$'}{2:-}" == "apply" ]]; then
    |  exit 0
    |fi
    |if [[ "${'$'}{1:-}" == "install" && "${'$'}{2:-}" == "apply-external-addons" ]]; then
    |  exit 0
    |fi
    """.trimMargin()

  private val fakeRuntimeCliRootBlocks: String =
    """
    |if [[ "${'$'}{1:-}" == "install" && "${'$'}{2:-}" == "claude-roots" ]]; then
    |  printf '%s\n' "${'$'}home/.claude"
    |  exit 0
    |fi
    |if [[ "${'$'}{1:-}" == "install" && "${'$'}{2:-}" == "codex-roots" ]]; then
    |  printf '%s\n' "${'$'}home/.codex"
    |  exit 0
    |fi
    """.trimMargin()

  private val fakeRuntimeCliCleanupCaseBlock: String =
    """
    |case "${'$'}{1:-} ${'$'}{2:-}" in
    |  "install cleanup-agent-target"|"install unlink-codex-agents"|"install unlink-claude-agents"|\
    "install unlink-junie-agents"|"install unlink-cursor-agents"|"install unregister-mcp")
    |    exit 0
    |    ;;
    |esac
    """.trimMargin()

  private val fakeRuntimeMcpScript: String =
    """
    |#!/usr/bin/env bash
    |exit 0
    |
    """.trimMargin()

  private val reconcileFakeCliBlock: String =
    """
    |if [[ "${'$'}{1:-}" == "install" && "${'$'}{2:-}" == "reconcile" ]]; then
    |  fail_once_marker="${'$'}{SKILL_BILL_TEST_RUNTIME_LOG:?}.reconcile-failed-once"
    |  if [[ "${'$'}{SKILL_BILL_FAKE_RECONCILE_FAIL_ONCE:-}" == "1" && ! -e "${'$'}fail_once_marker" ]]; then
    |    printf '%s\n' "synthetic reconcile failure" >&2
    |    touch "${'$'}fail_once_marker"
    |    exit 9
    |  fi
    |  applying=0
    |  if printf '%s ' "${'$'}@" | grep -q -- '--apply'; then applying=1; fi
    |  cand_skills="${'$'}home/.skill-bill/.candidate-source/skills"
    |  cand_packs="${'$'}home/.skill-bill/.candidate-source/platform-packs"
    |  cand_agent_addons="${'$'}home/.skill-bill/.candidate-source/agent-addons"
    |  live_skills="${'$'}home/.skill-bill/skills"
    |  live_packs="${'$'}home/.skill-bill/platform-packs"
    |  live_agent_addons="${'$'}home/.skill-bill/agent-addons"
    |  if [[ "${'$'}applying" -eq 1 ]]; then
    |    if [[ -d "${'$'}cand_skills" ]]; then
    |      mkdir -p "${'$'}live_skills"
    |      for sd in "${'$'}cand_skills"/*/; do
    |        [[ -d "${'$'}sd" ]] || continue
    |        name="${'$'}(basename "${'$'}sd")"
    |        rm -rf "${'$'}live_skills/${'$'}name"
    |        cp -R "${'$'}sd" "${'$'}live_skills/${'$'}name"
    |      done
    |    fi
    |    if [[ -d "${'$'}cand_packs" ]]; then
    |      mkdir -p "${'$'}live_packs"
    |      cp -R "${'$'}cand_packs/." "${'$'}live_packs/"
    |    fi
    |    if [[ -d "${'$'}cand_agent_addons" ]]; then
    |      mkdir -p "${'$'}live_agent_addons"
    |      for addon_dir in "${'$'}cand_agent_addons"/*/; do
    |        [[ -d "${'$'}addon_dir" ]] || continue
    |        name="${'$'}(basename "${'$'}addon_dir")"
    |        rm -rf "${'$'}live_agent_addons/${'$'}name"
    |        cp -R "${'$'}addon_dir" "${'$'}live_agent_addons/${'$'}name"
    |      done
    |    fi
    |    printf '%s\n' '{"version":"1.0"}' > "${'$'}home/.skill-bill/baseline-manifest.json"
    |    printf 'reconcile_outcome: kind=adopt upstream_hash=deadbeefdeadbeef path=skills/bill-sample\n'
    |    printf 'reconcile_summary: applied=true baseline_refreshed=true installed_count=1 pruned_count=0\n'
    |    exit 0
    |  fi
    |  printf 'reconcile_outcome: kind=adopt upstream_hash=deadbeefdeadbeef path=skills/bill-sample\n'
    |  printf 'reconcile_summary: applied=false baseline_refreshed=false installed_count=0 pruned_count=0\n'
    |  exit 0
    |fi
    """.trimMargin()

  private fun fakeRuntimeCliScript(
    homeInit: String,
    middleBlocks: String,
    trailingBlock: String = "",
  ): String =
    """
    |#!/usr/bin/env bash
    |set -euo pipefail
    |if [[ "${'$'}{1:-}" == "--check-packaged-contracts" ]]; then exit 0; fi
    |$fakeRuntimeCliLoggingBlock
    |$homeInit
    |$fakeRuntimeCliHomeShiftBlock
    |$middleBlocks
    |$trailingBlock
    |$fakeRuntimeCliCleanupCaseBlock
    |exit 2
    |
    """.trimMargin()

  private fun writeFakeRuntimeBins(
    repoRoot: Path,
    cliScript: String,
  ) {
    val cliBin = repoRoot.resolve("runtime-kotlin/runtime-cli/build/install/runtime-cli/bin/runtime-cli")
    val mcpBin = repoRoot.resolve("runtime-kotlin/runtime-mcp/build/install/runtime-mcp/bin/runtime-mcp")
    Files.createDirectories(cliBin.parent)
    Files.createDirectories(mcpBin.parent)
    Files.writeString(cliBin, cliScript)
    Files.writeString(mcpBin, fakeRuntimeMcpScript)
    cliBin.toFile().setExecutable(true)
    mcpBin.toFile().setExecutable(true)
  }

  private val fakeInstallerRuntimeMiddleBlocks: String =
    listOf(
      fakeRuntimeCliAgentPathBlock,
      fakeRuntimeCliApplyBlocks,
      fakeRuntimeCliRootBlocks,
      reconcileFakeCliBlock,
    ).joinToString(separator = "\n")

  private val fakeUninstallerRuntimeMiddleBlocks: String =
    listOf(
      fakeRuntimeCliRootBlocks,
      """
      |if [[ "${'$'}{1:-}" == "install" && "${'$'}{2:-}" == "reconcile" ]]; then
      |  printf 'reconcile_summary: applied=false baseline_refreshed=false installed_count=0 pruned_count=0\n'
      |  exit 0
      |fi
      |if [[ "${'$'}{1:-}" == "install" && "${'$'}{2:-}" == "apply-external-addons" ]]; then
      |  exit 0
      |fi
      """.trimMargin(),
    ).joinToString(separator = "\n")

  private val fakeGradlewEmbeddedCliBody: String =
    fakeRuntimeCliScript(
      homeInit = """home=""""",
      middleBlocks =
        listOf(
          fakeRuntimeCliAgentPathBlock,
          """
        |if [[ "${'$'}{1:-}" == "install" && ( "${'$'}{2:-}" == "apply" ||\
         "${'$'}{2:-}" == "apply-external-addons" ) ]]; then
        |  exit 0
        |fi
          """.trimMargin(),
          fakeRuntimeCliRootBlocks,
          """
        |if [[ "${'$'}{1:-}" == "install" && "${'$'}{2:-}" == "reconcile" ]]; then
        |  printf 'reconcile_summary: applied=false baseline_refreshed=false installed_count=0 pruned_count=0\n'
        |  exit 0
        |fi
          """.trimMargin(),
        ).joinToString(separator = "\n"),
    )

  fun seedInstallerRuntime(repoRoot: Path) {
    writeFakeRuntimeBins(
      repoRoot,
      fakeRuntimeCliScript(
        homeInit = """home=""""",
        middleBlocks = fakeInstallerRuntimeMiddleBlocks,
      ),
    )
  }

  fun seedInstallerPlatformPack(
    repoRoot: Path,
    slug: String,
  ) {
    val packRoot = repoRoot.resolve("platform-packs/$slug")
    Files.createDirectories(packRoot)
    Files.writeString(packRoot.resolve("platform.yaml"), "platform: \"$slug\"\n")
  }

  fun seedAuthoredSource(repoRoot: Path) {
    val skillDir = repoRoot.resolve("skills/bill-sample")
    Files.createDirectories(skillDir)
    Files.writeString(
      skillDir.resolve("content.md"),
      "---\nname: bill-sample\ndescription: Sample skill.\n---\n\nBody.\n",
    )
    seedInstallerPlatformPack(repoRoot, "kotlin")
    val orchestrationDir = repoRoot.resolve("orchestration/review-orchestrator")
    Files.createDirectories(orchestrationDir)
    Files.writeString(orchestrationDir.resolve("PLAYBOOK.md"), "# Review orchestrator\n")
  }

  fun seedUninstallerRuntime(
    repoRoot: Path,
    failingNativeUnlinkCommand: String? = null,
  ) {
    val failingNativeUnlinkBlock =
      if (failingNativeUnlinkCommand == null) {
        ""
      } else {
        """
      |if [[ "${'$'}{1:-} ${'$'}{2:-}" == "$failingNativeUnlinkCommand" ]]; then
      |  printf '%s\n' "synthetic native cleanup failure" >&2
      |  exit 9
      |fi
      |
        """.trimMargin()
      }
    writeFakeRuntimeBins(
      repoRoot,
      fakeRuntimeCliScript(
        homeInit = """home="${'$'}{HOME:-}"""",
        middleBlocks = fakeUninstallerRuntimeMiddleBlocks,
        trailingBlock = failingNativeUnlinkBlock,
      ),
    )
  }

  fun seedFakeGradlew(repoRoot: Path) {
    val gradlew = repoRoot.resolve("runtime-kotlin/gradlew")
    Files.createDirectories(gradlew.parent)
    Files.writeString(
      gradlew,
      """
      |#!/usr/bin/env bash
      |set -euo pipefail
      |root="${'$'}(cd "${'$'}(dirname "${'$'}0")" && pwd)"
      |cli_bin="${'$'}root/runtime-cli/build/install/runtime-cli/bin/runtime-cli"
      |mcp_bin="${'$'}root/runtime-mcp/build/install/runtime-mcp/bin/runtime-mcp"
      |mkdir -p "${'$'}(dirname "${'$'}cli_bin")" "${'$'}(dirname "${'$'}mcp_bin")"
      |cat > "${'$'}cli_bin" <<'RUNTIME_CLI'
      |$fakeGradlewEmbeddedCliBody
      |RUNTIME_CLI
      |cat > "${'$'}mcp_bin" <<'RUNTIME_MCP'
      |#!/usr/bin/env bash
      |exit 0
      |RUNTIME_MCP
      |chmod +x "${'$'}cli_bin" "${'$'}mcp_bin"
      """.trimMargin(),
    )
    gradlew.toFile().setExecutable(true)
  }

  fun seedDesktopInstall(
    desktopRoot: Path,
    binDir: Path,
  ): DesktopInstallFixture {
    val os = currentDesktopOs()
    val appTarget =
      when (os) {
        "macos" -> desktopRoot.resolve("SkillBill.app")
        else -> desktopRoot.resolve("SkillBill")
      }
    val executable =
      when (os) {
        "macos" -> appTarget.resolve("Contents/MacOS/SkillBill")
        "windows" -> appTarget.resolve("bin/SkillBill.bat")
        else -> appTarget.resolve("bin/SkillBill")
      }
    Files.createDirectories(executable.parent)
    Files.writeString(executable, "")
    executable.toFile().setExecutable(true)
    return DesktopInstallFixture(appTarget, seedDesktopLauncher(os, binDir, executable))
  }

  private fun seedDesktopLauncher(
    os: String,
    binDir: Path,
    executable: Path,
  ): Path =
    when (os) {
      "windows" -> {
        val launcher = binDir.resolve("skillbill-desktop.cmd")
        Files.writeString(launcher, "@echo off\ncall \"${executable}\" %*\n")
        launcher
      }
      else -> {
        val launcher = binDir.resolve("skillbill-desktop")
        Files.createSymbolicLink(launcher, executable)
        launcher
      }
    }

  private fun currentDesktopOs(): String {
    val osName = System.getProperty("os.name").lowercase()
    return when {
      osName.contains("mac") -> "macos"
      osName.contains("win") -> "windows"
      osName.contains("linux") -> "linux"
      else -> "unknown"
    }
  }
}

internal data class ExpectedApply(
  val run: InstallerShellRun,
  val agentMode: String,
  val platformMode: String,
  val telemetry: String,
  val mcp: String,
)

internal data class InstallerShellRun(
  val repoRoot: Path,
  val home: Path,
  val binDir: Path,
  val applyArgs: List<String>,
  val output: String,
)

internal data class PrebuiltReuse(
  val home: Path,
  val binDir: Path,
)

internal data class PrebuiltOptions(
  val omitRuntimeAssets: Boolean = false,
  val skipPreinstallUninstall: Boolean = true,
  val seedPriorInstall: Boolean = false,
  val interactiveTty: Boolean = false,
)

internal data class PrebuiltInstallerRun(
  val home: Path,
  val binDir: Path,
  val exitCode: Int,
  val output: String,
  val runtimeLog: String,
)

internal data class UninstallerShellRun(
  val appTarget: Path,
  val launcherPath: Path,
  val exitCode: Int,
  val output: String,
)

internal data class DesktopInstallFixture(
  val appTarget: Path,
  val launcherPath: Path,
)

internal data class WipeFixtures(
  val repoRoot: Path,
  val home: Path,
  val binDir: Path,
  val logPath: Path,
  val stateDir: Path,
  val skillContent: Path,
  val packYaml: Path,
  val orchestrationPlaybook: Path,
  val baselineManifest: Path,
  val runtimeBin: Path,
  val installedSkill: Path,
  val stateDb: Path,
)

internal data class UninstallRun(
  val exitCode: Int,
  val output: String,
)

internal object PrebuiltReleaseStager {
  private const val VERSION = "9.9.9"

  fun hostToken(): String {
    val os =
      when {
        System.getProperty("os.name").lowercase().contains("mac") -> "macos"
        System.getProperty("os.name").lowercase().contains("win") -> "windows"
        System.getProperty("os.name").lowercase().contains("linux") -> "linux"
        else -> "unknown"
      }
    val arch =
      when (val raw = System.getProperty("os.arch").lowercase()) {
        "aarch64", "arm64" -> "arm64"
        "x86_64", "amd64" -> "x64"
        else -> raw
      }
    return "$os-$arch"
  }

  fun assumeReleaseStagingTools() {
    Assumptions.assumeTrue(
      toolOnPath("bsdtar"),
      "staged-release runtime image zips require bsdtar on PATH",
    )
  }

  fun toolOnPath(tool: String): Boolean =
    (System.getenv("PATH") ?: "")
      .split(File.pathSeparatorChar)
      .filter { it.isNotEmpty() }
      .any { dir -> Files.isExecutable(Path.of(dir).resolve(tool)) }

  fun buildPrebuiltCommand(
    repoRoot: Path,
    extraArgs: List<String>,
    interactiveTty: Boolean,
  ): List<String> {
    val installArgs = mutableListOf(repoRoot.resolve("install.sh").toString())
    installArgs.addAll(extraArgs)
    if (!interactiveTty) {
      return listOf("bash") + installArgs
    }
    val quoted = (listOf("bash") + installArgs).joinToString(" ") { "'${it.replace("'", "'\\''")}'" }
    return listOf("script", "-qec", quoted, "/dev/null")
  }

  fun stage(
    releaseDir: Path,
    releaseValid: Boolean,
    omitRuntimeAssets: Boolean,
  ) {
    val token = hostToken()
    if (!omitRuntimeAssets) {
      val cliZip = releaseDir.resolve("runtime-cli-$VERSION-$token.zip")
      val mcpZip = releaseDir.resolve("runtime-mcp-$VERSION-$token.zip")
      writeRuntimeImageZip(cliZip, "runtime-cli")
      writeRuntimeImageZip(mcpZip, "runtime-mcp")
      writeChecksumSibling(cliZip, corrupt = !releaseValid)
      writeChecksumSibling(mcpZip, corrupt = false)
    }
  }

  private fun writeRuntimeImageZip(
    zipPath: Path,
    base: String,
  ) {
    val staging = Files.createTempDirectory("skillbill-image-$base")
    val binDir = staging.resolve("$base/bin")
    Files.createDirectories(binDir)
    val launcher = binDir.resolve(base)
    Files.writeString(launcher, if (base == "runtime-cli") stagedCliStub else "#!/usr/bin/env bash\nexit 0\n")
    launcher.toFile().setExecutable(true)
    val lib = staging.resolve("$base/lib")
    Files.createDirectories(lib)
    Files.writeString(lib.resolve("$base.jar"), "stub")
    runOrThrow(staging, listOf("bsdtar", "-a", "-cf", zipPath.toString(), base))
  }

  private fun writeChecksumSibling(
    asset: Path,
    corrupt: Boolean,
  ) {
    val digest = MessageDigest.getInstance("SHA-256")
    val hexReal = digest.digest(Files.readAllBytes(asset)).joinToString("") { "%02x".format(it) }
    val hex = if (corrupt) "0".repeat(64) else hexReal
    Files.writeString(asset.resolveSibling("${asset.fileName}.sha256"), "$hex  ${asset.fileName}\n")
  }

  private fun runOrThrow(
    cwd: Path,
    command: List<String>,
  ) {
    val process = ProcessBuilder(command).directory(cwd.toFile()).redirectErrorStream(true).start()
    val out = process.inputStream.bufferedReader().readText()
    check(process.waitFor() == 0) { "command failed: ${command.joinToString(" ")}\n$out" }
  }

  private val stagedCliStub =
    """
    |#!/usr/bin/env bash
    |set -euo pipefail
    |if [[ "${'$'}{1:-}" == "--check-packaged-contracts" ]]; then exit 0; fi
    |if [[ -n "${'$'}{SKILL_BILL_TEST_RUNTIME_LOG:-}" ]]; then
    |  {
    |    echo CALL
    |    for arg in "${'$'}@"; do
    |      printf 'ARG\t%s\n' "${'$'}arg"
    |    done
    |  } >> "${'$'}SKILL_BILL_TEST_RUNTIME_LOG"
    |fi
    |home=""
    |if [[ "${'$'}{1:-}" == "--home" ]]; then
    |  home="${'$'}2"
    |  shift 2
    |fi
    |if [[ "${'$'}{1:-}" == "install" && "${'$'}{2:-}" == "agent-path" ]]; then
    |  printf '%s\n' "${'$'}home/agent-targets/${'$'}3"
    |  exit 0
    |fi
    |if [[ "${'$'}{1:-}" == "install" && "${'$'}{2:-}" == "apply" ]]; then
    |  exit 0
    |fi
    |if [[ "${'$'}{1:-}" == "install" && "${'$'}{2:-}" == "reconcile" ]]; then
    |  printf 'reconcile_summary: applied=false baseline_refreshed=false installed_count=0 pruned_count=0\n'
    |  exit 0
    |fi
    |exit 0
    |
    """.trimMargin()
}
