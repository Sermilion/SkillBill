package skillbill.cli

import skillbill.cli.core.CliRuntime
import skillbill.cli.install.apply.installApplyPayload
import skillbill.cli.install.apply.installPlanPayload
import skillbill.cli.kernel.cli.CliOutput
import skillbill.cli.model.CliFormat
import skillbill.cli.model.CliRuntimeContext
import skillbill.contracts.JsonCodec
import skillbill.contracts.telemetry.TelemetryOutboxEvent
import skillbill.di.core.RuntimeComponent
import skillbill.di.core.create
import skillbill.error.shellcontent.InvalidInstallPlanSchemaError
import skillbill.infrastructure.sqlite.SqliteTestDatabasePaths
import skillbill.infrastructure.sqlite.sqliteSessionFactoryForTests
import skillbill.install.model.InstallAgentSelection
import skillbill.install.model.InstallAgentSelectionMode
import skillbill.install.model.InstallAgentTarget
import skillbill.install.model.InstallAgentTargetSource
import skillbill.install.model.InstallApplyResult
import skillbill.install.model.InstallApplyStatus
import skillbill.install.model.InstallPlan
import skillbill.install.model.InstallPlanRequest
import skillbill.install.model.InstallPlanSkill
import skillbill.install.model.InstallPlanSkillKind
import skillbill.install.model.InstallStagingIntent
import skillbill.install.model.InstallStagingPathIntent
import skillbill.install.model.InstallTelemetryApplyOutcome
import skillbill.install.model.InstallTelemetryApplyStatus
import skillbill.install.model.InstallTelemetryLevel
import skillbill.install.model.InstallationTargetPaths
import skillbill.install.model.McpRegistrationChoice
import skillbill.install.model.McpRegistrationIntent
import skillbill.install.model.PACK_SIDECAR_PARENT_SKILL
import skillbill.install.model.PlatformPackSelection
import skillbill.install.model.PlatformPackSelectionMode
import skillbill.install.model.RuntimeDistributionInputs
import skillbill.install.model.SupportedAgent
import skillbill.install.model.WindowsSymlinkApplyOutcome
import skillbill.install.model.WindowsSymlinkDecision
import skillbill.install.model.WindowsSymlinkFallbackState
import skillbill.install.model.WindowsSymlinkPreflight
import skillbill.install.model.WindowsSymlinkPreflightState
import skillbill.install.model.selectedPlatformSlugs
import skillbill.ports.repository.toFileLocation
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CliInstallPlanApplyRuntimeTest {
  @Test
  fun `install plan maps manual agents platforms telemetry and mcp choices`() {
    val fixture = installPlanApplyFixture()

    val result = CliRuntime.run(manualPlanArguments(fixture), installPlanCliContext(fixture.home))

    assertEquals(0, result.exitCode, result.stdout)
    val payload = decodeInstallPlanApplyJson(result.stdout)
    assertEquals("planned", payload["status"])
    val agentsByName = payload.agents().associateBy { agent -> agent["agent"] as String }
    assertEquals(supportedAgents, agentsByName.keys)
    assertTrue(payload.agents().all { agent -> agent["source"] == "manual" })
    assertEquals(fixture.home.resolve(".claude/skills").toString(), agentsByName.getValue("claude")["path"])
    assertEquals(fixture.home.resolve(".agents/skills").toString(), agentsByName.getValue("codex")["path"])
    assertEquals(fixture.home.resolve(".junie/skills").toString(), agentsByName.getValue("junie")["path"])
    assertEquals(fixture.home.resolve(".cursor/skills").toString(), agentsByName.getValue("cursor")["path"])
    assertEquals(listOf("kotlin"), payload["selected_platforms"])
    val packsBySlug = payload.listOfMaps("platform_packs").associateBy { pack -> pack["slug"] as String }
    assertEquals(setOf("kmp", "kotlin"), packsBySlug.keys)
    assertEquals(false, packsBySlug.getValue("kmp")["selected"])
    assertEquals(true, packsBySlug.getValue("kotlin")["selected"])
    assertPlannedSkillKinds(payload)
    assertEquals("full", payload["telemetry_level"])
    val mcp = payload.mapValue("mcp_registration")
    assertEquals(true, mcp["register"])
    assertEquals("/tmp/runtime-mcp", mcp["runtime_mcp_bin"])
    assertEquals(supportedAgents, (mcp["agents"] as List<*>).toSet())
    val runtime = payload.mapValue("runtime_distribution")
    assertEquals(fixture.home.resolve(".skill-bill/runtime").toString(), runtime["runtime_install_root"])
    assertEquals(null, runtime["runtime_cli_build_dir"])
    assertEquals(null, runtime["runtime_mcp_build_dir"])
    assertEquals(false, payload["replace_existing_skill_bill_links"])
    val preflight = payload.mapValue("windows_symlink_preflight")
    assertEquals("requires_elevation_or_developer_mode", preflight["state"])
    assertEquals("proceed_with_symlinks", preflight["decision"])
    assertEquals("Windows symlink support was not confirmed.", preflight["message"])
  }

  @Test
  fun `install plan accepts manual cursor without a detected cursor home`() {
    val fixture = installPlanApplyFixture()

    val result = CliRuntime.run(cursorPlanArguments(fixture), installPlanCliContext(fixture.home))

    assertEquals(0, result.exitCode, result.stdout)
    val payload = decodeInstallPlanApplyJson(result.stdout)
    assertEquals("planned", payload["status"])
    val cursor = payload.agents().single { agent -> agent["agent"] == "cursor" }
    assertEquals(fixture.home.resolve(".cursor/skills").toString(), cursor["path"])
    assertEquals("manual", cursor["source"])
    assertEquals(listOf("cursor"), payload.mapValue("mcp_registration")["agents"])
    assertFalse(Files.exists(fixture.home.resolve(".cursor")))
  }

  @Test
  fun `install plan detects cursor when a cursor home exists`() {
    val fixture = installPlanApplyFixture()
    createDetectedAgentHomes(fixture.home)
    Files.createDirectories(fixture.home.resolve(".cursor"))

    val result = CliRuntime.run(detectedPlanArguments(fixture, "anonymous"), installPlanCliContext(fixture.home))

    assertEquals(0, result.exitCode, result.stdout)
    val payload = decodeInstallPlanApplyJson(result.stdout)
    val cursor = payload.agents().single { agent -> agent["agent"] == "cursor" }
    assertEquals(fixture.home.resolve(".cursor/skills").toString(), cursor["path"])
    assertEquals("detected", cursor["source"])
  }

  @Test
  fun `install plan maps detected agents all platforms and telemetry levels`() {
    listOf("anonymous", "full", "off").forEach { telemetry ->
      val fixture = installPlanApplyFixture()
      createDetectedAgentHomes(fixture.home)

      val result = CliRuntime.run(detectedPlanArguments(fixture, telemetry), installPlanCliContext(fixture.home))

      assertEquals(0, result.exitCode, "$telemetry: ${result.stdout}")
      val payload = decodeInstallPlanApplyJson(result.stdout)
      assertEquals(telemetry, payload["telemetry_level"])
      assertEquals(false, payload.mapValue("mcp_registration")["register"])
      assertEquals(null, payload.mapValue("mcp_registration")["runtime_mcp_bin"])
      assertEquals(supportedAgents, payload.agents().map { agent -> agent["agent"] }.toSet())
      assertTrue(payload.agents().all { agent -> agent["source"] == "detected" })
      assertEquals(setOf("kmp", "kotlin"), (payload["selected_platforms"] as List<*>).toSet())
      assertTrue(payload.listOfMaps("platform_packs").all { pack -> pack["selected"] == true })
      assertTrue(payload.listOfMaps("skills").any { skill -> skill["name"] == "bill-code-review" })
      assertTrue(payload.listOfMaps("skills").any { skill -> skill["name"] == "bill-code-check" })
      assertFalse(payload.listOfMaps("skills").any { skill -> skill["name"] == "bill-kotlin-code-review" })
    }
  }

  @Test
  fun `install apply maps all manual agent targets base staging telemetry and skipped mcp`() {
    val fixture = installPlanApplyFixture()
    val targetsByAgent = supportedAgents.associateWith { agent -> fixture.home.resolve("manual-targets/$agent") }

    val result =
      CliRuntime.run(
        manualBaseOnlyApplyArguments(fixture, targetsByAgent),
        installPlanCliContext(fixture.home),
      )

    assertEquals(0, result.exitCode, result.stdout)
    val payload = decodeInstallPlanApplyJson(result.stdout)
    assertEquals("success", payload["status"])
    assertEquals(emptyList<String>(), payload["selected_platforms"])
    assertEquals(supportedAgents, payload.agents().map { agent -> agent["agent"] }.toSet())
    payload.agents().forEach { agent ->
      val agentName = agent["agent"] as String
      assertEquals(targetsByAgent.getValue(agentName).toString(), agent["path"])
      assertEquals("manual", agent["source"])
    }
    val stagingRoot = fixture.home.resolve(".skill-bill/installed-skills").toString()
    assertEquals(stagingRoot, payload["staging_root"])
    assertTrue(
      payload.listOfMaps("staging").all { staging -> staging["staging_dir"].toString().startsWith(stagingRoot) },
    )
    val skills = payload.listOfMaps("skills")
    assertEquals(
      setOf(PACK_SIDECAR_PARENT_SKILL, "bill-code-review", "bill-code-check"),
      skills.map { skill -> skill["name"] }.toSet(),
    )
    assertTrue(skills.all { skill -> skill["kind"] == "base" })
    assertTrue(skills.all { skill -> skill.mapValue("staging")["status"] == "staged" })
    val links = skills.flatMap { skill -> skill.listOfMaps("links") }
    assertEquals(supportedAgents.size * skills.size, links.size)
    assertEquals(supportedAgents, links.map { link -> link["agent"] }.toSet())
    supportedAgents.forEach { agent ->
      val agentLinks = links.filter { link -> link["agent"] == agent }
      val targetDir = targetsByAgent.getValue(agent).toString()
      assertEquals(skills.size, agentLinks.size)
      assertTrue(agentLinks.all { link -> link["target_dir"] == targetDir })
      assertEquals(
        skills.map { skill -> "$targetDir/${skill["name"]}" }.toSet(),
        agentLinks.map { link -> link["link_path"] }.toSet(),
      )
    }
    assertTrue(links.all { link -> link["status"] == "created" })
    assertTrue(links.all { link -> link["link_target"].toString().startsWith(stagingRoot) })
    val telemetry = payload.mapValue("telemetry")
    assertEquals("anonymous", telemetry["level"])
    assertEquals("success", telemetry["status"])
    val mcp = payload.mapValue("mcp_registration")
    assertEquals(false, mcp["register"])
    assertEquals(supportedAgents, (mcp["agents"] as List<*>).toSet())
    assertTrue(mcp.listOfMaps("outcomes").all { outcome -> outcome["status"] == "skipped" })

    val selection = readInstallSelection(fixture.home)
    assertEquals(supportedAgents, (selection["selected_agents"] as List<*>).toSet())
    assertEquals("none", selection.mapValue("platform_pack_selection")["mode"])
    assertEquals(emptyList<String>(), selection.mapValue("platform_pack_selection")["selected_slugs"])
    assertEquals("anonymous", selection["telemetry_level"])
    assertEquals(false, selection.mapValue("mcp_registration")["register"])
    assertEquals(null, selection.mapValue("mcp_registration")["runtime_mcp_bin"])
  }

  @Test
  fun `install apply telemetry uses parsed home and db overrides`() {
    val componentHome = Files.createTempDirectory("skillbill-cli-install-component-home")
    val fixture = installPlanApplyFixture()
    val overrideDb = Files.createTempFile("skillbill-cli-install-override", ".db")
    val componentDb = SqliteTestDatabasePaths.defaultDbPath(componentHome)
    val componentConfig = writeTelemetryConfig(componentHome, "full")
    val overrideConfig = writeTelemetryConfig(fixture.home, "full")
    enqueueTelemetryEvent(componentDb, componentHome)
    enqueueTelemetryEvent(overrideDb, fixture.home)

    val result =
      CliRuntime.run(
        listOf(
          "--home",
          fixture.home.toString(),
          "--db",
          overrideDb.toString(),
          "install",
          "apply",
          "--repo-root",
          fixture.repoRoot.toString(),
          "--agent-mode",
          "manual",
          "--agent",
          "codex",
          "--agent-target",
          "codex=${fixture.home.resolve("manual-targets/codex")}",
          "--platform-mode",
          "none",
          "--telemetry",
          "off",
          "--mcp",
          "skip",
          "--format",
          "json",
        ),
        CliRuntimeContext(userHome = componentHome, environment = isolatedCliEnvironment(componentHome)),
      )

    assertEquals(0, result.exitCode, result.stdout)
    val payload = decodeInstallPlanApplyJson(result.stdout)
    val telemetry = payload.mapValue("telemetry")
    assertEquals("off", telemetry["level"])
    assertEquals("success", telemetry["status"])
    assertEquals(overrideConfig.toString(), telemetry["config_path"])
    assertEquals(1, telemetry["cleared_events"])
    assertTrue(Files.exists(overrideConfig), "off must rewrite the config in place, not delete it")
    val disabledConfig = Files.readString(overrideConfig)
    assertTrue("\"level\":\"off\"" in disabledConfig, disabledConfig)
    assertTrue("\"install_id\":\"test-install-id\"" in disabledConfig, disabledConfig)
    assertTrue(Files.exists(componentConfig))
    assertEquals(0, pendingTelemetryEvents(overrideDb, fixture.home))
    assertEquals(1, pendingTelemetryEvents(componentDb, componentHome))
  }

  @Test
  fun `install apply refuses to mutate during goal continuation`() {
    val fixture = installPlanApplyFixture()

    val result =
      CliRuntime.run(
        singleCodexApplyArguments(fixture, platformMode = "none"),
        CliRuntimeContext(
          userHome = fixture.home,
          environment = mapOf("SKILL_BILL_GOAL_CONTINUATION" to "1"),
        ),
      )

    assertEquals(64, result.exitCode, result.stdout)
    assertContains(result.stdout, "Refusing to run skill-bill install apply during skill-bill goal-continuation.")
    assertFalse(Files.exists(installSelectionPath(fixture.home)))
    assertFalse(Files.exists(fixture.home.resolve("manual-targets/codex/bill-code-review")))
  }

  @Test
  fun `install apply links selected and all platform pack payloads through cli contract`() {
    val selectedFixture = installPlanApplyFixture()
    assertApplyPlatformSkills(
      payload =
        decodeInstallPlanApplyJson(
          CliRuntime.run(
            singleCodexApplyArguments(selectedFixture, platformMode = "selected", platforms = listOf("kotlin")),
            installPlanCliContext(selectedFixture.home),
          ).also { result -> assertEquals(0, result.exitCode, result.stdout) }.stdout,
        ),
      expectedSelectedPlatforms = setOf("kotlin"),
      expectedSkillNames =
        setOf(
          PACK_SIDECAR_PARENT_SKILL,
          "bill-code-review",
          "bill-code-check",
        ),
      targetDir = selectedFixture.home.resolve("manual-targets/codex"),
    )

    val allFixture = installPlanApplyFixture()
    assertApplyPlatformSkills(
      payload =
        decodeInstallPlanApplyJson(
          CliRuntime.run(
            singleCodexApplyArguments(allFixture, platformMode = "all"),
            installPlanCliContext(allFixture.home),
          ).also { result -> assertEquals(0, result.exitCode, result.stdout) }.stdout,
        ),
      expectedSelectedPlatforms = setOf("kmp", "kotlin"),
      expectedSkillNames =
        setOf(
          PACK_SIDECAR_PARENT_SKILL,
          "bill-code-review",
          "bill-code-check",
        ),
      targetDir = allFixture.home.resolve("manual-targets/codex"),
    )
  }

  @Test
  fun `install apply persists detected agents platform packs telemetry and mcp choice`() {
    val fixture = installPlanApplyFixture()
    createDetectedAgentHomes(fixture.home)

    val result = CliRuntime.run(detectedApplyArguments(fixture), installPlanCliContext(fixture.home))

    assertEquals(0, result.exitCode, result.stdout)
    val selection = readInstallSelection(fixture.home)
    assertEquals(supportedAgents, (selection["selected_agents"] as List<*>).toSet())
    val platformSelection = selection.mapValue("platform_pack_selection")
    assertEquals("all", platformSelection["mode"])
    assertEquals(emptyList<String>(), platformSelection["selected_slugs"])
    assertEquals("off", selection["telemetry_level"])
    val mcp = selection.mapValue("mcp_registration")
    assertEquals(false, mcp["register"])
    assertEquals(null, mcp["runtime_mcp_bin"])
  }

  @Test
  fun `install apply persists manual selected platforms and mcp registration choice`() {
    val fixture = installPlanApplyFixture()
    val runtimeMcpBin = fixture.home.resolve(".skill-bill/runtime/runtime-mcp/bin/runtime-mcp")

    val result =
      CliRuntime.run(
        manualSelectedPlatformRegisterMcpApplyArguments(fixture, runtimeMcpBin),
        installPlanCliContext(fixture.home),
      )

    assertEquals(0, result.exitCode, result.stdout)
    val selection = readInstallSelection(fixture.home)
    assertEquals(setOf("codex"), (selection["selected_agents"] as List<*>).toSet())
    val platformSelection = selection.mapValue("platform_pack_selection")
    assertEquals("selected", platformSelection["mode"])
    assertEquals(listOf("kotlin"), platformSelection["selected_slugs"])
    assertEquals("full", selection["telemetry_level"])
    val mcp = selection.mapValue("mcp_registration")
    assertEquals(true, mcp["register"])
    assertEquals(runtimeMcpBin.toString(), mcp["runtime_mcp_bin"])
  }

  @Test
  fun `install apply parses replace existing flag and removes legacy managed targets`() {
    val fixture = installPlanApplyFixture()
    val targetDir = fixture.home.resolve("manual-targets/codex")
    val legacyManaged = targetDir.resolve("mdp-gcheck")
    val stalePlatformManaged = targetDir.resolve("bill-kotlin-code-review")
    listOf(legacyManaged, stalePlatformManaged).forEach { managed ->
      Files.createDirectories(managed)
      Files.writeString(managed.resolve(".skill-bill-install"), "")
      Files.writeString(managed.resolve("SKILL.md"), "old managed install")
    }

    val result =
      CliRuntime.run(
        singleCodexApplyArguments(
          fixture = fixture,
          platformMode = "none",
          extraArgs = listOf("--replace-existing-skill-bill-links"),
        ),
        installPlanCliContext(fixture.home),
      )

    assertEquals(0, result.exitCode, result.stdout)
    assertFalse(Files.exists(legacyManaged))
    assertFalse(Files.exists(stalePlatformManaged))
    val payload = decodeInstallPlanApplyJson(result.stdout)
    assertEquals("success", payload["status"])
    assertTrue(Files.isSymbolicLink(targetDir.resolve("bill-code-review")))
    assertTrue(Files.isSymbolicLink(targetDir.resolve("bill-code-check")))
  }

  @Test
  fun `install apply renders structured windows symlink preflight failure`() {
    val fixture = installPlanApplyFixture()

    val result = CliRuntime.run(windowsFailureApplyArguments(fixture), installPlanCliContext(fixture.home))

    assertEquals(1, result.exitCode, result.stdout)
    val payload = decodeInstallPlanApplyJson(result.stdout)
    assertEquals("failure", payload["status"])
    val outcome = payload.mapValue("windows_symlink_outcome")
    assertEquals("user_action_required", outcome["fallback_state"])
    val preflight = outcome.mapValue("preflight")
    assertEquals("decision_required", preflight["state"])
    assertEquals("require_user_action", preflight["decision"])
    val failures = payload.listOfMaps("failures")
    assertEquals("windows_symlink_precheck_failed", failures.single()["kind"])
    assertEquals(
      "Windows requires elevation or Developer Mode before symlink install.",
      failures.single()["message"],
    )
    assertFalse(Files.exists(installSelectionPath(fixture.home)))
  }

  @Test
  fun `cli plan and apply seams loud fail typed install-plan schema errors`() {
    val fixture = installPlanApplyFixture()
    val invalidPlan = invalidCliInstallPlan(fixture)

    val installService =
      RuntimeComponent::class
        .create(installPlanCliContext(fixture.home).toRuntimeContext())
        .installService
    val planError =
      assertFailsWith<InvalidInstallPlanSchemaError> {
        installPlanPayload(invalidPlan, installService)
      }
    assertContains(planError.message.orEmpty(), "mcp_registration.runtime_mcp_bin")

    val applyError =
      assertFailsWith<InvalidInstallPlanSchemaError> {
        installApplyPayload(invalidPlan, minimalApplyResult(invalidPlan), installService)
      }
    assertContains(applyError.message.orEmpty(), "mcp_registration.runtime_mcp_bin")
  }

  @Test
  fun `install plan and apply JSON preserve byte-equivalent wire ordering`() {
    val fixture = installByteEquivalenceFixture()

    val planResult = CliRuntime.run(singleCodexPlanArguments(fixture), installPlanCliContext(fixture.home))
    assertEquals(0, planResult.exitCode, planResult.stdout)
    val planPayload = decodeInstallPlanApplyJson(planResult.stdout)
    val contentHashes =
      planPayload.listOfMaps("staging")
        .associate { staging -> staging["skill_name"].toString() to staging["content_hash"].toString() }
    assertEquals(
      CliOutput.emit(singleCodexPlanGoldenPayload(fixture, contentHashes), CliFormat.JSON),
      planResult.stdout,
    )

    val applyResult =
      CliRuntime.run(
        singleCodexApplyArguments(fixture, platformMode = "none"),
        installPlanCliContext(fixture.home),
      )
    assertEquals(0, applyResult.exitCode, applyResult.stdout)
    assertEquals(
      CliOutput.emit(singleCodexApplyGoldenPayload(fixture, contentHashes), CliFormat.JSON),
      applyResult.stdout,
    )
  }

  private fun assertPlannedSkillKinds(payload: Map<String, Any?>) {
    val skills = payload.listOfMaps("skills").associateBy { skill -> skill["name"] }
    assertEquals("base", skills.getValue("bill-code-review")["kind"])
    assertEquals("base", skills.getValue("bill-code-check")["kind"])
    assertFalse("bill-kotlin-code-review" in skills.keys)
    assertFalse("bill-kotlin-code-review-architecture" in skills.keys)
    assertFalse("bill-kotlin-code-check" in skills.keys)
  }

  private fun assertApplyPlatformSkills(
    payload: Map<String, Any?>,
    expectedSelectedPlatforms: Set<String>,
    expectedSkillNames: Set<String>,
    targetDir: Path,
  ) {
    assertEquals("success", payload["status"])
    assertEquals(expectedSelectedPlatforms, (payload["selected_platforms"] as List<*>).toSet())
    val skills = payload.listOfMaps("skills")
    assertEquals(expectedSkillNames, skills.map { skill -> skill["name"] }.toSet())
    assertTrue(skills.all { skill -> skill.mapValue("staging")["status"] == "staged" })
    val links = skills.flatMap { skill -> skill.listOfMaps("links") }
    assertEquals(expectedSkillNames.size, links.size)
    assertTrue(links.all { link -> link["agent"] == "codex" })
    assertTrue(links.all { link -> link["target_dir"] == targetDir.toString() })
    assertEquals(
      expectedSkillNames.map { skillName -> targetDir.resolve(skillName).toString() }.toSet(),
      links.map { link -> link["link_path"] }.toSet(),
    )
    assertTrue(links.all { link -> link["status"] == "created" })
  }

  private fun cursorPlanArguments(fixture: InstallPlanApplyFixture): List<String> =
    listOf(
      "install",
      "plan",
      "--repo-root",
      fixture.repoRoot.toString(),
      "--agent-mode",
      "manual",
      "--agent",
      "cursor",
      "--telemetry",
      "anonymous",
      "--mcp",
      "register",
      "--runtime-mcp-bin",
      "/tmp/runtime-mcp",
      "--format",
      "json",
    )

  private fun manualPlanArguments(fixture: InstallPlanApplyFixture): List<String> =
    listOf(
      "install",
      "plan",
      "--repo-root",
      fixture.repoRoot.toString(),
      "--agent-mode",
      "manual",
      "--agent",
      "claude",
      "--agent",
      "codex",
      "--agent",
      "junie",
      "--agent",
      "cursor",
      "--platform",
      "kotlin",
      "--telemetry",
      "full",
      "--mcp",
      "register",
      "--runtime-mcp-bin",
      "/tmp/runtime-mcp",
      "--windows-symlink-state",
      "requires-elevation-or-developer-mode",
      "--windows-symlink-decision",
      "proceed-with-symlinks",
      "--windows-symlink-message",
      "Windows symlink support was not confirmed.",
      "--format",
      "json",
    )

  private fun manualBaseOnlyApplyArguments(
    fixture: InstallPlanApplyFixture,
    targetsByAgent: Map<String, Path>,
  ): List<String> =
    buildList {
      add("install")
      add("apply")
      add("--repo-root")
      add(fixture.repoRoot.toString())
      add("--agent-mode")
      add("manual")
      supportedAgents.sorted().forEach { agent ->
        add("--agent")
        add(agent)
        add("--agent-target")
        add("$agent=${targetsByAgent.getValue(agent)}")
      }
      add("--platform-mode")
      add("none")
      add("--telemetry")
      add("anonymous")
      add("--mcp")
      add("skip")
      add("--format")
      add("json")
    }

  private fun singleCodexApplyArguments(
    fixture: InstallPlanApplyFixture,
    platformMode: String,
    platforms: List<String> = emptyList(),
    extraArgs: List<String> = emptyList(),
  ): List<String> =
    buildList {
      add("install")
      add("apply")
      add("--repo-root")
      add(fixture.repoRoot.toString())
      add("--agent-mode")
      add("manual")
      add("--agent")
      add("codex")
      add("--agent-target")
      add("codex=${fixture.home.resolve("manual-targets/codex")}")
      add("--platform-mode")
      add(platformMode)
      platforms.forEach { platform ->
        add("--platform")
        add(platform)
      }
      add("--telemetry")
      add("anonymous")
      add("--mcp")
      add("skip")
      addAll(extraArgs)
      add("--format")
      add("json")
    }

  private fun detectedPlanArguments(
    fixture: InstallPlanApplyFixture,
    telemetry: String,
  ): List<String> =
    listOf(
      "install",
      "plan",
      "--repo-root",
      fixture.repoRoot.toString(),
      "--agent-mode",
      "detected",
      "--platform-mode",
      "all",
      "--telemetry",
      telemetry,
      "--mcp",
      "skip",
      "--format",
      "json",
    )

  private fun detectedApplyArguments(fixture: InstallPlanApplyFixture): List<String> =
    listOf(
      "install",
      "apply",
      "--repo-root",
      fixture.repoRoot.toString(),
      "--agent-mode",
      "detected",
      "--platform-mode",
      "all",
      "--telemetry",
      "off",
      "--mcp",
      "skip",
      "--format",
      "json",
    )

  private fun manualSelectedPlatformRegisterMcpApplyArguments(
    fixture: InstallPlanApplyFixture,
    runtimeMcpBin: Path,
  ): List<String> =
    listOf(
      "install",
      "apply",
      "--repo-root",
      fixture.repoRoot.toString(),
      "--agent-mode",
      "manual",
      "--agent",
      "codex",
      "--agent-target",
      "codex=${fixture.home.resolve("manual-targets/codex")}",
      "--platform-mode",
      "selected",
      "--platform",
      "kotlin",
      "--telemetry",
      "full",
      "--mcp",
      "register",
      "--runtime-mcp-bin",
      runtimeMcpBin.toString(),
      "--format",
      "json",
    )

  private fun windowsFailureApplyArguments(fixture: InstallPlanApplyFixture): List<String> =
    listOf(
      "install",
      "apply",
      "--repo-root",
      fixture.repoRoot.toString(),
      "--agent-mode",
      "manual",
      "--agent",
      "codex",
      "--platform-mode",
      "none",
      "--mcp",
      "skip",
      "--windows-symlink-state",
      "decision-required",
      "--windows-symlink-decision",
      "require-user-action",
      "--windows-symlink-message",
      "Windows requires elevation or Developer Mode before symlink install.",
      "--format",
      "json",
    )

  private fun singleCodexPlanArguments(fixture: InstallPlanApplyFixture): List<String> =
    listOf(
      "install",
      "plan",
      "--repo-root",
      fixture.repoRoot.toString(),
      "--agent-mode",
      "manual",
      "--agent",
      "codex",
      "--agent-target",
      "codex=${fixture.home.resolve("manual-targets/codex")}",
      "--platform-mode",
      "none",
      "--telemetry",
      "anonymous",
      "--mcp",
      "skip",
      "--format",
      "json",
    )
}

