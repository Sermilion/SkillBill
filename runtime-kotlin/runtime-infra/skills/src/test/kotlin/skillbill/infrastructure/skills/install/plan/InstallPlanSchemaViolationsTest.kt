package skillbill.infrastructure.skills.install.plan

import skillbill.contracts.install.INSTALL_PLAN_CONTRACT_VERSION
import skillbill.error.core.SkillBillRuntimeException
import skillbill.error.shellcontent.InstallFailureCode
import skillbill.infrastructure.contracts.install.InstallPlanSchemaValidator
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class InstallPlanSchemaViolationsTest {
  private class ValidInstallPlanFixture {
    val skills: MutableList<MutableMap<String, Any?>> =
      mutableListOf(
        linkedMapOf(
          "name" to "bill-code-review",
          "kind" to "base",
          "platform" to null,
          "source_dir" to "/repo/skills/bill-code-review",
        ),
      )
    val staging: MutableList<MutableMap<String, Any?>> =
      mutableListOf(
        linkedMapOf(
          "skill_name" to "bill-code-review",
          "source_dir" to "/repo/skills/bill-code-review",
          "staging_dir" to "/home/user/.skill-bill/installed-skills/bill-code-review-abcdef",
          "content_hash" to "abcdef",
        ),
      )
    val wireMap: MutableMap<String, Any?> =
      linkedMapOf(
        "status" to "planned",
        "contract_version" to INSTALL_PLAN_CONTRACT_VERSION,
        "agents" to
          mutableListOf<Map<String, Any?>>(
            linkedMapOf(
              "agent" to "codex",
              "path" to "/home/user/.codex/skills",
              "source" to "manual",
            ),
          ),
        "platform_packs" to
          listOf(
            linkedMapOf(
              "slug" to "kotlin",
              "pack_root" to "/repo/platform-packs/kotlin",
              "selected" to false,
            ),
          ),
        "selected_platforms" to emptyList<String>(),
        "skills" to skills,
        "staging_root" to "/home/user/.skill-bill/installed-skills",
        "staging" to staging,
        "telemetry_level" to "anonymous",
        "mcp_registration" to
          linkedMapOf(
            "register" to true,
            "runtime_mcp_bin" to "/home/user/.skill-bill/runtime/runtime-mcp/bin/runtime-mcp",
            "agents" to listOf("codex"),
          ),
        "runtime_distribution" to
          linkedMapOf(
            "runtime_install_root" to "/home/user/.skill-bill/runtime",
            "runtime_cli_build_dir" to null,
            "runtime_mcp_build_dir" to null,
            "runtime_cli_install_dir" to null,
            "runtime_mcp_install_dir" to null,
            "runtime_launcher_bin_dir" to null,
          ),
        "windows_symlink_preflight" to
          linkedMapOf(
            "state" to "not_windows",
            "decision" to "not_required",
            "message" to "",
          ),
        "replace_existing_skill_bill_links" to false,
      )
  }

  private fun validBaseWireMap(): MutableMap<String, Any?> = ValidInstallPlanFixture().wireMap

  @Test
  fun `valid base wire map passes validation`() {
    InstallPlanSchemaValidator.validate(validBaseWireMap())
  }

  @Test
  fun `unknown skill kind value fails validation with skills field path`() {
    val fixture = ValidInstallPlanFixture()
    fixture.skills[0]["kind"] = "bogus_kind"

    val error =
      assertFailsWith<SkillBillRuntimeException> {
        InstallPlanSchemaValidator.validate(fixture.wireMap)
      }.also { assertEquals(InstallFailureCode.INVALID_INSTALL_PLAN_SCHEMA, it.code) }
    assertContains(error.message.orEmpty(), "skills")
    assertContains(error.message.orEmpty(), "kind")
  }

  @Test
  fun `missing required staging_root fails validation with root field path`() {
    val wireMap = validBaseWireMap()
    wireMap.remove("staging_root")

    val error =
      assertFailsWith<SkillBillRuntimeException> { InstallPlanSchemaValidator.validate(wireMap) }
        .also { assertEquals(InstallFailureCode.INVALID_INSTALL_PLAN_SCHEMA, it.code) }

    assertContains(error.message.orEmpty(), "at '<root>'")
    assertContains(error.message.orEmpty(), "staging_root")
  }

  @Test
  fun `wrong contract_version value fails validation with contract_version field path`() {
    val wireMap = validBaseWireMap()
    wireMap["contract_version"] = "0.1"

    val error =
      assertFailsWith<SkillBillRuntimeException> { InstallPlanSchemaValidator.validate(wireMap) }
        .also { assertEquals(InstallFailureCode.INVALID_INSTALL_PLAN_SCHEMA, it.code) }
    assertContains(error.message.orEmpty(), "contract_version")
  }

  @Test
  fun `unknown additional property fails validation`() {
    val wireMap = validBaseWireMap()
    wireMap["bogus_extra"] = true

    val error =
      assertFailsWith<SkillBillRuntimeException> { InstallPlanSchemaValidator.validate(wireMap) }
        .also { assertEquals(InstallFailureCode.INVALID_INSTALL_PLAN_SCHEMA, it.code) }

    assertContains(error.message.orEmpty(), "bogus_extra")
  }

  @Test
  fun `invalid path shape empty string for staging source_dir fails validation`() {
    val fixture = ValidInstallPlanFixture()
    fixture.staging[0]["source_dir"] = ""

    val error =
      assertFailsWith<SkillBillRuntimeException> {
        InstallPlanSchemaValidator.validate(fixture.wireMap)
      }.also { assertEquals(InstallFailureCode.INVALID_INSTALL_PLAN_SCHEMA, it.code) }
    assertContains(error.message.orEmpty(), "staging")
    assertContains(error.message.orEmpty(), "source_dir")
  }
}
