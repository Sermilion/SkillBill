package skillbill.infrastructure.contracts.workflow.decomposition

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper
import skillbill.contracts.decomposition.BUNDLE_JOURNAL_CONTRACT_VERSION
import skillbill.infrastructure.contracts.locator.DecompositionManifestBundleJournalSchemaPaths
import skillbill.testing.repoRootFromTest
import java.nio.file.Files
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DecompositionManifestBundleJournalSchemaContractVersionTest {
  @Test
  fun `schema contract_version const matches BUNDLE_JOURNAL_CONTRACT_VERSION`() {
    val schema = classpathSchema()
    val contractVersionNode = schema.path("properties").path("contract_version").path("const")

    assertTrue(!contractVersionNode.isMissingNode && contractVersionNode.isTextual)
    assertEquals(BUNDLE_JOURNAL_CONTRACT_VERSION, contractVersionNode.asText())
  }

  @Test
  fun `schema id matches DecompositionManifestBundleJournalSchemaPaths EXPECTED_SCHEMA_ID`() {
    val schema = classpathSchema()
    val idNode = schema.path("\$id")
    assertTrue(!idNode.isMissingNode && idNode.isTextual)
    assertEquals(DecompositionManifestBundleJournalSchemaPaths.EXPECTED_SCHEMA_ID, idNode.asText())
  }

  @Test
  fun `packaged schema is copied from the canonical repository schema`() {
    val canonical = repoRootFromTest().resolve(DecompositionManifestBundleJournalSchemaPaths.REPO_RELATIVE_PATH)
    assertTrue(Files.isRegularFile(canonical))
    val packaged =
      DecompositionManifestBundleJournalSchemaValidator::class.java.classLoader
        .getResourceAsStream(DecompositionManifestBundleJournalSchemaPaths.CLASSPATH_RESOURCE)
    assertNotNull(packaged)
    packaged.use { stream ->
      assertEquals(Files.readString(canonical), stream.readBytes().toString(Charsets.UTF_8))
    }
  }

  @Test
  fun `schema content hash matches known hash for current contract version — bump version if schema changed`() {
    val resourceStream =
      DecompositionManifestBundleJournalSchemaValidator::class.java.classLoader
        .getResourceAsStream(DecompositionManifestBundleJournalSchemaPaths.CLASSPATH_RESOURCE)
    assertNotNull(resourceStream)
    val yamlText = resourceStream.use { it.readBytes().toString(Charsets.UTF_8) }
    val contentWithoutVersionLine =
      yamlText.lines()
        .filter { !it.trimStart().startsWith("const:") }
        .joinToString("\n")
    val actualHash =
      MessageDigest.getInstance("SHA-256")
        .digest(contentWithoutVersionLine.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
    assertEquals(KNOWN_SCHEMA_CONTENT_HASH, actualHash)
  }

  private fun classpathSchema(): JsonNode {
    val resourceStream =
      DecompositionManifestBundleJournalSchemaValidator::class.java.classLoader
        .getResourceAsStream(DecompositionManifestBundleJournalSchemaPaths.CLASSPATH_RESOURCE)
    assertNotNull(resourceStream)
    val yamlText = resourceStream.use { it.readBytes().toString(Charsets.UTF_8) }
    return YAMLMapper().readTree(yamlText)
  }

  private companion object {
    const val KNOWN_SCHEMA_CONTENT_HASH: String =
      "01912421481b57bef86163e1ff8951aa41f6add378119afd82b5e5f17c0c6f43"
  }
}
