package skillbill.workflow.taskruntime.model.persistence

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class FeatureTaskRuntimePriorGapMemoryTest {
  @Test
  fun `fromMap decodes legacy prior gap memory for database compatibility`() {
    val priorAuditValues = listOf("""{"gaps":[{"criterion":"AC-002","note":"gap"}]}""")
    val decoded =
      FeatureTaskRuntimePriorGapMemory.fromMap(
        mapOf(
          FeatureTaskRuntimePriorGapMemory.FIELD_ROUND to 2,
          FeatureTaskRuntimePriorGapMemory.FIELD_PRIOR_AUDIT_VALUES to priorAuditValues,
        ),
      )
    assertEquals(2, decoded.round)
    assertEquals(priorAuditValues, decoded.priorAuditValues)
    assertFailsWith<IllegalArgumentException> { FeatureTaskRuntimePriorGapMemory.fromMap(mapOf()) }
  }

  @Test
  fun `fromMap rejects blank prior audit values`() {
    assertFailsWith<IllegalArgumentException> {
      FeatureTaskRuntimePriorGapMemory.fromMap(
        mapOf(
          FeatureTaskRuntimePriorGapMemory.FIELD_ROUND to 1,
          FeatureTaskRuntimePriorGapMemory.FIELD_PRIOR_AUDIT_VALUES to listOf(" "),
        ),
      )
    }
  }
}
