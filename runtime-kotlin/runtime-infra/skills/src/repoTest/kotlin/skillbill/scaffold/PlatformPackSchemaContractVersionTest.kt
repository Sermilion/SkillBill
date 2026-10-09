package skillbill.scaffold

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper
import skillbill.infrastructure.skills.scaffold.platformpack.manifest.PlatformPackSchemaPaths
import skillbill.infrastructure.skills.scaffold.runtime.service.contract.SHELL_CONTRACT_VERSION
import skillbill.scaffold.policy.APPROVED_CODE_REVIEW_AREAS
import skillbill.scaffold.policy.UNIVERSAL_CODE_REVIEW_AREAS
import skillbill.testing.repoRootFromTest
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class PlatformPackSchemaContractVersionTest {
  @Test
  fun `schema contract_version const matches SHELL_CONTRACT_VERSION`() {
    val schemaFile = repoRootFromTest().resolve(PlatformPackSchemaPaths.REPO_RELATIVE_PATH)
    assertTrue(Files.isRegularFile(schemaFile), "Canonical schema file is missing at $schemaFile.")

    val schema: JsonNode = YAMLMapper().readTree(Files.readString(schemaFile))
    val contractVersionNode = schema.path("properties").path("contract_version").path("const")
    assertNotNull(
      contractVersionNode.takeIf { !it.isMissingNode && it.isTextual },
      "Schema must pin properties.contract_version.const as a string; found: $contractVersionNode",
    )
    assertEquals(
      SHELL_CONTRACT_VERSION,
      contractVersionNode.asText(),
      "Schema contract_version.const must equal SHELL_CONTRACT_VERSION ($SHELL_CONTRACT_VERSION).",
    )
    assertEquals("1.8", SHELL_CONTRACT_VERSION, "Platform pack shell contract version must be pinned at 1.8.")
  }

  @Test
  fun `schema defs codeReviewArea enum equals approved and universal areas`() {
    val schemaFile = repoRootFromTest().resolve(PlatformPackSchemaPaths.REPO_RELATIVE_PATH)
    assertTrue(Files.isRegularFile(schemaFile), "Canonical schema file is missing at $schemaFile.")

    val schema: JsonNode = YAMLMapper().readTree(Files.readString(schemaFile))
    val enumNode = schema.path("\$defs").path("codeReviewArea").path("enum")
    assertNotNull(
      enumNode.takeIf { !it.isMissingNode && it.isArray },
      "Schema must declare \$defs.codeReviewArea.enum as an array; found: $enumNode",
    )
    val schemaAreas: Set<String> =
      (0 until enumNode.size())
        .map { index -> enumNode.path(index).asText() }
        .toSet()
    assertEquals(
      APPROVED_CODE_REVIEW_AREAS + UNIVERSAL_CODE_REVIEW_AREAS,
      schemaAreas,
      "Schema \$defs.codeReviewArea.enum must equal approved and universal areas. " +
        "Schema-only: ${schemaAreas - (APPROVED_CODE_REVIEW_AREAS + UNIVERSAL_CODE_REVIEW_AREAS)}. " +
        "Kotlin-only: ${(APPROVED_CODE_REVIEW_AREAS + UNIVERSAL_CODE_REVIEW_AREAS) - schemaAreas}.",
    )
  }

  @Test
  fun `full_gate_command description does not permit intermediate repair-cycle runs`() {
    val schemaFile = repoRootFromTest().resolve(PlatformPackSchemaPaths.REPO_RELATIVE_PATH)
    val schema: JsonNode = YAMLMapper().readTree(Files.readString(schemaFile))
    val description =
      schema.path("properties")
        .path("validation_gate")
        .path("properties")
        .path("full_gate_command")
        .path("description")
        .asText()
    assertFalse(
      description.contains("intermediate repair-cycle", ignoreCase = true),
      "full_gate_command description must not call argv an intermediate repair-cycle run",
    )
    assertTrue(
      description.contains("Not permitted while a finding set remains open", ignoreCase = true),
      "full_gate_command description must forbid use during an open repair window",
    )
  }
}
