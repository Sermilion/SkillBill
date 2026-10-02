package skillbill.workflow.model.persistence.artifact

import skillbill.contracts.scaffold.wire.optionalList
import skillbill.contracts.scaffold.wire.optionalString
import skillbill.error.shellcontent.InvalidWorkflowStateSchemaError
import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class FeatureTaskRuntimePersistenceMapFieldsTest {
  @Test
  fun `exact int coercion rejects lossy big decimal`() {
    assertFailsWith<InvalidWorkflowStateSchemaError> {
      durableArtifactMapReader(mapOf("n" to BigDecimal("1.5"))).requiredInt("n")
    }
  }

  @Test
  fun `durable reader preserves long string round trip`() {
    val map = mapOf("branch" to "feat/x")
    assertEquals("feat/x", durableArtifactMapReader(map).requiredString("branch"))
  }

  @Test
  fun `durable reader exposes typed scalar collection and object accessors`() {
    val reader =
      durableArtifactMapReader(
        mapOf(
          "count" to 3,
          "bytes" to 12L,
          "enabled" to true,
          "names" to listOf("plan", "implement"),
          "nested" to mapOf("value" to "kept"),
          "optional_text" to "optional",
          "optional_bytes" to 4L,
          "optional_enabled" to false,
          "optional_names" to listOf("audit"),
          "optional_values" to listOf(1, "two"),
          "optional_nested" to mapOf("value" to "optional"),
        ),
      )
    assertEquals(3, reader.requiredInt("count"))
    assertEquals(12L, reader.requiredLong("bytes"))
    assertEquals(true, reader.requiredBoolean("enabled"))
    assertEquals(listOf("plan", "implement"), reader.requiredStringList("names"))
    assertEquals(mapOf("value" to "kept"), reader.requiredNestedObject("nested"))
    assertEquals("optional", reader.optionalString("optional_text"))
    assertEquals(4L, reader.optionalLong("optional_bytes"))
    assertEquals(false, reader.optionalBoolean("optional_enabled"))
    assertEquals(listOf("audit"), reader.optionalStringList("optional_names"))
    assertEquals(listOf(1, "two"), reader.optionalList("optional_values"))
    assertEquals(mapOf("value" to "optional"), reader.optionalNestedObject("optional_nested"))
  }
}