private val supportedAgents = setOf("claude", "codex", "junie", "cursor")

private fun installPlanApplyFixture(): InstallPlanApplyFixture {
  val home = Files.createTempDirectory("skillbill-cli-install-plan-apply-home")
  val repoRoot = Files.createTempDirectory("skillbill-cli-install-plan-apply-repo")
  seedCliBaseSkill(repoRoot, PACK_SIDECAR_PARENT_SKILL)
  seedCliBaseSkill(repoRoot, "bill-code-review")
  seedCliBaseSkill(repoRoot, "bill-code-check")
  seedCliPlatformPack(repoRoot, "kmp")
  seedCliPlatformPack(repoRoot, "kotlin")
  return InstallPlanApplyFixture(repoRoot = repoRoot, home = home)
}

private fun installByteEquivalenceFixture(): InstallPlanApplyFixture {
  val home = Files.createTempDirectory("skillbill-cli-install-byte-equivalence-home")
  val repoRoot = Files.createTempDirectory("skillbill-cli-install-byte-equivalence-repo")
  Files.createDirectories(repoRoot.resolve("platform-packs"))
  seedCliBaseSkill(repoRoot, "bill-code-review")
  seedCliBaseSkill(repoRoot, "bill-code-check")
  return InstallPlanApplyFixture(repoRoot = repoRoot, home = home)
}

