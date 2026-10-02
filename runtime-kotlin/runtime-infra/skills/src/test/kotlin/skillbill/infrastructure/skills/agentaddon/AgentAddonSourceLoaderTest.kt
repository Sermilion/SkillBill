package skillbill.infrastructure.skills.agentaddon

import skillbill.agentaddon.model.AgentAddonConsumer
import skillbill.error.core.InvalidAgentAddonAgentIdError
import skillbill.error.core.SkillBillRuntimeException
import skillbill.error.shellcontent.AgentAddonFailureCode
import skillbill.install.model.SupportedAgent
import skillbill.ports.repository.toFileLocation
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class AgentAddonSourceLoaderTest {
  @Test
  fun `unknown agent id fails with typed parse error`() {
    val error =
      assertFailsWith<InvalidAgentAddonAgentIdError> {
        SupportedAgent.parseAgentAddonId("unsupported-agent")
      }

    assertTrue(error.reason.contains("Unknown agent"), error.reason)
  }

  @Test
  fun `absent and empty roots are valid`() {
    val repo = Files.createTempDirectory("agent-addon-empty")
    assertEquals(emptyList(), discoverAgentAddons(repo))
    Files.createDirectory(repo.resolve("agent-addons"))
    assertEquals(emptyList(), discoverAgentAddons(repo))
  }

  @Test
  fun `discovery returns typed declarations in slug order and required lookup works`() {
    val repo = Files.createTempDirectory("agent-addon-order")
    writeAddon(repo, "z-last", listOf("codex"))
    writeAddon(repo, "a-first", SupportedAgent.supportedIds)

    val declarations = discoverAgentAddons(repo)

    assertEquals(listOf("a-first", "z-last"), declarations.map { it.slug })
    assertEquals(SupportedAgent.supportedIds, declarations.first().agents)
    assertEquals("z-last", requireAgentAddon(repo, "z-last").slug)
  }

  @Test
  fun `discovery includes external agent add-on roots`() {
    val repo = Files.createTempDirectory("agent-addon-external-repo")
    val external = Files.createTempDirectory("agent-addon-external-root")
    writeAddon(repo, "repo-addon", listOf("codex"))
    writeAddon(external, "external-addon", listOf("codex"))

    val declarations = discoverAgentAddons(repo, listOf(external.resolve("agent-addons")))

    assertEquals(listOf("external-addon", "repo-addon"), declarations.map { it.slug })
    assertTrue(
      declarations.first { it.slug == "external-addon" }.manifestPath
        .startsWith(external.resolve("agent-addons").toFileLocation()),
    )
  }

  @Test
  fun `external roots share duplicate slug validation with repo root`() {
    val repo = Files.createTempDirectory("agent-addon-external-duplicate-repo")
    val external = Files.createTempDirectory("agent-addon-external-duplicate-root")
    writeAddon(repo, "shared", listOf("codex"))
    writeAddon(external, "shared", listOf("codex"))

    val error =
      assertSchemaFailure {
        discoverAgentAddons(repo, listOf(external.resolve("agent-addons")))
      }

    assertTrue(error.message.orEmpty().contains("duplicate slug 'shared'"), error.message)
  }

  @Test
  fun `required lookup reports a typed missing declaration`() {
    val repo = Files.createTempDirectory("agent-addon-required")
    val error = assertFailsWith<SkillBillRuntimeException> { requireAgentAddon(repo, "missing") }
    assertEquals(AgentAddonFailureCode.MISSING_DECLARATION, error.code)
  }

  @Test
  fun `existing malformed roots fail with a typed error`() {
    val nonDirectoryRepo = Files.createTempDirectory("agent-addon-root-file")
    Files.writeString(nonDirectoryRepo.resolve("agent-addons"), "not a directory")
    val nonDirectoryError =
      assertSchemaFailure {
        discoverAgentAddons(nonDirectoryRepo)
      }
    assertTrue(nonDirectoryError.message.orEmpty().contains("root must be a directory"), nonDirectoryError.message)

    val danglingLinkRepo = Files.createTempDirectory("agent-addon-root-link")
    Files.createSymbolicLink(danglingLinkRepo.resolve("agent-addons"), danglingLinkRepo.resolve("missing"))
    val danglingLinkError =
      assertSchemaFailure {
        discoverAgentAddons(danglingLinkRepo)
      }
    assertTrue(danglingLinkError.message.orEmpty().contains("root must be a directory"), danglingLinkError.message)
  }

  @Test
  fun `invalid slugs are rejected`() {
    val repo = Files.createTempDirectory("agent-addon-invalid-slug")
    writeAddon(repo, "invalid_slug", listOf("codex"))

    val error = assertSchemaFailure { discoverAgentAddons(repo) }

    assertTrue(error.message.orEmpty().contains("slug"), error.message)
  }

  @Test
  fun `duplicate slugs are rejected`() {
    val repo = Files.createTempDirectory("agent-addon-duplicate-slug")
    val overrides = AddonOverrides(manifestSlug = "shared-slug")
    writeAddon(repo, "first-directory", listOf("codex"), overrides)
    writeAddon(repo, "second-directory", listOf("codex"), overrides)

    val error = assertSchemaFailure { discoverAgentAddons(repo) }

    assertTrue(error.message.orEmpty().contains("duplicate slug 'shared-slug'"), error.message)
  }

  @Test
  fun `source directory must match the declared slug`() {
    val repo = Files.createTempDirectory("agent-addon-directory-mismatch")
    writeAddon(
      repo,
      "source-directory",
      listOf("codex"),
      AddonOverrides(manifestSlug = "declared-slug"),
    )

    val error = assertSchemaFailure { discoverAgentAddons(repo) }

    assertTrue(
      error.message.orEmpty().contains("source directory 'source-directory' must match slug 'declared-slug'"),
      error.message,
    )
  }

  @Test
  fun `tolerant inspection retains valid entries and reports catalogue coherence failures`() {
    val repo = Files.createTempDirectory("agent-addon-inspection-coherence")
    writeAddon(repo, "valid-addon", listOf("codex"))
    val duplicate = AddonOverrides(manifestSlug = "shared-slug")
    writeAddon(repo, "first-directory", listOf("codex"), duplicate)
    writeAddon(repo, "second-directory", listOf("codex"), duplicate)

    val inspection = inspectAgentAddons(repo)

    assertEquals(listOf("valid-addon"), inspection.entries.map { it.slug })
    assertEquals(2, inspection.invalidEntries.size)
    assertTrue(
      inspection.invalidEntries.all { entry ->
        entry.validationStatus.wireValue == "invalid" &&
          entry.diagnostics.any { it.contains("duplicate slug 'shared-slug'") } &&
          entry.diagnostics.any { it.contains("source directory") }
      },
    )
  }

  @Test
  fun `missing content is rejected`() {
    val repo = Files.createTempDirectory("agent-addon-missing-content")
    val root = writeAddon(repo, "fixture", listOf("codex"))
    Files.delete(root.resolve("content.md"))

    val error = assertSchemaFailure { discoverAgentAddons(repo) }

    assertTrue(error.message.orEmpty().contains("content.md must be a regular file"), error.message)
  }

  @Test
  fun `non-regular content is rejected`() {
    val repo = Files.createTempDirectory("agent-addon-non-regular-content")
    val root = writeAddon(repo, "fixture", listOf("codex"))
    Files.delete(root.resolve("content.md"))
    Files.createDirectory(root.resolve("content.md"))

    val error = assertSchemaFailure { discoverAgentAddons(repo) }

    assertTrue(error.message.orEmpty().contains("content.md must be a regular file"), error.message)
  }

  @Test
  fun `wrong contract versions are rejected`() {
    val repo = Files.createTempDirectory("agent-addon-wrong-version")
    writeAddon(repo, "fixture", listOf("codex"), AddonOverrides(contractVersion = "2.0"))

    val error = assertSchemaFailure { discoverAgentAddons(repo) }

    assertTrue(error.message.orEmpty().contains("contract_version"), error.message)
  }

  @Test
  fun `unknown agent ids are rejected through the agent registry`() {
    val repo = Files.createTempDirectory("agent-addon-unknown-agent")
    writeAddon(repo, "fixture", listOf("unknown"))

    val error = assertSchemaFailure { discoverAgentAddons(repo) }

    assertTrue(error.message.orEmpty().contains("unknown agent id 'unknown'"), error.message)
    SupportedAgent.supportedIds.forEach { assertTrue(error.message.orEmpty().contains(it), error.message) }
  }

  @Test
  fun `duplicate agent ids are rejected`() {
    val repo = Files.createTempDirectory("agent-addon-duplicate-agent")
    writeAddon(repo, "fixture", listOf("codex", "codex"))

    val error = assertSchemaFailure { discoverAgentAddons(repo) }

    assertTrue(error.message.orEmpty().contains("agent_ids"), error.message)
  }

  @Test
  fun `unknown consumers are rejected`() {
    val repo = Files.createTempDirectory("agent-addon-unknown-consumer")
    writeAddon(
      repo,
      "fixture",
      listOf("codex"),
      AddonOverrides(consumers = listOf("bill-review")),
    )

    val error = assertSchemaFailure { discoverAgentAddons(repo) }

    assertTrue(error.message.orEmpty().contains("consumers"), error.message)
  }

  @Test
  fun `duplicate consumers are rejected`() {
    val repo = Files.createTempDirectory("agent-addon-duplicate-consumer")
    writeAddon(
      repo,
      "fixture",
      listOf("codex"),
      AddonOverrides(consumers = listOf("skill-bill", "skill-bill")),
    )

    val error = assertSchemaFailure { discoverAgentAddons(repo) }

    assertTrue(error.message.orEmpty().contains("consumers"), error.message)
  }

  @Test
  fun `legacy bill-feature consumer loads as skill-bill and emits one migration record`() {
    val repo = Files.createTempDirectory("agent-addon-legacy-consumer")
    writeAddon(repo, "fixture", listOf("codex"), AddonOverrides(consumers = listOf("bill-feature")))
    val diagnostics = RecordingAgentAddonDiagnostics()

    val declaration = discoverAgentAddons(repo, diagnostics = diagnostics).single()

    assertEquals(listOf(AgentAddonConsumer.SKILL_BILL), declaration.consumers)
    assertEquals(
      listOf(
        "skillbill agent-addon: record_kind=migration; seam=$AGENT_ADDON_DECLARED_CONSUMER_SEAM; " +
          "value_used=skill-bill; value_expected=skill-bill; cause=legacy_consumer_bill-feature",
      ),
      diagnostics.migrationRecords,
    )
  }

  @Test
  fun `padded multiline and blank descriptions are rejected`() {
    listOf(" padded", "line one\nline two", "   ").forEachIndexed { index, description ->
      val repo = Files.createTempDirectory("agent-addon-description-$index")
      writeAddon(repo, "fixture", listOf("codex"), AddonOverrides(description = description))

      val error = assertSchemaFailure { discoverAgentAddons(repo) }

      assertTrue(error.message.orEmpty().contains("description"), error.message)
    }
  }

  @Test
  fun `unexpected source entries are rejected`() {
    val repo = Files.createTempDirectory("agent-addon-unexpected-entry")
    val root = writeAddon(repo, "fixture", listOf("codex"))
    Files.writeString(root.resolve("SKILL.md"), "generated")

    val error = assertSchemaFailure { discoverAgentAddons(repo) }

    assertTrue(error.message.orEmpty().contains("only agent-addon.yaml and content.md are allowed"), error.message)
  }

  @Test
  fun `duplicate canonical source identities are rejected`() {
    val repo = Files.createTempDirectory("agent-addon-duplicates")
    val source = writeAddon(repo, "source", listOf("codex"))
    Files.createSymbolicLink(repo.resolve("agent-addons/alias"), source)

    val error = assertSchemaFailure { discoverAgentAddons(repo) }

    assertTrue(error.message.orEmpty().contains("duplicate canonical source identity"), error.message)
  }

  private fun assertSchemaFailure(block: () -> Unit): SkillBillRuntimeException {
    val error = assertFailsWith<SkillBillRuntimeException> { block() }
    assertEquals(AgentAddonFailureCode.INVALID_SCHEMA, error.code)
    return error
  }

  private fun writeAddon(
    repo: Path,
    slug: String,
    agents: List<String>,
    overrides: AddonOverrides = AddonOverrides(),
  ): Path {
    val root = repo.resolve("agent-addons").resolve(slug)
    val yamlDescription =
      overrides.description
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\n", "\\n")
    Files.createDirectories(root)
    Files.writeString(
      root.resolve("agent-addon.yaml"),
      buildString {
        appendLine("contract_version: \"${overrides.contractVersion}\"")
        appendLine("slug: ${overrides.manifestSlug ?: slug}")
        appendLine("description: \"$yamlDescription\"")
        appendLine("agent_ids:")
        agents.forEach { appendLine("  - $it") }
        appendLine("consumers:")
        overrides.consumers.forEach { appendLine("  - $it") }
      },
    )
    Files.writeString(root.resolve("content.md"), "# Fixture\n")
    return root
  }

  private data class AddonOverrides(
    val description: String = "Fixture guidance.",
    val contractVersion: String = "1.0",
    val manifestSlug: String? = null,
    val consumers: List<String> = listOf("skill-bill"),
  )
}
