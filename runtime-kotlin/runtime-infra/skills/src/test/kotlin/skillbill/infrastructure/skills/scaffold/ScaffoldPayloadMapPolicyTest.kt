package skillbill.infrastructure.skills.scaffold

import skillbill.error.core.SkillBillRuntimeException
import skillbill.error.shellcontent.ScaffoldFailureCode
import skillbill.infrastructure.skills.scaffold.payload.detectKind
import skillbill.infrastructure.skills.scaffold.payload.optionalSpecialistSubagents
import skillbill.infrastructure.skills.scaffold.payload.rejectBaselineLayersForNonPlatformPack
import skillbill.infrastructure.skills.scaffold.payload.rejectLeafSubagentSpecialists
import skillbill.infrastructure.skills.scaffold.payload.resolvePlatformPackDefaults
import skillbill.infrastructure.skills.scaffold.payload.resolvePlatformPackSelection
import skillbill.infrastructure.skills.scaffold.payload.validatePayloadVersion
import skillbill.infrastructure.skills.scaffold.runtime.service.contract.PRE_SHELL_FAMILIES
import skillbill.scaffold.policy.APPROVED_CODE_REVIEW_AREAS
import skillbill.scaffold.policy.SKILL_KIND_ADD_ON
import skillbill.scaffold.policy.SKILL_KIND_CODE_REVIEW_AREA
import skillbill.scaffold.policy.SKILL_KIND_HORIZONTAL
import skillbill.scaffold.policy.SKILL_KIND_PLATFORM_PACK
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ScaffoldPayloadMapPolicyTest {
  @Test
  fun `validatePayloadVersion accepts the canonical wire version`() {
    validatePayloadVersion(mapOf("scaffold_payload_version" to "1.0"))
  }

  @Test
  fun `validatePayloadVersion throws when the version disagrees`() {
    assertFailsWith<SkillBillRuntimeException> {
      validatePayloadVersion(mapOf("scaffold_payload_version" to "9.9"))
    }.also { assertEquals(ScaffoldFailureCode.PAYLOAD_VERSION_MISMATCH, it.code) }
  }

  @Test
  fun `validatePayloadVersion throws when the version field is missing`() {
    assertFailsWith<SkillBillRuntimeException> {
      validatePayloadVersion(mapOf("kind" to "horizontal"))
    }.also { assertEquals(ScaffoldFailureCode.INVALID_PAYLOAD, it.code) }
  }

  @Test
  fun `detectKind returns horizontal for a horizontal payload`() {
    assertEquals("horizontal", detectKind(mapOf("kind" to "horizontal")))
  }

  @Test
  fun `detectKind throws unknownSkillKindError for an unknown kind`() {
    assertFailsWith<SkillBillRuntimeException> {
      detectKind(mapOf("kind" to "not-a-kind"))
    }.also { assertEquals(ScaffoldFailureCode.UNKNOWN_SKILL_KIND, it.code) }
  }

  @Test
  fun `detectKind throws retiredScaffoldKindError for retired partial kind aliases`() {
    listOf("platform-override-piloted", "platform-override", "override", "code-review-area", "area", "specialist")
      .forEach { kind ->
        val error =
          assertFailsWith<SkillBillRuntimeException> {
            detectKind(mapOf("kind" to kind))
          }.also { assertEquals(ScaffoldFailureCode.RETIRED_KIND, it.code) }
        val message = error.message.orEmpty()
        assertTrue(kind in message, "Got: $message")
        assertTrue("platform-pack" in message, "Got: $message")
      }
  }

  @Test
  fun `detectKind rejects retired feature task family name with replacement`() {
    val retiredFamily = "feature-" + "implement"

    val error =
      assertFailsWith<SkillBillRuntimeException> {
        detectKind(mapOf("kind" to "platform-override-piloted", "family" to retiredFamily))
      }.also { assertEquals(ScaffoldFailureCode.UNKNOWN_PRE_SHELL_FAMILY, it.code) }

    val message = error.message.orEmpty()
    assertTrue(retiredFamily in message, "Got: $message")
    assertTrue("feature-task" in message, "Got: $message")
  }

  @Test
  fun `pre shell family taxonomy contains feature task and feature verify only`() {
    assertEquals(setOf("feature-task", "feature-verify"), PRE_SHELL_FAMILIES)
  }

  @Test
  fun `resolvePlatformPackSelection returns all approved areas when no selector is provided`() {
    val selection = resolvePlatformPackSelection(emptyMap())
    assertEquals(APPROVED_CODE_REVIEW_AREAS.sorted(), selection.selectedAreas)
  }

  @Test
  fun `resolvePlatformPackSelection rejects retired skeleton_mode with migration message`() {
    val error =
      assertFailsWith<SkillBillRuntimeException> {
        resolvePlatformPackSelection(mapOf("skeleton_mode" to "full"))
      }.also { assertEquals(ScaffoldFailureCode.INVALID_PAYLOAD, it.code) }
    val message = error.message.orEmpty()
    assertTrue("skeleton_mode" in message, "Got: $message")
    assertTrue("no longer supported" in message, "Got: $message")
    assertTrue("remove unwanted focus areas" in message, "Got: $message")
  }

  @Test
  fun `resolvePlatformPackSelection rejects retired specialist_areas with migration message`() {
    val error =
      assertFailsWith<SkillBillRuntimeException> {
        resolvePlatformPackSelection(mapOf("specialist_areas" to listOf("ui")))
      }.also { assertEquals(ScaffoldFailureCode.INVALID_PAYLOAD, it.code) }
    val message = error.message.orEmpty()
    assertTrue("specialist_areas" in message, "Got: $message")
    assertTrue("no longer supported" in message, "Got: $message")
    assertTrue("remove unwanted focus areas" in message, "Got: $message")
  }

  @Test
  fun `resolvePlatformPackDefaults resolves java preset defaults`() {
    val defaults = resolvePlatformPackDefaults(emptyMap(), "java")
    assertEquals("Java", defaults.displayName)
    assertEquals(true, defaults.presetUsed)
    assertTrue(defaults.strongSignals.isNotEmpty())
  }

  @Test
  fun `resolvePlatformPackDefaults resolves python preset defaults`() {
    val defaults = resolvePlatformPackDefaults(emptyMap(), "python")
    assertEquals("Python", defaults.displayName)
    assertEquals(true, defaults.presetUsed)
    assertTrue(defaults.strongSignals.contains("pyproject.toml"))
    assertTrue(defaults.strongSignals.contains("*.py"))
    assertTrue(defaults.tieBreakers.any { it.contains("generated") })
    assertTrue(defaults.tieBreakers.any { it.contains("vendored") })
  }

  @Test
  fun `resolvePlatformPackDefaults resolves php preset defaults`() {
    val defaults = resolvePlatformPackDefaults(emptyMap(), "php")
    assertEquals("PHP", defaults.displayName)
    assertEquals(true, defaults.presetUsed)
    assertTrue(defaults.strongSignals.contains("composer.json"))
    assertTrue(defaults.strongSignals.contains("*.php"))
    assertTrue(defaults.tieBreakers.any { it.contains("Composer metadata") })
    assertTrue(defaults.tieBreakers.any { it.contains("vendor") })
  }

  @Test
  fun `resolvePlatformPackDefaults resolves go preset defaults`() {
    val defaults = resolvePlatformPackDefaults(emptyMap(), "go")
    assertEquals("Go", defaults.displayName)
    assertEquals(true, defaults.presetUsed)
    assertTrue(defaults.strongSignals.contains("go.mod"))
    assertTrue(defaults.strongSignals.contains("*.go"))
    assertTrue(defaults.tieBreakers.any { it.contains("module/workspace metadata") })
    assertTrue(defaults.tieBreakers.any { it.contains("generated clients") })
  }

  @Test
  fun `resolvePlatformPackDefaults resolves rust preset defaults`() {
    val defaults = resolvePlatformPackDefaults(emptyMap(), "rust")
    assertEquals("Rust", defaults.displayName)
    assertEquals(true, defaults.presetUsed)
    assertTrue(defaults.strongSignals.contains("Cargo.toml"))
    assertTrue(defaults.strongSignals.contains("*.rs"))
    assertTrue(defaults.strongSignals.contains(".cargo/config.toml"))
    assertTrue(defaults.tieBreakers.any { it.contains("FFI bindings") })
    assertTrue(defaults.tieBreakers.any { it.contains("target/") })
    assertTrue(defaults.tieBreakers.any { it.contains("vendored") })
  }

  @Test
  fun `resolvePlatformPackDefaults resolves typescript preset defaults`() {
    val defaults = resolvePlatformPackDefaults(emptyMap(), "typescript")
    assertEquals("TypeScript", defaults.displayName)
    assertEquals(true, defaults.presetUsed)
    listOf(
      "tsconfig.json",
      "tsconfig.*.json",
      ".ts",
      "*.ts",
      ".tsx",
      "*.tsx",
      ".mts",
      "*.mts",
      ".cts",
      "*.cts",
    ).forEach { marker -> assertTrue(defaults.strongSignals.contains(marker)) }
    listOf(
      "package.json",
      "package-lock.json",
      "yarn.lock",
      "pnpm-lock.yaml",
      "bun.lockb",
      "biome.json",
      "eslint.config.*",
      ".eslintrc*",
      "prettier.config.*",
      ".prettierrc*",
    ).forEach { marker -> assertFalse(defaults.strongSignals.contains(marker)) }
    assertTrue(defaults.tieBreakers.any { it.contains("individually or combined") })
    assertTrue(defaults.tieBreakers.any { it.contains("without TypeScript ownership") })
    assertTrue(defaults.tieBreakers.any { it.contains("generated API clients") })
    assertTrue(defaults.tieBreakers.any { it.contains("ambient declaration files") })
    assertTrue(defaults.tieBreakers.any { it.contains("node_modules") })
    assertTrue(defaults.tieBreakers.any { it.contains("generated declaration files") })
  }

  @Test
  fun `resolvePlatformPackDefaults loud-fails when no preset and no routing signals are supplied`() {
    assertFailsWith<SkillBillRuntimeException> {
      resolvePlatformPackDefaults(emptyMap(), "no-such-preset")
    }.also { assertEquals(ScaffoldFailureCode.INVALID_PAYLOAD, it.code) }
  }

  @Test
  fun `resolvePlatformPackDefaults completes custom routing structure requirements`() {
    val defaults =
      resolvePlatformPackDefaults(
        mapOf(
          "display_name" to "Fixture",
          "routing_signals" to
            mapOf(
              "strong" to listOf(".fixture"),
              "tie_breakers" to listOf("Use Fixture for fixture modules."),
            ),
        ),
        "fixture",
      )

    assertEquals(listOf(".fixture", "*.fixture"), defaults.strongSignals)
    assertTrue(defaults.tieBreakers.any { "Prefer Fixture" in it && "dominate" in it })
    assertTrue(defaults.tieBreakers.any { "Do not prefer Fixture" in it && "adjacent" in it })
    assertTrue(defaults.tieBreakers.any { "generated and vendored" in it && "dominance" in it })
  }

  @Test
  fun `optionalSpecialistSubagents preserves supplied specialist names`() {
    val parsed =
      optionalSpecialistSubagents(
        mapOf("subagent_specialists" to listOf("ui", "api-contracts")),
        SKILL_KIND_PLATFORM_PACK,
      )
    assertEquals(listOf("ui", "api-contracts"), parsed.specialists)
  }

  @Test
  fun `optionalSpecialistSubagents rejects specialists for leaf kinds`() {
    assertFailsWith<SkillBillRuntimeException> {
      optionalSpecialistSubagents(
        mapOf("subagent_specialists" to listOf("ui")),
        SKILL_KIND_CODE_REVIEW_AREA,
      )
    }.also { assertEquals(ScaffoldFailureCode.INVALID_PAYLOAD, it.code) }
  }

  @Test
  fun `optionalSpecialistSubagents rejects duplicate specialist names`() {
    assertFailsWith<SkillBillRuntimeException> {
      optionalSpecialistSubagents(
        mapOf("subagent_specialists" to listOf("ui", "ui")),
        SKILL_KIND_HORIZONTAL,
      )
    }.also { assertEquals(ScaffoldFailureCode.INVALID_PAYLOAD, it.code) }
  }

  @Test
  fun `rejectLeafSubagentSpecialists no-ops when subagent_specialists is absent`() {
    rejectLeafSubagentSpecialists(emptyMap(), SKILL_KIND_ADD_ON)
  }

  @Test
  fun `rejectBaselineLayersForNonPlatformPack rejects baseline_layers on non-platform-pack kinds`() {
    assertFailsWith<SkillBillRuntimeException> {
      rejectBaselineLayersForNonPlatformPack(
        mapOf("baseline_layers" to listOf(emptyMap<String, Any?>())),
        SKILL_KIND_HORIZONTAL,
      )
    }.also { assertEquals(ScaffoldFailureCode.INVALID_PAYLOAD, it.code) }
  }

  @Test
  fun `optionalSpecialistSubagents rejects no_subagents=true with non-empty subagent list`() {
    val error =
      assertFailsWith<SkillBillRuntimeException> {
        optionalSpecialistSubagents(
          mapOf(
            "subagent_specialists" to listOf("ui"),
            "no_subagents" to true,
          ),
          SKILL_KIND_HORIZONTAL,
        )
      }.also { assertEquals(ScaffoldFailureCode.INVALID_PAYLOAD, it.code) }
    val message = error.message.orEmpty()
    assertTrue("no_subagents=true" in message, "Got: $message")
    assertTrue("subagent_specialists" in message, "Got: $message")
  }

  @Test
  fun `optionalSpecialistSubagents rejects subagent name that violates SUBAGENT_NAME_PATTERN`() {
    listOf("UPPER", "1starts-with-digit", "has/slash", "has space").forEach { invalidName ->
      val error =
        assertFailsWith<SkillBillRuntimeException> {
          optionalSpecialistSubagents(
            mapOf("subagent_specialists" to listOf(invalidName)),
            SKILL_KIND_HORIZONTAL,
          )
        }.also { assertEquals(ScaffoldFailureCode.INVALID_PAYLOAD, it.code) }
      val message = error.message.orEmpty()
      assertTrue("invalid name '$invalidName'" in message, "Got: $message for input '$invalidName'")
    }
  }

  @Test
  fun `optionalSpecialistSubagents loud-fails when no_subagents is not a boolean`() {
    val error =
      assertFailsWith<SkillBillRuntimeException> {
        optionalSpecialistSubagents(
          mapOf("no_subagents" to "true"),
          SKILL_KIND_HORIZONTAL,
        )
      }.also { assertEquals(ScaffoldFailureCode.INVALID_PAYLOAD, it.code) }
    val message = error.message.orEmpty()
    assertTrue("no_subagents" in message, "Got: $message")
    assertTrue("boolean" in message, "Got: $message")
  }

  @Test
  fun `optionalSpecialistSubagents loud-fails when subagent_specialists is not a list`() {
    val error =
      assertFailsWith<SkillBillRuntimeException> {
        optionalSpecialistSubagents(
          mapOf("subagent_specialists" to "ui"),
          SKILL_KIND_HORIZONTAL,
        )
      }.also { assertEquals(ScaffoldFailureCode.INVALID_PAYLOAD, it.code) }
    val message = error.message.orEmpty()
    assertTrue("subagent_specialists" in message, "Got: $message")
    assertTrue("list of strings" in message, "Got: $message")
  }
}