private fun invalidCliInstallPlan(fixture: InstallPlanApplyFixture): InstallPlan {
  val target = codexInstallTarget(fixture)
  val sourceDir = fixture.repoRoot.resolve("skills/bill-code-review")
  val request = invalidCliInstallRequest(fixture, target)
  val stagingRoot = fixture.home.resolve(".skill-bill/installed-skills")
  return InstallPlan(
    request = request,
    agents = listOf(target),
    discoveredPlatformPacks = emptyList(),
    selectedPlatformSlugs = emptyList(),
    skills = listOf(baseInstallPlanSkill(sourceDir)),
    staging = invalidCliStagingIntent(stagingRoot, sourceDir),
    telemetryLevel = InstallTelemetryLevel.ANONYMOUS,
    mcpRegistrationIntent = invalidMcpRegistrationIntent(),
    runtimeDistributionInputs = request.runtimeDistributionInputs,
    installationTargetPaths = request.targetPaths,
    windowsSymlinkPreflight = request.windowsSymlinkPreflight,
  )
}

private fun codexInstallTarget(fixture: InstallPlanApplyFixture): InstallAgentTarget =
  InstallAgentTarget(
    agent = SupportedAgent.CODEX,
    path = fixture.home.resolve("manual-targets/codex").toFileLocation(),
    source = InstallAgentTargetSource.MANUAL,
  )

