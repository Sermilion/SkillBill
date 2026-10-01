package skillbill.infrastructure.contracts.workflow.featuretask

import skillbill.contracts.workflow.featuretask.FEATURE_TASK_RUNTIME_HANDOFF_ENVELOPE_CONTRACT_VERSION
import skillbill.error.featuretask.FeatureTaskRuntimeHandoffProjectionFailureKind
import skillbill.error.shellcontent.InvalidFeatureTaskRuntimeHandoffProjectionError
import skillbill.infrastructure.contracts.FeatureTaskRuntimeWireArtifactValidator
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimeWireArtifactKind
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimeWorkflowArtifactMap
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class FeatureTaskRuntimeHandoffEnvelopeSchemaValidatorTest {
  private val validator = FeatureTaskRuntimeWireArtifactValidator()

  @Test
  fun `every closed wire artifact kind dispatches to its schema validator`() {
    val expectedErrors =
      mapOf(
        FeatureTaskRuntimeWireArtifactKind.QUARANTINE_RECORD to
          "InvalidFeatureTaskRuntimeQuarantineSchemaError",
        FeatureTaskRuntimeWireArtifactKind.IMPLEMENTATION_ATTEMPT to
          "InvalidFeatureTaskRuntimeImplementationAttemptSchemaError",
        FeatureTaskRuntimeWireArtifactKind.BUILD_RECEIPT to
          "InvalidFeatureTaskRuntimeBuildReceiptSchemaError",
        FeatureTaskRuntimeWireArtifactKind.HANDOFF_DECLARATION to
          "InvalidFeatureTaskRuntimePhaseHandoffSchemaError",
        FeatureTaskRuntimeWireArtifactKind.HANDOFF_PERSISTENCE_RECORD to
          "InvalidFeatureTaskRuntimePersistenceSchemaError",
        FeatureTaskRuntimeWireArtifactKind.HANDOFF_MEASUREMENT to
          "InvalidFeatureTaskRuntimeProjectionMeasurementSchemaError",
        FeatureTaskRuntimeWireArtifactKind.HANDOFF_SHARED_EVIDENCE_PROJECTION to
          "InvalidFeatureTaskRuntimeSharedEvidenceProjectionSchemaError",
        FeatureTaskRuntimeWireArtifactKind.HANDOFF_ENVELOPE to
          "InvalidFeatureTaskRuntimeHandoffProjectionError",
        FeatureTaskRuntimeWireArtifactKind.GOAL_PROGRESS_EVENT to
          "InvalidGoalProgressEventSchemaError",
        FeatureTaskRuntimeWireArtifactKind.GOAL_OBSERVABILITY_EVENT to
          "InvalidGoalObservabilityEventSchemaError",
        FeatureTaskRuntimeWireArtifactKind.GOAL_PLANNING_PREPARATION_ENVELOPE to
          "InvalidGoalPlanningPreparationSchemaError",
      )

    FeatureTaskRuntimeWireArtifactKind.entries.forEach { kind ->
      val error =
        assertFailsWith<RuntimeException> {
          validator.validate(kind, FeatureTaskRuntimeWorkflowArtifactMap.from(emptyMap<String, Any?>()), kind.name)
        }
      assertEquals(expectedErrors.getValue(kind), error::class.simpleName)
    }
  }

  @Test
  fun `a well-formed envelope validates through the domain port`() {
    validateEnvelope(validator, envelope(), "wftr-1")
  }

  @Test
  fun `a wrong contract version is rejected`() {
    val error =
      assertFailsWith<InvalidFeatureTaskRuntimeHandoffProjectionError> {
        validateEnvelope(validator, envelope(contractVersion = "9.9"), "wftr-1")
      }

    assertEquals(FeatureTaskRuntimeHandoffProjectionFailureKind.SCHEMA_INVALID, error.failureKind)
    assertEquals("implement", error.consumerPhaseId)
    assertEquals("wftr-1", error.workflowId)
  }

  @Test
  fun `an undeclared wire field is rejected by strict additionalProperties`() {
    val invalid = envelope() + ("upstream_outputs_by_phase_id" to mapOf("plan" to "raw"))

    assertFailsWith<InvalidFeatureTaskRuntimeHandoffProjectionError> { validateEnvelope(validator, invalid) }
  }

  @Test
  fun `a forbidden raw-context field name is rejected`() {
    val error =
      assertFailsWith<InvalidFeatureTaskRuntimeHandoffProjectionError> {
        validateEnvelope(validator, envelope(fieldName = "payload"))
      }

    assertContains(error.message.orEmpty(), "projections")
  }

  @Test
  fun `an unknown projection value kind is rejected`() {
    assertFailsWith<InvalidFeatureTaskRuntimeHandoffProjectionError> {
      validateEnvelope(
        validator,
        envelope(field = mapOf("name" to "phase_output_receipt", "kind" to "raw_blob", "text" to "x")),
      )
    }
  }

  @Test
  fun `a delivered prior_gap_memory source ref is rejected by the schema gate`() {
    assertFailsWith<InvalidFeatureTaskRuntimeHandoffProjectionError> {
      validateEnvelope(validator, priorGapMemoryEnvelope(), "wftr-1")
    }
  }

  private fun priorGapMemoryEnvelope(): Map<String, Any?> =
    envelope().toMutableMap().apply {
      put(
        "projections",
        listOf(
          mapOf(
            "projection_name" to "prior_gap_memory",
            "source_ref" to "prior_gap_memory",
            "projection_contract_id" to "feature_task_runtime.prior_gap_memory",
            "projection_contract_version" to "0.2",
            "prompt_visibility" to "prompt_visible",
            "producer_iteration" to mapOf("phase_id" to "implement", "iteration" to 1),
            "fields" to
              listOf(
                mapOf("name" to "round", "kind" to "text", "text" to "2"),
                mapOf(
                  "name" to "prior_audit_values",
                  "kind" to "text_list",
                  "items" to listOf("""{"gaps":[{"criterion":"AC-002","note":"gap"}]}"""),
                ),
              ),
          ),
        ),
      )
    }

  @Test
  fun `a compact reference longer than the schema bound is rejected`() {
    assertFailsWith<InvalidFeatureTaskRuntimeHandoffProjectionError> {
      validateEnvelope(
        validator,
        envelope(
          field =
            mapOf(
              "name" to "phase_output_receipt",
              "kind" to "compact_reference",
              "reference_kind" to "private_evidence_artifact",
              "reference_value" to "a".repeat(600),
            ),
        ),
      )
    }
  }

  private fun validateEnvelope(
    validator: FeatureTaskRuntimeWireArtifactValidator,
    envelope: Map<String, Any?>,
    label: String = "handoff-envelope",
  ) {
    validator.validate(
      FeatureTaskRuntimeWireArtifactKind.HANDOFF_ENVELOPE,
      FeatureTaskRuntimeWorkflowArtifactMap.from(envelope),
      label,
    )
  }

  private fun envelope(
    contractVersion: String = FEATURE_TASK_RUNTIME_HANDOFF_ENVELOPE_CONTRACT_VERSION,
    fieldName: String = "phase_output_receipt",
    field: Map<String, Any?> = mapOf("name" to fieldName, "kind" to "text", "text" to """{"plan":"ok"}"""),
  ): Map<String, Any?> =
    mapOf(
      "contract_version" to contractVersion,
      "consumer_phase_id" to "implement",
      "projections" to
        listOf(
          mapOf(
            "projection_name" to "plan_receipt",
            "source_ref" to "upstream_phase_output:plan",
            "projection_contract_id" to "feature_task_runtime.upstream_phase_receipt",
            "projection_contract_version" to "0.1",
            "prompt_visibility" to "prompt_visible",
            "producer_iteration" to mapOf("phase_id" to "plan", "iteration" to 1),
            "fields" to listOf(field),
          ),
        ),
      "repository_checkpoint" to mapOf("fingerprint" to "head-abc"),
    )
}
