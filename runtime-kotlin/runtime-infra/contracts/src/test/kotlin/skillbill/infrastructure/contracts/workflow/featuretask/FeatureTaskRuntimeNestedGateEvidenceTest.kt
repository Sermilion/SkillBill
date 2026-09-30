package skillbill.infrastructure.contracts.workflow.featuretask

import skillbill.contracts.JsonCodec
import skillbill.contracts.workflow.featuretask.FEATURE_TASK_RUNTIME_CONTRACT_VERSION
import skillbill.contracts.workflow.featuretask.FEATURE_TASK_RUNTIME_VALIDATION_EVIDENCE_CONTRACT_VERSION
import skillbill.error.shellcontent.InvalidFeatureTaskRuntimePhaseOutputSchemaError
import skillbill.infrastructure.contracts.FeatureTaskRuntimePhaseOutputSchemaValidator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class FeatureTaskRuntimeNestedGateEvidenceTest {
  @Test
  fun phaseReaderRejectsMalformedNestedEvidenceWithoutSynthesizingProseSuccess() {
    val result = validationResult()
    val run = (result.getValue("gate_runs") as List<*>).single() as Map<*, *>
    val evidence = result.getValue("validation_evidence") as Map<*, *>
    val invalid =
      listOf(
        result +
          mapOf(
            "gate_run_count" to 0,
            "gate_runs" to emptyList<Any>(),
          ),
        result + ("gate_runs" to listOf(run - "command")),
        result + ("gate_runs" to listOf(run - "executed_checks")),
        result + ("gate_runs" to listOf(run + ("repository_checkpoint" to "other"))),
        result + ("validation_evidence" to (evidence + ("contract_version" to "unsupported"))),
        result + (
          "validation_evidence" to (
            evidence + (
              "results" to
                listOf(
                  mapOf(
                    "command" to "other",
                    "exit_code" to 0,
                  ),
                )
            )
          )
        ),
        result +
          mapOf(
            "gate_run_count" to 2,
            "gate_runs" to
              listOf(
                run,
                run +
                  mapOf(
                    "exit_code" to 1,
                    "outcome" to "failed",
                  ),
              ),
          ),
      )
    invalid.forEach { malformed ->
      assertFailsWith<InvalidFeatureTaskRuntimePhaseOutputSchemaError> {
        FeatureTaskRuntimePhaseOutputSchemaValidator().normalizePhaseOutput(
          envelope(malformed),
          "validate",
        )
      }
    }
  }

  @Test
  fun phaseReaderRetainsExplicitCachedSuccessWithNoExecutedChecks() {
    val normalized =
      FeatureTaskRuntimePhaseOutputSchemaValidator().normalizePhaseOutput(
        envelope(validationResult()),
        "validate",
      )
    val decoded = JsonCodec.anyToStringAnyMap(JsonCodec.parseValue(normalized.canonicalJson)).orEmpty()
    val produced = JsonCodec.anyToStringAnyMap(decoded["produced_outputs"]).orEmpty()
    val result = JsonCodec.anyToStringAnyMap(produced["validation_result"]).orEmpty()
    assertEquals(emptyList<Any>(), result["checks"])
    val run = (result.getValue("gate_runs") as List<*>).single() as Map<*, *>
    assertEquals(emptyList<Any>(), run["executed_checks"])
    assertEquals(0, (run["executed_work_units"] as Number).toInt())
    assertEquals("echo validation", run["command"])
  }

  private fun envelope(result: Map<String, Any?>): String =
    JsonCodec.mapToJsonString(
      mapOf(
        "contract_version" to FEATURE_TASK_RUNTIME_CONTRACT_VERSION,
        "phase_id" to "validate",
        "status" to "completed",
        "summary" to "Gate passed.",
        "verdict" to "satisfied",
        "produced_outputs" to
          mapOf(
            "value" to "Gate passed.",
            "validation_passed" to true,
            "validation_result" to result,
          ),
      ),
    )

  private fun validationResult(): Map<String, Any?> =
    mapOf(
      "validation_status" to "passed",
      "checks" to emptyList<String>(),
      "gate_run_count" to 1,
      "repository_checkpoint" to mapOf("fingerprint" to "checkpoint"),
      "gate_runs" to
        listOf(
          mapOf(
            "duration_ms" to 1,
            "outcome" to "passed",
            "cache_mode" to "cache_eligible",
            "executed_work_units" to 0,
            "executed_checks" to emptyList<String>(),
            "command" to "echo validation",
            "exit_code" to 0,
            "repository_checkpoint" to "checkpoint",
          ),
        ),
      "validation_evidence" to
        mapOf(
          "contract_version" to FEATURE_TASK_RUNTIME_VALIDATION_EVIDENCE_CONTRACT_VERSION,
          "results" to
            listOf(
              mapOf(
                "command" to "echo validation",
                "exit_code" to 0,
              ),
            ),
        ),
    )
}