private fun invalidCliInstallRequest(
  fixture: InstallPlanApplyFixture,
  target: InstallAgentTarget,
): InstallPlanRequest =
  InstallPlanRequest(
    repoRoot = fixture.repoRoot.toFileLocation(),
    home = fixture.home.toFileLocation(),
    agentSelection =
      InstallAgentSelection(
        mode = InstallAgentSelectionMode.MANUAL,
        manualAgents = setOf(SupportedAgent.CODEX),
      ),
    platformPackSelection = PlatformPackSelection(PlatformPackSelectionMode.NONE),
    telemetryLevel = InstallTelemetryLevel.ANONYMOUS,
    mcpRegistrationChoice = McpRegistrationChoice(register = true, runtimeMcpBin = Path.of("").toFileLocation()),
    runtimeDistributionInputs =
      RuntimeDistributionInputs(
        runtimeInstallRoot = fixture.home.resolve(".skill-bill/runtime").toFileLocation(),
      ),
    targetPaths =
      InstallationTargetPaths(
        skillsRoot = fixture.repoRoot.resolve("skills").toFileLocation(),
        platformPacksRoot = fixture.repoRoot.resolve("platform-packs").toFileLocation(),
        agentTargets = listOf(target),
      ),
    windowsSymlinkPreflight =
      WindowsSymlinkPreflight(
        state = WindowsSymlinkPreflightState.NOT_WINDOWS,
        decision = WindowsSymlinkDecision.NOT_REQUIRED,
      ),
  )

