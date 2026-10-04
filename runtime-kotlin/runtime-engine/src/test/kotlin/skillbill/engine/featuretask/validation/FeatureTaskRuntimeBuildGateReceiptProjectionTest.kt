package skillbill.engine.featuretask.validation

import skillbill.contracts.JsonCodec
import skillbill.infrastructure.contracts.workflow.featuretask.FeatureTaskRuntimeBuildReceiptSchemaValidator
import skillbill.workflow.taskruntime.model.validation.FeatureTaskRuntimeValidationGateRunRecord
import kotlin.test.Test

class FeatureTaskRuntimeBuildGateReceiptProjectionTest {
  @Test
  fun `settled build gate coordinator output validates against build_receipt contract`() {
    val output =
      FeatureTaskRuntimeBuildGateCoordinator.runtimeOwnedBuildOutput(
        phaseId = "build",
        repositoryCheckpoint = "checkpoint-fp",
        measurements =
          listOf(
            FeatureTaskRuntimeValidationGateRunRecord(
              durationMs = 12,
              outcome = "passed",
              cacheMode = "cache_eligible",
              executedWorkUnits = 1,
              executedChecks = listOf("runtime-engine|compileKotlin"),
              command = "./gradlew compileKotlin",
              exitCode = 0,
              repositoryCheckpoint = "checkpoint-fp",
            ),
          ),
      )
    val envelope =
      JsonCodec.parseObjectOrNull(output.payload)?.let(JsonCodec::jsonElementToValue)
        ?.let(JsonCodec::anyToStringAnyMap)
        ?: error("build gate output must parse as an object")
    val buildReceipt =
      JsonCodec.anyToStringAnyMap(
        JsonCodec.anyToStringAnyMap(envelope["produced_outputs"])?.get("build_receipt"),
      ) ?: error("build gate output must carry build_receipt")
    FeatureTaskRuntimeBuildReceiptSchemaValidator.validate(buildReceipt, sourceLabel = "build#1")
  }
}
