package skillbill.infrastructure.contracts.workflow.featuretask

import com.fasterxml.jackson.dataformat.yaml.YAMLMapper
import skillbill.contracts.workflow.featuretask.AcceptanceCriterionProgressPayloadKeys
import skillbill.contracts.workflow.featuretask.FEATURE_TASK_RUNTIME_IMPLEMENTATION_PROGRESS_CONTRACT_VERSION
import skillbill.contracts.workflow.featuretask.FEATURE_TASK_RUNTIME_IMPLEMENTATION_PROGRESS_PREVIOUS_CONTRACT_VERSION
import skillbill.contracts.workflow.featuretask.FeatureTaskRuntimeImplementationProgressPayloadKeys
import skillbill.error.core.SkillBillRuntimeException
import skillbill.error.shellcontent.FeatureTaskRuntimeFailureCode
import skillbill.infrastructure.contracts.locator.FeatureTaskRuntimeImplementationProgressSchemaPaths
import skillbill.workflow.taskruntime.artifact.asImplementationProgressWireMap
import skillbill.workflow.taskruntime.model.persistence.FeatureTaskRuntimeImplementationProgress
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull

class FeatureTaskRuntimeImplementationProgressSchemaTest {
  @Test
  fun `schema identity and version match the governed constants`() {
    val stream =
      FeatureTaskRuntimeImplementationProgressSchemaValidator::class.java.classLoader
        .getResourceAsStream(FeatureTaskRuntimeImplementationProgressSchemaPaths.CLASSPATH_RESOURCE)
    assertNotNull(stream, "The implementation-progress schema is missing from the classpath.")
    val schema = YAMLMapper().readTree(stream.use { it.readBytes().toString(Charsets.UTF_8) })

    assertEquals(FeatureTaskRuntimeImplementationProgressSchemaPaths.EXPECTED_SCHEMA_ID, schema.path("\$id").asText())
    assertEquals(
      FEATURE_TASK_RUNTIME_IMPLEMENTATION_PROGRESS_CONTRACT_VERSION,
      schema.path("properties").path("contract_version").path("const").asText(),
    )
    assertEquals(false, schema.path("additionalProperties").asBoolean(true))
  }

  @Test
  fun `accepts the initial, reserved, advanced, and terminal cursors`() {
    listOf(
      progress(),
      progress(workerCount = 4),
      progress(currentIndex = 1, workerCount = 1),
      progress(currentIndex = 2, workerCount = 0),
    ).forEach { FeatureTaskRuntimeImplementationProgressSchemaValidator.validate(it, SOURCE) }
  }

  @Test
  fun `rejects corrupt progress with the typed implementation-progress failure`() {
    listOf(
      progress() + ("contract_version" to "0.0"),
      progress() + ("attempts" to emptyList<Any>()),
      progress() - "closed_ids",
      progress() - "launch_count",
      progress() + ("launch_count" to -1),
      progress(ids = listOf("AC-001", "AC-001")),
      progress(ids = listOf("AC-1", "AC-002")),
      progress(ids = emptyList()),
      progress(currentIndex = 1, closed = listOf("AC-002")),
      progress(currentIndex = 1, closed = emptyList()),
      progress(currentIndex = 3),
      progress(workerCount = -1),
      progress(currentIndex = 2, workerCount = 1),
      progress() + ("current_index" to 0.5),
    ).forEach { corrupt ->
      val failure =
        assertFailsWith<SkillBillRuntimeException>("Accepted $corrupt") {
          FeatureTaskRuntimeImplementationProgressSchemaValidator.validate(corrupt, SOURCE)
        }
      assertEquals(FeatureTaskRuntimeFailureCode.INVALID_IMPLEMENTATION_PROGRESS_SCHEMA, failure.code)
      assertContains(failure.message.orEmpty(), SOURCE)
    }
  }

  @Test
  fun `pinned old progress stays readable without granting current evidence authority`() {
    val keys = FeatureTaskRuntimeImplementationProgressPayloadKeys
    val evidence = AcceptanceCriterionProgressPayloadKeys
    val previous =
      progress() -
        setOf(
          evidence.COMPLETED_MUTATIONS,
          evidence.INDEPENDENT_CHECKS,
          evidence.REPAIR_BUDGETS,
          evidence.REPAIR_ACCOUNTING,
          evidence.WORKER_RESERVATION_ID,
        ) + (keys.CONTRACT_VERSION to FEATURE_TASK_RUNTIME_IMPLEMENTATION_PROGRESS_PREVIOUS_CONTRACT_VERSION)
    FeatureTaskRuntimeImplementationProgressSchemaValidator.validatePrevious(previous, SOURCE)
    assertFailsWith<SkillBillRuntimeException> {
      FeatureTaskRuntimeImplementationProgressSchemaValidator.validate(previous, SOURCE)
    }
    assertFailsWith<SkillBillRuntimeException> {
      FeatureTaskRuntimeImplementationProgressSchemaValidator.validatePrevious(
        previous + (keys.WORKER_COUNT to 4),
        SOURCE,
      )
    }
    assertFailsWith<SkillBillRuntimeException> {
      FeatureTaskRuntimeImplementationProgressSchemaValidator.validatePrevious(progress(), SOURCE)
    }
  }

  private fun progress(
    ids: List<String> = listOf("AC-001", "AC-002"),
    currentIndex: Int = 0,
    workerCount: Int = 0,
    closed: List<String> = ids.take(currentIndex),
  ): Map<String, Any?> =
    FeatureTaskRuntimeImplementationProgress.initial(listOf("AC-001", "AC-002")).asImplementationProgressWireMap() +
      linkedMapOf(
        "contract_version" to FEATURE_TASK_RUNTIME_IMPLEMENTATION_PROGRESS_CONTRACT_VERSION,
        "criterion_ids" to ids,
        "current_index" to currentIndex,
        "worker_count" to workerCount,
        "launch_count" to 0,
        "closed_ids" to closed,
      )

  private companion object {
    const val SOURCE = "feature_task_runtime_implementation_progress"
  }
}
