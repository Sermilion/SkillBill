package skillbill.engine.featuretask.validation

import skillbill.contracts.workflow.featuretask.FEATURE_TASK_RUNTIME_PERSISTENCE_CONTRACT_VERSION
import skillbill.engine.featuretask.validation.model.ValidationGateResolution
import skillbill.error.shellcontent.InvalidWorkflowStateSchemaError
import skillbill.workflow.taskruntime.artifact.decodeValidationGateProgressFromArtifact
import skillbill.workflow.taskruntime.model.validation.FeatureTaskRuntimeValidationGateProgress
import skillbill.workflow.taskruntime.model.validation.FeatureTaskRuntimeValidationGateRepairWindowPhase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class FeatureTaskRuntimeValidationGateTest {
  @Test
  fun `fromArtifactMap decodes findings_open with complete findings and legacy rows without repair_window_phase`() {
    val findingOne = validationFinding("m1", "r1")
    val findingTwo = validationFinding("m2", "r2")
    val decodedOpen =
      decodeProgress(
        gateRunCount = 1,
        outcome = "failed",
        cacheMode = "cache_eligible",
        extra =
          mapOf(
            "complete_findings" to listOf(findingOne, findingTwo),
            "repair_window_phase" to "findings_open",
          ),
      )
    assertEquals(FeatureTaskRuntimeValidationGateRepairWindowPhase.FINDINGS_OPEN, decodedOpen.repairWindowPhase)
    assertEquals(2, decodedOpen.completeFindings.size)
    assertEquals(0, decodedOpen.repairsUsed)

    val decodedLegacy =
      decodeProgress(
        gateRunCount = 1,
        outcome = "passed",
        cacheMode = "cache_eligible",
        extra =
          mapOf(
            "remaining_findings_dropped_count" to 0,
            "confirmation_retries_used" to 3,
            "substantiation_receipts" to listOf(mapOf("identity" to "legacy")),
          ),
      )
    assertEquals(FeatureTaskRuntimeValidationGateRepairWindowPhase.NONE, decodedLegacy.repairWindowPhase)
    assertEquals(1, decodedLegacy.gateRunCount)
    assertEquals(emptyList(), decodedLegacy.completeFindings)
    assertEquals(0, decodedLegacy.repairsUsed)

    val decodedWithRepairsUsed =
      decodeProgress(
        gateRunCount = 2,
        outcome = "failed",
        cacheMode = "forced_full",
        extra =
          mapOf(
            "complete_findings" to listOf(findingOne),
            "repair_window_phase" to "findings_open",
            "repairs_used" to 3,
          ),
      )
    assertEquals(3, decodedWithRepairsUsed.repairsUsed)
  }

  @Test
  fun persistedProgressRejectsUnsupportedVersionsAndMissingExecutionFacts() {
    val progress = progressArtifact(1, "passed", "cache_eligible", emptyMap())
    val run = (progress.getValue("gate_runs") as List<*>).single() as Map<*, *>
    val invalid =
      listOf(
        progress - "contract_version",
        progress + ("contract_version" to "unsupported"),
        progress + ("gate_run_count" to 2),
        progress + ("gate_runs" to listOf(run - "command")),
        progress + ("gate_runs" to listOf(run - "executed_checks")),
        progress + ("gate_runs" to listOf(run + ("outcome" to "unknown"))),
      )
    invalid.forEach { artifact ->
      assertFailsWith<InvalidWorkflowStateSchemaError> { decodeValidationGateProgressFromArtifact(artifact) }
    }
  }

  private fun validationFinding(
    module: String,
    rule: String,
  ): Map<String, String> =
    linkedMapOf(
      "module" to module,
      "rule_or_test_id" to rule,
      "message" to "msg",
      "location" to "loc",
    )

  private fun decodeProgress(
    gateRunCount: Int,
    outcome: String,
    cacheMode: String,
    extra: Map<String, Any?>,
  ): FeatureTaskRuntimeValidationGateProgress =
    requireNotNull(
      decodeValidationGateProgressFromArtifact(
        progressArtifact(gateRunCount, outcome, cacheMode, extra),
      ),
    )

  @Test
  fun `a no-gate fallback pack does not borrow an unrelated catalog gate`() {
    val resolver =
      ValidationGateResolver {
        listOf(
          reviewFallbackPackWithoutGate(),
          kotlinPackWithoutGate().copy(validationGate = validationGateTestDeclaration),
        )
      }
    val resolution = resolver.resolve(listOf("notes.txt"))
    val absent = assertIs<ValidationGateResolution.Absent>(resolution)
    assertEquals("generic", absent.routedPackSlug)
  }

  private fun progressArtifact(
    gateRunCount: Int,
    outcome: String,
    cacheMode: String,
    extra: Map<String, Any?>,
  ): Map<String, Any?> =
    mapOf(
      "contract_version" to FEATURE_TASK_RUNTIME_PERSISTENCE_CONTRACT_VERSION,
      "gate_run_count" to gateRunCount,
      "gate_runs" to
        List(gateRunCount) {
          mapOf(
            "duration_ms" to 1L,
            "outcome" to outcome,
            "cache_mode" to cacheMode,
            "executed_work_units" to 1,
            "executed_checks" to emptyList<String>(),
            "command" to "echo gate",
            "exit_code" to if (outcome == "passed") 0 else 1,
            "repository_checkpoint" to "checkpoint",
          )
        },
      "remaining_findings" to emptyList<Map<String, String?>>(),
    ) + extra
}
