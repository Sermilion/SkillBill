package skillbill.cli

import skillbill.cli.core.CliRuntime
import skillbill.cli.model.CliRuntimeContext
import skillbill.infrastructure.host.CanonicalRepositoryRoot
import skillbill.infrastructure.workflow.git.GitWorkflowGitOperations
import skillbill.telemetry.CONFIG_ENVIRONMENT_KEY
import skillbill.telemetry.INSTALL_ID_ENVIRONMENT_KEY
import skillbill.telemetry.RESERVED_TEST_INSTALL_ID
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CliRuntimeShellCommandsTest {
  @Test
  fun `help does not consume stdin`() {
    val original = System.`in`
    System.setIn(
      object : InputStream() {
        override fun read(): Int = error("help must not read stdin")
      },
    )

    try {
      val result = CliRuntime.run(listOf("--help"))
      assertEquals(0, result.exitCode, result.stdout)
    } finally {
      System.setIn(original)
    }
  }

  @Test
  fun `an unrelated dash argument does not consume stdin`() {
    val original = System.`in`
    System.setIn(
      object : InputStream() {
        override fun read(): Int = error("unrelated commands must not read stdin")
      },
    )

    try {
      val result = CliRuntime.run(listOf("version", "-"))
      assertEquals(1, result.exitCode)
    } finally {
      System.setIn(original)
    }
  }

  @Test
  fun `doctor and version expose stable metadata`() {
    val tempDir = Files.createTempDirectory("skillbill-cli-doctor")
    val dbPath = tempDir.resolve("metrics.db")
    val configPath = tempDir.resolve("config.json")
    Files.writeString(
      configPath,
      """
      {
        "install_id": "test-install-id",
        "telemetry": {
          "level": "anonymous",
          "proxy_url": "",
          "batch_size": 50
        }
      }
      """.trimIndent() + "\n",
    )
    val context =
      CliRuntimeContext(
        environment =
          mapOf(
            CONFIG_ENVIRONMENT_KEY to configPath.toString(),
            INSTALL_ID_ENVIRONMENT_KEY to RESERVED_TEST_INSTALL_ID,
          ),
      )

    val versionResult = CliRuntime.run(listOf("version", "--format", "json"), context)
    assertEquals(INSTALLED_VERSION, decodeJsonObject(versionResult.stdout)["version"])

    val doctorResult =
      CliRuntime.run(listOf("--db", dbPath.toString(), "doctor", "--format", "json"), context)
    val doctorPayload = decodeJsonObject(doctorResult.stdout)
    assertEquals(INSTALLED_VERSION, doctorPayload["version"])
    assertEquals(dbPath.toAbsolutePath().normalize().toString(), doctorPayload["db_path"])
    assertFalse(doctorPayload["db_exists"] as Boolean)
    assertEquals(true, doctorPayload["telemetry_enabled"])
    assertEquals("anonymous", doctorPayload["telemetry_level"])
  }

  @Test
  fun `help output documents nested clikt commands`() {
    val rootHelp = CliRuntime.run(listOf("--help"))
    val telemetryHelp = CliRuntime.run(listOf("telemetry", "--help"))

    assertEquals(0, rootHelp.exitCode)
    assertContains(rootHelp.stdout, "--generate-completion=(bash|zsh|fish)")
    assertContains(rootHelp.stdout, "list")
    assertContains(rootHelp.stdout, "show")
    assertContains(rootHelp.stdout, "explain")
    assertContains(rootHelp.stdout, "validate")
    assertContains(rootHelp.stdout, "upgrade")
    assertContains(rootHelp.stdout, "render")
    assertContains(rootHelp.stdout, "edit")
    assertContains(rootHelp.stdout, "fill")
    assertContains(rootHelp.stdout, "learnings")
    assertContains(rootHelp.stdout, "telemetry")
    assertContains(rootHelp.stdout, "new-skill")
    assertContains(rootHelp.stdout, "create-and-fill")
    assertContains(rootHelp.stdout, "new-addon")
    assertContains(rootHelp.stdout, "install")
    assertContains(rootHelp.stdout, "verify-workflow")

    assertFalse(
      Regex("""(?m)^\s*workflow\s{2,}""").containsMatchIn(rootHelp.stdout),
      "root help must not list the removed workflow command",
    )
    val workflowHelp = CliRuntime.run(listOf("workflow", "--help"))
    val workflowContinue = CliRuntime.run(listOf("workflow", "continue"))
    val verifyWorkflowHelp = CliRuntime.run(listOf("verify-workflow", "--help"))

    assertEquals(0, workflowHelp.exitCode)
    assertContains(workflowHelp.stdout, "Usage: skill-bill")
    assertEquals(1, workflowContinue.exitCode)
    assertContains(workflowContinue.stderr, "tracker issue key")
    assertEquals(0, verifyWorkflowHelp.exitCode)
    assertContains(verifyWorkflowHelp.stdout, "show")
    assertFalse(verifyWorkflowHelp.stdout.contains("--subtask-id"))
    assertEquals(0, telemetryHelp.exitCode)
    assertContains(telemetryHelp.stdout, "capabilities")
    assertContains(telemetryHelp.stdout, "set-level")
  }

  @Test
  fun `root help preserves the ordered command groups`() {
    val help = CliRuntime.run(listOf("--help")).stdout
    val commandNames =
      listOf(
        "import-review",
        "new-skill",
        "install",
        "verify-workflow",
        "goal",
        "version",
        "code-review",
      )
    val positions =
      commandNames.map { command ->
        Regex("""(?m)^\s+$command\s""").find(help)?.range?.first ?: -1
      }
    assertTrue(positions.all { it >= 0 }, "root help is missing an expected command: $positions")
    assertEquals(positions.sorted(), positions)
  }

  @Test
  fun `deprecated feature-task-runtime alias keeps the note on the result stderr channel`() {
    val tempDir = Files.createTempDirectory("skillbill-cli-deprecated-runtime-alias")
    val context = CliRuntimeContext(userHome = tempDir)
    val note = "feature-task-runtime is a deprecated alias for feature-task"

    val run = CliRuntime.run(listOf("--home", tempDir.toString(), "feature-task-runtime", "run"), context)
    val status =
      CliRuntime.run(
        listOf("--db", tempDir.resolve("runtime.db").toString(), "feature-task-runtime", "status", "missing-workflow"),
        context,
      )
    val resume =
      CliRuntime.run(
        listOf("feature-task-runtime", "resume", "missing-workflow", "SKILL-371", "missing-spec.md"),
        context,
      )

    assertContains(run.stderr, note)
    assertContains(status.stderr, note)
    assertContains(resume.stderr, note)
  }

  @Test
  fun `install commands expose agent path lookup and link-skill`() {
    val tempDir = Files.createTempDirectory("skillbill-cli-install")
    Files.createDirectories(tempDir.resolve(".claude"))
    Files.createDirectories(tempDir.resolve(".junie"))
    val context = CliRuntimeContext(userHome = tempDir, environment = isolatedCliEnvironment(tempDir))

    val agentPathResult = CliRuntime.run(listOf("install", "agent-path", "codex"), context)
    val detectAgentsResult = CliRuntime.run(listOf("install", "detect-agents"), context)

    val sourceSkill = tempDir.resolve("skill-source")
    Files.createDirectories(sourceSkill)
    Files.writeString(sourceSkill.resolve("SKILL.md"), "# Skill\n")
    val targetDir = tempDir.resolve("agents")
    val linkResult =
      CliRuntime.run(
        listOf(
          "install",
          "link-skill",
          "--source",
          sourceSkill.toString(),
          "--target-dir",
          targetDir.toString(),
          "--agent",
          "codex",
        ),
        context,
      )

    assertEquals(0, agentPathResult.exitCode, agentPathResult.stdout)
    assertEquals(tempDir.resolve(".agents/skills").toString(), agentPathResult.stdout.trim())
    assertEquals(0, detectAgentsResult.exitCode, detectAgentsResult.stdout)
    assertEquals(
      """
      claude	${tempDir.resolve(".claude/skills")}
      junie	${tempDir.resolve(".junie/skills")}
      """.trimIndent(),
      detectAgentsResult.stdout.trim(),
    )
    assertEquals(0, linkResult.exitCode, linkResult.stdout)
    assertTrue(Files.isSymbolicLink(targetDir.resolve("skill-source")))
    assertEquals(sourceSkill.toRealPath(), targetDir.resolve("skill-source").toRealPath())
  }

  @Test
  fun `detect-agents CLI reports all supported detected agent flows`() {
    val tempDir = Files.createTempDirectory("skillbill-cli-install-detect-all")
    Files.createDirectories(tempDir.resolve(".claude"))
    Files.createDirectories(tempDir.resolve(".codex"))
    Files.createDirectories(tempDir.resolve(".junie"))
    Files.createDirectories(tempDir.resolve(".cursor"))

    val result =
      CliRuntime.run(
        listOf("install", "detect-agents"),
        CliRuntimeContext(userHome = tempDir, environment = isolatedCliEnvironment(tempDir)),
      )

    assertEquals(0, result.exitCode, result.stdout)
    assertEquals(
      """
      claude	${tempDir.resolve(".claude/skills")}
      codex	${tempDir.resolve(".codex/skills")}
      codex	${tempDir.resolve(".agents/skills")}
      junie	${tempDir.resolve(".junie/skills")}
      cursor	${tempDir.resolve(".cursor/skills")}
      """.trimIndent(),
      result.stdout.trim(),
    )
  }

  @Test
  fun `link-skill stages content managed skills when repo root is supplied`() {
    val tempDir = Files.createTempDirectory("skillbill-cli-install-staged")
    val context =
      CliRuntimeContext(
        userHome = tempDir.resolve("home"),
        environment = isolatedCliEnvironment(tempDir.resolve("home")),
      )
    val repoRoot = tempDir.resolve("repo")
    val skillName = "bill-cli-staged"
    val sourceSkill = repoRoot.resolve("skills").resolve(skillName)
    Files.createDirectories(sourceSkill)
    Files.writeString(
      sourceSkill.resolve("content.md"),
      """
      ---
      name: $skillName
      description: CLI staging fixture.
      ---

      ## Purpose

      Verify installer staging.
      """.trimIndent() + "\n",
    )

    val targetDir = tempDir.resolve("agents")
    val result =
      CliRuntime.run(
        listOf(
          "install",
          "link-skill",
          "--source",
          sourceSkill.toString(),
          "--target-dir",
          targetDir.toString(),
          "--agent",
          "codex",
          "--repo-root",
          repoRoot.toString(),
        ),
        context,
      )

    val link = targetDir.resolve(skillName)
    val linkedTarget = Files.readSymbolicLink(link).toAbsolutePath().normalize()
    assertEquals(0, result.exitCode, result.stdout)
    assertTrue(Files.isSymbolicLink(link))
    assertTrue(linkedTarget.startsWith(context.userHome.resolve(".skill-bill/installed-skills").toAbsolutePath()))
    assertTrue(Files.isRegularFile(link.resolve("SKILL.md")))
    assertFalse(Files.exists(sourceSkill.resolve("SKILL.md")))
  }

  @Test
  fun `doctor skill subject is retired with stable replacement`() {
    val result =
      CliRuntime.run(
        listOf(
          "doctor",
          "skill",
          "bill-feature",
          "--repo-root",
          ".",
          "--content",
          "none",
          "--format",
          "json",
        ),
      )

    assertEquals(1, result.exitCode)
    assertEquals(
      "doctor skill was retired in SKILL-32; use " +
        "`skill-bill show bill-feature --repo-root ${
          Path.of("").toAbsolutePath().normalize()
        } --content none` instead.",
      result.stdout,
    )
  }

  @Test
  fun `removed prose workflow and implement-stats commands are unknown`() {
    val tempDir = Files.createTempDirectory("skillbill-cli-removed-workflow")
    val context = CliRuntimeContext(userHome = tempDir)

    val workflow = CliRuntime.run(listOf("workflow", "list", "--format", "json"), context)
    val workflowContinue = CliRuntime.run(listOf("workflow", "continue", "wfl-x", "--format", "json"), context)
    val implementStats = CliRuntime.run(listOf("implement-stats", "--format", "json"), context)

    assertEquals(1, workflow.exitCode)
    assertContains(workflow.stderr, "no such")
    assertEquals(1, workflowContinue.exitCode)
    assertContains(workflowContinue.stderr, "no such")
    assertEquals(1, implementStats.exitCode)
    assertContains(implementStats.stderr, "no such")
  }

  @Test
  fun `verify workflow cli preserves prior step completion and continuation payloads`() {
    val tempDir = Files.createTempDirectory("skillbill-cli-verify-workflow")
    val dbPath = tempDir.resolve("metrics.db")
    val opened =
      runJson(
        "--db",
        dbPath.toString(),
        "verify-workflow",
        "open",
        "--current-step-id",
        "code_review",
        "--format",
        "json",
      )
    val workflowId = opened["workflow_id"] as String
    val checkpoint =
      GitWorkflowGitOperations()
        .repositoryFingerprint(CanonicalRepositoryRoot.enclosingRepositoryRoot(Path.of(""))).value
    val steps = opened.steps()
    assertEquals("completed", steps.single { it["step_id"] == "gather_diff" }["status"])

    runJson(
      "--db",
      dbPath.toString(),
      "verify-workflow",
      "update",
      workflowId,
      "--workflow-status",
      "running",
      "--current-step-id",
      "verdict",
      "--step-updates",
      """[{"step_id":"verdict","status":"blocked","attempt_count":1}]""",
      "--artifacts-patch",
      "{" +
        "\"diff_projection\":{\"checkpoint\":\"$checkpoint\"," +
        "\"comparison_scope\":\"base..head\",\"changed_files\":[]}," +
        "\"feature_flag_audit_receipt\":{\"contract_version\":\"0.1\",\"verdict\":\"approved\",\"findings\":[]}," +
        "\"code_review_receipt\":{\"contract_version\":\"0.1\",\"verdict\":\"approved\",\"findings\":[]}," +
        "\"unit_test_value_receipt\":{\"contract_version\":\"0.1\",\"verdict\":\"approved\",\"findings\":[]}," +
        "\"completeness_audit_receipt\":{\"contract_version\":\"0.1\",\"verdict\":\"approved\",\"findings\":[]}" +
        "}",
      "--format",
      "json",
    )

    val continued = runJson("--db", dbPath.toString(), "verify-workflow", "continue", "--latest", "--format", "json")
    val shown = runJson("--db", dbPath.toString(), "verify-workflow", "show", workflowId, "--format", "json")
    assertEquals("reopened", continued["continue_status"])
    assertEquals("running", continued["workflow_status_before_continue"])
    assertEquals("verdict", continued["continue_step_id"])
    assertEquals("verdict", continued["resume_step_id"])
    assertEquals(
      "skill-bill --db '$dbPath' verify-workflow show '$workflowId' --format json",
      continued["read_only_full_state_command"],
    )
    assertFalse(continued.containsKey("artifacts"))
    assertFalse(continued.containsKey("steps"))
    assertEquals("verdict", shown["current_step_id"])
  }

  @Test
  fun `clikt validation reports command usage errors`() {
    val missingRequiredOption = CliRuntime.run(listOf("record-feedback", "--run-id", "rvw-1"))
    assertEquals(1, missingRequiredOption.exitCode)
    assertContains(missingRequiredOption.stderr, "Error:")
    assertContains(missingRequiredOption.stderr, "--event")

    val invalidFormat = CliRuntime.run(listOf("version", "--format", "yaml"))
    assertEquals(1, invalidFormat.exitCode)
    assertContains(invalidFormat.stderr, "invalid choice")

    val unknownCommand = CliRuntime.run(listOf("install", "unknown"))
    assertEquals(1, unknownCommand.exitCode)
    assertContains(unknownCommand.stderr, "no such subcommand")
  }
}
