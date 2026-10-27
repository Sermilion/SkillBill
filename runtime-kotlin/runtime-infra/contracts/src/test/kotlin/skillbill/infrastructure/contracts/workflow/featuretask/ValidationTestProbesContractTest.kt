package skillbill.infrastructure.contracts.workflow.featuretask

import skillbill.contracts.workflow.featuretask.FEATURE_TASK_RUNTIME_VALIDATION_TEST_PROBES_CONTRACT_VERSION
import skillbill.error.core.SkillBillRuntimeException
import skillbill.error.shellcontent.FeatureTaskRuntimeFailureCode
import skillbill.error.shellcontent.invalidValidationTestProbes
import skillbill.infrastructure.contracts.ClasspathContractSchemaLoader
import skillbill.infrastructure.contracts.locator.ValidationTestProbesSchemaPaths
import skillbill.workflow.taskruntime.artifact.asValidationTestProbesWireMap
import skillbill.workflow.taskruntime.model.validation.ValidationTestProbe
import skillbill.workflow.taskruntime.model.validation.ValidationTestProbeResult
import skillbill.workflow.taskruntime.model.validation.ValidationTestProbeShape
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import skillbill.contracts.workflow.featuretask.ValidationTestProbePayloadKeys as Keys

class ValidationTestProbesContractTest {
  private val schema =
    ClasspathContractSchemaLoader.readClasspathYamlNode(javaClass.classLoader, SCHEMA_RESOURCE) {
      invalidValidationTestProbes(SCHEMA_RESOURCE, "packaged schema resource is missing.")
    }

  @Test
  fun `schema packages the canonical identity version and probe cap`() {
    assertEquals(ValidationTestProbesSchemaPaths.EXPECTED_SCHEMA_ID, schema.path("\$id").asText())
    assertEquals(
      FEATURE_TASK_RUNTIME_VALIDATION_TEST_PROBES_CONTRACT_VERSION,
      schema.path("properties").path(Keys.CONTRACT_VERSION).path("const").asText(),
    )
    assertEquals(MAX_PROBES, schema.path("properties").path(Keys.TEST_PROBES).path("maxItems").asInt())
    assertFalse(schema.path("additionalProperties").asBoolean(true))
  }

  @Test
  fun `a survived probe is encoded as survived and not repaired and satisfies the schema`() {
    val wire = listOf(probe("t1", ValidationTestProbeResult.SURVIVED)).asValidationTestProbesWireMap()

    ValidationTestProbesSchemaValidator.validate(wire, "survived")
    val stored = (wire[Keys.TEST_PROBES] as List<*>).single() as Map<*, *>

    assertEquals("survived", stored[Keys.RESULT])
    assertEquals(false, stored[Keys.REPAIRED])
  }

  @Test
  fun `the schema validator and the Kotlin shape accept and reject the same lists`() {
    val killed = ValidationTestProbeResult.KILLED
    val survived = ValidationTestProbeResult.SURVIVED
    val notRun = ValidationTestProbeResult.NOT_RUN
    val cases =
      mapOf(
        "full cap" to ((1..MAX_PROBES).map { probe("t$it", killed, repaired = it % 2 == 0) } to true),
        "not_run with reason" to (listOf(probe("t1", notRun, "no targeted command")) to true),
        "survived" to (listOf(probe("t1", survived)) to true),
        "oversized" to ((1..MAX_PROBES + 1).map { probe("t$it", killed) } to false),
        "reasonless not_run" to (listOf(probe("t1", notRun, "")) to false),
        "repaired survivor" to (listOf(probe("t1", survived, repaired = true)) to false),
        "duplicate test" to (listOf(probe("t1", killed), probe("t1", killed)) to false),
        "blank test ref" to (listOf(probe(" ", killed)) to false),
        "blank guarded ref" to (listOf(probe("t1", killed).copy(guardedRef = "")) to false),
      )

    cases.forEach { (name, case) ->
      val (probes, valid) = case
      assertEquals(valid, ValidationTestProbeShape.issue(probes) == null, "Kotlin shape: $name")
      val schemaOutcome = runCatching { ValidationTestProbesSchemaValidator.validate(payload(probes), name) }
      assertEquals(valid, schemaOutcome.isSuccess, "schema validator: $name")
      schemaOutcome.exceptionOrNull()?.let { error ->
        assertEquals(
          FeatureTaskRuntimeFailureCode.INVALID_VALIDATION_TEST_PROBES,
          (error as SkillBillRuntimeException).code,
          name,
        )
      }
    }
  }

  private fun payload(probes: List<ValidationTestProbe>): Map<String, Any?> =
    mapOf(
      Keys.CONTRACT_VERSION to FEATURE_TASK_RUNTIME_VALIDATION_TEST_PROBES_CONTRACT_VERSION,
      Keys.TEST_PROBES to
        probes.map { probe ->
          mapOf(
            Keys.TEST_REF to probe.testRef,
            Keys.GUARDED_REF to probe.guardedRef,
            Keys.RESULT to probe.result.wireValue,
            Keys.REASON to probe.reason,
            Keys.REPAIRED to probe.repaired,
          )
        },
    )

  private fun probe(
    ref: String,
    result: ValidationTestProbeResult,
    reason: String = "still passes without the guarded line",
    repaired: Boolean = false,
  ) = ValidationTestProbe(ref, "src/Guarded:1", result, reason, repaired)

  private companion object {
    const val MAX_PROBES = 6
    const val SCHEMA_RESOURCE = ValidationTestProbesSchemaPaths.CLASSPATH_RESOURCE
  }
}
