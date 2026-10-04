package skillbill.installer

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class InstallerShellReuseLastSelectionTest {
  private val repoRoot: Path =
    Path.of("").toAbsolutePath().normalize().let { workingDir ->
      if (workingDir.fileName.toString().startsWith("runtime-")) {
        workingDir.parent
      } else {
        workingDir
      }
    }.parent

  @Test
  fun `installer shell reuses last selection without prompting and applies fresh mcp path`() {
    val run = runInstaller(extraArgs = listOf("--reuse-last-selection"))

    assertEquals(0, run.exitCode, run.output)
    assertContains(run.output, "Reusing latest successful install selections")
    assertContains(run.output, "Selections:     reused latest successful install selection")
    assertEquals(
      expectedApplyArgs(run) +
        listOf(
          "--agent",
          "codex",
          "--agent-target",
          "codex=${run.home.resolve("agent-targets/codex")}",
          "--platform",
          "kotlin",
        ),
      run.applyArgs,
    )
  }

  @Test
  fun `installer shell reuse preserves current telemetry config over stale saved selection`() {
    val run =
      runInstaller(
        extraArgs = listOf("--reuse-last-selection"),
        options =
          InstallerRunOptions(
            replayTelemetryLevel = "anonymous",
            currentTelemetryLevel = "full",
          ),
      )

    assertEquals(0, run.exitCode, run.output)
    assertContains(run.output, "Preserving current telemetry config level 'full'")
    assertEquals("full", run.applyArgs[run.applyArgs.indexOf("--telemetry") + 1])
  }

  @Test
  fun `installer shell reuse aborts when current telemetry config cannot be read`() {
    val run =
      runInstaller(
        extraArgs = listOf("--reuse-last-selection"),
        options =
          InstallerRunOptions(
            telemetryStatusFails = true,
            skipPreinstallUninstall = false,
          ),
      )

    assertFalse(run.exitCode == 0, "installer should fail. Output:\n${run.output}")
    assertContains(run.output, "current telemetry configuration could not be read or validated")
    assertFalse(run.output.contains("Pre-install cleanup"), "cleanup must not run after telemetry read failure")
  }

  @Test
  fun `installer shell reuse failure exits before cleanup`() {
    val run =
      runInstaller(
        extraArgs = listOf("--reuse-last-selection"),
        options =
          InstallerRunOptions(
            reuseSelection = "missing",
            skipPreinstallUninstall = false,
          ),
      )

    assertFalse(run.exitCode == 0, "installer should fail. Output:\n${run.output}")
    assertContains(run.output, "Cannot reuse saved install selections")
    assertContains(run.output, "Run ./install.sh without --reuse-last-selection")
    assertFalse(run.output.contains("Pre-install cleanup"), "cleanup must not run after reuse validation failure")
  }

  private fun runInstaller(
    extraArgs: List<String>,
    options: InstallerRunOptions = InstallerRunOptions(),
  ): InstallerRun {
    val testRepo = Files.createTempDirectory("skillbill-installer-reuse-repo")
    val home = Files.createTempDirectory("skillbill-installer-reuse-home")
    val binDir = Files.createTempDirectory("skillbill-installer-reuse-bin")
    val logPath = Files.createTempFile("skillbill-installer-reuse-runtime", ".log")
    seedRepo(testRepo)
    if (options.currentTelemetryLevel != null) {
      val configPath = home.resolve(".config/skill-bill/config.json")
      Files.createDirectories(configPath.parent)
      Files.writeString(configPath, """{"telemetry":{"level":"${options.currentTelemetryLevel}"}}""")
    }

    val command = mutableListOf("bash", testRepo.resolve("install.sh").toString()).apply { addAll(extraArgs) }
    val process =
      ProcessBuilder(command)
        .directory(testRepo.toFile())
        .redirectErrorStream(true)
        .apply {
          environment()["HOME"] = home.toString()
          environment()["SKILL_BILL_BIN_DIR"] = binDir.toString()
          environment()["SKILL_BILL_SKIP_RUNTIME_DISTRIBUTION_BUILD"] = "1"
          environment()["SKILL_BILL_TEST_RUNTIME_LOG"] = logPath.toString()
          environment()["SKILL_BILL_TEST_REUSE_SELECTION"] = options.reuseSelection
          environment()["SKILL_BILL_TEST_REPLAY_TELEMETRY"] = options.replayTelemetryLevel
          options.currentTelemetryLevel?.let { environment()["SKILL_BILL_TEST_CURRENT_TELEMETRY"] = it }
          if (options.telemetryStatusFails) environment()["SKILL_BILL_TEST_TELEMETRY_STATUS_FAILS"] = "1"
          if (options.skipPreinstallUninstall) {
            environment()["SKILL_BILL_SKIP_PREINSTALL_UNINSTALL"] = "1"
          }
          environment().remove("DISPLAY")
          environment().remove("WAYLAND_DISPLAY")
          environment().remove("SKILL_BILL_GOAL_CONTINUATION")
        }
        .start()
    process.outputStream.bufferedWriter().use { writer -> writer.write("") }
    val output = process.inputStream.bufferedReader().readText()
    val exitCode = process.waitFor()
    return InstallerRun(testRepo, home, binDir, output, exitCode, parseApplyArgs(logPath))
  }

  private fun seedRepo(testRepo: Path) {
    Files.writeString(testRepo.resolve("install.sh"), Files.readString(repoRoot.resolve("install.sh")))
    testRepo.resolve("install.sh").toFile().setExecutable(true)

    val skillDir = testRepo.resolve("skills/bill-sample")
    Files.createDirectories(skillDir)
    Files.writeString(
      skillDir.resolve("content.md"),
      "---\nname: bill-sample\ndescription: Sample skill.\n---\n\nBody.\n",
    )
    listOf("kotlin", "python").forEach { slug ->
      val packRoot = testRepo.resolve("platform-packs/$slug")
      Files.createDirectories(packRoot)
      Files.writeString(packRoot.resolve("platform.yaml"), "platform: \"$slug\"\n")
    }
    val orchestrationDir = testRepo.resolve("orchestration/review-orchestrator")
    Files.createDirectories(orchestrationDir)
    Files.writeString(orchestrationDir.resolve("PLAYBOOK.md"), "# Review orchestrator\n")
    val cliBin = testRepo.resolve("runtime-kotlin/runtime-cli/build/install/runtime-cli/bin/runtime-cli")
    val mcpBin = testRepo.resolve("runtime-kotlin/runtime-mcp/build/install/runtime-mcp/bin/runtime-mcp")
    Files.createDirectories(cliBin.parent)
    Files.createDirectories(mcpBin.parent)
    Files.writeString(cliBin, runtimeCliStub())
    Files.writeString(mcpBin, "#!/usr/bin/env bash\nexit 0\n")
    cliBin.toFile().setExecutable(true)
    mcpBin.toFile().setExecutable(true)
  }

  private fun runtimeCliStub(): String =
    """
    |#!/usr/bin/env bash
    |set -euo pipefail
    |if [[ "${'$'}{1:-}" == "--check-packaged-contracts" ]]; then exit 0; fi
    |{
    |  echo CALL
    |  for arg in "${'$'}@"; do
    |    printf 'ARG\t%s\n' "${'$'}arg"
    |  done
    |} >> "${'$'}{SKILL_BILL_TEST_RUNTIME_LOG:?}"
    |home=""
    |if [[ "${'$'}{1:-}" == "--home" ]]; then
    |  home="${'$'}2"
    |  shift 2
    |fi
    |if [[ "${'$'}{1:-}" == "install" && "${'$'}{2:-}" == "--help" ]]; then
    |  printf '%s\n' "Commands:"
    |  printf '%s\n' "  replay-last-selection"
    |  exit 0
    |fi
    |if [[ "${'$'}{1:-}" == "install" && "${'$'}{2:-}" == "agent-path" ]]; then
    |  printf '%s\n' "${'$'}home/agent-targets/${'$'}3"
    |  exit 0
    |fi
    |if [[ "${'$'}{1:-}" == "install" && "${'$'}{2:-}" == "replay-last-selection" ]]; then
    |  if [[ "${'$'}{SKILL_BILL_TEST_REUSE_SELECTION:-ok}" == "missing" ]]; then
    |    printf '%s\n' "Install selection record is missing at '${'$'}home/.skill-bill/install-selection.json'."
    |    exit 1
    |  fi
    |  printf '%s\n' "SLF4J(W): No SLF4J providers were found." >&2
    |  printf 'agent\tcodex\t%s\n' "${'$'}home/agent-targets/codex"
    |  printf 'platform-mode\tselected\nplatform\tkotlin\ntelemetry\t%s\nmcp\tregister\n' \
    |    "${'$'}{SKILL_BILL_TEST_REPLAY_TELEMETRY:-full}"
    |  exit 0
    |fi
    |if [[ "${'$'}{1:-}" == "telemetry" && "${'$'}{2:-}" == "status" ]]; then
    |  [[ "${'$'}{SKILL_BILL_TEST_TELEMETRY_STATUS_FAILS:-0}" == "1" ]] && exit 2
    |  printf 'config_path: %s\n' "${'$'}home/.config/skill-bill/config.json"
    |  printf 'telemetry_level: %s\n' "${'$'}{SKILL_BILL_TEST_CURRENT_TELEMETRY:-anonymous}"
    |  exit 0
    |fi
    |if [[ "${'$'}{1:-}" == "install" && "${'$'}{2:-}" == "apply" ]]; then
    |  exit 0
    |fi
    |if [[ "${'$'}{1:-}" == "install" && "${'$'}{2:-}" == "reconcile" ]]; then
    |  printf 'reconcile_summary: applied=false baseline_refreshed=false installed_count=0 pruned_count=0\n'
    |  exit 0
    |fi
    |exit 2
    |
    """.trimMargin()

  private fun parseApplyArgs(logPath: Path): List<String> {
    if (!Files.exists(logPath)) {
      return emptyList()
    }
    val calls = mutableListOf<MutableList<String>>()
    Files.readAllLines(logPath).forEach { line ->
      if (line == "CALL") {
        calls.add(mutableListOf())
      } else if (line.startsWith("ARG\t")) {
        calls.last().add(line.removePrefix("ARG\t"))
      }
    }
    return calls.singleOrNull { args -> args.drop(2).take(2) == listOf("install", "apply") }.orEmpty()
  }

  private fun expectedApplyArgs(run: InstallerRun): List<String> =
    listOf(
      "--home",
      run.home.toString(),
      "install",
      "apply",
      "--repo-root",
      run.home.resolve(".skill-bill").toString(),
      "--skills",
      run.home.resolve(".skill-bill/skills").toString(),
      "--platform-packs",
      run.home.resolve(".skill-bill/platform-packs").toString(),
      "--agent-mode",
      "manual",
      "--platform-mode",
      "selected",
      "--telemetry",
      "full",
      "--mcp",
      "register",
      "--replace-existing-skill-bill-links",
      "--runtime-install-root",
      run.home.resolve(".skill-bill/runtime").toString(),
      "--runtime-cli-build-dir",
      run.repoRoot.resolve("runtime-kotlin/runtime-cli/build/install/runtime-cli").toString(),
      "--runtime-mcp-build-dir",
      run.repoRoot.resolve("runtime-kotlin/runtime-mcp/build/install/runtime-mcp").toString(),
      "--runtime-cli-install-dir",
      run.home.resolve(".skill-bill/runtime/runtime-cli").toString(),
      "--runtime-mcp-install-dir",
      run.home.resolve(".skill-bill/runtime/runtime-mcp").toString(),
      "--runtime-launcher-bin-dir",
      run.binDir.toString(),
      "--runtime-mcp-bin",
      run.home.resolve(".skill-bill/runtime/runtime-mcp/bin/runtime-mcp").toString(),
    )

  private data class InstallerRun(
    val repoRoot: Path,
    val home: Path,
    val binDir: Path,
    val output: String,
    val exitCode: Int,
    val applyArgs: List<String>,
  )

  private data class InstallerRunOptions(
    val reuseSelection: String = "ok",
    val skipPreinstallUninstall: Boolean = true,
    val replayTelemetryLevel: String = "full",
    val currentTelemetryLevel: String? = null,
    val telemetryStatusFails: Boolean = false,
  )
}
