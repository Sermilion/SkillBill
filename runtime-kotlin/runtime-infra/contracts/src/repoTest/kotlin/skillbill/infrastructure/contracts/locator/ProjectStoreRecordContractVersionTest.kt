package skillbill.infrastructure.contracts.locator

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import skillbill.contracts.projectstore.PROJECT_STORE_RECORD_CONTRACT_VERSION
import skillbill.contracts.projectstore.ProjectStoreRecordKeys
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals

class ProjectStoreRecordContractVersionTest {
  @Test
  fun `schema version and property keys match the Kotlin contract`() {
    val schema =
      ObjectMapper(YAMLFactory()).readTree(
        Files.readString(repoRootFromTest().resolve("orchestration/contracts/project-store-record-schema.yaml")),
      )

    assertEquals(false, schema.path("additionalProperties").asBoolean(true))
    val properties = schema.path("properties")
    assertEquals(PROJECT_STORE_RECORD_CONTRACT_VERSION, properties.path("contract_version").path("const").asText())
    assertEquals(
      setOf(
        ProjectStoreRecordKeys.CONTRACT_VERSION,
        ProjectStoreRecordKeys.PROJECT_ID,
        ProjectStoreRecordKeys.KEY_KIND,
        ProjectStoreRecordKeys.COMMON_DIR,
        ProjectStoreRecordKeys.SHARED_COMMON_DIRS,
        ProjectStoreRecordKeys.ROOT_COMMITS,
        ProjectStoreRecordKeys.REMOTE_URL,
        ProjectStoreRecordKeys.LAST_SEEN,
        ProjectStoreRecordKeys.LINKED_TO,
        ProjectStoreRecordKeys.LEGACY_STATE_NOTICE_AT,
      ),
      properties.fieldNames().asSequence().toSet(),
    )
  }
}

private fun repoRootFromTest(): Path {
  var current = Path.of("").toAbsolutePath().normalize()
  while (current.parent != null) {
    val hasSettings = Files.isRegularFile(current.resolve("runtime-kotlin/settings.gradle.kts"))
    val hasContracts = Files.isDirectory(current.resolve("orchestration/contracts"))
    if (hasSettings && hasContracts) return current
    current = current.parent
  }
  error("Could not locate skill-bill repo root from ${Path.of("").toAbsolutePath().normalize()}")
}