private fun baseInstallPlanSkill(sourceDir: Path): InstallPlanSkill =
  InstallPlanSkill(
    name = "bill-code-review",
    sourceDir = sourceDir.toFileLocation(),
    kind = InstallPlanSkillKind.BASE,
  )

private fun invalidCliStagingIntent(
  stagingRoot: Path,
  sourceDir: Path,
): InstallStagingIntent =
  InstallStagingIntent(
    root = stagingRoot.toFileLocation(),
    skillPaths =
      listOf(
        InstallStagingPathIntent(
          skillName = "bill-code-review",
          sourceDir = sourceDir.toFileLocation(),
          stagingRoot = stagingRoot.toFileLocation(),
          stagingDir = stagingRoot.resolve("bill-code-review-testhash").toFileLocation(),
          contentHash = "testhash",
        ),
      ),
  )

private fun invalidMcpRegistrationIntent(): McpRegistrationIntent =
  McpRegistrationIntent(
    register = true,
    runtimeMcpBin = Path.of("").toFileLocation(),
    agents = listOf(SupportedAgent.CODEX),
  )

private fun minimalApplyResult(plan: InstallPlan): InstallApplyResult =
  InstallApplyResult(
    status = InstallApplyStatus.SUCCESS,
    skills = emptyList(),
    nativeAgents = emptyList(),
    telemetryOutcome =
      InstallTelemetryApplyOutcome(
        level = plan.telemetryLevel,
        status = InstallTelemetryApplyStatus.SKIPPED,
      ),
    mcpRegistrationOutcomes = emptyList(),
    warnings = emptyList(),
    failures = emptyList(),
    windowsSymlinkOutcome =
      WindowsSymlinkApplyOutcome(
        preflight = plan.windowsSymlinkPreflight,
        fallbackState = WindowsSymlinkFallbackState.NOT_REQUIRED,
      ),
    telemetryLevel = plan.telemetryLevel,
    mcpRegistrationIntent = plan.mcpRegistrationIntent,
  )

