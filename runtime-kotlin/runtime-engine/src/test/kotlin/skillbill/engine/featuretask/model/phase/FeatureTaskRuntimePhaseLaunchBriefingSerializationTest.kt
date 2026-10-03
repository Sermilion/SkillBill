package skillbill.engine.featuretask.model.phase

import skillbill.error.shellcontent.InvalidWorkflowStateSchemaError
import skillbill.workflow.taskruntime.model.handoff.task.FeatureTaskRuntimeHandoffEnvelope
import skillbill.workflow.taskruntime.model.handoff.task.FeatureTaskRuntimeHandoffProjection
import skillbill.workflow.taskruntime.model.handoff.task.FeatureTaskRuntimeHandoffProjectionField
import skillbill.workflow.taskruntime.model.handoff.task.FeatureTaskRuntimeHandoffProjectionValue
import skillbill.workflow.taskruntime.model.handoff.task.FeatureTaskRuntimeHandoffPromptVisibility
import skillbill.workflow.taskruntime.model.handoff.task.FeatureTaskRuntimeHandoffSourceRef
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class FeatureTaskRuntimePhaseLaunchBriefingSerializationTest {
  @Test
  fun `retired gap memory is ignored and omitted when a legacy briefing is saved again`() {
    val briefing = briefing()
    listOf(null, "obsolete gap notes", listOf("unreadable legacy memory")).forEach { memory ->
      val legacy =
        briefing.briefingArtifactWireMap() +
          (FeatureTaskRuntimeHandoffSourceRef.RETIRED_PRIOR_GAP_MEMORY_WIRE to memory)

      val decoded = FeatureTaskRuntimePhaseLaunchBriefing.fromBriefingArtifactWire(legacy)

      assertEquals(briefing, decoded)
      assertEquals(briefing.briefingArtifactWireMap(), decoded.briefingArtifactWireMap())
    }
  }

  @Test
  fun `a briefing round trips through the durable artifact map`() {
    val briefing = briefing()

    assertEquals(
      briefing,
      FeatureTaskRuntimePhaseLaunchBriefing.fromBriefingArtifactWire(briefing.briefingArtifactWireMap()),
    )
  }

  @Test
  fun `a legacy row carrying the removed upstream payload map loud-fails instead of migrating silently`() {
    val legacyRow =
      briefing().briefingArtifactWireMap() +
        ("upstream_outputs_by_phase_id" to mapOf("plan" to "raw payload"))

    val error =
      assertFailsWith<InvalidWorkflowStateSchemaError> {
        FeatureTaskRuntimePhaseLaunchBriefing.fromBriefingArtifactWire(legacyRow)
      }

    assertContains(error.message.orEmpty(), "upstream_outputs_by_phase_id")
    assertContains(error.message.orEmpty(), "Restart")
    assertContains(error.message.orEmpty(), "migrate")
  }

  @Test
  fun `a row missing the handoff envelope loud-fails rather than defaulting to an empty delivery`() {
    val rowWithoutEnvelope = briefing().briefingArtifactWireMap() - "handoff_envelope"

    assertFailsWith<InvalidWorkflowStateSchemaError> {
      FeatureTaskRuntimePhaseLaunchBriefing.fromBriefingArtifactWire(rowWithoutEnvelope)
    }
  }

  @Test
  fun `an unknown briefing field loud-fails with restart or migration guidance`() {
    val incompatible = briefing().briefingArtifactWireMap() + ("future_field" to "private body")

    val error =
      assertFailsWith<InvalidWorkflowStateSchemaError> {
        FeatureTaskRuntimePhaseLaunchBriefing.fromBriefingArtifactWire(incompatible)
      }

    assertContains(error.message.orEmpty(), "unsupported fields")
    assertContains(error.message.orEmpty(), "Restart")
    assertContains(error.message.orEmpty(), "migrate")
  }

  @Test
  fun `a missing or incompatible briefing version loud-fails`() {
    listOf(
      briefing().briefingArtifactWireMap() - "contract_version",
      briefing().briefingArtifactWireMap() + ("contract_version" to "9.9"),
    ).forEach { incompatible ->
      assertFailsWith<InvalidWorkflowStateSchemaError> {
        FeatureTaskRuntimePhaseLaunchBriefing.fromBriefingArtifactWire(incompatible)
      }
    }
  }

  @Test
  fun `legacy criterion progress is ignored and never serialized into a fresh briefing`() {
    val retiredField = "durably_closed_criterion_refs"
    val original = briefing().briefingArtifactWireMap()
    val restored =
      FeatureTaskRuntimePhaseLaunchBriefing.fromBriefingArtifactWire(
        original + (retiredField to "unreadable retired progress"),
      )
    assertEquals(original, restored.briefingArtifactWireMap())
    assertEquals(briefing().acceptanceCriteria, restored.acceptanceCriteria)
  }

  private fun briefing() =
    FeatureTaskRuntimePhaseLaunchBriefing(
      phaseId = "implement",
      specReference = ".feature-specs/SKILL-137/spec.md",
      featureSize = "MEDIUM",
      acceptanceCriteria = listOf("AC-1"),
      mandatesAndOverrides = listOf("mandate-X"),
      handoffEnvelope =
        FeatureTaskRuntimeHandoffEnvelope(
          consumerPhaseId = "implement",
          projections =
            listOf(
              FeatureTaskRuntimeHandoffProjection(
                projectionName = "plan_receipt",
                sourceRef = FeatureTaskRuntimeHandoffSourceRef.UpstreamPhaseOutput("plan"),
                projectionContractId = "feature_task_runtime.upstream_phase_receipt",
                projectionContractVersion = "0.1",
                promptVisibility = FeatureTaskRuntimeHandoffPromptVisibility.PROMPT_VISIBLE,
                fields =
                  listOf(
                    FeatureTaskRuntimeHandoffProjectionField(
                      name = "phase_output_receipt",
                      value = FeatureTaskRuntimeHandoffProjectionValue.Text("""{"plan":"ok"}"""),
                    ),
                  ),
              ),
            ),
        ),
      derivedContextKeys = emptyList(),
      briefingText = "# Feature-task-runtime phase briefing\nphase: implement\n",
    )
}
