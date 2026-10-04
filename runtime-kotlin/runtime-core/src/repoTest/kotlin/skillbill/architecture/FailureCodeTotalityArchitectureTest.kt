package skillbill.architecture

import skillbill.error.core.FailureWireCode
import skillbill.error.featuretask.FeatureTaskRuntimeHandoffProjectionFailureKind
import skillbill.error.featuretask.FeatureTaskRuntimePhaseOutputFailureCode
import skillbill.error.featuretask.FeatureTaskRuntimePhaseOutputFailureKind
import skillbill.workflow.decomposition.model.DecompositionManifestValidationFailureCode
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class FailureCodeTotalityArchitectureTest {
  @Test
  fun `in-scope failure wire codes stay total and injective`() {
    val violations = inScopeFailureWireCodeViolations()
    assertEquals(
      emptyList(),
      violations,
      "Each in-scope failure case must resolve to exactly one wire code and each wire code must belong to exactly one" +
        "case.",
    )
  }

  @Test
  fun `failure wire code totality scanner fires on synthetic orphan and duplicate fixtures`() {
    val orphanViolation =
      wireCodeViolation(
        hierarchy = "SyntheticFailureCode",
        entryCount = 2,
        wireValues = listOf("alpha"),
      )
    val orphan = assertNotNull(orphanViolation)
    assertTrue(
      orphan.contains("orphaned wire value"),
      "Regression if adding a failure case without a unique wire code stops failing the architecture gate.",
    )

    val duplicateViolation =
      wireCodeViolation(
        hierarchy = "SyntheticFailureCode",
        entryCount = 2,
        wireValues = listOf("shared", "shared"),
      )
    val duplicate = assertNotNull(duplicateViolation)
    assertTrue(
      duplicate.contains("duplicate wire value"),
      "Regression if an orphaned or duplicated failure wire code no longer fails totality enforcement.",
    )
  }

  @Test
  fun `every custom throwable in production main is listed in the throwable baseline`() {
    assertTrue(
      ArchitectureScanSupport.customThrowableRows().isNotEmpty(),
      "Regression if the throwable scan finds nothing in the real repository.",
    )
    val drift = ArchitectureScanSupport.customThrowableDrift()
    assertEquals(emptyList(), drift, drift.joinToString("\n"))
  }

  @Test
  fun `throwable baseline guard reports an unlisted declaration and a stale row`() {
    val root = Files.createTempDirectory("skillbill-custom-throwable-drift")
    seedModuleScanTreeWithEngineViolation(
      root,
      """
      package skillbill.engine

      class SyntheticDirectFailure(message: String) : IllegalStateException(message)

      class SyntheticMultilineFailure(
        message: String,
        val detail: String,
      ) : SyntheticDirectFailure(
          message,
        )

      sealed class SyntheticSealedFailure : kotlin.RuntimeException() {
        data class Variant(val name: String) : SyntheticSealedFailure()
      }

      sealed interface SyntheticOutcome {
        data class Error(val message: String) : SyntheticOutcome
      }

      class SyntheticKotlinErrorFailure : Error("boom")

      class SyntheticOutcomeConsumer(val message: String) : SyntheticOutcome.Error(message)

      val syntheticLiteral = ${"\"\"\""}
        class Fake : Exception()
      ${"\"\"\""}
      """.trimIndent(),
    )
    val drift =
      ArchitectureScanSupport.customThrowableDrift(
        scanRoot = root,
        readBaseline = { "runtime-engine:SyntheticRemovedFailure\n" },
      )
    assertEquals(
      listOf(
        "runtime-engine declares custom throwable SyntheticDirectFailure; " +
          "return a result, use require/check, or throw SkillBillRuntimeException with a code.",
        "runtime-engine declares custom throwable SyntheticKotlinErrorFailure; " +
          "return a result, use require/check, or throw SkillBillRuntimeException with a code.",
        "runtime-engine declares custom throwable SyntheticMultilineFailure; " +
          "return a result, use require/check, or throw SkillBillRuntimeException with a code.",
        "runtime-engine declares custom throwable SyntheticSealedFailure; " +
          "return a result, use require/check, or throw SkillBillRuntimeException with a code.",
        "runtime-engine declares custom throwable SyntheticSealedFailure.Variant; " +
          "return a result, use require/check, or throw SkillBillRuntimeException with a code.",
        "runtime-engine:SyntheticRemovedFailure is listed in custom-throwable-baseline.txt " +
          "but no longer exists; re-record the baseline.",
      ),
      drift,
      "Regression if the throwable baseline guard misses a transitive, multi-line or nested throwable, counts " +
        "a result variant or literal text, or ignores a stale baseline row.",
    )
  }

  private fun inScopeFailureWireCodeViolations(): List<String> =
    listOf(
      wireCodeEntries(FeatureTaskRuntimePhaseOutputFailureCode.entries.toList()),
      wireCodeEntries(DecompositionManifestValidationFailureCode.entries.toList()),
      wireCodeEntries(FeatureTaskRuntimePhaseOutputFailureKind.entries.toList()),
      wireCodeEntries(FeatureTaskRuntimeHandoffProjectionFailureKind.entries.toList()),
    ).flatten()

  private fun wireCodeEntries(entries: List<FailureWireCode>): List<String> {
    val wireValues = entries.map { it.wireValue }
    return wireCodeViolation(
      hierarchy = entries.firstOrNull()?.javaClass?.simpleName ?: "<unknown>",
      entryCount = entries.size,
      wireValues = wireValues,
    )?.let { violation -> listOf(violation) }.orEmpty()
  }

  private fun wireCodeViolation(
    hierarchy: String,
    entryCount: Int,
    wireValues: List<String>,
  ): String? {
    if (wireValues.toSet().size != wireValues.size) {
      return "$hierarchy has duplicate wire value"
    }
    if (entryCount != wireValues.size) {
      return "$hierarchy has orphaned wire value"
    }
    if (wireValues.any(String::isBlank)) {
      return "$hierarchy has blank wire value"
    }
    return null
  }
}