private fun seedCliBaseSkill(
  repoRoot: Path,
  skillName: String,
) {
  val skillDir = repoRoot.resolve("skills").resolve(skillName)
  Files.createDirectories(skillDir)
  Files.writeString(skillDir.resolve("content.md"), cliSkillContent(skillName))
}

private fun seedCliPlatformPack(
  repoRoot: Path,
  slug: String,
) {
  val codeReviewName = "bill-$slug-code-review"
  val architectureName = "$codeReviewName-architecture"
  val qualityCheckName = "bill-$slug-code-check"
  val packRoot = repoRoot.resolve("platform-packs").resolve(slug)
  val baselineDir = packRoot.resolve("code-review").resolve(codeReviewName)
  Files.createDirectories(baselineDir.resolve("native-agents"))
  Files.createDirectories(packRoot.resolve("code-review").resolve(architectureName))
  Files.createDirectories(packRoot.resolve("quality-check").resolve(qualityCheckName))
  Files.writeString(packRoot.resolve("platform.yaml"), cliPlatformManifest(slug, codeReviewName, qualityCheckName))
  Files.writeString(
    baselineDir.resolve("content.md"),
    cliSkillContent(codeReviewName, internalFor = PACK_SIDECAR_PARENT_SKILL, body = cliBaselineBody(slug)),
  )
  Files.writeString(
    packRoot.resolve("code-review").resolve(architectureName).resolve("content.md"),
    cliSkillContent(architectureName, internalFor = PACK_SIDECAR_PARENT_SKILL, body = cliArchitectureBody(slug)),
  )
  Files.writeString(
    packRoot.resolve("quality-check").resolve(qualityCheckName).resolve("content.md"),
    cliSkillContent(qualityCheckName, internalFor = PACK_SIDECAR_PARENT_SKILL, body = cliQualityCheckBody(slug)),
  )
  Files.writeString(baselineDir.resolve("native-agents/agents.yaml"), cliNativeAgents(slug))
}

private fun cliPlatformManifest(
  slug: String,
  codeReviewName: String,
  qualityCheckName: String,
): String =
  """
  |platform: "$slug"
  |contract_version: "1.8"
  |routing_signals:
  |  strong: [".$slug", "*.$slug"]
  |  tie_breakers:
  |    - "Prefer $slug when $slug source signals dominate the changed product surface."
  |    - "Do not prefer $slug when an adjacent pack's declared signals dominate."
  |    - "Exclude generated and vendored files from dominance scoring."
  |declared_code_review_areas: [architecture]
  |declared_files:
  |  baseline: "code-review/$codeReviewName/content.md"
  |  areas:
  |    architecture: "code-review/$codeReviewName-architecture/content.md"
  |area_metadata:
  |  architecture:
  |    focus: "$slug architecture boundary APIs and failure modes"
  |display_name: "$slug"
  |declared_quality_check_file: "quality-check/$qualityCheckName/content.md"
  |pointers:
  |  code-review/$codeReviewName: []
  |  code-review/$codeReviewName-architecture: []
  |
  """.trimMargin()

private fun cliSkillContent(
  skillName: String,
  internalFor: String? = null,
  body: String = "Test body.",
): String =
  buildString {
    appendLine("---")
    appendLine("name: $skillName")
    appendLine("description: Test skill.")
    internalFor?.let { parent -> appendLine("internal-for: $parent") }
    appendLine("---")
    appendLine()
    appendLine("# $skillName")
    appendLine()
    appendLine(body)
  }

private fun cliBaselineBody(slug: String): String =
  """
  ## Classification Rules

  If $slug source dominates, select the $slug pack. Otherwise select the adjacent pack.

  ## Diff-Signal Routing Table

  - Module boundary changes -> `architecture` specialist.

  ## Mixed Diffs

  Keep the baseline specialists for the whole review and use lightweight file-level classification.
  Exclude generated, vendored, and non-stack files from each specialist's scope.
  Launch selected specialists as subagents in this harness in a deterministic order and retain every selected
  specialist result.

  ## Finding Discipline

  Calibrate severity and verify each precondition. Keep findings attributed through merge.
  Deduplicate overlaps without losing evidence.
  """.trimIndent()

private fun cliArchitectureBody(slug: String): String =
  """
  ## Focus

  Review $slug architecture boundaries.

  ## Ignore

  Ignore unrelated concerns.

  ## Applicability

  Apply to architecture changes.

  ## Project-Specific Rules

  ### Failure Modes

  - Verify `$slug module APIs` preserve their invariant; reject dependency boundary failure.
  - For Blocker or Major findings, describe the concrete dependency-cycle or ownership-boundary failure scenario.
  """.trimIndent()

