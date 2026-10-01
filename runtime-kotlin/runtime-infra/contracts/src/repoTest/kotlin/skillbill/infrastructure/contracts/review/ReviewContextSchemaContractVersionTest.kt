package skillbill.infrastructure.contracts.review

import com.fasterxml.jackson.dataformat.yaml.YAMLMapper
import skillbill.contracts.review.REVIEW_CONTEXT_CONTRACT_VERSION
import skillbill.infrastructure.contracts.locator.ReviewContextSchemaPaths
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ReviewContextSchemaContractVersionTest {
  private val repoSchema: Path = repoSchemaPath()

  @Test fun `schema identity matches the runtime expectation`() {
    val node = YAMLMapper().readTree(Files.readString(repoSchema))
    assertEquals(ReviewContextSchemaPaths.EXPECTED_SCHEMA_ID, node.path("\$id").asText())
  }

  @Test fun `every envelope branch pins the runtime contract version`() {
    val node = YAMLMapper().readTree(Files.readString(repoSchema))
    val envelopeNames = node.path("oneOf").map { branch -> branch.path("\$ref").asText().substringAfterLast('/') }
    assertTrue(envelopeNames.isNotEmpty(), "Expected governed envelope branches in schema oneOf")
    envelopeNames.forEach { name ->
      val const = node.path("\$defs").path(name).path("properties").path("contract_version").path("const").asText("")
      assertTrue(const.isNotBlank(), "Branch '$name' does not pin contract_version")
      assertEquals(REVIEW_CONTEXT_CONTRACT_VERSION, const, "Branch '$name' pins a stale contract_version")
    }
    assertEquals("2.4", REVIEW_CONTEXT_CONTRACT_VERSION)
    assertTrue(
      envelopeNames.containsAll(listOf("verification_launch", "adjudication_launch", "finding_verdict")),
      "verification_launch, adjudication_launch, and finding_verdict must be oneOf envelope branches: $envelopeNames",
    )
  }

  @Test fun `schema hunk required keys match the index envelope and omit content`() {
    val node = YAMLMapper().readTree(Files.readString(repoSchema))
    val hunk = node.path("\$defs").path("hunk")
    val required = hunk.path("required").map { it.asText() }.toSet()
    val properties = hunk.path("properties").fieldNames().asSequence().toSet()
    val indexKeys =
      setOf(
        "hunk_id",
        "path",
        "old_start",
        "old_count",
        "new_start",
        "new_count",
        "content_digest",
        "evidence_locator",
      )
    assertEquals(indexKeys, required)
    assertEquals(indexKeys, properties)
    assertTrue("content" !in required)
    assertTrue("content" !in properties)
  }

  @Test fun `bundled classpath schema is byte identical to the repository schema`() {
    val bundled =
      javaClass.classLoader.getResourceAsStream(ReviewContextSchemaPaths.CLASSPATH_RESOURCE)
        ?.use { it.readBytes() }
    requireNotNull(bundled) { "Canonical review-context schema is not bundled on the test classpath." }
    assertTrue(
      bundled.contentEquals(Files.readAllBytes(repoSchema)),
      "Bundled review-context schema drifted from ${ReviewContextSchemaPaths.REPO_RELATIVE_PATH}.",
    )
  }

  private fun repoSchemaPath(): Path {
    var current: Path? = Path.of("").toAbsolutePath().normalize()
    while (current != null) {
      val candidate = current.resolve(ReviewContextSchemaPaths.REPO_RELATIVE_PATH)
      if (Files.isRegularFile(candidate)) return candidate
      current = current.parent
    }
    error("Canonical review-context schema not found under ${ReviewContextSchemaPaths.REPO_RELATIVE_PATH}.")
  }
}
