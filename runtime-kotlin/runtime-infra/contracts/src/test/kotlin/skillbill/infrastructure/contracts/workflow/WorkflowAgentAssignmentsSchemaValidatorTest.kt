package skillbill.infrastructure.contracts.workflow

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper
import skillbill.config.model.WORKFLOW_AGENT_ASSIGNMENT_PHASE_IDS
import skillbill.contracts.workflow.identity.task.WORKFLOW_AGENT_ASSIGNMENTS_CONTRACT_VERSION
import skillbill.error.core.SkillBillRuntimeException
import skillbill.error.workflow.WorkflowAgentAssignmentsFailureCode
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import skillbill.contracts.workflow.identity.task.WorkflowAgentAssignmentKeys as K

class WorkflowAgentAssignmentsSchemaValidatorTest {
  private val validator = WorkflowAgentAssignmentsSchemaValidator()
  private val json = ObjectMapper()

  @Test
  fun `schema version matches owner and complete frozen catalog canonicalizes`() {
    val schema =
      WorkflowAgentAssignmentsSchemaValidator::class.java.getResourceAsStream(
        "/skillbill/infrastructure/contracts/workflow-agent-assignments-schema.yaml",
      )!!.use { YAMLMapper().readTree(it) }
    assertEquals(
      WORKFLOW_AGENT_ASSIGNMENTS_CONTRACT_VERSION,
      schema.path("properties").path(K.CONTRACT_VERSION).path("const").asText(),
    )

    val encoded = json.writeValueAsBytes(frozenRecord())
    val canonical = validator.canonicalize(encoded, "fixture-workflow")
    assertContentEquals(canonical, validator.canonicalize(canonical, "fixture-workflow"))
  }

  @Test
  fun `partial legacy evidence is accepted without inventing missing phases`() {
    val record = frozenRecord().toMutableMap()
    record[K.POLICY_MODE] = "legacy"
    record[K.PHASE_ASSIGNMENTS] =
      mapOf(
        "implement" to mapOf(K.AGENT_ID to "cursor", K.TIER to "implementation", K.SOURCE to "legacy_recorded"),
      )
    assertEquals(
      "legacy",
      json.readTree(validator.canonicalize(json.writeValueAsBytes(record), "legacy-workflow"))
        .path(K.POLICY_MODE).asText(),
    )
  }

  @Test
  fun `malformed bytes ambiguous keys unknown phases and unsupported versions fail with owner codes`() {
    assertFailure(byteArrayOf(0xC3.toByte()), WorkflowAgentAssignmentsFailureCode.INVALID_RECORD)
    assertFailure("{\"a\":1,\"a\":2}".toByteArray(), WorkflowAgentAssignmentsFailureCode.INVALID_RECORD)

    val unknownPhase = frozenRecord().toMutableMap()
    unknownPhase[K.PHASE_ASSIGNMENTS] =
      mapOf(
        "unknown" to mapOf(K.AGENT_ID to "claude", K.TIER to "reasoning", K.SOURCE to "cli_phase"),
      )
    assertFailure(json.writeValueAsBytes(unknownPhase), WorkflowAgentAssignmentsFailureCode.INVALID_RECORD)

    val incomplete = frozenRecord().toMutableMap()
    incomplete[K.PHASE_ASSIGNMENTS] =
      phaseAssignments(frozenRecord()).apply {
        remove(WORKFLOW_AGENT_ASSIGNMENT_PHASE_IDS.last())
      }
    assertFailure(json.writeValueAsBytes(incomplete), WorkflowAgentAssignmentsFailureCode.INVALID_RECORD)

    val unknownProvider = frozenRecord().toMutableMap()
    unknownProvider[K.PHASE_ASSIGNMENTS] =
      phaseAssignments(frozenRecord()).apply {
        put(
          WORKFLOW_AGENT_ASSIGNMENT_PHASE_IDS.first(),
          mapOf(K.AGENT_ID to "unknown", K.TIER to "reasoning", K.SOURCE to "cli_phase"),
        )
      }
    assertFailure(json.writeValueAsBytes(unknownProvider), WorkflowAgentAssignmentsFailureCode.INVALID_RECORD)

    val wrongProvenance = frozenRecord().toMutableMap()
    wrongProvenance[K.PHASE_ASSIGNMENTS] =
      phaseAssignments(frozenRecord()).apply {
        put(
          WORKFLOW_AGENT_ASSIGNMENT_PHASE_IDS.first(),
          mapOf(K.AGENT_ID to "claude", K.TIER to "reasoning", K.SOURCE to "legacy_recorded"),
        )
      }
    assertFailure(json.writeValueAsBytes(wrongProvenance), WorkflowAgentAssignmentsFailureCode.INVALID_RECORD)

    val unsupported = frozenRecord().toMutableMap().apply { put(K.CONTRACT_VERSION, "9.9") }
    assertFailure(json.writeValueAsBytes(unsupported), WorkflowAgentAssignmentsFailureCode.UNSUPPORTED_VERSION)
  }

  @Test
  fun `blank admission identity fails before domain reconstruction`() {
    val record = frozenRecord().toMutableMap().apply { put(K.ADMISSION_WORKFLOW_ID, " ") }
    assertFailure(json.writeValueAsBytes(record), WorkflowAgentAssignmentsFailureCode.INVALID_RECORD)
  }

  private fun assertFailure(
    encoded: ByteArray,
    expected: WorkflowAgentAssignmentsFailureCode,
  ) {
    val failure = assertFailsWith<SkillBillRuntimeException> { validator.canonicalize(encoded, "fixture-workflow") }
    assertEquals(expected, failure.code)
  }

  private fun phaseAssignments(record: Map<String, Any?>): MutableMap<String, Any?> =
    checkNotNull(record.getValue(K.PHASE_ASSIGNMENTS) as? Map<*, *>).entries.associate { (key, value) ->
      checkNotNull(key as? String) to value
    }.toMutableMap()

  private fun frozenRecord(): Map<String, Any?> =
    mapOf(
      K.CONTRACT_VERSION to WORKFLOW_AGENT_ASSIGNMENTS_CONTRACT_VERSION,
      K.ADMISSION_WORKFLOW_ID to "fixture-workflow",
      K.POLICY_MODE to "frozen",
      K.INVOKED_AGENT_ID to "claude",
      K.CONFIGURED_AGENT_OVERRIDE_ID to null,
      K.PHASE_ASSIGNMENTS to
        WORKFLOW_AGENT_ASSIGNMENT_PHASE_IDS.associateWith {
          mapOf(K.AGENT_ID to "claude", K.TIER to "implementation", K.SOURCE to "invoking_agent")
        },
    )
}
