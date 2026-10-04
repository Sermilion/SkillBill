package skillbill.workflow.decomposition.model

import skillbill.error.core.FailureWireCode
import skillbill.error.core.UnrecognizedFailureWireCodeError
import skillbill.error.featuretask.FeatureTaskRuntimeHandoffProjectionFailureKind
import skillbill.error.featuretask.FeatureTaskRuntimePhaseOutputFailureCode
import skillbill.error.featuretask.FeatureTaskRuntimePhaseOutputFailureKind
import skillbill.error.featuretask.coarseFailureKindForPhaseOutputWireCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class FailureWireCodeConformanceTest {
  @Test
  fun `in-scope failure wire codes are total and injective`() {
    assertWireCodesTotalAndInjective(FeatureTaskRuntimePhaseOutputFailureCode.entries)
    assertWireCodesTotalAndInjective(DecompositionManifestValidationFailureCode.entries)
    assertWireCodesTotalAndInjective(FeatureTaskRuntimePhaseOutputFailureKind.entries)
    assertWireCodesTotalAndInjective(FeatureTaskRuntimeHandoffProjectionFailureKind.entries)
  }

  @Test
  fun `phase-output failure codes map to coarse kinds totally`() {
    FeatureTaskRuntimePhaseOutputFailureCode.entries.forEach { code ->
      assertEquals(code.coarseFailureKind, coarseFailureKindForPhaseOutputWireCode(code.wireValue))
    }
  }

  @Test
  fun `unrecognized phase-output failure wire token is a typed violation not schema invalid`() {
    val error =
      assertFailsWith<UnrecognizedFailureWireCodeError> {
        FeatureTaskRuntimePhaseOutputFailureCode.fromWire("not_a_real_failure_code")
      }
    assertEquals("not_a_real_failure_code", error.rejectedToken)
    assertEquals("FeatureTaskRuntimePhaseOutputFailureCode", error.hierarchy)
  }

  private fun <E> assertWireCodesTotalAndInjective(entries: Collection<E>) where E : Enum<E>, E : FailureWireCode {
    val wireValues = entries.map { it.wireValue }
    assertEquals(
      entries.size,
      wireValues.toSet().size,
      "wire values must be unique within ${entries.firstOrNull()?.javaClass?.simpleName}",
    )
    entries.forEach { assertTrue(it.wireValue.isNotBlank()) }
  }
}
