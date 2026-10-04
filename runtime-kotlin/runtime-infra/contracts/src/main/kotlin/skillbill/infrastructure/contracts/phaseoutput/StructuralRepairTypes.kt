package skillbill.infrastructure.contracts.phaseoutput

import com.fasterxml.jackson.databind.JsonNode
import skillbill.error.featuretask.FeatureTaskRuntimePhaseOutputFailureCode
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimePhaseOutputFormat

internal data class CandidateGeneration(
  val candidates: List<Candidate>,
  val limitExceeded: Boolean,
  val unsupportedYaml: Boolean,
)

internal data class Candidate(
  val text: String,
  val format: FeatureTaskRuntimePhaseOutputFormat,
  val changedOffset: Int,
)

internal data class DelimiterScan(
  val openingStack: List<Char>,
  val unmatchedClosingOffsets: List<Int>,
  val firstMismatchedClosing: MismatchedClosing?,
)

internal data class MismatchedClosing(
  val offset: Int,
  val missingCloser: Char?,
)

internal sealed interface StrictParse {
  data class Success(
    val format: FeatureTaskRuntimePhaseOutputFormat,
    val node: JsonNode,
  ) : StrictParse

  data class Failure(
    val code: FeatureTaskRuntimePhaseOutputFailureCode,
    val reason: String,
  ) : StrictParse
}
