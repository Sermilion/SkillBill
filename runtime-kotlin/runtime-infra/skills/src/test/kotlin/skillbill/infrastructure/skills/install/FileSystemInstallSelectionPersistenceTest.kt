package skillbill.infrastructure.skills.install

import skillbill.contracts.JsonCodec
import skillbill.error.core.SkillBillRuntimeException
import skillbill.error.shellcontent.InstallFailureCode
import skillbill.install.model.InstallTelemetryLevel
import skillbill.install.model.McpRegistrationChoice
import skillbill.install.model.PlatformPackSelection
import skillbill.install.model.PlatformPackSelectionMode
import skillbill.install.model.SharedInstallSelection
import skillbill.install.model.SupportedAgent
import skillbill.model.toPath
import skillbill.ports.install.selection.model.ReadLatestSuccessfulInstallSelectionRequest
import skillbill.ports.install.selection.model.WriteLatestSuccessfulInstallSelectionRequest
import skillbill.ports.repository.toFileLocation
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class FileSystemInstallSelectionPersistenceTest {
  @Test
  fun `writes and reads latest successful install selection`() {
    val home = Files.createTempDirectory("skillbill-install-selection-home")
    val store = FileSystemInstallSelectionPersistence()
    val selection =
      SharedInstallSelection(
        selectedAgents = setOf(SupportedAgent.CODEX, SupportedAgent.CLAUDE),
        platformPackSelection =
          PlatformPackSelection(
            mode = PlatformPackSelectionMode.SELECTED,
            selectedSlugs = setOf("kotlin", "kmp"),
          ),
        telemetryLevel = InstallTelemetryLevel.FULL,
        mcpRegistrationChoice =
          McpRegistrationChoice(
            register = true,
            runtimeMcpBin = Path.of("/runtime-mcp/bin/runtime-mcp").toFileLocation(),
          ),
      )

    val writeResult =
      store.writeLatestSuccessfulSelection(
        WriteLatestSuccessfulInstallSelectionRequest(installHome = home, selection = selection),
      )
    val readResult = store.readLatestSuccessfulSelection(ReadLatestSuccessfulInstallSelectionRequest(home))

    assertEquals(home.resolve(".skill-bill/install-selection.json"), writeResult.path)
    assertEquals(selection, readResult.selection)
  }

  @Test
  fun `reads canonical v1 json record and writer preserves durable keys`() {
    val home = Files.createTempDirectory("skillbill-install-selection-canonical")
    val path = home.resolve(".skill-bill/install-selection.json")
    Files.createDirectories(path.parent)
    Files.writeString(
      path,
      """
      {
        "contract_version": "1.0",
        "selected_agents": ["claude", "codex"],
        "platform_pack_selection": {
          "mode": "selected",
          "selected_slugs": ["kmp", "kotlin"]
        },
        "telemetry_level": "full",
        "mcp_registration": {
          "register": true,
          "runtime_mcp_bin": "/runtime-mcp/bin/runtime-mcp"
        }
      }
      """.trimIndent(),
    )
    val store = FileSystemInstallSelectionPersistence()

    val selection = store.readLatestSuccessfulSelection(ReadLatestSuccessfulInstallSelectionRequest(home)).selection

    assertEquals(setOf(SupportedAgent.CLAUDE, SupportedAgent.CODEX), selection.selectedAgents)
    assertEquals(PlatformPackSelectionMode.SELECTED, selection.platformPackSelection.mode)
    assertEquals(setOf("kmp", "kotlin"), selection.platformPackSelection.selectedSlugs)
    assertEquals(InstallTelemetryLevel.FULL, selection.telemetryLevel)
    assertEquals(true, selection.mcpRegistrationChoice.register)
    assertEquals(Path.of("/runtime-mcp/bin/runtime-mcp"), selection.mcpRegistrationChoice.runtimeMcpBin?.toPath())

    store.writeLatestSuccessfulSelection(
      WriteLatestSuccessfulInstallSelectionRequest(installHome = home, selection = selection),
    )
    val emittedPayload =
      JsonCodec.anyToStringAnyMap(
        JsonCodec.parseObjectOrNull(Files.readString(path))?.let(JsonCodec::jsonElementToValue),
      ).orEmpty()
    assertEquals(
      mapOf(
        "contract_version" to "1.0",
        "selected_agents" to listOf("claude", "codex"),
        "platform_pack_selection" to
          mapOf(
            "mode" to "selected",
            "selected_slugs" to listOf("kmp", "kotlin"),
          ),
        "telemetry_level" to "full",
        "mcp_registration" to
          mapOf(
            "register" to true,
            "runtime_mcp_bin" to "/runtime-mcp/bin/runtime-mcp",
          ),
      ),
      emittedPayload,
    )
  }

  @Test
  fun `requests select alternate install home per operation`() {
    val firstHome = Files.createTempDirectory("skillbill-install-selection-first")
    val alternateHome = Files.createTempDirectory("skillbill-install-selection-alternate")
    val store = FileSystemInstallSelectionPersistence()
    val selection =
      selection(
        selectedAgents = setOf(SupportedAgent.CURSOR),
        platformPackSelection = PlatformPackSelection(PlatformPackSelectionMode.ALL),
      )

    val writeResult =
      store.writeLatestSuccessfulSelection(
        WriteLatestSuccessfulInstallSelectionRequest(installHome = alternateHome, selection = selection),
      )

    assertEquals(alternateHome.resolve(".skill-bill/install-selection.json"), writeResult.path)
    assertEquals(
      selection,
      store.readLatestSuccessfulSelection(ReadLatestSuccessfulInstallSelectionRequest(alternateHome)).selection,
    )
    assertFailsWith<SkillBillRuntimeException> {
      store.readLatestSuccessfulSelection(ReadLatestSuccessfulInstallSelectionRequest(firstHome))
    }.also { assertEquals(InstallFailureCode.MISSING_INSTALL_SELECTION_RECORD, it.code) }
  }

  @Test
  fun `latest successful selection write overwrites previous record and persists mcp opt out`() {
    val home = Files.createTempDirectory("skillbill-install-selection-overwrite")
    val store = FileSystemInstallSelectionPersistence()
    val initialSelection =
      selection(
        selectedAgents = setOf(SupportedAgent.CLAUDE),
        telemetryLevel = InstallTelemetryLevel.FULL,
        mcpRegistrationChoice =
          McpRegistrationChoice(
            register = true,
            runtimeMcpBin = Path.of("/runtime-mcp/bin/runtime-mcp").toFileLocation(),
          ),
      )
    val latestSelection =
      selection(
        selectedAgents = setOf(SupportedAgent.CODEX),
        platformPackSelection =
          PlatformPackSelection(
            mode = PlatformPackSelectionMode.SELECTED,
            selectedSlugs = setOf("kotlin"),
          ),
        telemetryLevel = InstallTelemetryLevel.OFF,
        mcpRegistrationChoice = McpRegistrationChoice(register = false, runtimeMcpBin = null),
      )

    store.writeLatestSuccessfulSelection(
      WriteLatestSuccessfulInstallSelectionRequest(installHome = home, selection = initialSelection),
    )
    store.writeLatestSuccessfulSelection(
      WriteLatestSuccessfulInstallSelectionRequest(installHome = home, selection = latestSelection),
    )

    assertEquals(
      latestSelection,
      store.readLatestSuccessfulSelection(ReadLatestSuccessfulInstallSelectionRequest(home)).selection,
    )
  }

  @Test
  fun `write rejects platform selections that read parser would reject`() {
    val home = Files.createTempDirectory("skillbill-install-selection-write-invariants")
    val store = FileSystemInstallSelectionPersistence()
    val validSelection = selection(selectedAgents = setOf(SupportedAgent.CLAUDE))
    store.writeLatestSuccessfulSelection(
      WriteLatestSuccessfulInstallSelectionRequest(installHome = home, selection = validSelection),
    )

    val invalidSelections =
      mapOf(
        "selected mode with empty slugs" to
          selection(
            platformPackSelection = PlatformPackSelection(mode = PlatformPackSelectionMode.SELECTED),
          ),
        "all mode with selected slugs" to
          selection(
            platformPackSelection =
              PlatformPackSelection(
                mode = PlatformPackSelectionMode.ALL,
                selectedSlugs = setOf("kotlin"),
              ),
          ),
      )

    invalidSelections.forEach { (caseName, invalidSelection) ->
      assertFailsWith<SkillBillRuntimeException>(caseName) {
        store.writeLatestSuccessfulSelection(
          WriteLatestSuccessfulInstallSelectionRequest(installHome = home, selection = invalidSelection),
        )
      }.also { assertEquals(InstallFailureCode.MALFORMED_INSTALL_SELECTION_RECORD, it.code) }
    }
    assertEquals(
      validSelection,
      store.readLatestSuccessfulSelection(ReadLatestSuccessfulInstallSelectionRequest(home)).selection,
    )
  }

  @Test
  fun `canonical record round trips manual and detected reusable selections`() {
    val cases =
      mapOf(
        "manual single-agent opt out" to
          selection(
            selectedAgents = setOf(SupportedAgent.CODEX),
            telemetryLevel = InstallTelemetryLevel.OFF,
            mcpRegistrationChoice = McpRegistrationChoice(register = false, runtimeMcpBin = null),
          ),
        "detected multi-agent registration" to
          selection(
            selectedAgents = setOf(SupportedAgent.CLAUDE, SupportedAgent.CURSOR),
            platformPackSelection = PlatformPackSelection(PlatformPackSelectionMode.ALL),
            telemetryLevel = InstallTelemetryLevel.ANONYMOUS,
            mcpRegistrationChoice =
              McpRegistrationChoice(
                register = true,
                runtimeMcpBin = Path.of("/runtime-mcp/bin/runtime-mcp").toFileLocation(),
              ),
          ),
      )
    val store = FileSystemInstallSelectionPersistence()

    cases.forEach { (caseName, expectedSelection) ->
      val home = Files.createTempDirectory("skillbill-install-selection-$caseName")

      store.writeLatestSuccessfulSelection(
        WriteLatestSuccessfulInstallSelectionRequest(installHome = home, selection = expectedSelection),
      )

      assertEquals(
        expectedSelection,
        store.readLatestSuccessfulSelection(ReadLatestSuccessfulInstallSelectionRequest(home)).selection,
        caseName,
      )
      val emittedPayload =
        JsonCodec.anyToStringAnyMap(
          JsonCodec.parseObjectOrNull(Files.readString(home.resolve(".skill-bill/install-selection.json")))
            ?.let(JsonCodec::jsonElementToValue),
        ).orEmpty()
      assertFalse("recentRepoPath" in emittedPayload, caseName)
      assertFalse("firstRun.agents" in emittedPayload, caseName)
      assertEquals("1.0", emittedPayload["contract_version"], caseName)
    }
  }

  @Test
  fun `manually selected cursor round trips without a detected cursor home`() {
    val home = Files.createTempDirectory("skillbill-install-selection-cursor")
    val store = FileSystemInstallSelectionPersistence()
    val selection =
      selection(
        selectedAgents = setOf(SupportedAgent.CURSOR, SupportedAgent.CLAUDE),
        platformPackSelection =
          PlatformPackSelection(
            mode = PlatformPackSelectionMode.SELECTED,
            selectedSlugs = setOf("kotlin"),
          ),
        telemetryLevel = InstallTelemetryLevel.ANONYMOUS,
        mcpRegistrationChoice =
          McpRegistrationChoice(
            register = true,
            runtimeMcpBin = Path.of("/runtime-mcp/bin/runtime-mcp").toFileLocation(),
          ),
      )

    store.writeLatestSuccessfulSelection(
      WriteLatestSuccessfulInstallSelectionRequest(installHome = home, selection = selection),
    )
    val replayed = store.readLatestSuccessfulSelection(ReadLatestSuccessfulInstallSelectionRequest(home)).selection

    assertFalse(Files.exists(home.resolve(".cursor")))
    assertEquals(selection, replayed)
    assertContains(replayed.selectedAgents, SupportedAgent.CURSOR)
    assertContains(Files.readString(home.resolve(".skill-bill/install-selection.json")), "cursor")
  }

  @Test
  fun `missing install selection record fails loudly`() {
    val home = Files.createTempDirectory("skillbill-install-selection-missing")
    val store = FileSystemInstallSelectionPersistence()

    val error =
      assertFailsWith<SkillBillRuntimeException> {
        store.readLatestSuccessfulSelection(ReadLatestSuccessfulInstallSelectionRequest(home))
      }.also { assertEquals(InstallFailureCode.MISSING_INSTALL_SELECTION_RECORD, it.code) }

    assertContains(error.message.orEmpty(), "install-selection.json")
  }

  @Test
  fun `malformed install selection record fails loudly`() {
    val home = Files.createTempDirectory("skillbill-install-selection-malformed")
    val path = home.resolve(".skill-bill/install-selection.json")
    Files.createDirectories(path.parent)
    Files.writeString(path, "{\"recentRepoPath\":\"/repo\"}")
    val store = FileSystemInstallSelectionPersistence()

    val error =
      assertFailsWith<SkillBillRuntimeException> {
        store.readLatestSuccessfulSelection(ReadLatestSuccessfulInstallSelectionRequest(home))
      }.also { assertEquals(InstallFailureCode.MALFORMED_INSTALL_SELECTION_RECORD, it.code) }

    assertContains(error.message.orEmpty(), "unknown keys")
    assertContains(error.message.orEmpty(), "recentRepoPath")
  }

  @Test
  fun `oversized install selection record fails before parsing`() {
    val home = Files.createTempDirectory("skillbill-install-selection-oversized")
    val path = home.resolve(".skill-bill/install-selection.json")
    Files.createDirectories(path.parent)
    Files.writeString(path, "x".repeat(64 * 1024 + 1))
    val store = FileSystemInstallSelectionPersistence()

    assertFailsWith<SkillBillRuntimeException> {
      store.readLatestSuccessfulSelection(ReadLatestSuccessfulInstallSelectionRequest(home))
    }.also { assertEquals(InstallFailureCode.UNREADABLE_INSTALL_SELECTION_RECORD, it.code) }
  }

  @Test
  fun `oversized install selection write fails without replacing previous record`() {
    val home = Files.createTempDirectory("skillbill-install-selection-oversized-write")
    val store = FileSystemInstallSelectionPersistence()
    val previousSelection = selection(selectedAgents = setOf(SupportedAgent.CLAUDE))
    store.writeLatestSuccessfulSelection(
      WriteLatestSuccessfulInstallSelectionRequest(installHome = home, selection = previousSelection),
    )
    val oversizedSelection =
      selection(
        platformPackSelection =
          PlatformPackSelection(
            mode = PlatformPackSelectionMode.SELECTED,
            selectedSlugs = (1..900).mapTo(mutableSetOf()) { index -> "slug-$index-${"a".repeat(80)}" },
          ),
      )

    assertFailsWith<SkillBillRuntimeException> {
      store.writeLatestSuccessfulSelection(
        WriteLatestSuccessfulInstallSelectionRequest(installHome = home, selection = oversizedSelection),
      )
    }.also { assertEquals(InstallFailureCode.UNREADABLE_INSTALL_SELECTION_RECORD, it.code) }

    assertEquals(
      previousSelection,
      store.readLatestSuccessfulSelection(ReadLatestSuccessfulInstallSelectionRequest(home)).selection,
    )
  }

  @Test
  fun `invalid install selection records fail loudly`() {
    val cases =
      mapOf(
        "unsupported contract version" to validPayload(contractVersion = "2.0"),
        "missing required field" to
          """
          {
            "contract_version": "1.0",
            "selected_agents": [],
            "platform_pack_selection": {"mode": "none", "selected_slugs": []},
            "telemetry_level": "anonymous"
          }
          """.trimIndent(),
        "bad agent id" to validPayload(selectedAgents = listOf("codex", "unknown-agent")),
        "selected platform mode with empty slugs" to
          validPayload(
            platformMode = "selected",
            platformSlugs = emptyList(),
          ),
        "selected platform mode with empty slug" to
          validPayload(
            platformMode = "selected",
            platformSlugs = listOf(""),
          ),
        "selected platform mode with whitespace slug" to
          validPayload(
            platformMode = "selected",
            platformSlugs = listOf("   "),
          ),
        "none platform mode with selected slugs" to
          validPayload(
            platformMode = "none",
            platformSlugs = listOf("kotlin"),
          ),
        "invalid nested mcp field" to validPayload(runtimeMcpBin = "\"\""),
      )
    val store = FileSystemInstallSelectionPersistence()

    cases.forEach { (caseName, payload) ->
      val home = Files.createTempDirectory("skillbill-install-selection-invalid")
      val path = home.resolve(".skill-bill/install-selection.json")
      Files.createDirectories(path.parent)
      Files.writeString(path, payload)

      assertFailsWith<SkillBillRuntimeException>(caseName) {
        store.readLatestSuccessfulSelection(ReadLatestSuccessfulInstallSelectionRequest(home))
      }.also { assertEquals(InstallFailureCode.MALFORMED_INSTALL_SELECTION_RECORD, it.code) }
    }
  }

  @Test
  fun `unreadable install selection record fails loudly`() {
    val home = Files.createTempDirectory("skillbill-install-selection-unreadable")
    val path = home.resolve(".skill-bill/install-selection.json")
    Files.createDirectories(path)
    val store = FileSystemInstallSelectionPersistence()

    assertFailsWith<SkillBillRuntimeException> {
      store.readLatestSuccessfulSelection(ReadLatestSuccessfulInstallSelectionRequest(home))
    }.also { assertEquals(InstallFailureCode.UNREADABLE_INSTALL_SELECTION_RECORD, it.code) }
  }

  private fun selection(
    selectedAgents: Set<SupportedAgent> = setOf(SupportedAgent.CODEX),
    platformPackSelection: PlatformPackSelection = PlatformPackSelection(PlatformPackSelectionMode.NONE),
    telemetryLevel: InstallTelemetryLevel = InstallTelemetryLevel.ANONYMOUS,
    mcpRegistrationChoice: McpRegistrationChoice = McpRegistrationChoice(register = false),
  ): SharedInstallSelection =
    SharedInstallSelection(
      selectedAgents = selectedAgents,
      platformPackSelection = platformPackSelection,
      telemetryLevel = telemetryLevel,
      mcpRegistrationChoice = mcpRegistrationChoice,
    )

  private fun validPayload(
    contractVersion: String = "1.0",
    selectedAgents: List<String> = listOf("codex"),
    platformMode: String = "none",
    platformSlugs: List<String> = emptyList(),
    runtimeMcpBin: String = "null",
  ): String =
    """
    {
      "contract_version": "$contractVersion",
      "selected_agents": [${selectedAgents.joinToString { "\"$it\"" }}],
      "platform_pack_selection": {
        "mode": "$platformMode",
        "selected_slugs": [${platformSlugs.joinToString { "\"$it\"" }}]
      },
      "telemetry_level": "anonymous",
      "mcp_registration": {
        "register": false,
        "runtime_mcp_bin": $runtimeMcpBin
      }
    }
    """.trimIndent()
}
