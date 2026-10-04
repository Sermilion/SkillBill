package skillbill.engine.featuretask.runner

import skillbill.contracts.JsonCodec
import skillbill.engine.featuretask.lifecycle.continuation.isGoalContinuationRun
import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeRunFacts
import skillbill.workflow.taskruntime.model.skeleton.SkeletonDefinition

const val STATUS_RUNNING = "running"
const val STATUS_COMPLETED = "completed"
const val STATUS_BLOCKED = "blocked"

const val STATUS_PAUSED = "paused"

const val STATUS_ABANDONED = "abandoned"
const val BRANCH_SETUP_AGENT_ID = "branch-setup"
const val SCHEMA_GATE_DETAIL_MAX_CHARS = 500

fun serializeTokenData(accumulator: Map<String, Pair<Int, Int>>): Pair<String?, Int?> {
  if (accumulator.isEmpty()) return null to null
  val breakdown =
    accumulator.mapValues { (_, pair) ->
      mapOf("estimated_input_tokens" to pair.first, "estimated_output_tokens" to pair.second)
    }
  val total = accumulator.values.sumOf { (input, output) -> input + output }
  return JsonCodec.mapToJsonString(breakdown) to total
}

fun skeletonDefinitionFor(request: FeatureTaskRuntimeRunFacts): SkeletonDefinition =
  request.skeletonDefinition ?: SkeletonDefinition.forRun(isGoalContinuationRun(request))

fun boundedSchemaGateDetail(validationReason: String): String =
  if (validationReason.length <= SCHEMA_GATE_DETAIL_MAX_CHARS) {
    validationReason
  } else {
    validationReason.take(SCHEMA_GATE_DETAIL_MAX_CHARS) + "… [truncated]"
  }

fun withSchemaGateDetail(
  policyReason: String,
  validationReason: String,
): String = "$policyReason Last schema-gate failure: ${boundedSchemaGateDetail(validationReason)}"

fun nonRetryingPhaseSchemaBlockReason(phaseId: String): String =
  "Phase '$phaseId' produced schema-invalid output and does not participate in a fix loop; " +
    "the run blocks rather than advancing on invalid output."
