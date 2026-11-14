package skillbill.infrastructure.contracts.projectchecks

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper
import skillbill.contracts.config.projectchecks.PROJECT_CHECK_COMMAND_SNAPSHOT_CONTRACT_VERSION
import skillbill.contracts.config.projectchecks.ProjectCheckCommandSnapshotKeys
import skillbill.contracts.config.projectchecks.ProjectCheckConfigKeys
import skillbill.infrastructure.contracts.locator.ProjectCheckCommandSnapshotSchemaPaths
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals

class ProjectCheckCommandSnapshotSchemaContractTest {
  @Test
  fun `packaged snapshot schema preserves canonical identity and governed wire keys`() {
    val source = YAMLMapper().readTree(Files.readString(repositorySchemaFile()))
    val bundled =
      checkNotNull(
        javaClass.classLoader.getResourceAsStream(ProjectCheckCommandSnapshotSchemaPaths.CLASSPATH_RESOURCE),
      ).use { YAMLMapper().readTree(it) }
    assertEquals(source, bundled)
    assertEquals(ProjectCheckCommandSnapshotSchemaPaths.EXPECTED_SCHEMA_ID, source.path("$" + "id").asText())
    assertEquals(
      PROJECT_CHECK_COMMAND_SNAPSHOT_CONTRACT_VERSION,
      source.path("properties").path(ProjectCheckCommandSnapshotKeys.CONTRACT_VERSION).path("const").asText(),
    )
    val declared =
      ProjectCheckCommandSnapshotKeys::class.java.declaredFields
        .filter { it.type == String::class.java }
        .map { it.get(null) as String }
        .toSet() +
        setOf(
          ProjectCheckConfigKeys.COMMANDS,
          ProjectCheckConfigKeys.ARGV,
          ProjectCheckConfigKeys.WORKING_DIRECTORY,
        )
    assertEquals(declared, propertyNames(source))
  }

  private fun propertyNames(node: JsonNode): Set<String> =
    buildSet {
      if (node.isObject) {
        node.path("properties").fieldNames().forEachRemaining { add(it) }
      }
      if (node.isContainerNode) node.elements().forEachRemaining { addAll(propertyNames(it)) }
    }

  private fun repositorySchemaFile(): Path {
    var current: Path? = Path.of("").toAbsolutePath().normalize()
    while (current != null) {
      val candidate = current.resolve(ProjectCheckCommandSnapshotSchemaPaths.REPO_RELATIVE_PATH)
      if (Files.isRegularFile(candidate)) return candidate
      current = current.parent
    }
    error("canonical project-check-command snapshot schema not found")
  }
}
