package skillbill.engine.featuretask.persist

import skillbill.error.core.SkillBillRuntimeException
import skillbill.workflow.engine.model.DurableWorkflowArtifactFamily
import skillbill.workflow.taskruntime.model.core.FEATURE_TASK_RUNTIME_INCOMPATIBLE_RECORD_GUIDANCE
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFailsWith

private val FEATURE_TASK_RUNTIME_DELIVERED_PROJECTIONS_ARTIFACT_KEY =
  DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_DELIVERED_PROJECTIONS.label()

class FeatureTaskRuntimeHandoffEnvelopeArtifactDecodersTest {
  @Test
  fun `delivered projection read translates schema failure with consumer identity and migration guidance`() {
    val projectionKey = "wftr-1:review:2:plan:1"
    val artifacts =
      mapOf(
        FEATURE_TASK_RUNTIME_DELIVERED_PROJECTIONS_ARTIFACT_KEY to
          mapOf(
            projectionKey to
              mapOf(
                "consumer_phase_id" to "review",
                "contract_version" to "legacy",
              ),
          ),
      )

    val error =
      assertFailsWith<SkillBillRuntimeException> {
        deliveredProjectionsFrom(
          artifacts = artifacts,
          validatePersistenceRecord = { "unsupported legacy contract" },
        )
      }

    assertContains(error.message.orEmpty(), "consumer-phase:review")
    assertContains(error.message.orEmpty(), "delivered-projection:$projectionKey")
    assertContains(error.message.orEmpty(), FEATURE_TASK_RUNTIME_INCOMPATIBLE_RECORD_GUIDANCE)
  }
}
