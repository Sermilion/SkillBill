package skillbill.infrastructure.contracts.workflow.decomposition

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper
import skillbill.contracts.workflow.featuretask.DECOMPOSITION_MANIFEST_CONTRACT_VERSION
import skillbill.infrastructure.contracts.locator.DecompositionManifestSchemaPaths
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DecompositionManifestSchemaContractVersionTest {
  @Test
  fun `schema contract_version const matches DECOMPOSITION_MANIFEST_CONTRACT_VERSION`() {
    val schema = classpathSchema()
    val contractVersionNode = schema.path("properties").path("contract_version").path("const")

    assertTrue(
      !contractVersionNode.isMissingNode && contractVersionNode.isTextual,
      "Schema must pin properties.contract_version.const as a string; found: $contractVersionNode",
    )
    assertEquals(
      DECOMPOSITION_MANIFEST_CONTRACT_VERSION,
      contractVersionNode.asText(),
      "Schema contract_version.const must equal DECOMPOSITION_MANIFEST_CONTRACT_VERSION " +
        "($DECOMPOSITION_MANIFEST_CONTRACT_VERSION).",
    )
  }

  @Test
  fun `schema id matches DecompositionManifestSchemaPaths EXPECTED_SCHEMA_ID`() {
    val schema = classpathSchema()
    val idNode = schema.path("\$id")

    assertTrue(!idNode.isMissingNode && idNode.isTextual, "Schema must declare a textual `\$id`.")
    assertEquals(
      DecompositionManifestSchemaPaths.EXPECTED_SCHEMA_ID,
      idNode.asText(),
      "Schema `\$id` must equal DecompositionManifestSchemaPaths.EXPECTED_SCHEMA_ID.",
    )
  }

  @Test
  fun `schema id fields are capped at Kotlin Int max`() {
    val schema = classpathSchema()

    assertEquals(
      Int.MAX_VALUE,
      schema.path("\$defs").path("subtaskId").path("maximum").asInt(),
      "Schema \$defs.subtaskId.maximum must match Kotlin Int.MAX_VALUE.",
    )
    assertEquals(
      Int.MAX_VALUE,
      schema.path("\$defs")
        .path("currentSubtaskIntent")
        .path("properties")
        .path("subtask_id")
        .path("maximum")
        .asInt(),
      "Schema currentSubtaskIntent.subtask_id.maximum must match Kotlin Int.MAX_VALUE.",
    )
  }

  @Test
  fun `schema content hash matches known hash for current contract version — bump version if schema changed`() {
    val resourceStream =
      DecompositionManifestSchemaValidator::class.java.classLoader
        .getResourceAsStream(DecompositionManifestSchemaPaths.CLASSPATH_RESOURCE)
    assertNotNull(
      resourceStream,
      "Schema missing from classpath at '${DecompositionManifestSchemaPaths.CLASSPATH_RESOURCE}'.",
    )
    val yamlText = resourceStream.use { it.readBytes().toString(Charsets.UTF_8) }
    val contentWithoutVersionLine =
      yamlText.lines()
        .filter { !it.trimStart().startsWith("const:") }
        .joinToString("\n")
    val digest = MessageDigest.getInstance("SHA-256")
    val hashBytes = digest.digest(contentWithoutVersionLine.toByteArray(Charsets.UTF_8))
    val actualHash = hashBytes.joinToString("") { "%02x".format(it) }
    assertEquals(
      KNOWN_SCHEMA_CONTENT_HASH,
      actualHash,
      "Schema content changed without a contract_version bump. " +
        "If you intentionally changed the schema, bump DECOMPOSITION_MANIFEST_CONTRACT_VERSION " +
        "and update KNOWN_SCHEMA_CONTENT_HASH in this test to the new hash: $actualHash",
    )
  }

  private fun classpathSchema(): JsonNode {
    val resourceStream =
      DecompositionManifestSchemaValidator::class.java.classLoader
        .getResourceAsStream(DecompositionManifestSchemaPaths.CLASSPATH_RESOURCE)
    assertNotNull(
      resourceStream,
      "Canonical decomposition manifest schema is missing from the classpath at " +
        "'${DecompositionManifestSchemaPaths.CLASSPATH_RESOURCE}'. " +
        "Ensure `copyDecompositionManifestSchema` ran before this test.",
    )
    val yamlText = resourceStream.use { it.readBytes().toString(Charsets.UTF_8) }
    return YAMLMapper().readTree(yamlText)
  }

  private companion object {
    const val KNOWN_SCHEMA_CONTENT_HASH: String =
      "a3b606a62289a670dc1e318b313085e21bd8ab9688115a263011d20abde0ef22"
  }
}
