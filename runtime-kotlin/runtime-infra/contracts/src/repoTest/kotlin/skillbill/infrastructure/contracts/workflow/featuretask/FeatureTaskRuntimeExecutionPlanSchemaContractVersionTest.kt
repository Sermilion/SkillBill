package skillbill.infrastructure.contracts.workflow.featuretask

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper
import skillbill.contracts.workflow.identity.task.FEATURE_TASK_RUNTIME_EXECUTION_PLAN_CONTRACT_VERSION
import skillbill.contracts.workflow.identity.task.FeatureTaskRuntimeExecutionPlanKeys
import skillbill.infrastructure.contracts.locator.FeatureTaskRuntimeExecutionPlanSchemaPaths
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals

class FeatureTaskRuntimeExecutionPlanSchemaContractVersionTest {
  @Test
  fun `packaged schema preserves canonical identity version and governed wire keys`() {
    val source = YAMLMapper().readTree(Files.readString(repositorySchemaFile()))
    val bundled =
      checkNotNull(
        javaClass.classLoader.getResourceAsStream(
          FeatureTaskRuntimeExecutionPlanSchemaPaths.CLASSPATH_RESOURCE,
        ),
      ).use { YAMLMapper().readTree(it) }
    assertEquals(source, bundled)
    assertEquals(FeatureTaskRuntimeExecutionPlanSchemaPaths.EXPECTED_SCHEMA_ID, source.path("$" + "id").asText())
    assertEquals(
      FEATURE_TASK_RUNTIME_EXECUTION_PLAN_CONTRACT_VERSION,
      source.path("properties").path(FeatureTaskRuntimeExecutionPlanKeys.CONTRACT_VERSION).path("const").asText(),
    )
    val declared =
      FeatureTaskRuntimeExecutionPlanKeys::class.java.declaredFields
        .filter { it.type == String::class.java }
        .map { it.get(null) as String }.toSet()
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
      val candidate = current.resolve(FeatureTaskRuntimeExecutionPlanSchemaPaths.REPO_RELATIVE_PATH)
      if (Files.isRegularFile(candidate)) return candidate
      current = current.parent
    }
    error("canonical execution-plan schema not found")
  }
}