private fun cliQualityCheckBody(slug: String): String =
  """
  ## Purpose

  Check $slug changes.

  ## Execution Steps

  Determine the files in scope. Discover commands from build files, wrappers,
    and CI configuration before falling back to defaults.
  Run the pack's quality-check entrypoint and capture failures.

  ## Fix Strategy

  Follow the priority-ordered fix ladder and never suppress failures.

  ### Repair Window

  Collect one complete finding set before repairing anything. While that set is open, do not invoke any check, test,
    compile, format-task, quality-check command, pack checker, `bill-code-check`,
      or delegated subagent check. Allowed work is read, search, and source edits only.
  Escalate to the full suite when targeted checks cannot establish safety.
  """.trimIndent()

private fun cliNativeAgents(slug: String): String =
  """
  contract_version: "0.1"
  agents:
    - name: bill-$slug-code-review-architecture
      description: "$slug architecture specialist — $slug architecture boundary APIs and failure modes."
      compose: governed-content
  """.trimIndent()

private fun createDetectedAgentHomes(home: Path) {
  Files.createDirectories(home.resolve(".claude"))
  Files.createDirectories(home.resolve(".codex"))
  Files.createDirectories(home.resolve(".junie"))
  Files.createDirectories(home.resolve(".cursor"))
}

private fun enqueueTelemetryEvent(
  dbPath: Path,
  userHome: Path,
) {
  sqliteSessionFactoryForTests(userHome, dbPath.toString(), environment = emptyMap()).transaction {
    it.telemetryOutbox.enqueue(TelemetryOutboxEvent.GOAL_FINISHED, """{"ok":true}""")
  }
}

private fun pendingTelemetryEvents(
  dbPath: Path,
  userHome: Path,
): Int =
  sqliteSessionFactoryForTests(userHome, dbPath.toString(), environment = emptyMap()).read {
    it.telemetryOutbox.pendingCount()
  }

private data class InstallPlanApplyFixture(
  val repoRoot: Path,
  val home: Path,
)

private fun decodeInstallPlanApplyJson(rawJson: String): Map<String, Any?> =
  JsonCodec.anyToStringAnyMap(JsonCodec.parseObjectOrNull(rawJson)?.let(JsonCodec::jsonElementToValue))
    ?: emptyMap()

private fun readInstallSelection(home: Path): Map<String, Any?> =
  decodeInstallPlanApplyJson(Files.readString(installSelectionPath(home)))

private fun installSelectionPath(home: Path): Path = home.resolve(".skill-bill/install-selection.json")

private fun singleCodexPlanGoldenPayload(
  fixture: InstallPlanApplyFixture,
  contentHashes: Map<String, String> = emptyMap(),
): Map<String, Any?> {
  val paths = SingleCodexGoldenPaths(fixture, contentHashes)
  return mapOf(
    "status" to "planned",
    "contract_version" to "0.3",
    "agents" to listOf(codexAgentGoldenPayload(paths)),
    "platform_packs" to emptyList<Map<String, Any?>>(),
    "selected_platforms" to emptyList<String>(),
    "skills" to
      listOf(
        plannedSkillGoldenPayload("bill-code-check", paths.qualityCheckSourceDir),
        plannedSkillGoldenPayload("bill-code-review", paths.codeReviewSourceDir),
      ),
    "staging_root" to paths.stagingRoot,
    "staging" to
      listOf(
        stagingIntentGoldenPayload(
          skillName = "bill-code-check",
          sourceDir = paths.qualityCheckSourceDir,
          stagingDir = paths.qualityCheckStagingDir,
          contentHash = paths.qualityCheckHash,
        ),
        stagingIntentGoldenPayload(
          skillName = "bill-code-review",
          sourceDir = paths.codeReviewSourceDir,
          stagingDir = paths.codeReviewStagingDir,
          contentHash = paths.codeReviewHash,
        ),
      ),
    "telemetry_level" to "anonymous",
    "mcp_registration" to
      mapOf(
        "register" to false,
        "runtime_mcp_bin" to null,
        "agents" to listOf("codex"),
      ),
    "runtime_distribution" to runtimeDistributionGoldenPayload(paths),
    "windows_symlink_preflight" to windowsPreflightGoldenPayload(),
    "replace_existing_skill_bill_links" to false,
  )
}

private fun singleCodexApplyGoldenPayload(
  fixture: InstallPlanApplyFixture,
  contentHashes: Map<String, String> = emptyMap(),
): Map<String, Any?> {
  val paths = SingleCodexGoldenPaths(fixture, contentHashes)
  return singleCodexPlanGoldenPayload(fixture, contentHashes) +
    mapOf(
      "status" to "success",
      "skills" to appliedSkillsGoldenPayload(paths),
      "native_agents" to nativeAgentsGoldenPayload(),
      "telemetry" to telemetryGoldenPayload(paths),
      "mcp_registration" to applyMcpRegistrationGoldenPayload(),
      "warnings" to emptyList<Map<String, Any?>>(),
      "failures" to emptyList<Map<String, Any?>>(),
      "windows_symlink_outcome" to windowsSymlinkOutcomeGoldenPayload(),
    )
}

private fun appliedSkillsGoldenPayload(paths: SingleCodexGoldenPaths): List<Map<String, Any?>> =
  listOf(
    appliedSkillGoldenPayload(
      AppliedSkillGoldenValues(
        skillName = "bill-code-check",
        sourceDir = paths.qualityCheckSourceDir,
        stagingDir = paths.qualityCheckStagingDir,
        renderedSkillFile = paths.qualityCheckRenderedSkillFile,
        contentHash = paths.qualityCheckHash,
        targetDir = paths.codexTargetDir,
        linkPath = paths.qualityCheckLinkPath,
      ),
    ),
    appliedSkillGoldenPayload(
      AppliedSkillGoldenValues(
        skillName = "bill-code-review",
        sourceDir = paths.codeReviewSourceDir,
        stagingDir = paths.codeReviewStagingDir,
        renderedSkillFile = paths.codeReviewRenderedSkillFile,
        contentHash = paths.codeReviewHash,
        targetDir = paths.codexTargetDir,
        linkPath = paths.codeReviewLinkPath,
      ),
    ),
  )

private fun nativeAgentsGoldenPayload(): List<Map<String, Any?>> =
  listOf(
    mapOf(
      "provider" to "codex",
      "agent" to "codex",
      "status" to "skipped",
      "path" to null,
      "message" to "no native-agent target or artifacts available for selected plan",
      "issue" to null,
    ),
  )

private fun telemetryGoldenPayload(paths: SingleCodexGoldenPaths): Map<String, Any?> =
  mapOf(
    "level" to "anonymous",
    "status" to "success",
    "config_path" to paths.telemetryConfigPath,
    "cleared_events" to 0,
    "message" to "Telemetry level set to 'anonymous'.",
    "issue" to null,
  )

