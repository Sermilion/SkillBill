package skillbill.infrastructure.contracts.workflow.featuretask

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper
import skillbill.contracts.workflow.featuretask.FEATURE_TASK_RUNTIME_CHECKPOINT_IDENTITY_CONTRACT_VERSION
import skillbill.infrastructure.contracts.locator.FeatureTaskRuntimeCheckpointIdentitySchemaPaths
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FeatureTaskRuntimeCheckpointIdentitySchemaContractVersionTest {
  @Test
  fun `canonical repository schema pins its identity and contract version to the runtime constants`() {
    val schemaFile = repositorySchemaFile()
    assertTrue(
      Files.isRegularFile(schemaFile),
      "canonical schema must exist at ${FeatureTaskRuntimeCheckpointIdentitySchemaPaths.REPO_RELATIVE_PATH}",
    )
    val schema = YAMLMapper().readTree(Files.readString(schemaFile))

    assertEquals(
      FeatureTaskRuntimeCheckpointIdentitySchemaPaths.EXPECTED_SCHEMA_ID,
      schema.path("\$id").asText(),
    )
    assertEquals(FEATURE_TASK_RUNTIME_CHECKPOINT_IDENTITY_CONTRACT_VERSION, contractVersionOf(schema))
  }

  @Test
  fun `bundled classpath schema matches the canonical repository schema`() {
    val stream =
      checkNotNull(
        javaClass.classLoader.getResourceAsStream(
          FeatureTaskRuntimeCheckpointIdentitySchemaPaths.CLASSPATH_RESOURCE,
        ),
      )
    val bundled = stream.use { YAMLMapper().readTree(it) }

    assertEquals(YAMLMapper().readTree(Files.readString(repositorySchemaFile())), bundled)
  }

  private fun contractVersionOf(schema: JsonNode): String =
    schema.path("properties").path("contract_version").path("const").asText()

  private fun repositorySchemaFile(): Path {
    var current: Path? = Path.of("").toAbsolutePath().normalize()
    while (current != null) {
      val candidate = current.resolve(FeatureTaskRuntimeCheckpointIdentitySchemaPaths.REPO_RELATIVE_PATH)
      if (Files.isRegularFile(candidate)) return candidate
      current = current.parent
    }
    error("canonical checkpoint-identity schema not found walking up from ${Path.of("").toAbsolutePath()}")
  }
}
