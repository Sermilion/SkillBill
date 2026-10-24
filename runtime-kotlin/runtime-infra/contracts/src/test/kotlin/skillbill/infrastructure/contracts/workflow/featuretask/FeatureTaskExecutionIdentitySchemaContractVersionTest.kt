package skillbill.infrastructure.contracts.workflow.featuretask

import com.fasterxml.jackson.dataformat.yaml.YAMLMapper
import skillbill.contracts.workflow.identity.task.FEATURE_TASK_EXECUTION_IDENTITY_CONTRACT_VERSION
import skillbill.infrastructure.contracts.locator.FeatureTaskExecutionIdentitySchemaPaths
import skillbill.workflow.model.FeatureTaskExecutionIdentity
import skillbill.workflow.model.FeatureTaskExecutionIdentityPolicy
import skillbill.workflow.model.FeatureTaskWorkflowMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class FeatureTaskExecutionIdentitySchemaContractVersionTest {
  @Test
  fun `execution identity schema version and id match runtime constants`() {
    val stream =
      assertNotNull(
        javaClass.classLoader.getResourceAsStream(FeatureTaskExecutionIdentitySchemaPaths.CLASSPATH_RESOURCE),
      )
    val schema = stream.use { YAMLMapper().readTree(it) }
    assertEquals(
      FEATURE_TASK_EXECUTION_IDENTITY_CONTRACT_VERSION,
      schema.path("properties").path("contract_version").path("const").asText(),
    )
    assertEquals(FeatureTaskExecutionIdentitySchemaPaths.EXPECTED_SCHEMA_ID, schema.path("\$id").asText())
  }

  @Test
  fun `governed spec path pattern accepts the same paths as the identity policy`() {
    val stream =
      assertNotNull(
        javaClass.classLoader.getResourceAsStream(FeatureTaskExecutionIdentitySchemaPaths.CLASSPATH_RESOURCE),
      )
    val schema = stream.use { YAMLMapper().readTree(it) }
    val pattern = Regex(schema.path("properties").path("governed_spec_path").path("pattern").asText())

    GOVERNED_SPEC_PATH_SAMPLES.forEach { sample ->
      assertEquals(policyAccepts(sample), pattern.containsMatchIn(sample), "pattern and policy disagree on '$sample'")
    }
    assertTrue(pattern.containsMatchIn("specs/SKILL-1-example/spec.md"))
    assertTrue(pattern.containsMatchIn(".feature-specs/SKILL-1-example/spec.md"))
    assertFalse(pattern.containsMatchIn("specs/../spec.md"))
    assertFalse(pattern.containsMatchIn("docs/SKILL-1-example/spec.md"))
  }

  private fun policyAccepts(governedSpecPath: String): Boolean =
    runCatching {
      FeatureTaskExecutionIdentityPolicy.validate(
        FeatureTaskExecutionIdentity(
          workflowId = "wftr-parity",
          normalizedIssueKey = "SKILL-1",
          repositoryIdentity = "${FeatureTaskExecutionIdentityPolicy.REPOSITORY_IDENTITY_PREFIX}/srv/repo",
          governedSpecPath = governedSpecPath,
          mode = FeatureTaskWorkflowMode.RUNTIME,
        ),
      )
    }.isSuccess

  private companion object {
    val GOVERNED_SPEC_PATH_SAMPLES =
      listOf(
        "specs/SKILL-1-example/spec.md",
        "specs/SKILL-1-example/done/spec.md",
        "specs/spec_subtask_1_example.md",
        ".feature-specs/SKILL-1-example/spec.md",
        ".feature-specs/SKILL-1-example/done/spec.md",
        "specs/SKILL-1-example/spec.txt",
        ".feature-specs/SKILL-1-example/spec.txt",
        "specs/../spec.md",
        "specs/SKILL-1/../spec.md",
        ".feature-specs/../spec.md",
        ".feature-specs/./spec.md",
        "specs/SKILL-1\nexample/spec.md",
        "docs/SKILL-1-example/spec.md",
        "/specs/SKILL-1-example/spec.md",
        "spec.md",
      )
  }
}