private fun applyMcpRegistrationGoldenPayload(): Map<String, Any?> =
  mapOf(
    "register" to false,
    "runtime_mcp_bin" to null,
    "agents" to listOf("codex"),
    "outcomes" to
      listOf(
        mapOf(
          "agent" to "codex",
          "status" to "skipped",
          "config_path" to null,
          "changed" to false,
          "message" to "MCP registration not requested.",
          "issue" to null,
          "profiles" to emptyList<Map<String, Any?>>(),
        ),
      ),
  )

private fun windowsSymlinkOutcomeGoldenPayload(): Map<String, Any?> =
  mapOf(
    "preflight" to windowsPreflightGoldenPayload(),
    "fallback_state" to "not_required",
    "guidance" to WINDOWS_SYMLINK_GUIDANCE,
  )

private fun codexAgentGoldenPayload(paths: SingleCodexGoldenPaths): Map<String, Any?> =
  mapOf(
    "agent" to "codex",
    "path" to paths.codexTargetDir,
    "source" to "manual",
  )

private fun plannedSkillGoldenPayload(
  skillName: String,
  sourceDir: String,
): Map<String, Any?> =
  mapOf(
    "name" to skillName,
    "kind" to "base",
    "platform" to null,
    "source_dir" to sourceDir,
  )

private fun appliedSkillGoldenPayload(values: AppliedSkillGoldenValues): Map<String, Any?> =
  plannedSkillGoldenPayload(values.skillName, values.sourceDir) +
    mapOf(
      "staging" to
        mapOf(
          "status" to "staged",
          "staging_dir" to values.stagingDir,
          "rendered_skill_file" to values.renderedSkillFile,
          "content_hash" to values.contentHash,
          "issue" to null,
        ),
      "links" to
        listOf(
          mapOf(
            "agent" to "codex",
            "target_dir" to values.targetDir,
            "link_path" to values.linkPath,
            "link_target" to values.stagingDir,
            "status" to "created",
            "message" to "linked to ${values.stagingDir}",
            "issue" to null,
          ),
        ),
    )

private data class AppliedSkillGoldenValues(
  val skillName: String,
  val sourceDir: String,
  val stagingDir: String,
  val renderedSkillFile: String,
  val contentHash: String,
  val targetDir: String,
  val linkPath: String,
)

private fun stagingIntentGoldenPayload(
  skillName: String,
  sourceDir: String,
  stagingDir: String,
  contentHash: String,
): Map<String, Any?> =
  mapOf(
    "skill_name" to skillName,
    "source_dir" to sourceDir,
    "staging_dir" to stagingDir,
    "content_hash" to contentHash,
  )

private fun runtimeDistributionGoldenPayload(paths: SingleCodexGoldenPaths): Map<String, Any?> =
  mapOf(
    "runtime_install_root" to paths.runtimeInstallRoot,
    "runtime_cli_build_dir" to null,
    "runtime_mcp_build_dir" to null,
    "runtime_cli_install_dir" to null,
    "runtime_mcp_install_dir" to null,
    "runtime_launcher_bin_dir" to null,
  )

private fun windowsPreflightGoldenPayload(): Map<String, Any?> =
  mapOf(
    "state" to "not_windows",
    "decision" to "not_required",
    "message" to "",
  )

private data class SingleCodexGoldenPaths(
  private val fixture: InstallPlanApplyFixture,
  private val contentHashes: Map<String, String>,
) {
  val codexTargetDir: String = fixture.home.resolve("manual-targets/codex").toString()
  val stagingRoot: String = fixture.home.resolve(".skill-bill/installed-skills").toString()
  val runtimeInstallRoot: String = fixture.home.resolve(".skill-bill/runtime").toString()
  val telemetryConfigPath: String = fixture.home.resolve(".config/skill-bill/config.json").toString()
  val codeReviewSourceDir: String = fixture.repoRoot.resolve("skills/bill-code-review").toString()
  val qualityCheckSourceDir: String = fixture.repoRoot.resolve("skills/bill-code-check").toString()
  val codeReviewStagingDir: String = stagingDir("bill-code-review", codeReviewHash)
  val qualityCheckStagingDir: String = stagingDir("bill-code-check", qualityCheckHash)
  val codeReviewRenderedSkillFile: String = Path.of(codeReviewStagingDir).resolve("SKILL.md").toString()
  val qualityCheckRenderedSkillFile: String = Path.of(qualityCheckStagingDir).resolve("SKILL.md").toString()
  val codeReviewLinkPath: String = fixture.home.resolve("manual-targets/codex/bill-code-review").toString()
  val qualityCheckLinkPath: String = fixture.home.resolve("manual-targets/codex/bill-code-check").toString()

  val codeReviewHash: String
    get() = contentHashes["bill-code-review"] ?: CODE_REVIEW_GOLDEN_CONTENT_HASH
  val qualityCheckHash: String
    get() = contentHashes["bill-code-check"] ?: QUALITY_CHECK_GOLDEN_CONTENT_HASH

  private fun stagingDir(
    skillName: String,
    contentHash: String,
  ): String = fixture.home.resolve(".skill-bill/installed-skills").resolve("$skillName-$contentHash").toString()
}

private const val CODE_REVIEW_GOLDEN_CONTENT_HASH = "8047dd2294d395c3"
private const val QUALITY_CHECK_GOLDEN_CONTENT_HASH = "63299d67d27670bb"
private const val WINDOWS_SYMLINK_GUIDANCE =
  "On Windows, enable Developer Mode (Settings -> Privacy & security -> For developers) or run the install command " +
    "from an elevated shell so the JVM can create symlinks."

private fun installPlanCliContext(home: Path): CliRuntimeContext =
  CliRuntimeContext(
    userHome = home,
    environment = isolatedCliEnvironment(home),
  )

private fun Map<String, Any?>.mapValue(key: String): Map<String, Any?> = requireStringAnyMap(get(key), key)

private fun Map<String, Any?>.listOfMaps(key: String): List<Map<String, Any?>> {
  val rawValue = get(key)
  require(rawValue is List<*>) { "Expected '$key' to be a list." }
  return rawValue.mapIndexed { index, item -> requireStringAnyMap(item, "$key[$index]") }
}

private fun Map<String, Any?>.agents(): List<Map<String, Any?>> = listOfMaps("agents")

private fun requireStringAnyMap(
  rawValue: Any?,
  label: String,
): Map<String, Any?> {
  require(rawValue is Map<*, *>) { "Expected '$label' to be an object." }
  return rawValue.entries.associate { (key, value) ->
    require(key is String) { "Expected '$label' keys to be strings." }
    key to value
  }
}
