package skillbill.infrastructure.contracts.workflow.featuretask

import skillbill.contracts.workflow.featuretask.INTERACTION_SCENARIO_CATALOG_CONTRACT_VERSION
import skillbill.error.core.SkillBillRuntimeException
import skillbill.error.shellcontent.FeatureTaskRuntimeFailureCode
import skillbill.error.shellcontent.invalidInteractionScenarioCatalog
import skillbill.infrastructure.contracts.ClasspathContractSchemaLoader
import skillbill.infrastructure.contracts.locator.InteractionScenarioCatalogSchemaPaths
import skillbill.review.spec.model.InteractionScenario
import skillbill.review.spec.model.InteractionScenarioCatalog
import skillbill.review.spec.model.TransitionalRegression
import skillbill.workflow.taskruntime.artifact.asInteractionScenarioCatalogWireMap
import skillbill.workflow.taskruntime.artifact.toInteractionScenarioCatalog
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimeWorkflowArtifactMap
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import skillbill.contracts.workflow.featuretask.InteractionScenarioCatalogPayloadKeys as Keys

class InteractionScenarioCatalogContractTest {
  private val schema =
    ClasspathContractSchemaLoader.readClasspathYamlNode(javaClass.classLoader, SCHEMA_RESOURCE) {
      invalidInteractionScenarioCatalog(SCHEMA_RESOURCE, "packaged schema resource is missing.")
    }

  @Test
  fun `schema packages the canonical identity and version`() {
    assertEquals(InteractionScenarioCatalogSchemaPaths.EXPECTED_SCHEMA_ID, schema.path("\$id").asText())
    assertEquals(
      INTERACTION_SCENARIO_CATALOG_CONTRACT_VERSION,
      schema.path("properties").path(Keys.CONTRACT_VERSION).path("const").asText(),
    )
    assertFalse(schema.path("additionalProperties").asBoolean(true))
  }

  @Test
  fun `a catalog round trips through the wire codec and satisfies the schema`() {
    val catalog = catalog()

    val wire = catalog.asInteractionScenarioCatalogWireMap()

    assertTrue(violations(wire).isEmpty())
    assertEquals(catalog, wire.toInteractionScenarioCatalog("test"))
  }

  @Test
  fun `a payload that breaks the schema is rejected by both the schema and the codec`() {
    val valid = catalog().asInteractionScenarioCatalogWireMap()
    val oneCriterion =
      artifact(valid, Keys.SCENARIOS to listOf(scenarioMap(valid) + (Keys.CRITERION_REFS to listOf("AC-001"))))

    val sevenScenarios =
      artifact(
        valid,
        Keys.SCENARIOS to (1..MAX_SCENARIOS + 1).map { scenarioMap(valid) + (Keys.SCENARIO_REF to "IS-00$it") },
      )

    assertTrue(violations(oneCriterion).isNotEmpty())
    assertTrue(violations(sevenScenarios).isNotEmpty())
    listOf(
      oneCriterion,
      sevenScenarios,
      artifact(valid, Keys.CONTRACT_VERSION to "9.0"),
      artifact(valid, "extra" to true),
    ).forEach { payload ->
      val error = assertFailsWith<SkillBillRuntimeException> { payload.toInteractionScenarioCatalog("bad") }
      assertEquals(FeatureTaskRuntimeFailureCode.INVALID_INTERACTION_SCENARIO_CATALOG, error.code)
    }
  }

  private fun artifact(
    base: Map<String, Any?>,
    override: Pair<String, Any?>,
  ): FeatureTaskRuntimeWorkflowArtifactMap = FeatureTaskRuntimeWorkflowArtifactMap.from(base + override)

  private fun violations(wire: Map<String, Any?>) =
    ClasspathContractSchemaLoader.validate(
      ClasspathContractSchemaLoader.compileUncachedYamlNode(schema),
      ClasspathContractSchemaLoader.valueToTree(wire),
    )

  private fun scenarioMap(wire: Map<String, Any?>): Map<String, Any?> {
    val scenarios = wire[Keys.SCENARIOS] as List<*>
    return FeatureTaskRuntimeWorkflowArtifactMap.from(scenarios.first())
  }

  private fun catalog() =
    InteractionScenarioCatalog(
      specDigest = "a".repeat(DIGEST_LENGTH),
      scenarios =
        listOf(
          InteractionScenario(
            scenarioRef = "IS-001",
            criterionRefs = listOf("AC-001", "AC-002"),
            subtaskRefs = listOf(1, 2),
            entryPoint = "the operator runs the command",
            preconditions = "a prepared bundle exists",
            steps = "run it, then rerun it",
            outcome = "both runs report the same result",
          ),
        ),
      transitionalRegressions =
        listOf(
          TransitionalRegression(
            regressionRef = "TR-001",
            behavior = "the legacy path is disabled until the replacement lands",
            disabledBySubtask = 1,
            restoredBySubtask = 2,
          ),
        ),
    )

  private companion object {
    const val DIGEST_LENGTH = 64
    const val MAX_SCENARIOS = 6
    const val SCHEMA_RESOURCE = InteractionScenarioCatalogSchemaPaths.CLASSPATH_RESOURCE
  }
}
