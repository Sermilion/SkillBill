package skillbill.infrastructure.contracts.workflow.featuretask

import com.fasterxml.jackson.dataformat.yaml.YAMLMapper
import skillbill.contracts.JsonCodec
import skillbill.contracts.workflow.featuretask.ACCEPTANCE_CRITERION_MIGRATION_CONTRACT_VERSION
import skillbill.error.core.SkillBillRuntimeException
import skillbill.error.migration.AcceptanceCriterionMigrationFailureCode
import skillbill.infrastructure.contracts.workflow.featuretask.preservation.AcceptanceCriterionMigrationCodec
import skillbill.workflow.engine.model.DurableWorkflowArtifactFamily
import skillbill.workflow.taskruntime.artifact.asImplementationProgressWireMap
import skillbill.workflow.taskruntime.artifact.historical.toMigrationProvenance
import skillbill.workflow.taskruntime.model.audit.history.AcceptanceCriterionMigrationTarget
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimeWorkflowArtifactMap
import skillbill.workflow.taskruntime.model.persistence.FeatureTaskRuntimeImplementationProgress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import skillbill.contracts.workflow.featuretask.AcceptanceCriterionMigrationKeys as Keys

class AcceptanceCriterionMigrationContractTest {
  @Test
  fun `packaged migration contract matches the strict codec version and rejects altered original bytes`() {
    val stream = assertNotNull(javaClass.classLoader.getResourceAsStream(AcceptanceCriterionMigrationCodec.RESOURCE))
    val schema = stream.use { YAMLMapper().readTree(it) }
    assertEquals(AcceptanceCriterionMigrationCodec.SCHEMA_ID, schema.path("\$id").asText())
    assertEquals("https://json-schema.org/draft/2020-12/schema", schema.path("\$schema").asText())
    assertEquals(
      ACCEPTANCE_CRITERION_MIGRATION_CONTRACT_VERSION,
      schema.path("properties").path(Keys.CONTRACT_VERSION).path("const").asText(),
    )
    assertFalse(schema.path("additionalProperties").asBoolean(true))
    val source =
      "  {\"feature_task_runtime_implementation_progress\":{\"contract_version\":\"0.1\"," +
        "\"criterion_ids\":[\"AC-001\"],\"current_index\":0,\"worker_count\":3,\"launch_count\":7," +
        "\"closed_ids\":[]}}\n"
    val prepared =
      AcceptanceCriterionMigrationCodec.prepare(
        "workflow",
        source,
        AcceptanceCriterionMigrationTarget.COMPATIBLE,
      )
    val artifacts = FeatureTaskRuntimeWorkflowArtifactMap.from(JsonCodec.parseValue(prepared))
    val provenance =
      FeatureTaskRuntimeWorkflowArtifactMap.from(
        DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_CRITERION_MIGRATION.value(artifacts),
      )
    assertEquals(source, provenance.toMigrationProvenance("test").originalArtifactsJson)
    val corrupt =
      FeatureTaskRuntimeWorkflowArtifactMap.from(
        provenance + (Keys.ORIGINAL_ARTIFACTS_JSON to source.trim()),
      )
    val failure = assertFailsWith<SkillBillRuntimeException> { corrupt.toMigrationProvenance("altered") }
    assertEquals(AcceptanceCriterionMigrationFailureCode.INVALID_MIGRATION_PROVENANCE, failure.code)
  }

  @Test
  fun `already strict progress stays byte-identical under compatibility or strict preparation`() {
    val source =
      JsonCodec.valueToJsonString(
        mapOf(
          DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_IMPLEMENTATION_PROGRESS.entry(
            FeatureTaskRuntimeImplementationProgress.initial(listOf("AC-001")).asImplementationProgressWireMap(),
          ),
        ),
      )
    AcceptanceCriterionMigrationTarget.entries.forEach { target ->
      assertEquals(source, AcceptanceCriterionMigrationCodec.prepare("workflow", source, target))
    }
  }
}
