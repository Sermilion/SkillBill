
package skillbill.workflow.taskruntime.model.phase

import kotlin.test.Test
import kotlin.test.assertEquals

class FeatureTaskRuntimePlanningProjectionModelsTest {
  @Test
  fun `SHARED_REVIEW_EVIDENCE_ID remains the shared review evidence contract id`() {
    assertEquals(
      "feature_task_runtime.shared_review_evidence",
      FeatureTaskRuntimePlanningProjectionContract.SHARED_REVIEW_EVIDENCE_ID,
    )
  }
}
